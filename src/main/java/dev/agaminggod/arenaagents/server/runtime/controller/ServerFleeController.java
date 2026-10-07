package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.server.perception.ThreatPerception;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;

/**
 * Model-chosen flee_from: sprints away from the named entity and every other hostile threat within 16 blocks
 * (proximity weighted; creepers, and swelling creepers most, push hardest) until the named entity is at least
 * the requested distance away and no longer closing in, or it lost the agent. It never reports success while
 * another threat inside that distance is still closing in or any creeper is within 7 blocks, so escaping a
 * zombie cannot end beside a creeper. Unlike navigate_to it has no destination to "arrive" at.
 * Steering re-picks a walkable heading every tick (step-ups are jumped, hazards and deep drops avoided).
 */
public final class ServerFleeController implements ServerController {
	private final Entity target;
	private final String targetType;
	private final double distance;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final CombatPlanning.FleeProgress progress;
	private final CombatPlanning.ClosingTracker closing = new CombatPlanning.ClosingTracker();
	private final CombatInputLease input = new CombatInputLease();
	private AgentInputStates.MotorState motor;
	private Float heading;

	public ServerFleeController(Entity target, double distance, long timeoutMs, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.targetType = typeOf(target);
		this.distance = distance;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
		this.progress = new CombatPlanning.FleeProgress(distance);
	}

	/** Another threat sensed this tick, with the geometry the pure flee rules need. */
	private record Other(Mob mob, CombatPlanning.FleeThreat threat) {
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) return finish(TickResult.failed("AGENT_DEAD", "Agent player died while fleeing", 0.0D));
		List<Other> others = otherThreats(player);
		List<CombatPlanning.FleeThreat> otherThreats = others.stream().map(Other::threat).toList();
		int blocker = CombatPlanning.escapeBlocker(otherThreats, distance);
		boolean targetPresent = target.isAlive() && !target.isRemoved() && target.level() == player.level();
		if (!targetPresent) {
			if (blocker < 0) return finish(TickResult.succeeded("TARGET_GONE", targetType + " is gone" + clearOf(others)));
			// The named chaser is gone but others still close in: keep fleeing from them.
			return drive(player, nowEpochMs, others, false) ? running(elapsed, others, blocker, 0.0D)
					: blocked(player, others, -1.0D);
		}
		double current = player.distanceTo(target);
		double fraction = Math.min(0.99D, current / distance);
		boolean hunting = target instanceof Mob mob
				? mob.getTarget() == player || mob.hasLineOfSight(player)
				: target instanceof LivingEntity living && living.hasLineOfSight(player);
		CombatPlanning.FleeProgress.Outcome outcome = progress.observe(current, hunting);
		// A creeper as the named target is held to the same blast margin as any other creeper.
		boolean creeperTooClose = target instanceof Creeper && current < CombatPlanning.CREEPER_SAFE_DISTANCE;
		if (blocker < 0 && !creeperTooClose) {
			switch (outcome) {
				case ESCAPED -> {
					return finish(TickResult.succeeded("ESCAPED", String.format(Locale.ROOT,
							"%.1f blocks from %s and it is not closing in", current, targetType) + clearOf(others)));
				}
				case LOST -> {
					return finish(TickResult.succeeded("TARGET_LOST", String.format(Locale.ROOT,
							"%s lost track of the agent at %.1f blocks", targetType, current) + clearOf(others)));
				}
				case RUNNING -> { }
			}
		}
		if (elapsed >= timeoutMs) {
			return finish(TickResult.timedOut("FLEE_TIMED_OUT", String.format(Locale.ROOT,
					"Still %.1f of %.1f blocks from %s when the flee timed out", current, distance, targetType)
					+ blockerText(others, blocker), fraction));
		}
		if (!drive(player, nowEpochMs, others, true)) return blocked(player, others, current);
		return TickResult.running(fraction);
	}

	private TickResult running(long elapsed, List<Other> others, int blocker, double fraction) {
		if (elapsed >= timeoutMs) {
			return finish(TickResult.timedOut("FLEE_TIMED_OUT", targetType + " is gone but the flee timed out"
					+ blockerText(others, blocker), fraction));
		}
		return TickResult.running(fraction);
	}

	private TickResult blocked(ServerPlayer player, List<Other> others, double current) {
		String from = current < 0.0D ? "the remaining threats" : String.format(Locale.ROOT, "%s (%.1f blocks)", targetType, current);
		return finish(TickResult.failed("FLEE_BLOCKED", "No safe direction away from " + from
				+ "; every heading is walled, hazardous or a deep drop" + blockerText(others, CombatPlanning.escapeBlocker(
						others.stream().map(Other::threat).toList(), distance)),
				current < 0.0D ? 0.0D : Math.min(0.99D, current / distance)));
	}

	/**
	 * Hostiles other than the named target that the flee must also get away from: sensed threats within 16 blocks
	 * (targeting the agent, its last attacker, or with sight of it) and any creeper within the 7-block blast margin.
	 */
	private List<Other> otherThreats(ServerPlayer player) {
		List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(CombatPlanning.THREAT_RANGE),
				mob -> mob != target && mob.isAlive() && ThreatPerception.isHostileTo(mob, player));
		List<Other> others = new ArrayList<>(mobs.size());
		Set<String> present = new HashSet<>();
		for (Mob mob : mobs) {
			double away = player.distanceTo(mob);
			boolean creeper = mob instanceof Creeper;
			boolean nearCreeper = creeper && away < CombatPlanning.CREEPER_SAFE_DISTANCE;
			if (!nearCreeper && !ThreatPerception.isSensedThreat(player, mob)) continue;
			String id = mob.getUUID().toString();
			present.add(id);
			boolean swelling = creeper && ((Creeper) mob).getSwellDir() > 0;
			others.add(new Other(mob, new CombatPlanning.FleeThreat(mob.getX() - player.getX(), mob.getZ() - player.getZ(),
					away, creeper, swelling, closing.observe(id, away))));
		}
		closing.retain(present);
		return others;
	}

	/** Applies one flee tick; false when standing with no safe heading, so the model chooses (fight, pillar, block). */
	private boolean drive(ServerPlayer player, long nowEpochMs, List<Other> others, boolean includeTarget) {
		if (motor == null) motor = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		List<CombatPlanning.FleeThreat> threats = new ArrayList<>(others.size() + 1);
		if (includeTarget) {
			boolean creeper = target instanceof Creeper;
			threats.add(new CombatPlanning.FleeThreat(target.getX() - player.getX(), target.getZ() - player.getZ(),
					player.distanceTo(target), creeper, creeper && ((Creeper) target).getSwellDir() > 0, true));
		}
		for (Other other : others) threats.add(other.threat());
		// Directly above or below every threat there is no "away"; keep the current heading.
		Float away = CombatPlanning.fleeAwayYaw(threats);
		float awayYaw = away == null ? motor.yaw() : away;
		GridPosition feet = new GridPosition(Mth.floor(player.getX()), Mth.floor(player.getY() + 0.2D), Mth.floor(player.getZ()));
		CombatPlanning.Heading chosen = CombatPlanning.fleeHeading(
				new MinecraftNavigationWorld(player.level()), feet, awayYaw, heading);
		if (!chosen.clear()) {
			if (player.onGround() || player.isInWater()) return false;
			// Mid-jump the feet cell is ambiguous; coast without input and judge again on landing.
			input.apply(player, new AgentInputState(0.0F, 0.0F, false, false, false, false, false,
					motor.yaw(), motor.pitch(), player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND));
			return true;
		}
		heading = chosen.yaw();
		boolean jump = player.isInWater() || (player.onGround() && (chosen.jump() || player.horizontalCollision));
		boolean sprint = !player.isInWater() && player.getFoodData().getFoodLevel() > 6;
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(motor,
				new AgentInputStates.MotorTarget(chosen.yaw(), 0.0F, true, jump, sprint), nowEpochMs);
		motor = step.state();
		input.apply(player, new AgentInputState(step.forward(), step.strafe(), step.jump(), false, step.sprint(),
				false, false, step.yaw(), step.pitch(), player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND));
		return true;
	}

	/** "; also clear of 2 other threat(s), nearest minecraft:creeper at 11.4 blocks" when others are around. */
	private static String clearOf(List<Other> others) {
		if (others.isEmpty()) return "";
		Other nearest = others.get(0);
		for (Other other : others) if (other.threat().distance() < nearest.threat().distance()) nearest = other;
		return String.format(Locale.ROOT, "; also clear of %d other threat(s), nearest %s at %.1f blocks",
				others.size(), typeOf(nearest.mob()), nearest.threat().distance());
	}

	private static String blockerText(List<Other> others, int blocker) {
		if (blocker < 0) return "";
		Other other = others.get(blocker);
		CombatPlanning.FleeThreat threat = other.threat();
		String why = threat.creeper() && threat.distance() < CombatPlanning.CREEPER_SAFE_DISTANCE
				? (threat.swelling() ? "swelling creeper" : "creeper") + " within blast range" : "still closing in";
		return String.format(Locale.ROOT, "; %s %s at %.1f blocks (%s)", typeOf(other.mob()), other.mob().getUUID(),
				threat.distance(), why);
	}

	private static String typeOf(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
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
