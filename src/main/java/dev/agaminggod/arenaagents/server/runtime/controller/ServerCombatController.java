package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Sustained, target-preserving controller for fight, flee, and follow actions.
 */
public final class ServerCombatController implements ServerController {
	private static final double ATTACK_REACH = 3.0D;
	private static final double PURSUIT_REPLAN_DISTANCE_SQUARED = 0.25D;

	private final Entity target;
	private final CombatIntent intent;
	private final ElapsedTimeAccumulator elapsedTime;
	private final ResourceKey<Level> startingDimension;
	private final CombatPolicy policy = new CombatPolicy();
	private ServerNavigationController navigation;
	private Vec3 navigationTarget;
	private InputLease combatLease;
	private AgentInputStates.MotorState motorState;

	public ServerCombatController(Entity target, CombatIntent intent, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.intent = Objects.requireNonNull(intent, "intent must not be null");
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
		this.startingDimension = target.level().dimension();
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		if (!remainsInDimension(startingDimension, player.level().dimension())) {
			return failed(player, "ACTION_DIMENSION_CHANGED",
					"Agent player changed dimension during combat");
		}
		long elapsedMs = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) {
			return failed(player, "AGENT_DEAD", "Agent player died");
		}
		if (elapsedMs >= intent.timeoutMs()) {
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
					player.getAttackStrengthScale(0.5F) >= 0.9F,
					targetAlive && hasLineOfSight(player, target),
					targetAlive ? aimErrorDegrees(player, target) : 180.0D
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
				applyCombatInput(player, false, nowEpochMs);
				yield TickResult.running(progress(distance));
			}
			case ATTACK -> {
				stopNavigation(player);
				applyCombatInput(player, true, nowEpochMs);
				yield TickResult.running(progress(distance));
			}
			case APPROACH -> navigate(player, target.position(), policyRange, true, nowEpochMs, elapsedMs, distance);
			case RETREAT -> navigate(
					player,
					retreatDestination(player.position(), target.position(), intent.desiredRange(), distance),
					1.25D,
					true,
					nowEpochMs,
					elapsedMs,
					distance
			);
		};
	}

	@Override
	public void cancel(ServerPlayer player) {
		ServerTransactionAdapter.runBestEffort(
				() -> stopNavigation(player),
				() -> releaseCombat(player),
				() -> motorState = null
		);
	}

	private TickResult navigate(
			ServerPlayer player,
			Vec3 destination,
			double tolerance,
			boolean sprint,
			long nowEpochMs,
			long elapsedMs,
			double targetDistance
	) {
		releaseCombat(player);
		if (navigation == null || navigationTarget == null
				|| navigationTarget.distanceToSqr(destination) > PURSUIT_REPLAN_DISTANCE_SQUARED) {
			stopNavigation(player);
			navigationTarget = destination;
			long remainingTimeout = remainingNavigationTimeout(intent.timeoutMs(), elapsedMs);
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

	static boolean remainsInDimension(ResourceKey<Level> startingDimension, ResourceKey<Level> currentDimension) {
		return ServerNavigationController.remainsInDimension(startingDimension, currentDimension);
	}

	static long remainingNavigationTimeout(long timeoutMs, long elapsedMs) {
		if (timeoutMs <= 0L || elapsedMs < 0L) throw new IllegalArgumentException("invalid timeout state");
		return Math.max(1_000L, timeoutMs - elapsedMs);
	}

	private void stopNavigation(ServerPlayer player) {
		if (navigation != null) navigation.cancel(player);
		navigation = null;
		navigationTarget = null;
	}

	private void applyCombatInput(ServerPlayer player, boolean attack, long nowEpochMs) {
		LeasedServerInputController controller = AgentInputRuntime.controller(player);
		if (combatLease == null) {
			combatLease = controller.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.COMBAT, 200);
		}
		Vec3 lookTarget = target.getEyePosition();
		Vec3 delta = lookTarget.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float targetYaw = net.minecraft.util.Mth.wrapDegrees(
				(float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float targetPitch = net.minecraft.util.Mth.clamp(
				(float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		if (motorState == null) motorState = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(
				motorState,
				new AgentInputStates.MotorTarget(targetYaw, targetPitch, false, false, false),
				nowEpochMs
		);
		motorState = step.state();
		controller.apply(combatLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				0.0F, 0.0F, false, false, false, attack, false,
				step.state().yaw(), step.state().pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	private static boolean hasLineOfSight(ServerPlayer player, Entity target) {
		return player.level() == target.level() && player.hasLineOfSight(target);
	}

	private static double aimErrorDegrees(ServerPlayer player, Entity target) {
		Vec3 offset = target.getEyePosition().subtract(player.getEyePosition());
		if (offset.lengthSqr() < 1.0E-8D) return 0.0D;
		Vec3 view = player.getViewVector(1.0F);
		if (view.lengthSqr() < 1.0E-8D) return 180.0D;
		double dot = Math.max(-1.0D, Math.min(1.0D, view.normalize().dot(offset.normalize())));
		return Math.toDegrees(Math.acos(dot));
	}

	private void releaseCombat(ServerPlayer player) {
		if (combatLease == null) return;
		try {
			AgentInputRuntime.controller(player).release(combatLease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
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
