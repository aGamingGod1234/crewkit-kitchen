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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server-sensed threats for the model: hostile mobs that target the agent, creepers that swell or come
 * within 5 blocks, and ranged mobs with a clear shot. These are awareness facts (a player hears and turns
 * toward them), so unlike the entity list they do not require the mob to be inside the view cone.
 * Signals are latched by {@link ThreatSignalLatch}; a new latched signal raises one urgent attention edge.
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

	public record Entry(String uuid, String type, double distance, double bearing, boolean targeting,
			boolean swelling, boolean lineOfSight, List<String> signals) {
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
		if (!(entity instanceof Mob mob) || !isHostileTo(mob, agent) || !mob.isAlive()) return false;
		if (agent.distanceTo(mob) > RANGE) return false;
		return mob.getTarget() == agent || agent.getLastHurtByMob() == mob || mob.getSensing().hasLineOfSight(agent);
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
		List<String> signals = new ArrayList<>(4);
		if (distance > RANGE || (neutral && !targeting)) return signals;
		if (targeting) signals.add(TARGETING);
		if (swelling) signals.add(SWELLING);
		if (creeper && distance <= CREEPER_CLOSE && (sight || targeting)) signals.add(CREEPER_CLOSE_SIGNAL);
		if (ranged && sight) signals.add(RANGED_SIGHT);
		return signals;
	}

	private Snapshot scan(AgentId agentId, ServerPlayer agent, long tick) {
		List<Mob> mobs = agent.level().getEntitiesOfClass(Mob.class, agent.getBoundingBox().inflate(RELEASE_RANGE),
				mob -> mob instanceof Enemy && mob.isAlive() && !(mob instanceof EnderDragon) && !(mob instanceof WitherBoss));
		Set<String> raw = new HashSet<>();
		Set<String> present = new HashSet<>();
		Map<String, Mob> byId = new HashMap<>();
		Map<String, double[]> geometry = new HashMap<>();
		Map<String, boolean[]> flags = new HashMap<>();
		for (Mob mob : mobs) {
			double distance = agent.distanceTo(mob);
			if (distance > RELEASE_RANGE) continue;
			String id = mob.getUUID().toString();
			boolean targeting = mob.getTarget() == agent;
			boolean creeper = mob instanceof Creeper;
			boolean swelling = creeper && ((Creeper) mob).getSwellDir() > 0;
			boolean ranged = isRanged(mob);
			// The mob's own sensing caches line of sight for this tick (its AI asks the same question).
			boolean sight = distance <= RANGE && (targeting || creeper || ranged) && mob.getSensing().hasLineOfSight(agent);
			present.add(id);
			byId.put(id, mob);
			geometry.put(id, new double[] {distance, bearing(agent, mob)});
			flags.put(id, new boolean[] {targeting, swelling, sight});
			for (String signal : signals(isNeutral(mob), targeting, creeper, swelling, ranged, sight, distance)) {
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
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, List<String>> threat : signals.entrySet()) {
			Mob mob = byId.get(threat.getKey());
			double[] where = geometry.get(threat.getKey());
			boolean[] state = flags.get(threat.getKey());
			entries.add(new Entry(threat.getKey(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(),
					round(where[0]), round(where[1]), state[0], state[1], state[2], List.copyOf(threat.getValue())));
		}
		// With more than 8 threats, report the 8 latched first. A distance cut would reshuffle as mobs move and
		// every reshuffle would look like a new signal; first-seen order only changes when a threat clears.
		entries.sort(Comparator.<Entry>comparingLong(entry -> firstSeen.get(entry.uuid())).thenComparing(Entry::uuid));
		List<Entry> limited = new ArrayList<>(entries.subList(0, Math.min(MAX_ENTRIES, entries.size())));
		limited.sort(Comparator.comparingDouble(Entry::distance));
		int weaponSlot = HotbarWeapons.bestSlotOrNone(agent);
		ItemStack weapon = weaponSlot < 0 ? ItemStack.EMPTY : agent.getInventory().getItem(weaponSlot);
		// signalKeys is the full latched set, so forced delivery tracks real edges, not the output cut.
		return new Snapshot(List.copyOf(limited), Set.copyOf(latched), weaponSlot,
				weapon.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString());
	}

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
