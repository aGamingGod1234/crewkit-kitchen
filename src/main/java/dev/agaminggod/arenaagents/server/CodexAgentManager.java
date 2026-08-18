package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentEntityRecoveryTarget;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.CodexAgentEntities;
import dev.agaminggod.arenaagents.agent.CodexAgentEntity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentManager.class);
	private static final Map<MinecraftServer, CodexAgentManager> INSTANCES = new WeakHashMap<>();
	private static final int AGENT_TICKET_RADIUS = 2;
	private static final long PLAYER_SPAWN_TIMEOUT_MS = 10_000L;
	private static final long CANCELLED_SPAWN_RETENTION_MS = 120_000L;
	private static final String HIDDEN_AGENT_TEAM = "arenaagents_hidden";
	private static final TicketType AGENT_TICKET_TYPE = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
	);

	private final MinecraftServer server;
	private final AgentSavedData savedData;
	private final Map<AgentId, AgentChunkTicket> chunkTickets = new LinkedHashMap<>();
	private final Map<AgentChunkTicket, Integer> chunkTicketReferences = new LinkedHashMap<>();
	private final Map<AgentId, Long> pendingPlayerSpawns = new LinkedHashMap<>();
	private final PendingSpawnCancellationLedger cancelledPlayerSpawns =
			new PendingSpawnCancellationLedger(CANCELLED_SPAWN_RETENTION_MS);
	private final Set<AgentId> seenPlayers = new LinkedHashSet<>();
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	private CodexAgentManager(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server must not be null");
		this.savedData = AgentSavedData.get(server);
		this.savedData.setRuntimeHooks(new ForwardingRuntimeHooks());
	}

	public static synchronized CodexAgentManager get(MinecraftServer server) {
		return INSTANCES.computeIfAbsent(
				Objects.requireNonNull(server, "server must not be null"),
				CodexAgentManager::new
		);
	}

	public static synchronized void release(MinecraftServer server) {
		CodexAgentManager manager = INSTANCES.remove(server);
		if (manager != null) {
			manager.runtimeHooks.onServerStopping();
			manager.releaseChunkTickets();
			manager.savedData.setRuntimeHooks(AgentRuntimeHooks.NO_OP);
		}
	}

	public void setRuntimeHooks(AgentRuntimeHooks runtimeHooks) {
		this.runtimeHooks = Objects.requireNonNull(runtimeHooks, "runtimeHooks must not be null");
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String model,
			String reasoning,
			Optional<String> userName
	) {
		return summon(level, position, "codex", model, reasoning, userName);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName
	) {
		return summon(level, position, provider, model, reasoning, userName, AgentGameMode.SURVIVAL);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			AgentGameMode gameMode
	) {
		return summon(level, position, provider, model, reasoning, "priority", userName, gameMode);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			Optional<String> userName,
			AgentGameMode gameMode
	) {
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(position, "position must not be null");
		long now = System.currentTimeMillis();
		AgentRegistry registry = savedData.registry();
		AgentRecord created = registry.create(provider, model, reasoning, serviceTier, userName, gameMode, now);
		try {
			runtimeHooks.validateProfile(created.profile());
			OfflineAgentPlayers.spawn(
					server,
					created.agentId(),
					created.profile(),
					position,
					0.0F,
					0.0F,
					level.dimension(),
					gameMode
			);
			pendingPlayerSpawns.put(created.agentId(), now + PLAYER_SPAWN_TIMEOUT_MS);
			AgentRecord attached = registry.attachEntity(
					created.agentId(),
					OfflineAgentPlayers.offlineUuid(created.agentId(), created.profile()),
					entityLocation(level, new ChunkPos(
							((int) Math.floor(position.x)) >> 4,
							((int) Math.floor(position.z)) >> 4
					)),
					now
			);
			restoreChunkTicket(attached);
			runtimeHooks.onCreated(attached);
			return attached;
		} catch (RuntimeException exception) {
			releaseChunkTicket(created.agentId());
			OfflineAgentPlayers.find(server, created.agentId(), created.profile()).ifPresent(OfflineAgentPlayers::remove);
			if (pendingPlayerSpawns.remove(created.agentId()) != null) {
				cancelledPlayerSpawns.record(created.agentId(), System.currentTimeMillis());
			}
			try {
				registry.remove(created.agentId());
			} catch (AgentDomainException ignored) {
				// The record may already have been removed by a failing integration hook.
			}
			throw exception;
		}
	}

	public AgentTransition start(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		return savedData.registry().start(record.agentId(), prompt, System.currentTimeMillis());
	}

	public AgentTransition stop(String selector) {
		AgentRecord record = resolve(selector);
		return savedData.registry().stop(record.agentId(), System.currentTimeMillis());
	}

	public AgentTransition resume(String selector) {
		AgentRecord record = resolve(selector);
		return savedData.registry().resume(record.agentId(), System.currentTimeMillis());
	}

	public AgentTransition queue(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		return savedData.registry().queue(record.agentId(), prompt, System.currentTimeMillis());
	}

	public AgentTransition steer(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		return savedData.registry().steer(record.agentId(), prompt, System.currentTimeMillis());
	}

	public VanillaRespawnAttempt beginVanillaRespawn(AgentId agentId) {
		AgentRecord record = savedData.registry().require(Objects.requireNonNull(agentId, "agentId must not be null"));
		if (record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
			throw new AgentDomainException("AGENT_NOT_DEAD", "Only a dead Codex agent can be respawned");
		}
		runtimeHooks.validateProfile(record.profile());
		try {
			AgentDeathSnapshot death = record.deathSnapshot().orElseThrow(
					() -> new AgentDomainException("DEATH_SNAPSHOT_MISSING", "Dead agent has no persisted vanilla respawn facts")
			);
			OfflineAgentPlayers.VanillaRespawnTarget target = OfflineAgentPlayers.resolveVanillaRespawn(server, death);
			findAgentPlayer(record.agentId()).ifPresent(OfflineAgentPlayers::remove);
			OfflineAgentPlayers.spawn(
					server,
					record.agentId(),
					record.profile(),
					target.position(),
					target.yaw(),
					target.pitch(),
					target.level().dimension(), target.gameMode()
			);
			long now = System.currentTimeMillis();
			pendingPlayerSpawns.put(record.agentId(), now + PLAYER_SPAWN_TIMEOUT_MS);
			return new VanillaRespawnAttempt(record, target, now + PLAYER_SPAWN_TIMEOUT_MS);
		} catch (RuntimeException exception) {
			rollbackVanillaRespawn(record);
			throw exception;
		}
	}

	public boolean verifyVanillaRespawn(VanillaRespawnAttempt attempt, long nowEpochMs) {
		Objects.requireNonNull(attempt, "attempt must not be null");
		if (!savedData.registry().require(attempt.deadRecord().agentId()).equals(attempt.deadRecord())) {
			throw new AgentDomainException("STALE_RESPAWN_ATTEMPT", "Dead lifecycle changed during respawn");
		}
		Optional<ServerPlayer> found = findAgentPlayer(attempt.deadRecord().agentId());
		if (found.isEmpty()) {
			if (nowEpochMs >= attempt.deadlineEpochMs()) throw new AgentDomainException("PLAYER_SPAWN_TIMEOUT", "Respawned player did not appear before the deadline");
			return false;
		}
		ServerPlayer player = found.orElseThrow();
		if (!player.isAlive()) throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Respawned player is not alive");
		Vec3 finalPosition = attempt.target().finalPosition(player);
		if (player.level() != attempt.target().level() || player.position().distanceToSqr(finalPosition) > 1.0E-8D
				|| Math.abs(player.getYRot() - attempt.target().yaw()) > 0.001F || Math.abs(player.getXRot() - attempt.target().pitch()) > 0.001F) {
			boolean moved = player.teleportTo(
					attempt.target().level(), finalPosition.x, finalPosition.y, finalPosition.z,
					Set.of(), attempt.target().yaw(), attempt.target().pitch(), true
			);
			if (!moved) throw new AgentDomainException("PLAYER_SPAWN_VERIFY_FAILED", "Respawned player could not reach the vanilla target");
		}
		if (player.level() != attempt.target().level() || player.position().distanceToSqr(finalPosition) > 1.0E-8D
				|| player.gameMode.getGameModeForPlayer() != attempt.target().gameMode()) {
			throw new AgentDomainException("PLAYER_SPAWN_VERIFY_FAILED", "Respawned player failed physical verification");
		}
		attempt.verifiedPlayer = player;
		return true;
	}

	public AgentTransition commitVanillaRespawn(
			VanillaRespawnAttempt attempt,
			BiConsumer<AgentTransition, Runnable> publicationBarrier
	) {
		Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null");
		ServerPlayer player = Objects.requireNonNull(attempt.verifiedPlayer, "respawn must be physically verified before commit");
		Runnable rollbackWorld = () -> { };
		try {
			rollbackWorld = attempt.target().commitWorldEffects();
			AgentEntityLocation location = entityLocation(player.level(), player.chunkPosition());
			AgentTransition transition = savedData.registry().respawnAtomically(
					attempt.deadRecord().agentId(), player.getUUID(), location, System.currentTimeMillis(),
					(prepared, commit) -> {
						restoreChunkTicket(prepared.after());
						try {
							publicationBarrier.accept(prepared, commit);
						} catch (RuntimeException exception) {
							releaseChunkTicket(prepared.after().agentId());
							throw exception;
						}
					}
			);
			pendingPlayerSpawns.remove(attempt.deadRecord().agentId());
			return transition;
		} catch (RuntimeException exception) {
			rollbackWorld.run();
			rollbackVanillaRespawn(attempt.deadRecord());
			throw exception;
		}
	}

	public void rollbackVanillaRespawn(VanillaRespawnAttempt attempt) {
		if (attempt != null) rollbackVanillaRespawn(attempt.deadRecord());
	}

	private void rollbackVanillaRespawn(AgentRecord deadRecord) {
		releaseChunkTicket(deadRecord.agentId());
		pendingPlayerSpawns.remove(deadRecord.agentId());
		OfflineAgentPlayers.find(server, deadRecord.agentId(), deadRecord.profile()).ifPresent(OfflineAgentPlayers::remove);
	}

	public static final class VanillaRespawnAttempt {
		private final AgentRecord deadRecord;
		private final OfflineAgentPlayers.VanillaRespawnTarget target;
		private final long deadlineEpochMs;
		private ServerPlayer verifiedPlayer;

		private VanillaRespawnAttempt(AgentRecord deadRecord, OfflineAgentPlayers.VanillaRespawnTarget target, long deadlineEpochMs) {
			this.deadRecord = deadRecord;
			this.target = target;
			this.deadlineEpochMs = deadlineEpochMs;
		}

		public AgentRecord deadRecord() { return deadRecord; }
		public OfflineAgentPlayers.VanillaRespawnTarget target() { return target; }
		public long deadlineEpochMs() { return deadlineEpochMs; }
	}

	public void reconcileDeaths() {
		long now = System.currentTimeMillis();
		for (AgentId cancelled : cancelledPlayerSpawns.active(now)) {
			try {
				AgentRecord cancelledRecord = savedData.registry().require(cancelled);
				OfflineAgentPlayers.find(server, cancelled, cancelledRecord.profile()).ifPresent(OfflineAgentPlayers::remove);
			} catch (AgentDomainException ignored) {
				// Cancellation can outlive the rolled-back registry entry; no mapped player remains addressable.
			}
		}
		for (AgentRecord record : records()) {
			Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
			if (player.isPresent() && player.get().isAlive()) {
				pendingPlayerSpawns.remove(record.agentId());
				seenPlayers.add(record.agentId());
				if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
					continue;
				}
				if (record.entityUuid().isEmpty() || !record.entityUuid().get().equals(player.get().getUUID())) {
					savedData.registry().attachEntity(
							record.agentId(),
							player.get().getUUID(),
							entityLocation(player.get().level(), player.get().chunkPosition()),
							now
					);
				}
				// Identity and status belong in the field console, not as noisy world-space labels.
				player.get().setCustomName(null);
				player.get().setCustomNameVisible(false);
				hideWorldName(player.get());
				trackChunkTicket(record.agentId(), player.get());
			} else if (player.isPresent() && record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				savedData.registry().die(record.agentId(), deathSnapshot(player.get(), now), now);
			} else if (record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				long deadline = pendingPlayerSpawns.getOrDefault(record.agentId(), 0L);
				if (deadline > now) continue;
				if (seenPlayers.contains(record.agentId())) {
					// A missing fake is not evidence of vanilla death. Preserve its lifecycle for recovery.
					savedData.registry().disconnect(record.agentId(), now);
					continue;
				}
				recoverOfflinePlayer(record, now);
			}
		}
	}

	public boolean captureDeath(ServerPlayer player, DamageSource source) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(source, "source must not be null");
		long now = System.currentTimeMillis();
		String cause;
		try {
			cause = source.getLocalizedDeathMessage(player).getString();
		} catch (RuntimeException ignored) {
			cause = "Agent died";
		}
		return AgentDeathCapture.record(
				savedData.registry(), player.getUUID(), deathSnapshot(player, cause, now), now
		);
	}

	private static AgentDeathSnapshot deathSnapshot(ServerPlayer player, long now) {
		String cause;
		try {
			cause = player.getCombatTracker().getDeathMessage().getString();
		} catch (RuntimeException ignored) {
			cause = "Agent died";
		}
		return deathSnapshot(player, cause, now);
	}

	private static AgentDeathSnapshot deathSnapshot(ServerPlayer player, String cause, long now) {
		ServerPlayer.RespawnConfig config = player.getRespawnConfig();
		Optional<String> respawnDimension = Optional.empty();
		Optional<Double> respawnX = Optional.empty();
		Optional<Double> respawnY = Optional.empty();
		Optional<Double> respawnZ = Optional.empty();
		Optional<Float> respawnYaw = Optional.empty();
		Optional<Float> respawnPitch = Optional.empty();
		Optional<Boolean> respawnForced = Optional.empty();
		if (config != null) {
			var data = config.respawnData();
			respawnDimension = Optional.of(data.dimension().identifier().toString());
			respawnX = Optional.of((double) data.pos().getX());
			respawnY = Optional.of((double) data.pos().getY());
			respawnZ = Optional.of((double) data.pos().getZ());
			respawnYaw = Optional.of(data.yaw());
			respawnPitch = Optional.of(data.pitch());
			respawnForced = Optional.of(config.forced());
		}
		return new AgentDeathSnapshot(
				cause, player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
				respawnDimension, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced,
				player.gameMode.getGameModeForPlayer().getName(), now
		);
	}


	private void hideWorldName(ServerPlayer player) {
		PlayerTeam team = server.getScoreboard().getPlayerTeam(HIDDEN_AGENT_TEAM);
		if (team == null) {
			team = server.getScoreboard().addPlayerTeam(HIDDEN_AGENT_TEAM);
			team.setNameTagVisibility(Team.Visibility.NEVER);
		}
		server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
	}

	private void recoverOfflinePlayer(AgentRecord record, long now) {
		ServerLevel level;
		Vec3 position;
		Optional<CodexAgentEntity> legacy = findAgentEntity(record.agentId());
		if (legacy.isPresent() && legacy.get().level() instanceof ServerLevel legacyLevel) {
			level = legacyLevel;
			position = legacy.get().position();
			legacy.get().discard();
		} else if (record.entityLocation().isPresent()) {
			AgentEntityLocation location = record.entityLocation().get();
			level = findLevel(location.dimension()).orElse(server.overworld());
			int x = (location.chunkX() << 4) + 8;
			int z = (location.chunkZ() << 4) + 8;
			BlockPos ground = level.getHeightmapPos(
					Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					new BlockPos(x, 0, z)
			);
			position = Vec3.atBottomCenterOf(ground.above());
		} else {
			level = server.overworld();
			BlockPos ground = level.getHeightmapPos(
					Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					new BlockPos(0, 0, 0)
			);
			position = Vec3.atBottomCenterOf(ground.above());
		}
		try {
			OfflineAgentPlayers.spawn(
					server,
					record.agentId(),
					record.profile(),
					position,
					0.0F,
					0.0F,
					level.dimension(),
					record.profile().gameMode()
			);
			pendingPlayerSpawns.put(record.agentId(), now + PLAYER_SPAWN_TIMEOUT_MS);
			savedData.registry().attachEntity(
					record.agentId(),
					OfflineAgentPlayers.offlineUuid(record.agentId(), record.profile()),
					entityLocation(level, new ChunkPos(
							((int) Math.floor(position.x)) >> 4,
							((int) Math.floor(position.z)) >> 4
					)),
					now
			);
		} catch (AgentDomainException exception) {
			pendingPlayerSpawns.put(record.agentId(), now + 5_000L);
		}
	}

	public void maintainChunkTickets() {
		for (AgentRecord record : records()) {
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				releaseChunkTicket(record.agentId());
				continue;
			}
			restoreChunkTicket(record);
			findAgentPlayer(record.agentId()).ifPresent(entity -> trackChunkTicket(record.agentId(), entity));
		}
	}

	public AgentRecord remove(String selector) {
		AgentRecord record = resolve(selector);
		Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
		long terminalRevision = record.goalRevision() == Long.MAX_VALUE
				? Long.MAX_VALUE
				: record.goalRevision() + 1L;
		return AgentRemovalCoordinator.removeRegistryFirst(
				savedData.registry(),
				record.agentId(),
				() -> {
					releaseChunkTicket(record.agentId());
					player.ifPresent(OfflineAgentPlayers::remove);
					if (pendingPlayerSpawns.remove(record.agentId()) != null) {
						cancelledPlayerSpawns.record(record.agentId(), System.currentTimeMillis());
					}
					seenPlayers.remove(record.agentId());
				},
				() -> runtimeHooks.onRemoved(record.agentId(), terminalRevision),
				failure -> LOGGER.warn("Post-delete cleanup failed for agent {}", record.agentId(), failure)
		);
	}

	public boolean toggleAutomaticProgress(String selector) {
		AgentRecord record = resolve(selector);
		boolean enabled = !record.automaticProgress();
		savedData.registry().setAutomaticProgress(record.agentId(), enabled, System.currentTimeMillis());
		return enabled;
	}

	public boolean automaticProgress(AgentId agentId) {
		return savedData.registry().require(agentId).automaticProgress();
	}

	public String displayName(AgentRecord target) {
		String base = AgentIdentity.displayName(target.profile());
		int duplicate = 0;
		for (AgentRecord record : records()) {
			if (record.agentId().equals(target.agentId())) break;
			String candidate = AgentIdentity.displayName(record.profile());
			if (candidate.equals(base)) duplicate++;
		}
		return duplicate == 0 ? base : base + " (" + duplicate + ")";
	}

	public AgentRecord resolve(String selector) {
		return savedData.registry().resolve(selector);
	}

	public List<AgentRecord> records() {
		return savedData.registry().records();
	}

	public List<String> selectors() {
		return savedData.registry().selectors();
	}

	public AgentRegistry registry() {
		return savedData.registry();
	}

	public MinecraftServer server() {
		return server;
	}

	public Optional<CodexAgentEntity> findAgentEntity(AgentId agentId) {
		AgentRecord record = savedData.registry().require(agentId);
		return findEntity(record)
				.filter(CodexAgentEntity.class::isInstance)
				.map(CodexAgentEntity.class::cast)
				.or(() -> findLoadedAgentEntity(agentId));
	}

	public Optional<ServerPlayer> findAgentPlayer(AgentId agentId) {
		AgentRecord record = savedData.registry().require(agentId);
		return OfflineAgentPlayers.find(server, agentId, record.profile());
	}

	private Optional<CodexAgentEntity> findLoadedAgentEntity(AgentId agentId) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof CodexAgentEntity agentEntity
						&& agentEntity.getAgentId().filter(agentId::equals).isPresent()) {
					return Optional.of(agentEntity);
				}
			}
		}
		return Optional.empty();
	}

	private Optional<Entity> findEntity(AgentRecord record) {
		Optional<AgentEntityRecoveryTarget> recoveryTarget = AgentEntityRecoveryTarget.from(record);
		if (recoveryTarget.isPresent()) {
			AgentEntityRecoveryTarget target = recoveryTarget.orElseThrow();
			Optional<ServerLevel> level = findLevel(target.location().dimension());
			if (level.isPresent()) {
				Entity entity = level.orElseThrow().getEntity(target.entityUuid());
				if (entity != null) {
					return Optional.of(entity);
				}
			}
		}
		return record.entityUuid().flatMap(this::findEntity);
	}

	private Optional<Entity> findEntity(UUID entityUuid) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.getUUID().equals(entityUuid)) {
					return Optional.of(entity);
				}
			}
		}
		return Optional.empty();
	}

	private void restoreChunkTicket(AgentRecord record) {
		AgentEntityRecoveryTarget.from(record).ifPresent(target -> findLevel(target.location().dimension()).ifPresent(level -> {
			AgentChunkTicket current = chunkTickets.get(record.agentId());
			AgentChunkTicket recovered = new AgentChunkTicket(
					level,
					new ChunkPos(target.location().chunkX(), target.location().chunkZ())
			);
			if (recovered.equals(current)) {
				return;
			}
			retainChunkTicket(recovered);
			if (current != null) {
				releaseChunkTicket(current);
			}
			chunkTickets.put(record.agentId(), recovered);
		}));
	}

	private Optional<ServerLevel> findLevel(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return Optional.of(level);
			}
		}
		return Optional.empty();
	}

	private static AgentEntityLocation entityLocation(ServerLevel level, ChunkPos position) {
		return new AgentEntityLocation(
				level.dimension().identifier().toString(),
				position.x(),
				position.z()
		);
	}

	private void trackChunkTicket(AgentId agentId, Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			throw new AgentDomainException("AGENT_LEVEL_INVALID", "Codex agent is not in a server level");
		}
		ChunkPos position = entity.chunkPosition();
		AgentChunkTicket current = chunkTickets.get(agentId);
		if (current != null && current.level() == level && current.position().equals(position)) {
			savedData.registry().updateEntityLocation(agentId, entityLocation(level, position), System.currentTimeMillis());
			return;
		}
		AgentChunkTicket next = new AgentChunkTicket(level, position);
		retainChunkTicket(next);
		if (current != null) {
			releaseChunkTicket(current);
		}
		chunkTickets.put(agentId, next);
		savedData.registry().updateEntityLocation(agentId, entityLocation(level, position), System.currentTimeMillis());
	}

	private void releaseChunkTicket(AgentId agentId) {
		AgentChunkTicket removed = chunkTickets.remove(agentId);
		if (removed == null) {
			return;
		}
		releaseChunkTicket(removed);
	}

	private void retainChunkTicket(AgentChunkTicket ticket) {
		int references = chunkTicketReferences.getOrDefault(ticket, 0);
		if (references == 0) {
			ticket.level().getChunkSource().addTicketWithRadius(
					AGENT_TICKET_TYPE,
					ticket.position(),
					AGENT_TICKET_RADIUS
			);
		}
		chunkTicketReferences.put(ticket, references + 1);
	}

	private void releaseChunkTicket(AgentChunkTicket ticket) {
		Integer references = chunkTicketReferences.get(ticket);
		if (references == null) {
			return;
		}
		if (references > 1) {
			chunkTicketReferences.put(ticket, references - 1);
			return;
		}
		chunkTicketReferences.remove(ticket);
		ticket.level().getChunkSource().removeTicketWithRadius(
				AGENT_TICKET_TYPE,
				ticket.position(),
				AGENT_TICKET_RADIUS
		);
	}

	private void releaseChunkTickets() {
		for (AgentId agentId : List.copyOf(chunkTickets.keySet())) {
			releaseChunkTicket(agentId);
		}
	}

	private record AgentChunkTicket(ServerLevel level, ChunkPos position) {
		private AgentChunkTicket {
			Objects.requireNonNull(level, "level must not be null");
			Objects.requireNonNull(position, "position must not be null");
		}
	}

	private final class ForwardingRuntimeHooks implements AgentRuntimeHooks {
		@Override
		public void onTransition(AgentTransition transition) {
			if (transition.cancelAction()) {
				findAgentPlayer(transition.after().agentId()).ifPresent(OfflineAgentPlayers::stop);
			}
			if (transition.after().state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.PLANNING
					&& transition.before().state() != transition.after().state()) {
				AgentChatReporter.planning(CodexAgentManager.this, transition.after());
			}
			runtimeHooks.onTransition(transition);
		}
	}
}
