package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Model-chosen flee_from: sprints away from one exact entity until the agent is at least the requested
 * distance away and the entity is no longer closing in, or it lost the agent. Unlike navigate_to it has no
 * destination to "arrive" at, so it never stops while the chaser is still gaining (the old run-then-stop bug).
 * Steering re-picks a walkable heading every tick (step-ups are jumped, hazards and deep drops avoided).
 */
public final class ServerFleeController implements ServerController {
	private final Entity target;
	private final String targetType;
	private final double distance;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final CombatPlanning.FleeProgress progress;
	private final CombatInputLease input = new CombatInputLease();
	private AgentInputStates.MotorState motor;
	private Float heading;

	public ServerFleeController(Entity target, double distance, long timeoutMs, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
		this.distance = distance;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
		this.progress = new CombatPlanning.FleeProgress(distance);
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) return finish(TickResult.failed("AGENT_DEAD", "Agent player died while fleeing", 0.0D));
		if (!target.isAlive() || target.isRemoved() || target.level() != player.level()) {
			return finish(TickResult.succeeded("TARGET_GONE", targetType + " is gone"));
		}
		double current = player.distanceTo(target);
		double fraction = Math.min(0.99D, current / distance);
		boolean hunting = target instanceof Mob mob
				? mob.getTarget() == player || mob.hasLineOfSight(player)
				: target instanceof LivingEntity living && living.hasLineOfSight(player);
		switch (progress.observe(current, hunting)) {
			case ESCAPED -> {
				return finish(TickResult.succeeded("ESCAPED", String.format(Locale.ROOT,
						"%.1f blocks from %s and it is not closing in", current, targetType)));
			}
			case LOST -> {
				return finish(TickResult.succeeded("TARGET_LOST", String.format(Locale.ROOT,
						"%s lost track of the agent at %.1f blocks", targetType, current)));
			}
			case RUNNING -> { }
		}
		if (elapsed >= timeoutMs) {
			return finish(TickResult.timedOut("FLEE_TIMED_OUT", String.format(Locale.ROOT,
					"Still %.1f of %.1f blocks from %s when the flee timed out", current, distance, targetType), fraction));
		}
		drive(player, nowEpochMs);
		return TickResult.running(fraction);
	}

	private void drive(ServerPlayer player, long nowEpochMs) {
		if (motor == null) motor = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		double dx = player.getX() - target.getX();
		double dz = player.getZ() - target.getZ();
		// Directly above or below the target there is no "away"; keep the current heading.
		float awayYaw = dx * dx + dz * dz < 1.0E-4D ? motor.yaw()
				: CombatPlanning.yawToward(target.getX(), target.getZ(), player.getX(), player.getZ());
		GridPosition feet = new GridPosition(Mth.floor(player.getX()), Mth.floor(player.getY() + 0.2D), Mth.floor(player.getZ()));
		CombatPlanning.Heading chosen = CombatPlanning.fleeHeading(
				new MinecraftNavigationWorld(player.level()), feet, awayYaw, heading);
		heading = chosen.yaw();
		boolean jump = player.isInWater() || (player.onGround() && (chosen.jump() || player.horizontalCollision));
		boolean sprint = !player.isInWater() && player.getFoodData().getFoodLevel() > 6;
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(motor,
				new AgentInputStates.MotorTarget(chosen.yaw(), 0.0F, true, jump, sprint), nowEpochMs);
		motor = step.state();
		input.apply(player, new AgentInputState(step.forward(), step.strafe(), step.jump(), false, step.sprint(),
				false, false, step.yaw(), step.pitch(), player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND));
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
