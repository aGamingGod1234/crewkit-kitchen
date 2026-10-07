package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure decisions behind the model-chosen fight_target and flee_from actions. Nothing here reads the
 * world directly, so weapon choice, melee pacing, flee completion and flee steering verify without a server.
 * The model decides whether to fight or flee; these rules only make the chosen action effective.
 */
public final class CombatPlanning {
	/** Vanilla deals full melee damage near a full attack-strength charge; earlier clicks waste the swing. */
	public static final float READY_ATTACK_STRENGTH = 0.9F;
	/** Short step back after each hit, which also lets vanilla knockback open space before the next swing. */
	public static final int BACKOFF_TICKS = 4;
	public static final float BACKOFF_FORWARD = -0.6F;
	public static final double DEFAULT_FIGHT_RANGE = 2.5D;
	/** A flee only ends once the target has not gained ground over this window (half a second). */
	public static final int NOT_CLOSING_WINDOW_TICKS = 10;
	/** Ticks without targeting or sight before a chaser counts as having lost the agent. */
	public static final int LOST_TICKS = 20;

	private static final int[] FLEE_OFFSETS = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150};
	private static final float STICKY_HEADING_DEGREES = 45.0F;

	private CombatPlanning() {
	}

	/** 3 sword, 2 axe or spear, 1 other tool or weapon (pickaxe, shovel, hoe, mace, trident), 0 not a weapon. */
	public static int weaponRank(boolean sword, boolean axeOrSpear, boolean otherWeapon) {
		if (sword) return 3;
		if (axeOrSpear) return 2;
		return otherWeapon ? 1 : 0;
	}

	public record WeaponCandidate(int slot, int rank, int maxDamage) {
		public WeaponCandidate {
			if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be a hotbar slot");
		}
	}

	/**
	 * Best hotbar weapon: highest rank, then the most durable (a proxy for tier: wood < stone < iron < diamond),
	 * then the current slot so an equal choice never causes a visible swap. With no weapon the current slot stays.
	 */
	public static int bestWeaponSlot(List<WeaponCandidate> hotbar, int currentSlot) {
		Objects.requireNonNull(hotbar, "hotbar must not be null");
		WeaponCandidate best = null;
		for (WeaponCandidate candidate : hotbar) {
			if (candidate.rank() <= 0) continue;
			if (best == null || compare(candidate, best, currentSlot) > 0) best = candidate;
		}
		return best == null ? currentSlot : best.slot();
	}

	private static int compare(WeaponCandidate left, WeaponCandidate right, int currentSlot) {
		if (left.rank() != right.rank()) return Integer.compare(left.rank(), right.rank());
		if (left.maxDamage() != right.maxDamage()) return Integer.compare(left.maxDamage(), right.maxDamage());
		return Boolean.compare(left.slot() == currentSlot, right.slot() == currentSlot);
	}

	public record FightInput(
			double distance,
			double desiredRange,
			boolean inReach,
			boolean aimed,
			boolean lineOfSight,
			float attackStrength,
			boolean weaponReady,
			int backoffTicks
	) {
	}

	public record FightStep(float forward, boolean sprint, boolean attack, int backoffTicks) {
	}

	/** One melee tick: back off after a hit, swing only at a full charge, otherwise close to (or hold) range. */
	public static FightStep fightStep(FightInput input) {
		Objects.requireNonNull(input, "input must not be null");
		if (input.backoffTicks() > 0) return new FightStep(BACKOFF_FORWARD, false, false, input.backoffTicks() - 1);
		if (input.inReach() && input.aimed() && input.lineOfSight() && input.weaponReady()
				&& input.attackStrength() >= READY_ATTACK_STRENGTH) {
			return new FightStep(0.0F, false, true, BACKOFF_TICKS);
		}
		if (!input.inReach() || input.distance() > input.desiredRange()) {
			return new FightStep(1.0F, input.distance() > input.desiredRange() + 3.0D, false, 0);
		}
		// Too close lets the target body-block and hit first; a slight retreat keeps it at the edge of reach.
		if (input.distance() < input.desiredRange() - 1.0D) return new FightStep(-0.4F, false, false, 0);
		return new FightStep(0.0F, false, false, 0);
	}

	/** A model-chosen bail-out threshold; absent means fight until the target is dead, gone or time runs out. */
	public static boolean shouldBailOut(float health, Float fleeAtHealth) {
		return fleeAtHealth != null && health <= fleeAtHealth;
	}

	/** Follow-through and multi-threat flee consider hostiles within the threat-sensing range. */
	public static final double THREAT_RANGE = 16.0D;
	/** A flee never reports escape while a creeper is this close: its blast reaches about 6 blocks. */
	public static final double CREEPER_SAFE_DISTANCE = 7.0D;

	/** One hostile near the agent when the chosen fight target dies. */
	public record AttackerCandidate(double distance, boolean targetingAgent, boolean hurtAgent, boolean creeper) {
	}

	/**
	 * The model's fight_target continues (unless it opted out) to the nearest hostile that is targeting or just
	 * hurt the agent. Only mobs already attacking the agent qualify, so follow-through never starts a new fight.
	 * Creepers are never chased into melee; they are reported back so the model chooses fight or flee itself.
	 * Returns the candidate index, or -1 when nobody else is attacking.
	 */
	public static int nextAttacker(List<AttackerCandidate> candidates) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		int best = -1;
		for (int index = 0; index < candidates.size(); index++) {
			AttackerCandidate candidate = candidates.get(index);
			if (candidate.creeper() || !(candidate.targetingAgent() || candidate.hurtAgent())) continue;
			if (!(candidate.distance() <= THREAT_RANGE)) continue;
			if (best < 0 || candidate.distance() < candidates.get(best).distance()) best = index;
		}
		return best;
	}

	/**
	 * Model-chosen fight_target targeting policy. The model picks it; the controller only applies it live.
	 * named: fight the given target (then follow-through, if enabled). highest_risk: keep switching to the attacker
	 * with the highest risk score. nearest_attacker: keep switching to the nearest attacker. Only mobs or players
	 * already attacking the agent qualify and creepers are never chosen, so a policy never starts a new fight.
	 */
	public enum TargetPolicy {
		NAMED("named"), HIGHEST_RISK("highest_risk"), NEAREST_ATTACKER("nearest_attacker");

		private final String wireName;

		TargetPolicy(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static TargetPolicy parse(String value) {
			if (value == null) return NAMED;
			for (TargetPolicy policy : values()) if (policy.wireName.equals(value)) return policy;
			throw new IllegalArgumentException("unknown targetPolicy " + value);
		}
	}

	/** Policies are re-evaluated twice a second. */
	public static final int POLICY_INTERVAL_TICKS = 10;
	/** Hysteresis: stay on a target at least 1.5 s before a policy may switch away from it. */
	public static final int POLICY_MIN_DWELL_TICKS = 30;
	/** Hysteresis: highest_risk switches only to a clearly riskier attacker (25% and 5 points more). */
	public static final double RISK_SWITCH_RATIO = 1.25D;
	public static final double RISK_SWITCH_MARGIN = 5.0D;
	/** Hysteresis: nearest_attacker switches only to an attacker at least 2 blocks nearer. */
	public static final double NEAREST_SWITCH_MARGIN = 2.0D;

	/** One fight candidate for a target policy; attacking means targeting or recently hurting the agent. */
	public record PolicyCandidate(double distance, double risk, boolean attacking, boolean creeper) {
	}

	/** Players (other agents included) are switch candidates only when the model passed includePlayers:true. */
	public static boolean switchableKind(boolean player, boolean includePlayers) {
		return !player || includePlayers;
	}

	static boolean policyEligible(PolicyCandidate candidate) {
		return candidate.attacking() && !candidate.creeper() && candidate.distance() <= THREAT_RANGE;
	}

	/**
	 * The candidate index a live policy switches to, or -1 to keep the current target. {@code current} is the
	 * current target's own facts (null when it is gone, which skips the dwell and margin checks).
	 */
	public static int policyRetarget(TargetPolicy policy, PolicyCandidate current, List<PolicyCandidate> others, int ticksOnTarget) {
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(others, "others must not be null");
		if (policy == TargetPolicy.NAMED) return -1;
		if (current != null && ticksOnTarget < POLICY_MIN_DWELL_TICKS) return -1;
		int best = -1;
		for (int index = 0; index < others.size(); index++) {
			PolicyCandidate candidate = others.get(index);
			if (!policyEligible(candidate)) continue;
			if (best < 0) {
				best = index;
				continue;
			}
			PolicyCandidate leader = others.get(best);
			boolean better = policy == TargetPolicy.HIGHEST_RISK
					? candidate.risk() > leader.risk() || (candidate.risk() == leader.risk() && candidate.distance() < leader.distance())
					: candidate.distance() < leader.distance();
			if (better) best = index;
		}
		if (best < 0 || current == null) return best;
		PolicyCandidate leader = others.get(best);
		boolean clearlyBetter = policy == TargetPolicy.HIGHEST_RISK
				? leader.risk() > current.risk() * RISK_SWITCH_RATIO + RISK_SWITCH_MARGIN
				: leader.distance() < current.distance() - NEAREST_SWITCH_MARGIN;
		return clearlyBetter ? best : -1;
	}

	/**
	 * One threat during a flee. dx/dz point from the agent to the threat; closing means it gained ground over
	 * the last half second (or has not been watched that long yet).
	 */
	public record FleeThreat(double dx, double dz, double distance, boolean creeper, boolean swelling, boolean closing) {
	}

	/** Nearer threats push harder; a creeper weighs three times a zombie and a swelling one six times. */
	static double fleeWeight(FleeThreat threat) {
		double type = threat.swelling() ? 6.0D : threat.creeper() ? 3.0D : 1.0D;
		return type / Math.max(1.0D, threat.distance());
	}

	/**
	 * Direction away from every threat at once: the proximity-weighted sum of "away" unit vectors. When the
	 * pushes cancel out (surrounded evenly) it falls back to straight away from the heaviest threat. Null when
	 * there is no horizontal "away" at all (every threat directly above or below).
	 */
	public static Float fleeAwayYaw(List<FleeThreat> threats) {
		Objects.requireNonNull(threats, "threats must not be null");
		double sumX = 0.0D;
		double sumZ = 0.0D;
		FleeThreat heaviest = null;
		for (FleeThreat threat : threats) {
			double length = Math.sqrt(threat.dx() * threat.dx() + threat.dz() * threat.dz());
			if (length < 1.0E-2D) continue;
			double weight = fleeWeight(threat);
			sumX -= threat.dx() / length * weight;
			sumZ -= threat.dz() / length * weight;
			if (heaviest == null || weight > fleeWeight(heaviest)) heaviest = threat;
		}
		if (heaviest == null) return null;
		if (sumX * sumX + sumZ * sumZ < 1.0E-6D) return yawToward(heaviest.dx(), heaviest.dz(), 0.0D, 0.0D);
		return yawToward(0.0D, 0.0D, sumX, sumZ);
	}

	/**
	 * The nearest threat (other than the named one) that still blocks an escape: any creeper within
	 * {@link #CREEPER_SAFE_DISTANCE}, or a threat inside the requested distance that is still closing in.
	 * Returns its index, or -1 when the flee may report success.
	 */
	public static int escapeBlocker(List<FleeThreat> others, double requestedDistance) {
		Objects.requireNonNull(others, "others must not be null");
		int blocker = -1;
		for (int index = 0; index < others.size(); index++) {
			FleeThreat threat = others.get(index);
			boolean blocks = (threat.creeper() && threat.distance() < CREEPER_SAFE_DISTANCE)
					|| (threat.distance() < requestedDistance && threat.closing());
			if (blocks && (blocker < 0 || threat.distance() < others.get(blocker).distance())) blocker = index;
		}
		return blocker;
	}

	/** Per-threat "is it closing in" over the same half-second window the named flee target uses. */
	public static final class ClosingTracker {
		private final Map<String, double[]> windows = new HashMap<>();
		private final Map<String, Integer> samples = new HashMap<>();

		/** True while the threat gained ground over the window, or has not been watched for a full window. */
		public boolean observe(String id, double distance) {
			Objects.requireNonNull(id, "id must not be null");
			double[] window = windows.computeIfAbsent(id, ignored -> new double[NOT_CLOSING_WINDOW_TICKS + 1]);
			int count = samples.getOrDefault(id, 0);
			double windowStart = window[count % window.length];
			boolean full = count >= window.length;
			window[count % window.length] = distance;
			samples.put(id, count + 1);
			return !full || distance < windowStart - 0.05D;
		}

		/** Forgets threats no longer present so a returning mob is watched afresh. */
		public void retain(Set<String> present) {
			windows.keySet().retainAll(present);
			samples.keySet().retainAll(present);
		}
	}

	/** Tracks one flee so it ends on safety, not on reaching a waypoint. */
	public static final class FleeProgress {
		public enum Outcome { RUNNING, ESCAPED, LOST }

		private final double requestedDistance;
		private final double[] window = new double[NOT_CLOSING_WINDOW_TICKS + 1];
		private int samples;
		private int lostTicks;

		public FleeProgress(double requestedDistance) {
			if (!Double.isFinite(requestedDistance) || requestedDistance <= 0.0D) {
				throw new IllegalArgumentException("requestedDistance must be positive");
			}
			this.requestedDistance = requestedDistance;
		}

		/**
		 * Escaped means at least the requested distance away and the target has not closed in over the last
		 * half second. Lost means a chaser stopped targeting the agent and has no sight of it for a second
		 * while at least half the distance away.
		 */
		public Outcome observe(double distance, boolean targetStillHunting) {
			if (!Double.isFinite(distance)) throw new IllegalArgumentException("distance must be finite");
			double windowStart = window[samples % window.length];
			boolean full = samples >= window.length;
			window[samples % window.length] = distance;
			samples++;
			if (distance >= requestedDistance && full && distance >= windowStart - 0.05D) return Outcome.ESCAPED;
			lostTicks = !targetStillHunting && distance >= requestedDistance * 0.5D ? lostTicks + 1 : 0;
			return lostTicks >= LOST_TICKS ? Outcome.LOST : Outcome.RUNNING;
		}
	}

	public record Heading(float yaw, boolean jump, boolean clear) {
	}

	/**
	 * Picks a flee heading: straight away from the target when the next two cells are walkable, otherwise
	 * the nearest detour (30 degree steps), never into hazards or drops deeper than two blocks. The previous
	 * heading wins while it is still walkable and within 45 degrees of "away", which stops left/right dithering.
	 */
	public static Heading fleeHeading(WalkabilityView world, GridPosition feet, float awayYaw, Float previousYaw) {
		Objects.requireNonNull(world, "world must not be null");
		Objects.requireNonNull(feet, "feet must not be null");
		return fleeHeading(world, feet, awayYaw, previousYaw, SwimPlanning.WATER_PENALTY_DEGREES);
	}

	/**
	 * As {@link #fleeHeading(WalkabilityView, GridPosition, float, Float)} with deep water costed, not blocked: each
	 * candidate costs its offset from "away" plus {@code waterPenaltyDegrees} when the next two cells cross deep water,
	 * so a land escape wins whenever one exists nearby and the flee only swims when water is clearly the best way out.
	 * Once already swimming every heading is water and the cheapest (straightest, or a shore) wins.
	 */
	public static Heading fleeHeading(WalkabilityView world, GridPosition feet, float awayYaw, Float previousYaw,
			float waterPenaltyDegrees) {
		Objects.requireNonNull(world, "world must not be null");
		Objects.requireNonNull(feet, "feet must not be null");
		Heading best = null;
		float bestCost = Float.POSITIVE_INFINITY;
		if (previousYaw != null && Math.abs(wrap(previousYaw - awayYaw)) <= STICKY_HEADING_DEGREES) {
			Probe sticky = probe(world, feet, previousYaw);
			if (sticky.passable() && !sticky.water()) return new Heading(wrap(previousYaw), sticky.jump(), true);
			if (sticky.passable()) {
				best = new Heading(wrap(previousYaw), sticky.jump(), true);
				bestCost = waterPenaltyDegrees + 0.5F;
			}
		}
		for (int offset : FLEE_OFFSETS) {
			float yaw = wrap(awayYaw + offset);
			Probe probe = probe(world, feet, yaw);
			if (!probe.passable()) continue;
			// Equal cost: land wins over water.
			float cost = Math.abs(offset) + (probe.water() ? waterPenaltyDegrees + 0.5F : 0.0F);
			if (cost < bestCost) {
				best = new Heading(yaw, probe.jump(), true);
				bestCost = cost;
			}
		}
		// Boxed in (pillar, ledge over lava, dead-end tunnel): report it instead of jumping off; the model decides.
		return best != null ? best : new Heading(wrap(awayYaw), false, false);
	}

	/** True when the next two cells along {@code yaw} cross deep water (swimming, not walking). */
	public static boolean headingCrossesWater(WalkabilityView world, GridPosition feet, float yaw) {
		Probe probe = probe(world, feet, yaw);
		return probe.passable() && probe.water();
	}

	/**
	 * Nearest land a swimmer can climb onto: a standable (walk) cell within {@code radius} columns, at the feet level
	 * or one to two above (a bank) or one below, nearest horizontally. Null when only water is in reach.
	 */
	public static GridPosition nearestShore(WalkabilityView world, GridPosition feet, int radius) {
		Objects.requireNonNull(world, "world must not be null");
		Objects.requireNonNull(feet, "feet must not be null");
		GridPosition best = null;
		int bestDistance = Integer.MAX_VALUE;
		int[] levels = {0, 1, -1, 2};
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int distance = dx * dx + dz * dz;
				if (distance == 0 || distance > radius * radius || distance >= bestDistance) continue;
				for (int dy : levels) {
					GridPosition candidate = feet.offset(dx, dy, dz);
					if (world.traversalAt(candidate) == TraversalType.WALK) {
						best = candidate;
						bestDistance = distance;
						break;
					}
				}
			}
		}
		return best;
	}

	/** True when walking along {@code yaw} keeps safe footing for the next two cells (the flee probe). */
	public static boolean canStep(WalkabilityView world, GridPosition feet, float yaw) {
		return probe(world, feet, yaw).passable();
	}

	/**
	 * Forward input scaled to how well the body faces its goal: full when aligned, none when 90 degrees or more
	 * off, so a turn toward a target behind the agent never walks it the wrong way first (as stepMotor does).
	 */
	public static float alignedForward(float forward, float yawError) {
		return forward * (float) Math.max(0.0D, Math.cos(Math.toRadians(yawError)));
	}

	/**
	 * Flee and fight only walk, swim or climb; a crouch-only gap would need input they never give. Swimming includes
	 * submerged water with room above (a player swims through it), not just the surface cells the path planner uses.
	 */
	static boolean walkable(WalkabilityView world, GridPosition position) {
		TraversalType traversal = world.traversalAt(position);
		return traversal == TraversalType.WALK || traversal == TraversalType.SWIM || traversal == TraversalType.CLIMB
				|| swimmable(world, position);
	}

	static boolean swimmable(WalkabilityView world, GridPosition position) {
		return world.cellAt(position) == WalkabilityView.Cell.WATER && open(world, position.above());
	}

	/** Room for the body: clear air or water (swimming through it). */
	static boolean open(WalkabilityView world, GridPosition position) {
		return world.isBodyClear(position) || world.cellAt(position) == WalkabilityView.Cell.WATER;
	}

	record Probe(boolean passable, boolean jump, boolean water) {
		Probe(boolean passable, boolean jump) {
			this(passable, jump, false);
		}
	}

	static Probe probe(WalkabilityView world, GridPosition feet, float yaw) {
		double radians = Math.toRadians(yaw);
		double dx = -Math.sin(radians);
		double dz = Math.cos(radians);
		int y = feet.y();
		boolean jump = false;
		boolean water = false;
		for (int step = 1; step <= 2; step++) {
			int x = feet.x() + (int) Math.round(dx * step);
			int z = feet.z() + (int) Math.round(dz * step);
			if (x == feet.x() && z == feet.z()) continue;
			GridPosition level = new GridPosition(x, y, z);
			GridPosition reached;
			if (walkable(world, level)) {
				reached = level;
			} else if (walkable(world, level.above()) && open(world, new GridPosition(feet.x(), y + 2, feet.z()))) {
				if (step == 1) jump = true;
				y += 1;
				reached = level.above();
			} else if (walkable(world, level.below()) && open(world, level) && open(world, level.above())) {
				y -= 1;
				reached = level.below();
			} else if (walkable(world, level.below(2)) && open(world, level) && open(world, level.above())
					&& open(world, level.below())) {
				y -= 2;
				reached = level.below(2);
			} else {
				return new Probe(false, false);
			}
			// Deep water (not one-deep standing water, which the world reports as clear footing) means swimming.
			if (world.cellAt(reached) == WalkabilityView.Cell.WATER) water = true;
		}
		return new Probe(true, jump, water);
	}

	/** Yaw (Minecraft convention) that faces from {@code fromX,fromZ} toward {@code toX,toZ}. */
	public static float yawToward(double fromX, double fromZ, double toX, double toZ) {
		return wrap((float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ)));
	}

	static float wrap(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) wrapped -= 360.0F;
		if (wrapped < -180.0F) wrapped += 360.0F;
		return wrapped;
	}
}
