package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.pov.AgentControlReservations;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Model-chosen fight_target: selects the best hotbar weapon (visible slot change), turns with the eased
 * player turn, closes to reach, swings only at a full attack charge and steps back briefly after each hit.
 * It runs until the target dies or is gone, the optional model-chosen fleeAtHealth bail-out, or timeout.
 * The hit itself is the same server call the attack action uses (player.attack + swing).
 */
public final class ServerFightController implements ServerController {
	static final double MAX_CHASE_DISTANCE = 32.0D;
	/** No hit landed and no ground gained for this long means the target cannot be reached (5 s). */
	static final int UNREACHABLE_TICKS = 100;

	private final LivingEntity target;
	private final String targetType;
	private final double desiredRange;
	private final Float fleeAtHealth;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final CombatInputLease input = new CombatInputLease();
	private int weaponSlot = -1;
	private int backoffTicks;
	private int hits;
	private int ticksWithoutProgress;
	private double closestDistance = Double.MAX_VALUE;

	public ServerFightController(LivingEntity target, Double desiredRange, Float fleeAtHealth, long timeoutMs, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
		this.desiredRange = desiredRange == null ? CombatPlanning.DEFAULT_FIGHT_RANGE : desiredRange;
		this.fleeAtHealth = fleeAtHealth;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) return finish(TickResult.failed("AGENT_DEAD", "Agent player died while fighting " + targetType, progress()));
		if (target.isDeadOrDying()) {
			return finish(TickResult.succeeded("TARGET_KILLED", "Killed " + targetType + " with " + hits + " hit(s)"));
		}
		if (!target.isAlive() || target.isRemoved() || target.level() != player.level()) {
			return finish(TickResult.succeeded("TARGET_GONE", targetType + " is gone"));
		}
		if (CombatPlanning.shouldBailOut(player.getHealth(), fleeAtHealth)) {
			return finish(TickResult.failed("LOW_HEALTH_BAILOUT", String.format(Locale.ROOT,
					"Health %.1f reached fleeAtHealth %.1f; %s has %.1f health at %.1f blocks",
					player.getHealth(), fleeAtHealth, targetType, target.getHealth(), player.distanceTo(target)), progress()));
		}
		double distance = player.distanceTo(target);
		if (distance > MAX_CHASE_DISTANCE) {
			return finish(TickResult.failed("TARGET_ESCAPED", String.format(Locale.ROOT,
					"%s moved %.1f blocks away", targetType, distance), progress()));
		}
		if (elapsed >= timeoutMs) {
			return finish(TickResult.timedOut("FIGHT_TIMED_OUT", String.format(Locale.ROOT,
					"%s still has %.1f health after %d hit(s)", targetType, target.getHealth(), hits), progress()));
		}
		if (distance < closestDistance - 0.5D) {
			closestDistance = distance;
			ticksWithoutProgress = 0;
		} else if (++ticksWithoutProgress >= UNREACHABLE_TICKS) {
			return finish(TickResult.failed("TARGET_UNREACHABLE", String.format(Locale.ROOT,
					"No hit or approach on %s for 5 seconds at %.1f blocks", targetType, distance), progress()));
		}
		if (weaponSlot < 0) {
			weaponSlot = CombatPlanning.bestWeaponSlot(HotbarWeapons.candidates(player), player.getInventory().getSelectedSlot());
		}

		Vec3 eye = player.getEyePosition();
		AABB box = target.getBoundingBox();
		ServerLookController.Angles goal = ServerLookController.anglesTo(eye, box.getCenter());
		float yaw = AgentInputStates.turnYaw(player.getYRot(), goal.yaw());
		float pitch = AgentInputStates.turnPitch(player.getXRot(), goal.pitch());
		boolean weaponReady = player.getInventory().getSelectedSlot() == weaponSlot;
		CombatPlanning.FightStep step = CombatPlanning.fightStep(new CombatPlanning.FightInput(
				distance, desiredRange,
				player.isWithinAttackRange(player.getMainHandItem(), box, 0.0D),
				aimed(eye, yaw, pitch, box, player.entityInteractionRange() + 1.0D),
				player.hasLineOfSight(target),
				player.getAttackStrengthScale(0.5F),
				weaponReady,
				backoffTicks));
		backoffTicks = step.backoffTicks();
		boolean jump = player.isInWater() || (step.forward() > 0.0F && player.onGround() && player.horizontalCollision);
		input.apply(player, new AgentInputState(step.forward(), 0.0F, jump, false,
				step.sprint() && player.getFoodData().getFoodLevel() > 6, false, false,
				yaw, pitch, weaponSlot, InteractionHand.MAIN_HAND));
		// The swing is a direct server call, so it must also yield to an operator takeover (whose input lease wins).
		if (step.attack() && !operatorControlled(player)) {
			player.attack(target);
			player.swing(InteractionHand.MAIN_HAND);
			hits++;
			ticksWithoutProgress = 0;
		}
		return TickResult.running(progress());
	}

	private static boolean operatorControlled(ServerPlayer player) {
		return AgentInputRuntime.findAgentId(player)
				.map(agentId -> AgentControlReservations.isReserved(player.level().getServer(), agentId))
				.orElse(false);
	}

	/** True when the eased view this tick already points into the target's (slightly inflated) box. */
	static boolean aimed(Vec3 eye, float yaw, float pitch, AABB box, double range) {
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		return box.inflate(0.1D).contains(eye) || box.inflate(0.1D).clip(eye, eye.add(look.scale(range))).isPresent();
	}

	private double progress() {
		float max = target.getMaxHealth();
		if (!(max > 0.0F)) return 0.0D;
		return Math.max(0.0D, Math.min(0.99D, 1.0D - target.getHealth() / max));
	}

	private TickResult finish(TickResult result) {
		input.release();
		return result;
	}

	@Override
	public void cancel(ServerPlayer player) {
		input.release();
	}
}
