package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.controller.HotbarWeapons;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server-sensed threats for the model: hostile mobs that target the agent, creepers that swell or come
 * within 5 blocks, and ranged mobs with a clear shot. These are awareness facts (a player hears and turns
 * toward them), so unlike the entity list they do not require the mob to be inside the view cone.
 * Signals are latched by {@link ThreatSignalLatch}; a new latched signal raises one urgent attention edge.
 * Players and calm creatures appear only after they hurt the agent ({@link #ATTACKED}, see {@link AggressionLedger}).
 * Each entry carries an open-ended {@link RiskModel} score with its factors and the expected hit after the agent's
 * defenses; entries are reported highest risk first. Risk is a snapshot dominated by distance, so each entry also
 * carries its trend: closing speed, whether it approaches, seconds until it reaches contact range (melee reach, or the
 * 3 blocks at which a creeper lights its fuse) and the risk it will carry there. A hunter that will arrive within
 * {@link #IMMINENT_SECONDS} raises {@link #IMMINENT}, one more urgent edge between first sighting and contact.
 * The model chooses what to do with them.
 */
public final class ThreatPerception {
	public static final double RANGE = 16.0D;
	/** A latched threat is kept while it stays alive within this range, so pacing at 16 blocks cannot re-raise it. */
	public static final double RELEASE_RANGE = 20.0D;
	public static final double CREEPER_CLOSE = 5.0D;
	public static final int MAX_ENTRIES = 8;
	public static final String TARGETING = "targeting";
	public static final String SWELLING = "swelling";
	public static final String CREEPER_CLOSE_SIGNAL = "creeper_close";
	public static final String RANGED_SIGHT = "ranged_sight";
	/** Anyone (player, neutral or hostile mob) that hurt the agent within {@link AggressionLedger#ACTIVE_TICKS}. */
	public static final String ATTACKED = "attacked";
	/** A melee hunter or a creeper that will reach contact range within {@link #IMMINENT_SECONDS} at its closing speed. */
	public static final String IMMINENT = "imminent";
	public static final double IMMINENT_SECONDS = 3.0D;
	/** Closing faster than this (blocks per second) counts as approaching; slower is pacing or standing still. */
	public static final double APPROACH_SPEED = 0.3D;
	/** Vanilla's SwellGoal lights a creeper's fuse once its target is inside 3 blocks. */
	public static final double CREEPER_CONTACT = 3.0D;
	/** About where a hunting melee mob starts landing hits on a player. */
	public static final double MELEE_CONTACT = 2.0D;
	private static final double TICKS_PER_SECOND = 20.0D;

	public record Entry(String uuid, String type, double distance, double bearing, boolean targeting,
			boolean swelling, boolean lineOfSight, List<String> signals, double risk, Map<String, Double> riskFactors,
			double expectedHitDamage, Trend trend, double contactRisk) {
		public Entry(String uuid, String type, double distance, double bearing, boolean targeting,
				boolean swelling, boolean lineOfSight, List<String> signals, double risk, Map<String, Double> riskFactors,
				double expectedHitDamage) {
			this(uuid, type, distance, bearing, targeting, swelling, lineOfSight, signals, risk, riskFactors, expectedHitDamage,
					Trend.NONE, Double.NaN);
		}

		public Entry(String uuid, String type, double distance, double bearing, boolean targeting,
				boolean swelling, boolean lineOfSight, List<String> signals) {
			this(uuid, type, distance, bearing, targeting, swelling, lineOfSight, signals, 0.0D, Map.of(), 0.0D);
		}
	}

	/**
	 * How a threat moves relative to the agent. closingSpeed is blocks per second along the line between them
	 * (positive approaches, negative recedes); etaSeconds is NaN unless it approaches toward a contact range
	 * (ranged attackers already reach from where they stand, so they have none).
	 */
	public record Trend(double closingSpeed, boolean approaching, double contactRange, double etaSeconds) {
		public static final Trend NONE = new Trend(0.0D, false, Double.NaN, Double.NaN);
	}

	public record Snapshot(List<Entry> entries, Set<String> signalKeys, int bestWeaponSlot, String bestWeaponItemId) {
		static final Snapshot EMPTY = new Snapshot(List.of(), Set.of(), -1, null);
	}

	private record Tracked(long tick, Snapshot snapshot) {
	}

	private final Map<AgentId, ThreatSignalLatch> latches = new HashMap<>();
	private final Map<AgentId, Tracked> latest = new HashMap<>();

	/** Samples once per game tick per agent; repeated calls in the same tick return the same snapshot. */
	public synchronized Snapshot sample(AgentId agentId, ServerPlayer agent) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		long tick = agent.level().getGameTime();
		Tracked tracked = latest.get(agentId);
		if (tracked != null && tracked.tick() == tick) return tracked.snapshot();
		Snapshot snapshot = scan(agentId, agent, tick);
		latest.put(agentId, new Tracked(tick, snapshot));
		return snapshot;
	}

	public synchronized void forget(AgentId agentId) {
		latches.remove(agentId);
		latest.remove(agentId);
	}

	public synchronized void retain(Set<AgentId> agents) {
		latches.keySet().retainAll(agents);
		latest.keySet().retainAll(agents);
	}

	/** True for a hostile the agent can sense as a threat even outside its view cone (used by fight/flee). */
	public static boolean isSensedThreat(ServerPlayer agent, Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || entity == agent) return false;
		if (agent.distanceTo(living) > RANGE) return false;
		// Players and other non-hostile attackers count only while they actively attack the agent.
		if (RiskAssessment.attackedRecently(agent, living)) return true;
		if (!(entity instanceof Mob mob) || !isHostileTo(mob, agent)) return false;
		return mob.getTarget() == agent || agent.getLastHurtByMob() == mob || mob.getSensing().hasLineOfSight(agent);
	}

	/**
	 * An active threat for fight follow-through, flee steering and target policies: a hostile mob by the existing
	 * rule, or anyone (players included) that hurt the agent within the last 30 seconds.
	 */
	public static boolean isActiveThreat(ServerPlayer agent, LivingEntity entity) {
		if (!entity.isAlive() || entity.isDeadOrDying() || entity == agent) return false;
		if (!RiskAssessment.isRiskablePlayer(entity)) return false;
		if (entity instanceof Mob mob && isHostileTo(mob, agent)) return true;
		return RiskAssessment.attackedRecently(agent, entity);
	}

	/**
	 * Neutral mobs (endermen, zombified piglins, piglins that tolerate gold armour, wolves, bees...) only count
	 * as hostile once they target this agent; attacking a calm one would start a fight the model did not want.
	 */
	public static boolean isNeutral(Mob mob) {
		return mob instanceof NeutralMob || mob instanceof AbstractPiglin;
	}

	public static boolean isHostileTo(Mob mob, ServerPlayer agent) {
		return mob instanceof Enemy && (!isNeutral(mob) || mob.getTarget() == agent);
	}

	/**
	 * The signals one mob raises right now. Pure so the rules verify without a server: neutral mobs signal only
	 * while targeting the agent, and a close creeper counts only when it can see or is hunting the agent
	 * (one behind a wall cannot reach it).
	 */
	static List<String> signals(boolean neutral, boolean targeting, boolean creeper, boolean swelling, boolean ranged,
			boolean sight, double distance) {
		return signals(neutral, targeting, creeper, swelling, ranged, sight, distance, false);
	}

	/** As above, plus {@link #ATTACKED} for anyone that hurt the agent recently (the only signal a player raises). */
	static List<String> signals(boolean neutral, boolean targeting, boolean creeper, boolean swelling, boolean ranged,
			boolean sight, double distance, boolean attacked) {
		return signals(neutral, targeting, creeper, swelling, ranged, sight, distance, attacked, Double.NaN);
	}

	/**
	 * As above, plus {@link #IMMINENT} for a melee hunter (or a creeper that sees the agent) due at contact range
	 * within {@link #IMMINENT_SECONDS}; {@code etaSeconds} is NaN when it is not approaching.
	 */
	static List<String> signals(boolean neutral, boolean targeting, boolean creeper, boolean swelling, boolean ranged,
			boolean sight, double distance, boolean attacked, double etaSeconds) {
		List<String> signals = new ArrayList<>(6);
		if (distance > RANGE) return signals;
		if (!neutral || targeting) {
			if (targeting) signals.add(TARGETING);
			if (swelling) signals.add(SWELLING);
			if (creeper && distance <= CREEPER_CLOSE && (sight || targeting)) signals.add(CREEPER_CLOSE_SIGNAL);
			if (ranged && sight) signals.add(RANGED_SIGHT);
			if (!ranged && (targeting || (creeper && sight)) && etaSeconds <= IMMINENT_SECONDS) signals.add(IMMINENT);
		}
		// attacked never duplicates another signal for the same creature.
		if (attacked && signals.isEmpty()) signals.add(ATTACKED);
		return signals;
	}

	/**
	 * Horizontal closing speed in blocks per second from both velocities (blocks per tick), projected on the line from
	 * the threat to the agent. Pure so it verifies without a server; vertical motion is left out because gravity's
	 * constant pull on grounded bodies would read as motion.
	 */
	static double closingSpeed(double agentX, double agentZ, double agentVelX, double agentVelZ,
			double threatX, double threatZ, double threatVelX, double threatVelZ) {
		double dx = agentX - threatX;
		double dz = agentZ - threatZ;
		double length = Math.hypot(dx, dz);
		if (!(length > 1.0E-6D)) return 0.0D;
		double closingPerTick = ((threatVelX - agentVelX) * dx + (threatVelZ - agentVelZ) * dz) / length;
		return Double.isFinite(closingPerTick) ? closingPerTick * TICKS_PER_SECOND : 0.0D;
	}

	/** Trend facts for one threat; see {@link Trend}. A body already at contact range has an ETA of 0. */
	static Trend trend(double distance, double closingSpeed, boolean creeper, boolean ranged) {
		double speed = Double.isFinite(closingSpeed) ? closingSpeed : 0.0D;
		boolean approaching = speed >= APPROACH_SPEED;
		if (ranged) return new Trend(round(speed), approaching, Double.NaN, Double.NaN);
		double contact = creeper ? CREEPER_CONTACT : MELEE_CONTACT;
		double eta = distance <= contact ? 0.0D : approaching ? (distance - contact) / speed : Double.NaN;
		return new Trend(round(speed), approaching, contact, Double.isFinite(eta) ? round(eta) : Double.NaN);
	}

	private Snapshot scan(AgentId agentId, ServerPlayer agent, long tick) {
		// Hostile mobs, plus any creature or player that hurt the agent recently. Players are otherwise only
		// potential risks (entity rows) and never appear here just for being near or armed.
		boolean anyAttackers = AggressionLedger.server().hasAttackers(agent.getUUID(), tick);
		List<LivingEntity> candidates = agent.level().getEntitiesOfClass(LivingEntity.class, agent.getBoundingBox().inflate(RELEASE_RANGE),
				entity -> entity != agent && entity.isAlive() && !(entity instanceof EnderDragon) && !(entity instanceof WitherBoss)
						&& (entity instanceof Enemy || (anyAttackers && RiskAssessment.attackedRecently(agent, entity))));
		Set<String> raw = new HashSet<>();
		Set<String> present = new HashSet<>();
		Map<String, LivingEntity> byId = new HashMap<>();
		Map<String, double[]> geometry = new HashMap<>();
		Map<String, boolean[]> flags = new HashMap<>();
		Map<String, Trend> trends = new HashMap<>();
		Vec3 agentVelocity = agent.getDeltaMovement();
		for (LivingEntity entity : candidates) {
			double distance = agent.distanceTo(entity);
			if (distance > RELEASE_RANGE) continue;
			String id = entity.getUUID().toString();
			boolean attacked = anyAttackers && RiskAssessment.attackedRecently(agent, entity);
			Mob mob = entity instanceof Mob value ? value : null;
			boolean targeting = mob != null && mob.getTarget() == agent;
			boolean creeper = entity instanceof Creeper;
			boolean swelling = creeper && ((Creeper) entity).getSwellDir() > 0;
			boolean ranged = mob != null && isRanged(mob);
			// The mob's own sensing caches line of sight for this tick (its AI asks the same question).
			boolean sight = distance <= RANGE && (mob != null
					? (targeting || creeper || ranged) && mob.getSensing().hasLineOfSight(agent)
					: attacked && agent.hasLineOfSight(entity));
			present.add(id);
			byId.put(id, entity);
			geometry.put(id, new double[] {distance, bearing(agent, entity)});
			flags.put(id, new boolean[] {targeting, swelling, sight});
			// A player's server-side delta is not its walking motion; its known speed is (as in RiskAssessment).
			Vec3 velocity = entity instanceof Player ? entity.getKnownSpeed() : entity.getDeltaMovement();
			Trend trend = trend(distance, closingSpeed(agent.getX(), agent.getZ(), agentVelocity.x, agentVelocity.z,
					entity.getX(), entity.getZ(), velocity.x, velocity.z), creeper, ranged);
			trends.put(id, trend);
			boolean neutral = mob == null || isNeutral(mob) || !(entity instanceof Enemy);
			// attacked is only for players and neutral or passive creatures; a hostile mob's hit is already damage attention.
			for (String signal : signals(neutral, targeting, creeper, swelling, ranged, sight, distance, attacked && neutral,
					trend.etaSeconds())) {
				raw.add(ThreatSignalLatch.key(id, signal));
			}
		}
		ThreatSignalLatch latch = latches.computeIfAbsent(agentId, ignored -> new ThreatSignalLatch());
		Set<String> latched = latch.update(raw, present, tick);
		Map<String, Long> firstSeen = new HashMap<>();
		Map<String, List<String>> signals = new HashMap<>();
		for (String key : latched) {
			signals.computeIfAbsent(ThreatSignalLatch.threatId(key), ignored -> new ArrayList<>()).add(ThreatSignalLatch.signal(key));
			firstSeen.merge(ThreatSignalLatch.threatId(key), latch.firstSeen(key), Math::min);
		}
		if (signals.isEmpty()) return Snapshot.EMPTY;
		// With more than 8 threats, report the 8 latched first. A distance cut would reshuffle as mobs move and
		// every reshuffle would look like a new signal; first-seen order only changes when a threat clears.
		// Risk is assessed only for the kept threats, after the cut, to keep the per-tick cost bounded.
		List<String> kept = new ArrayList<>(signals.keySet());
		kept.sort(Comparator.<String>comparingLong(firstSeen::get).thenComparing(Comparator.naturalOrder()));
		List<Entry> limited = new ArrayList<>(Math.min(MAX_ENTRIES, kept.size()));
		for (String id : kept.subList(0, Math.min(MAX_ENTRIES, kept.size()))) {
			LivingEntity entity = byId.get(id);
			double[] where = geometry.get(id);
			boolean[] state = flags.get(id);
			RiskAssessment.Assessment assessment = RiskAssessment.assess(agent, entity);
			Trend trend = trends.get(id);
			// Only an approaching body still outside contact range has a different "risk at contact" worth reporting.
			double contactRisk = trend.approaching() && Double.isFinite(trend.contactRange()) && where[0] > trend.contactRange()
					? RiskAssessment.contactRisk(agent, entity, trend.contactRange()) : Double.NaN;
			limited.add(new Entry(id, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
					round(where[0]), round(where[1]), state[0], state[1], state[2], List.copyOf(signals.get(id)),
					assessment.risk(), assessment.model().factors(), assessment.hit().damage(), trend, contactRisk));
		}
		limited.sort(RISK_ORDER);
		int weaponSlot = HotbarWeapons.bestSlotOrNone(agent);
		ItemStack weapon = weaponSlot < 0 ? ItemStack.EMPTY : agent.getInventory().getItem(weaponSlot);
		// signalKeys is the full latched set, so forced delivery tracks real edges, not the output cut.
		return new Snapshot(List.copyOf(limited), Set.copyOf(latched), weaponSlot,
				weapon.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString());
	}

	/** Highest risk first; equal risk falls back to nearest, then uuid, so the order is stable. */
	static final Comparator<Entry> RISK_ORDER = Comparator.comparingDouble(Entry::risk).reversed()
			.thenComparingDouble(Entry::distance).thenComparing(Entry::uuid);

	static boolean isRanged(Mob mob) {
		// Drowned implement the ranged interface for tridents but mostly melee; treat them as melee.
		return (mob instanceof RangedAttackMob && !(mob instanceof Drowned)) || mob instanceof Blaze || mob instanceof Ghast;
	}

	/** Same convention as landmark bearings: degrees to turn from the current view, negative is left. */
	private static double bearing(ServerPlayer agent, Entity entity) {
		Vec3 delta = entity.position().subtract(agent.position());
		double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
		return Mth.wrapDegrees((float) (yaw - agent.getYRot()));
	}

	private static double round(double value) {
		return Math.round(value * 10.0D) / 10.0D;
	}

	/** Observation section, or null when nothing is threatening the agent (the field is then omitted). */
	public static JsonObject toJson(Snapshot snapshot) {
		if (snapshot.entries().isEmpty()) return null;
		JsonObject json = new JsonObject();
		JsonArray entries = new JsonArray();
		for (Entry entry : snapshot.entries()) {
			JsonObject value = new JsonObject();
			value.addProperty("uuid", entry.uuid());
			value.addProperty("type", entry.type());
			value.addProperty("distance", entry.distance());
			value.addProperty("bearing", entry.bearing());
			value.addProperty("targeting", entry.targeting());
			value.addProperty("swelling", entry.swelling());
			value.addProperty("lineOfSight", entry.lineOfSight());
			JsonArray signals = new JsonArray();
			entry.signals().stream().sorted().forEach(signals::add);
			value.add("signals", signals);
			value.addProperty("risk", entry.risk());
			JsonObject factors = new JsonObject();
			entry.riskFactors().forEach(factors::addProperty);
			value.add("riskFactors", factors);
			value.addProperty("expectedHitDamage", entry.expectedHitDamage());
			value.addProperty("closingSpeed", entry.trend().closingSpeed());
			value.addProperty("approaching", entry.trend().approaching());
			if (Double.isFinite(entry.trend().etaSeconds())) value.addProperty("etaSeconds", entry.trend().etaSeconds());
			if (Double.isFinite(entry.contactRisk())) value.addProperty("contactRisk", entry.contactRisk());
			entries.add(value);
		}
		json.add("entries", entries);
		if (snapshot.bestWeaponSlot() >= 0) {
			JsonObject weapon = new JsonObject();
			weapon.addProperty("slot", snapshot.bestWeaponSlot());
			weapon.addProperty("itemId", snapshot.bestWeaponItemId());
			json.add("bestWeapon", weapon);
		}
		return json;
	}
}
