package dev.agaminggod.arenaagents.server.perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Who actually hurt each player, and when. A player or creature only turns from potential risk into an active threat
 * once it has hurt the agent (or, for hostile mobs, engages it some other way); being near or armed never counts.
 * Active status decays back to potential after {@link #ACTIVE_TICKS} without another hit.
 */
public final class AggressionLedger {
	/** 30 seconds of calm without another hit before an attacker counts as potential again. */
	public static final long ACTIVE_TICKS = 600L;
	private static final AggressionLedger SERVER = new AggressionLedger();
	private static boolean registered;

	private final Map<UUID, Map<UUID, Long>> hits = new HashMap<>();

	/** Records every damage a living attacker deals to a player (projectiles credit their shooter). */
	public static synchronized void register() {
		if (registered) return;
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (!(entity instanceof ServerPlayer victim)) return;
			Entity attacker = source.getEntity();
			if (attacker instanceof LivingEntity && attacker != victim) {
				SERVER.record(victim.getUUID(), attacker.getUUID(), victim.level().getGameTime());
			}
		});
		// Nothing outlives the server (or an integrated-server world switch), and a departed player is forgotten
		// both as a victim and as an attacker.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SERVER.clear());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SERVER.forgetEverywhere(handler.getPlayer().getUUID()));
		registered = true;
	}

	public static AggressionLedger server() {
		return SERVER;
	}

	public synchronized void record(UUID victim, UUID attacker, long gameTime) {
		Objects.requireNonNull(victim, "victim must not be null");
		Objects.requireNonNull(attacker, "attacker must not be null");
		Map<UUID, Long> attackers = hits.computeIfAbsent(victim, ignored -> new HashMap<>());
		attackers.values().removeIf(tick -> gameTime - tick > ACTIVE_TICKS || tick > gameTime);
		attackers.put(attacker, gameTime);
	}

	/** True while the attacker hurt the victim within the active window; expired entries are pruned on read. */
	public synchronized boolean attackedRecently(UUID victim, UUID attacker, long gameTime) {
		Map<UUID, Long> attackers = hits.get(victim);
		if (attackers == null) return false;
		Long tick = attackers.get(attacker);
		if (tick == null) return false;
		if (tick > gameTime || gameTime - tick > ACTIVE_TICKS) {
			attackers.remove(attacker);
			if (attackers.isEmpty()) hits.remove(victim);
			return false;
		}
		return true;
	}

	/** Cheap guard so per-entity lookups are skipped while nobody has hurt this victim recently. */
	public synchronized boolean hasAttackers(UUID victim, long gameTime) {
		Map<UUID, Long> attackers = hits.get(victim);
		if (attackers == null) return false;
		attackers.values().removeIf(tick -> tick > gameTime || gameTime - tick > ACTIVE_TICKS);
		if (attackers.isEmpty()) hits.remove(victim);
		return !attackers.isEmpty();
	}

	public synchronized void forget(UUID victim) {
		hits.remove(victim);
	}

	/** Drops a departed entity both as a victim and as an attacker. */
	public synchronized void forgetEverywhere(UUID id) {
		hits.remove(id);
		hits.values().forEach(attackers -> attackers.remove(id));
		hits.values().removeIf(Map::isEmpty);
	}

	public synchronized void clear() {
		hits.clear();
	}

	synchronized int victims() {
		return hits.size();
	}
}
