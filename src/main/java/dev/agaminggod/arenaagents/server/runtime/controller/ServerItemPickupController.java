package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Walks a fake player into a dropped entity and trusts only vanilla collision pickup. */
public final class ServerItemPickupController implements ServerController {
	private static final long DEFAULT_TIMEOUT_MS = 30_000L;
	private static final double REPLAN_DISTANCE_SQUARED = 1.0D;

	private final ItemEntity item;
	private final ItemStack identity;
	private final int initialInventoryCount;
	private final long startedAt;
	private final long timeoutMs;
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
		this.startedAt = startedAt;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		int currentCount = countMatching(player, identity);
		boolean alive = item.isAlive() && !item.isRemoved() && !item.getItem().isEmpty();
		ItemPickupProgress.Decision decision = ItemPickupProgress.evaluate(
				initialInventoryCount, currentCount, alive, Math.max(0L, nowEpochMs - startedAt) >= timeoutMs);
		return switch (decision) {
			case SUCCEEDED -> succeed(player);
			case ITEM_UNAVAILABLE -> fail(player, "ITEM_UNAVAILABLE", "The dropped item disappeared before this player picked it up");
			case TIMED_OUT -> fail(player, "ITEM_PICKUP_TIMED_OUT", "The player could not physically reach the dropped item");
			case RUNNING -> approach(player, nowEpochMs);
		};
	}

	@Override
	public void cancel(ServerPlayer player) {
		OfflineAgentPlayers.stop(player);
	}

	private TickResult approach(ServerPlayer player, long nowEpochMs) {
		Vec3 currentTarget = item.position();
		if (navigation == null || navigationTarget.distanceToSqr(currentTarget) > REPLAN_DISTANCE_SQUARED) {
			navigationTarget = currentTarget;
			navigation = new ServerNavigationController(currentTarget, 0.2D, true, nowEpochMs,
					Math.max(1L, timeoutMs - Math.max(0L, nowEpochMs - startedAt)));
		}
		TickResult result = navigation.tick(player, nowEpochMs);
		if (result.state() == State.SUCCEEDED) {
			// At collision range, vanilla's ItemEntity#playerTouch owns the inventory mutation.
			return TickResult.running(0.98D);
		}
		return result;
	}

	private TickResult succeed(ServerPlayer player) {
		OfflineAgentPlayers.stop(player);
		return TickResult.succeeded("ITEM_PICKED_UP", "Vanilla collision pickup was observed in the player's inventory");
	}

	private TickResult fail(ServerPlayer player, String reasonCode, String message) {
		OfflineAgentPlayers.stop(player);
		return TickResult.failed(reasonCode, message, 0.0D);
	}

	private static int countMatching(ServerPlayer player, ItemStack identity) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, identity)) count += stack.getCount();
		}
		return count;
	}
}
