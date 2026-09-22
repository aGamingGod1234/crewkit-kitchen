package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Walks a fake player into a dropped entity and trusts only vanilla collision pickup. */
public final class ServerItemPickupController implements ServerController {
	private static final long DEFAULT_TIMEOUT_MS = 30_000L;
	private static final double REPLAN_DISTANCE_SQUARED = 1.0D;

	private final ItemEntity item;
	private final ItemStack identity;
	private final int initialInventoryCount;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final ResourceKey<Level> startingDimension;
	private ServerNavigationController navigation;
	private Vec3 navigationTarget;

	public ServerItemPickupController(ServerPlayer player, ItemEntity item, long startedAt) {
		this(player, item, startedAt, DEFAULT_TIMEOUT_MS);
	}

	public ServerItemPickupController(ServerPlayer player, ItemEntity item, long startedAt, long timeoutMs) {
		Objects.requireNonNull(player, "player must not be null");
		this.item = Objects.requireNonNull(item, "item must not be null");
		if (timeoutMs <= 0L) throw new IllegalArgumentException("timeout must be positive");
		this.identity = item.getItem().copy();
		this.initialInventoryCount = countMatching(player, identity);
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
		this.startingDimension = player.level().dimension();
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		if (!remainsInDimension(startingDimension, player.level().dimension())) {
			return fail(player, "ACTION_DIMENSION_CHANGED",
					"Agent player changed dimension during item pickup");
		}
		if (!remainsInDimension(startingDimension, item.level().dimension())) {
			return fail(player, "ITEM_UNAVAILABLE", "The dropped item left its starting dimension");
		}
		long elapsedMs = elapsedTime.advance(nowEpochMs);
		int currentCount = countMatching(player, identity);
		boolean alive = item.isAlive() && !item.isRemoved() && !item.getItem().isEmpty();
		ItemPickupProgress.Decision decision = ItemPickupProgress.evaluate(
				initialInventoryCount, currentCount, alive, elapsedMs >= timeoutMs);
		return switch (decision) {
			case SUCCEEDED -> succeed(player);
			case ITEM_UNAVAILABLE -> fail(player, "ITEM_UNAVAILABLE", "The dropped item disappeared before this player picked it up");
			case TIMED_OUT -> fail(player, "ITEM_PICKUP_TIMED_OUT", "The player could not physically reach the dropped item");
			case RUNNING -> approach(player, nowEpochMs, elapsedMs);
		};
	}

	@Override
	public void cancel(ServerPlayer player) {
		stopNavigation(player);
	}

	private TickResult approach(ServerPlayer player, long nowEpochMs, long elapsedMs) {
		Vec3 currentTarget = item.position();
		AABB pickupRegion = pickupStandingRegion(player.getBoundingBox(), player.position(), item.getBoundingBox());
		if (player.getBoundingBox().inflate(1.0D, 0.5D, 1.0D).intersects(item.getBoundingBox())) {
			stopNavigation(player);
			return TickResult.running(0.98D);
		}
		if (navigation == null || navigationTarget.distanceToSqr(currentTarget) > REPLAN_DISTANCE_SQUARED) {
			navigation = replaceNavigation(
					navigation,
					existing -> existing.cancel(player),
					() -> new ServerNavigationController(currentTarget, 0.2D, true, nowEpochMs,
							remainingNavigationTimeout(timeoutMs, elapsedMs), pickupRegion)
			);
			navigationTarget = currentTarget;
		}
		TickResult result = navigation.tick(player, nowEpochMs);
		if (awaitingDropLanding(result, item.onGround(), item.isNoGravity(), item.isInWater() || item.isInLava())) {
			// A fresh mining drop has no standing position while airborne. Let vanilla
			// gravity move it, then plan from its new position within the original deadline.
			stopNavigation(player);
			return TickResult.running(0.0D);
		}
		if (result.state() == State.SUCCEEDED) {
			// At collision range, vanilla's ItemEntity#playerTouch owns the inventory mutation.
			stopNavigation(player);
			return TickResult.running(0.98D);
		}
		return result;
	}

	private TickResult succeed(ServerPlayer player) {
		stopNavigation(player);
		return TickResult.succeeded("ITEM_PICKED_UP", "Vanilla collision pickup was observed in the player's inventory");
	}

	private TickResult fail(ServerPlayer player, String reasonCode, String message) {
		stopNavigation(player);
		return TickResult.failed(reasonCode, message, 0.0D);
	}

	private void stopNavigation(ServerPlayer player) {
		if (navigation != null) navigation.cancel(player);
		navigation = null;
		navigationTarget = null;
	}

	private static int countMatching(ServerPlayer player, ItemStack identity) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, identity)) count += stack.getCount();
		}
		return count;
	}

	static boolean remainsInDimension(ResourceKey<Level> startingDimension, ResourceKey<Level> currentDimension) {
		return ServerNavigationController.remainsInDimension(startingDimension, currentDimension);
	}

	static boolean awaitingDropLanding(TickResult result, boolean onGround, boolean noGravity, boolean inFluid) {
		return result.state() == State.FAILED && result.reasonCode().equals("NO_STANDABLE_PATH")
				&& !onGround && !noGravity && !inFluid;
	}

	static AABB pickupStandingRegion(AABB playerBounds, Vec3 playerPosition, AABB itemBounds) {
		// Player#aiStep checks entities against its bounds inflated by (1, 0.5, 1).
		// Invert that intersection to obtain legal feet positions, including beside
		// a drop under a canopy. Navigation still requires support and body clearance.
		AABB relative = playerBounds.move(playerPosition.scale(-1.0D)).inflate(1.0D, 0.5D, 1.0D);
		return new AABB(Math.nextUp(itemBounds.minX - relative.maxX), Math.nextUp(itemBounds.minY - relative.maxY), Math.nextUp(itemBounds.minZ - relative.maxZ),
				Math.nextDown(itemBounds.maxX - relative.minX), Math.nextDown(itemBounds.maxY - relative.minY), Math.nextDown(itemBounds.maxZ - relative.minZ));
	}

	static long remainingNavigationTimeout(long timeoutMs, long elapsedMs) {
		if (timeoutMs <= 0L || elapsedMs < 0L) throw new IllegalArgumentException("invalid timeout state");
		return Math.max(1L, timeoutMs - elapsedMs);
	}

	static <T> T replaceNavigation(T current, Consumer<T> stop, Supplier<T> replacement) {
		Objects.requireNonNull(stop, "navigation stop must not be null");
		Objects.requireNonNull(replacement, "navigation replacement must not be null");
		if (current != null) stop.accept(current);
		return Objects.requireNonNull(replacement.get(), "navigation replacement returned null");
	}
}
