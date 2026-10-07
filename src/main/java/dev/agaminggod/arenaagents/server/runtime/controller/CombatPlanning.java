package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import java.util.List;
import java.util.Objects;

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
		if (previousYaw != null && Math.abs(wrap(previousYaw - awayYaw)) <= STICKY_HEADING_DEGREES) {
			Probe sticky = probe(world, feet, previousYaw);
			if (sticky.passable()) return new Heading(wrap(previousYaw), sticky.jump(), true);
		}
		for (int offset : FLEE_OFFSETS) {
			float yaw = wrap(awayYaw + offset);
			Probe probe = probe(world, feet, yaw);
			if (probe.passable()) return new Heading(yaw, probe.jump(), true);
		}
		// Boxed in: keep pushing away and jumping; the action's timeout and the model handle a true dead end.
		return new Heading(wrap(awayYaw), true, false);
	}

	record Probe(boolean passable, boolean jump) {
	}

	static Probe probe(WalkabilityView world, GridPosition feet, float yaw) {
		double radians = Math.toRadians(yaw);
		double dx = -Math.sin(radians);
		double dz = Math.cos(radians);
		int y = feet.y();
		boolean jump = false;
		for (int step = 1; step <= 2; step++) {
			int x = feet.x() + (int) Math.round(dx * step);
			int z = feet.z() + (int) Math.round(dz * step);
			if (x == feet.x() && z == feet.z()) continue;
			GridPosition level = new GridPosition(x, y, z);
			if (world.traversalAt(level) != null) continue;
			GridPosition up = level.above();
			if (world.traversalAt(up) != null && world.isBodyClear(new GridPosition(feet.x(), y + 2, feet.z()))) {
				if (step == 1) jump = true;
				y += 1;
				continue;
			}
			if (world.traversalAt(level.below()) != null && world.isBodyClear(level) && world.isBodyClear(level.above())) {
				y -= 1;
				continue;
			}
			if (world.traversalAt(level.below(2)) != null && world.isBodyClear(level) && world.isBodyClear(level.above())
					&& world.isBodyClear(level.below())) {
				y -= 2;
				continue;
			}
			return new Probe(false, false);
		}
		return new Probe(true, jump);
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
