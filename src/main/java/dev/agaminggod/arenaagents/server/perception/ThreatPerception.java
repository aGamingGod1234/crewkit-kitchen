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
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
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

	/** True for an enemy the agent can sense as a threat even outside its view cone (used by fight/flee). */
	public static boolean isSensedThreat(ServerPlayer agent, Entity entity) {
		if (!(entity instanceof Mob mob) || !(entity instanceof Enemy) || !mob.isAlive()) return false;
		if (agent.distanceTo(mob) > RANGE) return false;
		return mob.getTarget() == agent || agent.getLastHurtByMob() == mob || mob.hasLineOfSight(agent);
	}

	private Snapshot scan(AgentId agentId, ServerPlayer agent, long tick) {
		List<Mob> mobs = agent.level().getEntitiesOfClass(Mob.class, agent.getBoundingBox().inflate(RANGE),
				mob -> mob instanceof Enemy && mob.isAlive() && !(mob instanceof EnderDragon) && !(mob instanceof WitherBoss));
		Set<String> raw = new HashSet<>();
		Set<String> present = new HashSet<>();
		Map<String, Mob> byId = new HashMap<>();
		Map<String, double[]> geometry = new HashMap<>();
		Map<String, boolean[]> flags = new HashMap<>();
		for (Mob mob : mobs) {
			double distance = agent.distanceTo(mob);
			if (distance > RANGE) continue;
			String id = mob.getUUID().toString();
			boolean targeting = mob.getTarget() == agent;
			boolean creeper = mob instanceof Creeper;
			boolean swelling = creeper && ((Creeper) mob).getSwellDir() > 0;
			boolean ranged = isRanged(mob);
			// Line of sight is a ray cast; only pay for it where a signal depends on it.
			boolean sight = (targeting || creeper || ranged) && mob.hasLineOfSight(agent);
			present.add(id);
			byId.put(id, mob);
			geometry.put(id, new double[] {distance, bearing(agent, mob)});
			flags.put(id, new boolean[] {targeting, swelling, sight});
			if (targeting) raw.add(ThreatSignalLatch.key(id, TARGETING));
			if (swelling) raw.add(ThreatSignalLatch.key(id, SWELLING));
			if (creeper && distance <= CREEPER_CLOSE) raw.add(ThreatSignalLatch.key(id, CREEPER_CLOSE_SIGNAL));
			if (ranged && sight) raw.add(ThreatSignalLatch.key(id, RANGED_SIGHT));
		}
		Set<String> latched = latches.computeIfAbsent(agentId, ignored -> new ThreatSignalLatch()).update(raw, present, tick);
		Map<String, List<String>> signals = new HashMap<>();
		for (String key : latched) {
			signals.computeIfAbsent(ThreatSignalLatch.threatId(key), ignored -> new ArrayList<>()).add(ThreatSignalLatch.signal(key));
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
		entries.sort(Comparator.comparingDouble(Entry::distance));
		List<Entry> limited = List.copyOf(entries.subList(0, Math.min(MAX_ENTRIES, entries.size())));
		Set<String> reported = new HashSet<>();
		for (Entry entry : limited) for (String signal : entry.signals()) reported.add(ThreatSignalLatch.key(entry.uuid(), signal));
		int weaponSlot = HotbarWeapons.bestSlotOrNone(agent);
		ItemStack weapon = weaponSlot < 0 ? ItemStack.EMPTY : agent.getInventory().getItem(weaponSlot);
		return new Snapshot(limited, Set.copyOf(reported), weaponSlot,
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
