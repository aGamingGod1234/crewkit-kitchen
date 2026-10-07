package dev.agaminggod.arenaagents.server.perception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, open-ended risk score for one creature or player relative to one agent. It is a fact the model reads to
 * choose its own targets and retreats; nothing in the mod acts on it by itself.
 *
 * <p>risk = {@link #BASE} x proximity x health^0.5 x speed^0.5 x size^0.35 x damage^0.5 x behaviour, where every
 * factor is 1.0 for the reference opponent: an ordinary zombie (20 HP, 0.6 x 1.95 hitbox, movement 0.23, a 3 HP hit
 * on an unarmoured agent) standing 3 blocks away scores about 45, about 56 while it hunts the agent. Lucas's four
 * factors are primary: smaller hitbox, more health, more speed and less distance all raise it.
 * <ul>
 * <li>proximity = 3 / (distance + 3): 1.0 touching, 0.5 at 3 blocks, 0.16 at 16, so distance dominates up close.
 * A ranged attacker with a clear line of sight keeps at least 0.4 (an arrow does not care about distance).</li>
 * <li>health = effective health / 20, where effective health counts armour (health x (1 + armour/25)).</li>
 * <li>speed = movement speed relative to the type's ordinary baseline (0.23 for mobs, 0.1 for players), or the
 * observed horizontal speed / 0.2 blocks per tick when that is higher (a sprinting player, a charging ravager).</li>
 * <li>size = (0.6 x 1.95) / (width x height): a silverfish is harder to hit and scores 2.2, a ghast 0.4.</li>
 * <li>damage = expected hit damage on this agent (after its armour and protection) / 3.</li>
 * <li>behaviour = 1.25 while hunting the agent; a swelling creeper x (2 + 2 x fuse), up to x4 just before it
 * explodes.</li>
 * </ul>
 * There is no upper clamp: boosted or modded opponents keep rising. Only non-finite inputs are neutralised, and a
 * non-finite product is reported as {@link #NON_FINITE_RISK}.
 */
public final class RiskModel {
	public static final double BASE = 90.0D;
	public static final double PROXIMITY_HALF_DISTANCE = 3.0D;
	public static final double RANGED_PROXIMITY_FLOOR = 0.4D;
	public static final double REFERENCE_HEALTH = 20.0D;
	public static final double ARMOR_HEALTH_DIVISOR = 25.0D;
	public static final double MOB_REFERENCE_SPEED = 0.23D;
	public static final double PLAYER_REFERENCE_SPEED = 0.1D;
	public static final double OBSERVED_REFERENCE_SPEED = 0.2D;
	public static final double REFERENCE_HITBOX_AREA = 0.6D * 1.95D;
	public static final double REFERENCE_HIT_DAMAGE = 3.0D;
	public static final double HEALTH_WEIGHT = 0.5D;
	public static final double SPEED_WEIGHT = 0.5D;
	public static final double SIZE_WEIGHT = 0.35D;
	public static final double DAMAGE_WEIGHT = 0.5D;
	public static final double HUNTING_MULTIPLIER = 1.25D;
	public static final double NON_FINITE_RISK = 1_000_000.0D;
	/** Floors that keep a zero (an unarmed player's 0 damage, a stationary mob) from erasing the other factors. */
	private static final double MIN_RATIO = 0.05D;

	private RiskModel() {
	}

	public record Input(
			double width,
			double height,
			double health,
			double armor,
			boolean player,
			double speedAttribute,
			double observedSpeed,
			double distance,
			double expectedHitDamage,
			boolean ranged,
			boolean lineOfSight,
			boolean hunting,
			boolean swelling,
			double fuse
	) {
	}

	/** Every factor rounded to two decimals so the model can see why one opponent outranks another. */
	public record Result(double risk, double proximity, double health, double speed, double size, double damage,
			double behaviour) {
		public Map<String, Double> factors() {
			Map<String, Double> factors = new LinkedHashMap<>();
			factors.put("proximity", round2(proximity));
			factors.put("health", round2(health));
			factors.put("speed", round2(speed));
			factors.put("size", round2(size));
			factors.put("damage", round2(damage));
			factors.put("behaviour", round2(behaviour));
			return factors;
		}
	}

	public static Result score(Input input) {
		double distance = Math.max(0.0D, finite(input.distance(), 0.0D));
		double proximity = PROXIMITY_HALF_DISTANCE / (distance + PROXIMITY_HALF_DISTANCE);
		if (input.ranged() && input.lineOfSight()) proximity = Math.max(proximity, RANGED_PROXIMITY_FLOOR);

		double armor = Math.max(0.0D, finite(input.armor(), 0.0D));
		double effectiveHealth = Math.max(0.0D, finite(input.health(), REFERENCE_HEALTH)) * (1.0D + armor / ARMOR_HEALTH_DIVISOR);
		double health = ratio(effectiveHealth, REFERENCE_HEALTH);

		double baseline = input.player() ? PLAYER_REFERENCE_SPEED : MOB_REFERENCE_SPEED;
		double attributeSpeed = finite(input.speedAttribute(), baseline) / baseline;
		double observedSpeed = finite(input.observedSpeed(), 0.0D) / OBSERVED_REFERENCE_SPEED;
		double speed = Math.max(MIN_RATIO, Math.max(attributeSpeed, observedSpeed));

		double area = finite(input.width(), 0.6D) * finite(input.height(), 1.95D);
		double size = area > 0.0D ? REFERENCE_HITBOX_AREA / area : REFERENCE_HITBOX_AREA / 0.01D;

		double damage = ratio(Math.max(0.0D, finite(input.expectedHitDamage(), REFERENCE_HIT_DAMAGE)), REFERENCE_HIT_DAMAGE);

		double behaviour = input.hunting() ? HUNTING_MULTIPLIER : 1.0D;
		if (input.swelling()) behaviour *= 2.0D + 2.0D * Math.min(1.0D, Math.max(0.0D, finite(input.fuse(), 0.0D)));

		double healthTerm = Math.pow(health, HEALTH_WEIGHT);
		double speedTerm = Math.pow(speed, SPEED_WEIGHT);
		double sizeTerm = Math.pow(size, SIZE_WEIGHT);
		double damageTerm = Math.pow(damage, DAMAGE_WEIGHT);
		double risk = BASE * proximity * healthTerm * speedTerm * sizeTerm * damageTerm * behaviour;
		if (!Double.isFinite(risk)) risk = NON_FINITE_RISK;
		return new Result(Math.round(risk * 10.0D) / 10.0D, proximity, healthTerm, speedTerm, sizeTerm, damageTerm, behaviour);
	}

	private static double ratio(double value, double reference) {
		return Math.max(MIN_RATIO, value / reference);
	}

	private static double finite(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
	}

	static double round2(double value) {
		return Double.isFinite(value) ? Math.round(value * 100.0D) / 100.0D : NON_FINITE_RISK;
	}
}
