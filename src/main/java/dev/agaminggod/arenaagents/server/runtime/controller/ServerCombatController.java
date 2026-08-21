package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Sustained, target-preserving controller for fight, flee, and follow actions.
 */
public final class ServerCombatController implements ServerController {
	private static final double ATTACK_REACH = 3.0D;

	private final Entity target;
	private final CombatIntent intent;
	private final long startedAt;
	private final CombatPolicy policy = new CombatPolicy();
	private ServerNavigationController navigation;
	private Vec3 navigationTarget;
	private InputLease combatLease;

	public ServerCombatController(Entity target, CombatIntent intent, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.intent = Objects.requireNonNull(intent, "intent must not be null");
		this.startedAt = startedAt;
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		if (!player.isAlive()) {
			return failed(player, "AGENT_DEAD", "Agent player died");
		}
		if (Math.max(0L, nowEpochMs - startedAt) >= intent.timeoutMs()) {
			return failed(player, "ACTION_TIMED_OUT", "Controller action timed out");
		}
		boolean targetAlive = target.isAlive() && !target.isRemoved();
		boolean invulnerable = target instanceof ServerPlayer targetPlayer
				&& (targetPlayer.isCreative() || targetPlayer.isSpectator());
		double distance = targetAlive && target.level() == player.level()
				? player.distanceTo(target) : Double.MAX_VALUE;
		if (targetAlive && target.level() != player.level()) {
			return failed(player, "TARGET_LOST", "Target left the agent's dimension");
		}
		double policyRange = intent.mode() == CombatIntent.Mode.FIGHT
				? Math.min(intent.desiredRange(), ATTACK_REACH) : intent.desiredRange();
		CombatIntent effective = new CombatIntent(
				intent.targetSelector(),
				policyRange,
				intent.timeoutMs(),
				intent.mode()
		);
		CombatDecision decision = policy.decide(
				new CombatSnapshot(
						targetAlive,
						invulnerable,
						targetAlive ? distance : 0.0D,
						player.getAttackStrengthScale(0.5F) >= 0.9F
				),
				effective
		);
		return switch (decision) {
			case TARGET_DEFEATED -> succeeded(player, "TARGET_DEFEATED", "Target is no longer alive");
			case TARGET_INVALID -> failed(player, "TARGET_INVALID", "Target cannot be harmed");
			case HOLD -> intent.mode() == CombatIntent.Mode.FLEE
					? succeeded(player, "RETREAT_DISTANCE_REACHED", "Requested retreat distance reached")
					: succeeded(player, "FOLLOW_DISTANCE_REACHED", "Requested follow distance reached");
			case FACE -> {
				stopNavigation(player);
				applyCombatInput(player, false);
				yield TickResult.running(progress(distance));
			}
			case ATTACK -> {
				stopNavigation(player);
				applyCombatInput(player, true);
				yield TickResult.running(progress(distance));
			}
			case APPROACH -> navigate(player, target.position(), policyRange, true, nowEpochMs, distance);
			case RETREAT -> navigate(
					player,
					retreatDestination(player.position(), target.position(), intent.desiredRange(), distance),
					1.25D,
					true,
					nowEpochMs,
					distance
			);
		};
	}

	@Override
	public void cancel(ServerPlayer player) {
		stopNavigation(player);
		releaseCombat(player);
		OfflineAgentPlayers.stop(player);
	}

	private TickResult navigate(
			ServerPlayer player,
			Vec3 destination,
			double tolerance,
			boolean sprint,
			long nowEpochMs,
			double targetDistance
	) {
		releaseCombat(player);
		if (navigation == null || navigationTarget == null
				|| navigationTarget.distanceToSqr(destination) > 4.0D) {
			stopNavigation(player);
			navigationTarget = destination;
			long remainingTimeout = Math.max(1_000L, intent.timeoutMs() - Math.max(0L, nowEpochMs - startedAt));
			navigation = new ServerNavigationController(
					destination,
					Math.max(0.5D, tolerance),
					sprint,
					nowEpochMs,
					remainingTimeout
			);
		}
		TickResult result = navigation.tick(player, nowEpochMs);
		if (result.state() != State.RUNNING) {
			navigation = null;
			navigationTarget = null;
		}
		return resolveNavigationTick(result, progress(targetDistance));
	}

	static TickResult resolveNavigationTick(TickResult navigationResult, double combatProgress) {
		Objects.requireNonNull(navigationResult, "navigationResult must not be null");
		return navigationResult.state() == State.FAILED
				? navigationResult
				: TickResult.running(combatProgress);
	}

	private void stopNavigation(ServerPlayer player) {
		if (navigation != null) navigation.cancel(player);
		navigation = null;
		navigationTarget = null;
	}

	private void applyCombatInput(ServerPlayer player, boolean attack) {
		LeasedServerInputController controller = AgentInputRuntime.controller(player);
		if (combatLease == null) {
			combatLease = controller.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.COMBAT, 200);
		}
		controller.apply(combatLease, AgentInputStates.lookingAt(
				player,
				target.getEyePosition(),
				0.0F,
				0.0F,
				false,
				false,
				false,
				attack,
				false,
				InteractionHand.MAIN_HAND
		));
	}

	private void releaseCombat(ServerPlayer player) {
		if (combatLease == null) return;
		try {
			AgentInputRuntime.controller(player).release(combatLease);
		} catch (IllegalStateException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		combatLease = null;
	}

	private TickResult succeeded(ServerPlayer player, String reasonCode, String message) {
		cancel(player);
		return TickResult.succeeded(reasonCode, message);
	}

	private TickResult failed(ServerPlayer player, String reasonCode, String message) {
		cancel(player);
		return TickResult.failed(reasonCode, message, 0.0D);
	}

	private double progress(double distance) {
		if (!Double.isFinite(distance)) return 0.0D;
		return switch (intent.mode()) {
			case FIGHT, FOLLOW -> Math.max(0.0D, Math.min(1.0D, 1.0D - distance / Math.max(1.0D, intent.desiredRange() * 4.0D)));
			case FLEE -> Math.max(0.0D, Math.min(1.0D, distance / intent.desiredRange()));
		};
	}

	private static Vec3 retreatDestination(Vec3 player, Vec3 threat, double requestedDistance, double currentDistance) {
		double dx = player.x - threat.x;
		double dz = player.z - threat.z;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.001D) {
			dx = 1.0D;
			dz = 0.0D;
			length = 1.0D;
		}
		double travel = Math.max(4.0D, requestedDistance - currentDistance + 2.0D);
		return new Vec3(
				player.x + dx / length * travel,
				player.y,
				player.z + dz / length * travel
		);
	}
}
