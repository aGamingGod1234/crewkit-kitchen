package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.server.pov.AgentControlReservations;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.perception.ThreatPerception;
import dev.agaminggod.arenaagents.server.perception.RiskAssessment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Model-chosen fight_target: selects the best hotbar weapon (visible slot change), turns with the eased
 * player turn, closes to reach, swings only at a full attack charge and steps back briefly after each hit.
 * When the target dies it continues to the nearest hostile already attacking the agent (never a creeper) unless the
 * model passed continueWithAttackers:false, so one fight_target clears a pack without ever starting a new fight.
 * It ends when no attacker remains, at the optional model-chosen fleeAtHealth bail-out, or timeout.
 * The hit itself is the same server call the attack action uses (player.attack + swing).
 *
 * <p>Retargeting stays the model's choice: replaceAction with a new fight_target switches targets and inherits the
 * cancelled fight's weapon slot and swing timing (a short handoff, so the stance carries over), and the optional
 * model-chosen targetPolicy (highest_risk or nearest_attacker) re-evaluates attackers twice a second with
 * hysteresis (see {@link CombatPlanning#policyRetarget}).
 *
 * <p>Follow-through and policy switching only consider mobs. Players (other agents included) are only ever switched to
 * when the model passed includePlayers:true; creative and spectator players never are. A stray sweep, arrow or thorns
 * hit from another player therefore never starts a player fight the model did not choose.
 */
public final class ServerFightController implements ServerController {
	static final double MAX_CHASE_DISTANCE = 32.0D;
	/** No hit landed and no ground gained for this long means the target cannot be reached (5 s). */
	static final int UNREACHABLE_TICKS = 100;
	/** A replacement fight_target that starts within 2 s of a cancelled one inherits its weapon and timing. */
	static final long HANDOFF_TICKS = 40L;

	private record Handoff(int weaponSlot, int backoffTicks, long gameTime) {
	}

	private static final Map<UUID, Handoff> HANDOFFS = new HashMap<>();

	private LivingEntity target;
	private String targetType;
	private final double desiredRange;
	private final Float fleeAtHealth;
	private final boolean continueWithAttackers;
	private final CombatPlanning.TargetPolicy targetPolicy;
	private final boolean includePlayers;
	private int ticksOnTarget;
	private int policyCountdown = CombatPlanning.POLICY_INTERVAL_TICKS;
	private int policySwitches;
	private boolean handoffChecked;
	private final long timeoutMs;
	/** Kill counts per entity type in kill order, reported in every result. */
	private final Map<String, Integer> kills = new LinkedHashMap<>();
	private int totalHits;
	private final ElapsedTimeAccumulator elapsedTime;
	private final CombatInputLease input = new CombatInputLease();
	private int weaponSlot = -1;
	private int backoffTicks;
	private int hits;
	private int ticksWithoutProgress;
	private double closestDistance = Double.MAX_VALUE;

	public ServerFightController(LivingEntity target, Double desiredRange, Float fleeAtHealth, long timeoutMs, long startedAt) {
		this(target, desiredRange, fleeAtHealth, true, timeoutMs, startedAt);
	}

	public ServerFightController(LivingEntity target, Double desiredRange, Float fleeAtHealth, boolean continueWithAttackers,
			long timeoutMs, long startedAt) {
		this(target, desiredRange, fleeAtHealth, continueWithAttackers, CombatPlanning.TargetPolicy.NAMED, false, timeoutMs, startedAt);
	}

	public ServerFightController(LivingEntity target, Double desiredRange, Float fleeAtHealth, boolean continueWithAttackers,
			CombatPlanning.TargetPolicy targetPolicy, boolean includePlayers, long timeoutMs, long startedAt) {
		this.target = Objects.requireNonNull(target, "target must not be null");
		this.targetPolicy = Objects.requireNonNull(targetPolicy, "targetPolicy must not be null");
		this.includePlayers = includePlayers;
		this.targetType = typeOf(target);
		this.desiredRange = desiredRange == null ? CombatPlanning.DEFAULT_FIGHT_RANGE : desiredRange;
		this.fleeAtHealth = fleeAtHealth;
		this.continueWithAttackers = continueWithAttackers;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) {
			return finish(TickResult.failed("AGENT_DEAD", "Agent player died while fighting " + targetType + kills(), progress()));
		}
		if (!handoffChecked) inheritHandoff(player);
		boolean killed = target.isDeadOrDying();
		if (killed || !target.isAlive() || target.isRemoved() || target.level() != player.level()) {
			if (killed) kills.merge(targetType, 1, Integer::sum);
			String ended = killed ? "Killed " + targetType + " with " + hits + " hit(s)" : targetType + " is gone";
			// Follow-through: the model chose this fight, so keep fighting whoever is already attacking the agent.
			LivingEntity next = continueWithAttackers ? nextAttacker(player) : null;
			if (next == null) {
				String reasonCode = killed || !kills.isEmpty() ? "TARGET_KILLED" : "TARGET_GONE";
				return finish(TickResult.succeeded(reasonCode, ended + kills() + remainingThreats(player)));
			}
			switchTo(next);
		}
		applyTargetPolicy(player);
		ticksOnTarget++;
		if (CombatPlanning.shouldBailOut(player.getHealth(), fleeAtHealth)) {
			return finish(TickResult.failed("LOW_HEALTH_BAILOUT", String.format(Locale.ROOT,
					"Health %.1f reached fleeAtHealth %.1f; %s has %.1f health at %.1f blocks",
					player.getHealth(), fleeAtHealth, targetType, target.getHealth(), player.distanceTo(target))
					+ kills() + remainingThreats(player), progress()));
		}
		double distance = player.distanceTo(target);
		if (distance > MAX_CHASE_DISTANCE) {
			return finish(TickResult.failed("TARGET_ESCAPED", String.format(Locale.ROOT,
					"%s moved %.1f blocks away", targetType, distance) + kills() + remainingThreats(player), progress()));
		}
		if (elapsed >= timeoutMs) {
			return finish(TickResult.timedOut("FIGHT_TIMED_OUT", String.format(Locale.ROOT,
					"%s still has %.1f health after %d hit(s)", targetType, target.getHealth(), hits)
					+ kills() + remainingThreats(player), progress()));
		}
		if (distance < closestDistance - 0.5D) {
			closestDistance = distance;
			ticksWithoutProgress = 0;
		} else if (++ticksWithoutProgress >= SwimPlanning.unreachableTicks(player.isInWater(), target.isInWater(), UNREACHABLE_TICKS)) {
			// In water the approach is a swim, which is slower and bobs; it gets a fair (10 s) attempt first.
			return finish(TickResult.failed("TARGET_UNREACHABLE", String.format(Locale.ROOT,
					"No hit or approach on %s for %d seconds at %.1f blocks%s", targetType, ticksWithoutProgress / 20, distance,
					player.isInWater() ? " while swimming" : "")
					+ kills() + remainingThreats(player), progress()));
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
		float forward = safeForward(player, step.forward(), yaw, AgentInputStates.shortestAngleDelta(yaw, goal.yaw()));
		boolean inWater = player.isInWater();
		int food = player.getFoodData().getFoodLevel();
		// Swimming approach: stay afloat (or sink toward a target clearly below) and sprint-swim along the aim while
		// submerged, so the view pitch toward the target steers the swim up or down like a player's.
		boolean jump = SwimPlanning.fightJump(inWater, target.getY() - player.getY())
				|| (forward > 0.0F && player.onGround() && player.horizontalCollision);
		boolean sprint = (step.sprint() && food > 6) || SwimPlanning.swimSprint(inWater, player.isUnderWater(), forward, food);
		input.apply(player, new AgentInputState(forward, 0.0F, jump, false, sprint, false, false,
				yaw, pitch, weaponSlot, InteractionHand.MAIN_HAND));
		// The swing is a direct server call, so it must also yield to an operator takeover (whose input lease wins).
		if (step.attack() && !operatorControlled(player)) {
			player.attack(target);
			player.swing(InteractionHand.MAIN_HAND);
			hits++;
			totalHits++;
			ticksWithoutProgress = 0;
		}
		return TickResult.running(progress());
	}

	/**
	 * Approach and step-back only move while facing the target (forward scaled by the remaining turn) and only
	 * onto safe footing: the same two-cell probe flee uses, so a fight on a ravine edge or beside lava holds
	 * position instead of walking off or across it.
	 */
	private static float safeForward(ServerPlayer player, float forward, float yaw, float yawError) {
		float aligned = CombatPlanning.alignedForward(forward, yawError);
		if (aligned == 0.0F) return 0.0F;
		float moveYaw = aligned > 0.0F ? yaw : yaw + 180.0F;
		GridPosition feet = new GridPosition(Mth.floor(player.getX()), Mth.floor(player.getY() + 0.2D), Mth.floor(player.getZ()));
		return CombatPlanning.canStep(new MinecraftNavigationWorld(player.level()), feet, moveYaw) ? aligned : 0.0F;
	}

	/**
	 * Next attacker after the target dies: the nearest one (never a creeper), or under highest_risk the riskiest.
	 * Players count only while they are actively attacking the agent.
	 */
	private LivingEntity nextAttacker(ServerPlayer player) {
		List<LivingEntity> hostiles = nearbyHostiles(player);
		if (targetPolicy != CombatPlanning.TargetPolicy.NAMED) {
			int index = CombatPlanning.policyRetarget(targetPolicy, null, policyCandidates(player, hostiles), 0);
			return index < 0 ? null : hostiles.get(index);
		}
		List<CombatPlanning.AttackerCandidate> candidates = new ArrayList<>(hostiles.size());
		for (LivingEntity hostile : hostiles) {
			candidates.add(new CombatPlanning.AttackerCandidate(player.distanceTo(hostile), attacking(player, hostile),
					player.getLastHurtByMob() == hostile, hostile instanceof Creeper));
		}
		int index = CombatPlanning.nextAttacker(candidates);
		return index < 0 ? null : hostiles.get(index);
	}

	/** The model-chosen live policy: every half second, switch only to a clearly better attacker. */
	private void applyTargetPolicy(ServerPlayer player) {
		if (targetPolicy == CombatPlanning.TargetPolicy.NAMED || --policyCountdown > 0) return;
		policyCountdown = CombatPlanning.POLICY_INTERVAL_TICKS;
		List<LivingEntity> hostiles = nearbyHostiles(player);
		if (hostiles.isEmpty()) return;
		RiskAssessment.Assessment current = RiskAssessment.assess(player, target);
		int index = CombatPlanning.policyRetarget(targetPolicy, new CombatPlanning.PolicyCandidate(player.distanceTo(target),
				current.risk(), true, target instanceof Creeper), policyCandidates(player, hostiles), ticksOnTarget);
		if (index < 0) return;
		policySwitches++;
		switchTo(hostiles.get(index));
	}

	private List<CombatPlanning.PolicyCandidate> policyCandidates(ServerPlayer player, List<LivingEntity> hostiles) {
		List<CombatPlanning.PolicyCandidate> candidates = new ArrayList<>(hostiles.size());
		for (LivingEntity hostile : hostiles) {
			candidates.add(new CombatPlanning.PolicyCandidate(player.distanceTo(hostile), RiskAssessment.assess(player, hostile).risk(),
					attacking(player, hostile), hostile instanceof Creeper));
		}
		return candidates;
	}

	private static boolean attacking(ServerPlayer player, LivingEntity hostile) {
		return (hostile instanceof Mob mob && mob.getTarget() == player) || player.getLastHurtByMob() == hostile
				|| RiskAssessment.attackedRecently(player, hostile);
	}

	/**
	 * Candidates for follow-through and policy switches: hostile mobs and creatures actively attacking the agent.
	 * Players only when the model opted in with includePlayers (never creative or spectator players).
	 */
	private List<LivingEntity> nearbyHostiles(ServerPlayer player) {
		return player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(CombatPlanning.THREAT_RANGE),
				entity -> entity != target && entity != player && switchable(entity, includePlayers)
						&& ThreatPerception.isActiveThreat(player, entity));
	}

	/** Pure gate: a player is only a follow-through or policy candidate when the model opted in. */
	static boolean switchable(LivingEntity entity, boolean includePlayers) {
		return CombatPlanning.switchableKind(entity instanceof net.minecraft.world.entity.player.Player, includePlayers);
	}

	/** Takes over the weapon slot and swing timing of a fight the model just replaced (replaceAction retarget). */
	private void inheritHandoff(ServerPlayer player) {
		handoffChecked = true;
		Handoff handoff;
		synchronized (HANDOFFS) {
			handoff = HANDOFFS.remove(player.getUUID());
		}
		long now = player.level().getGameTime();
		if (handoff == null || handoff.gameTime() > now || now - handoff.gameTime() > HANDOFF_TICKS) return;
		if (handoff.weaponSlot() >= 0 && HotbarWeapons.rank(player.getInventory().getItem(handoff.weaponSlot())) > 0) {
			weaponSlot = handoff.weaponSlot();
		}
		backoffTicks = handoff.backoffTicks();
	}

	private void switchTo(LivingEntity next) {
		target = next;
		targetType = typeOf(next);
		hits = 0;
		backoffTicks = 0;
		ticksOnTarget = 0;
		ticksWithoutProgress = 0;
		closestDistance = Double.MAX_VALUE;
	}

	/** "; kills: 2x minecraft:zombie (7 hits)" once anything died, so every result reports the whole fight. */
	private String kills() {
		if (kills.isEmpty()) return "";
		StringBuilder text = new StringBuilder("; kills: ");
		int written = 0;
		for (Map.Entry<String, Integer> entry : kills.entrySet()) {
			if (written++ > 0) text.append(", ");
			text.append(entry.getValue()).append("x ").append(entry.getKey());
		}
		return text.append(" (").append(totalHits).append(" hits)").toString();
	}

	/** Sensed hostiles still around (creepers included), nearest first, so the model can choose what is next. */
	private String remainingThreats(ServerPlayer player) {
		String switches = policySwitches == 0 ? "" : "; " + targetPolicy.wireName() + " switched target " + policySwitches + "x";
		List<LivingEntity> remaining = new ArrayList<>();
		// Reported to the model even when they are not switch candidates: it can still choose to fight them.
		for (LivingEntity hostile : player.level().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(CombatPlanning.THREAT_RANGE),
				entity -> entity != target && entity != player && ThreatPerception.isActiveThreat(player, entity))) {
			if (ThreatPerception.isSensedThreat(player, hostile)) remaining.add(hostile);
		}
		if (remaining.isEmpty()) return switches + (continueWithAttackers ? "; no other attackers" : "");
		// Highest risk first, the same order as the threats observation, so the model can pick what is next.
		remaining.sort(Comparator.comparingDouble((LivingEntity hostile) -> RiskAssessment.assess(player, hostile).risk()).reversed());
		StringBuilder text = new StringBuilder(switches).append("; remaining threats: ");
		for (int index = 0; index < Math.min(4, remaining.size()); index++) {
			LivingEntity hostile = remaining.get(index);
			if (index > 0) text.append(", ");
			text.append(typeOf(hostile)).append(' ').append(hostile.getUUID())
					.append(String.format(Locale.ROOT, " at %.1f blocks, risk %.0f", player.distanceTo(hostile),
							RiskAssessment.assess(player, hostile).risk()));
			if (hostile instanceof Creeper creeper && creeper.getSwellDir() > 0) text.append(" (swelling)");
		}
		if (remaining.size() > 4) text.append(" and ").append(remaining.size() - 4).append(" more");
		return text.toString();
	}

	private static String typeOf(LivingEntity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
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
		// Remember the stance for a replacement fight_target the model may start right after (a retarget).
		synchronized (HANDOFFS) {
			HANDOFFS.put(player.getUUID(), new Handoff(weaponSlot, backoffTicks, player.level().getGameTime()));
		}
	}
}
