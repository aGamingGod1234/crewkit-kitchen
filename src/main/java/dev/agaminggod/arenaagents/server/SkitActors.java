package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.*;
import dev.agaminggod.arenaagents.server.voice.VoiceDirector;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Relative;

/** Physical cast lifecycle. Actors never enter AgentRegistry or coordinator publication. */
public final class SkitActors {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SkitActors.class);
	private static final Map<MinecraftServer, Set<AgentId>> RESTORED = new HashMap<>();
	private static final Map<MinecraftServer, Set<AgentId>> CONNECTED = new HashMap<>();
	private static final Map<MinecraftServer, PendingSpawnCancellationLedger> CANCELLED = new HashMap<>();
	private SkitActors() { }
	public static List<SkitActor> records(MinecraftServer server) { return SkitModeSavedData.get(server).actors(); }
	public static SkitActor resolve(MinecraftServer server, String selector) {
		List<SkitActor> matches = records(server).stream().filter(actor -> actor.name().equalsIgnoreCase(selector)
				|| actor.agentId().toString().equals(selector) || actor.agentId().startsWith(selector)).toList();
		if (matches.size() != 1) throw new AgentDomainException("ACTOR_NOT_FOUND", "Select one actor from the Director cast");
		return matches.getFirst();
	}
	public static Optional<ServerPlayer> find(MinecraftServer server, AgentId id) {
		return records(server).stream().filter(actor -> actor.agentId().equals(id)).findFirst()
				.flatMap(actor -> OfflineAgentPlayers.find(server, id, actor.profile()));
	}
	public static boolean reservesName(MinecraftServer server, String name) {
		var cancelled = CANCELLED.get(server);
		return records(server).stream().anyMatch(actor -> AgentIdentity.playerName(actor.agentId(), actor.profile()).equalsIgnoreCase(name))
				|| cancelled != null && cancelled.active(System.currentTimeMillis()).stream().anyMatch(entry -> AgentIdentity.playerName(entry.agentId(), entry.profile()).equalsIgnoreCase(name));
	}
	public static SkitActor summon(ServerLevel level, Vec3 position, String appearance, String name) {
		MinecraftServer server = level.getServer();
		SkitModeRuntime.requireEnabled(server);
		SkitActor actor = new SkitActor(AgentId.random(), name, appearance, false);
		String playerName = AgentIdentity.playerName(actor.agentId(), actor.profile());
		boolean reserved = reservesName(server, playerName) || CodexAgentManager.get(server).hasPendingPlayerName(playerName)
				|| carpet.patches.EntityPlayerMPFake.isSpawningPlayer(playerName) || CodexAgentManager.get(server).records().stream()
				.anyMatch(record -> AgentIdentity.playerName(record.agentId(), record.profile()).equalsIgnoreCase(playerName));
		if (reserved || server.getPlayerList().getPlayerByName(playerName) != null || AgentPlayerNameReservations.isReserved(
				server, playerName))
			throw new AgentDomainException("ACTOR_NAME_TAKEN", "That name belongs to a player or actor in this world. Select the existing actor to respawn it, or choose another name");
		SkitModeSavedData data = SkitModeSavedData.get(server);
		data.putActor(actor);
		data.putPlacement(actor.agentId().toString(), new SkitPlacement(level.dimension().identifier().toString(), position.x, position.y, position.z, 0, 0));
		try { spawn(server, actor); }
		catch (RuntimeException exception) { data.removeActor(actor.agentId()); throw exception; }
		return actor;
	}
	private static void spawn(MinecraftServer server, SkitActor actor) {
		SkitPlacement at = Optional.ofNullable(SkitModeSavedData.get(server).placement(actor.agentId().toString()))
				.orElseThrow(() -> new AgentDomainException("ACTOR_POSITION_MISSING", "Actor has no saved stage position"));
		ServerLevel level = level(server, at);
		OfflineAgentPlayers.spawn(server, actor.agentId(), actor.profile(), new Vec3(at.x(), at.y(), at.z()), at.yaw(), at.pitch(), level.dimension(), AgentGameMode.SURVIVAL);
		RESTORED.computeIfAbsent(server, ignored -> new HashSet<>()).add(actor.agentId());
		LOGGER.info("Director actor spawn accepted: actorId={}, dimension={}", actor.agentId(), at.dimension());
	}
	private static ServerLevel level(MinecraftServer server, SkitPlacement at) {
		for (ServerLevel level : server.getAllLevels()) if (level.dimension().identifier().toString().equals(at.dimension())) return level;
		throw new AgentDomainException("ACTOR_DIMENSION_MISSING", "Saved actor dimension is unavailable");
	}
	public static void respawn(MinecraftServer server, String selector) {
		SkitModeRuntime.requireEnabled(server);
		SkitActor actor = resolve(server, selector);
		ServerPlayer old = find(server, actor.agentId()).orElse(null);
		if (!actor.dead() && old != null && old.isAlive()) throw new AgentDomainException("ACTOR_ALIVE", "This actor is already alive");
		SkitPlacement at = SkitModeSavedData.get(server).placement(actor.agentId().toString());
		ServerLevel level = level(server, at);
		if (old != null) {
			if (old.isAlive()) throw new AgentDomainException("ACTOR_REMOVAL_PENDING", "The previous body is still leaving. Try Respawn again in a moment");
			ServerPlayer replacement = OfflineAgentPlayers.respawnConnected(old);
			replacement.teleportTo(level, at.x(), at.y(), at.z(), Set.<Relative>of(), at.yaw(), at.pitch(), false);
		} else spawn(server, actor);
		SkitModeSavedData.get(server).putActor(actor.withDead(false));
		LOGGER.info("Director actor respawned manually: actorId={}", actor.agentId());
	}
	public static void disconnected(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (!RESTORED.containsKey(server)) return;
		for (SkitActor actor : records(server)) {
			if (!player.getUUID().equals(OfflineAgentPlayers.offlineUuid(actor.agentId(), actor.profile()))) continue;
			SkitModeRuntime.stop(server, actor.agentId());
			VoiceDirector.stop(server, actor.agentId());
			SkitModeSavedData.get(server).putActor(actor.withDead(true));
			LOGGER.info("Director actor disconnected; manual respawn required: actorId={}", actor.agentId());
		}
	}
	public static boolean retainDeath(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		for (SkitActor actor : records(server)) {
			if (find(server, actor.agentId()).orElse(null) != player) continue;
			SkitModeRuntime.stop(server, actor.agentId());
			VoiceDirector.stop(server, actor.agentId());
			OfflineAgentPlayers.retainConnectedDeath(player);
			SkitModeSavedData.get(server).putActor(actor.withDead(true));
			LOGGER.info("Director actor died; retained in cast for manual respawn: actorId={}", actor.agentId());
			return true;
		}
		return false;
	}
	/** Explicit conversion only: previous releases did not persist which summons were skit actors. */
	public static SkitActor adopt(ServerLevel fallbackLevel, Vec3 fallbackPosition, String selector) {
		MinecraftServer server = fallbackLevel.getServer();
		SkitModeRuntime.requireEnabled(server);
		CodexAgentManager manager = CodexAgentManager.get(server);
		AgentRecord record = manager.resolve(selector);
		manager.requireStableDirectorTransfer(record.agentId());
		String appearance = record.profile().model().startsWith("claude-") ? "claude" : record.profile().provider();
		SkitActor actor = new SkitActor(record.agentId(), AgentIdentity.playerName(record.agentId(), record.profile()), appearance, true);
		SkitModeSavedData data = SkitModeSavedData.get(server);
		SkitPlacement previousPlacement = data.placement(actor.agentId().toString());
		SkitPlacement placement = Optional.ofNullable(previousPlacement)
				.orElseGet(() -> manager.findAgentPlayer(record.agentId()).map(SkitPlacement::fromPlayer).orElse(
						new SkitPlacement(fallbackLevel.dimension().identifier().toString(), fallbackPosition.x, fallbackPosition.y, fallbackPosition.z, 0, 0)));
		data.putActor(actor);
		try { manager.remove(selector); }
		catch (RuntimeException exception) {
			data.removeActor(actor.agentId());
			if (previousPlacement != null) data.putPlacement(actor.agentId().toString(), previousPlacement);
			throw exception;
		}
		data.putPlacement(actor.agentId().toString(), placement);
		LOGGER.info("Moved existing agent to Director cast; manual respawn required: actorId={}", actor.agentId());
		return actor;
	}
	public static void remove(MinecraftServer server, String selector) {
		SkitActor actor = resolve(server, selector);
		SkitModeRuntime.stop(server, actor.agentId());
		VoiceDirector.stop(server, actor.agentId());
		CANCELLED.computeIfAbsent(server, ignored -> new PendingSpawnCancellationLedger(30_000)).record(actor.agentId(), actor.profile(), System.currentTimeMillis());
		find(server, actor.agentId()).ifPresent(OfflineAgentPlayers::remove);
		dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime.removeAgent(server, actor.agentId());
		SkitModeSavedData.get(server).removeActor(actor.agentId());
	}
	public static void tick(MinecraftServer server) {
		PendingSpawnCancellationLedger cancelled = CANCELLED.get(server);
		if (cancelled != null) for (var cancellation : cancelled.active(System.currentTimeMillis()))
			OfflineAgentPlayers.find(server, cancellation.agentId(), cancellation.profile()).ifPresent(OfflineAgentPlayers::remove);
		Set<AgentId> attempted = RESTORED.computeIfAbsent(server, ignored -> new HashSet<>());
		Set<AgentId> connected = CONNECTED.computeIfAbsent(server, ignored -> new HashSet<>());
		for (SkitActor actor : records(server)) {
			if (actor.dead()) { connected.remove(actor.agentId()); continue; }
			if (find(server, actor.agentId()).isPresent()) { connected.add(actor.agentId()); continue; }
			if (connected.remove(actor.agentId())) {
				SkitModeRuntime.stop(server, actor.agentId());
				VoiceDirector.stop(server, actor.agentId());
				SkitModeSavedData.get(server).putActor(actor.withDead(true));
				LOGGER.info("Director actor body left the world; retained for manual respawn: actorId={}", actor.agentId());
				continue;
			}
			if (!attempted.add(actor.agentId())) continue;
			try { spawn(server, actor); }
			catch (RuntimeException exception) { LOGGER.warn("Director actor restore failed; manual respawn remains available: actorId={}", actor.agentId(), exception); }
		}
	}
	public static void release(MinecraftServer server) { RESTORED.remove(server); CONNECTED.remove(server); CANCELLED.remove(server); }
}
