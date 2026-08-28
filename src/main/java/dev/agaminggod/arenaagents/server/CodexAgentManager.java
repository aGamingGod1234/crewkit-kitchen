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
import dev.agaminggod.arenaagents.server.group.AgentGroup;
import dev.agaminggod.arenaagents.server.group.AgentGroupRegistry;
import dev.agaminggod.arenaagents.server.group.AgentGroupSavedData;
import dev.agaminggod.arenaagents.server.group.AgentGroupSpawnCoordinator;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
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
	private static final long RECOVERY_RETRY_DELAY_MS = 30_000L;
	private static final long CANCELLED_SPAWN_RETENTION_MS = 120_000L;
	private static final String HIDDEN_AGENT_TEAM = "arenaagents_hidden";
	private static final TicketType AGENT_TICKET_TYPE = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
	);

	private final MinecraftServer server;
	private final AgentSavedData savedData;
	private final AgentGroupSavedData groupSavedData;
	private final Map<AgentId, AgentChunkTicket> chunkTickets = new LinkedHashMap<>();
	private final Map<AgentChunkTicket, Integer> chunkTicketReferences = new LinkedHashMap<>();
	private final Map<AgentId, Long> pendingPlayerSpawns = new LinkedHashMap<>();
	private final Map<AgentId, VanillaRespawnAttempt> pendingVerifiedRespawns = new LinkedHashMap<>();
	private final Set<AgentId> pendingAgentRegistrations = ConcurrentHashMap.newKeySet();
	private final Set<AgentId> pendingEntityRecoveries = new LinkedHashSet<>();
	private final PendingSpawnCancellationLedger cancelledPlayerSpawns =
			new PendingSpawnCancellationLedger(CANCELLED_SPAWN_RETENTION_MS);
	private final Set<AgentId> seenPlayers = new LinkedHashSet<>();
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	private CodexAgentManager(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server must not be null");
		this.savedData = AgentSavedData.get(server);
		this.groupSavedData = AgentGroupSavedData.get(server);
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
			manager.releasePendingVerifiedRespawns();
			manager.releaseChunkTickets();
			manager.savedData.setRuntimeHooks(AgentRuntimeHooks.NO_OP);
			AgentInputRuntime.release(server);
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
		AgentRecord created;
		synchronized (registry) {
			created = runtimeHooks.withinPublicationBoundary(() -> {
				AgentRecord record = registry.create(provider, model, reasoning, serviceTier, userName, gameMode, now);
				pendingAgentRegistrations.add(record.agentId());
				return record;
			});
		}
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
			return created;
		} catch (RuntimeException exception) {
			releaseChunkTicket(created.agentId());
			pendingEntityRecoveries.remove(created.agentId());
			OfflineAgentPlayers.find(server, created.agentId(), created.profile()).ifPresent(OfflineAgentPlayers::remove);
			if (pendingPlayerSpawns.remove(created.agentId()) != null) {
				cancelledPlayerSpawns.record(created.agentId(), System.currentTimeMillis());
			}
			synchronized (registry) {
				runtimeHooks.withinPublicationBoundary(() -> {
					try {
						registry.remove(created.agentId());
					} catch (AgentDomainException ignored) {
						// The record may already have been removed by a failing integration hook.
					} finally {
						pendingAgentRegistrations.remove(created.agentId());
					}
					return null;
				});
			}
			throw exception;
		}
	}

	public AgentTransition start(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		return savedData.registry().start(record.agentId(), prompt, System.currentTimeMillis());
	}

	public AgentTransition startAtomically(
			AgentId agentId,
			String prompt,
			BiConsumer<AgentTransition, Runnable> publicationBarrier
	) {
		return savedData.registry().startAtomically(
				Objects.requireNonNull(agentId, "agentId must not be null"),
				prompt,
				System.currentTimeMillis(),
				Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null")
		);
	}

	public AgentTransition startConversationWakeAtomically(
			ConversationEvent event,
			String prompt,
			BiConsumer<PendingConversationWake, Runnable> publicationBarrier
	) {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null");
		PendingConversationWake[] staged = { null };
		try {
			return savedData.registry().startAtomically(
					event.agentId(), prompt, System.currentTimeMillis(),
					(transition, commit) -> {
						PendingConversationWake wake = PendingConversationWake.create(event, transition);
						savedData.stageConversationWake(wake);
						staged[0] = wake;
						publicationBarrier.accept(wake, commit);
					}
			);
		} catch (RuntimeException exception) {
			if (staged[0] != null) savedData.rollbackConversationWake(staged[0].transactionId());
			throw exception;
		}
	}

	public List<PendingConversationWake> pendingConversationWakes() {
		return savedData.conversationWakes();
	}

	public boolean acknowledgeConversationWake(UUID transactionId, AgentId agentId, long goalRevision) {
		return savedData.acknowledgeConversationWake(transactionId, agentId, goalRevision);
	}

	public Optional<PendingConversationWake> pendingConversationWake(AgentId agentId) {
		return savedData.conversationWake(agentId);
	}

	public AgentTransition rearmConversationWake(PendingConversationWake wake) {
		Objects.requireNonNull(wake, "wake must not be null");
		return savedData.registry().rearmConversationWake(
				wake.event().agentId(), wake.goalRevision(), wake.goal().goalId(), System.currentTimeMillis()
		);
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
			long now = System.currentTimeMillis();
			VanillaRespawnAttempt attempt = new VanillaRespawnAttempt(record, target, now + PLAYER_SPAWN_TIMEOUT_MS);
			Optional<ServerPlayer> existing = findAgentPlayer(record.agentId());
			if (existing.isPresent()) {
				AgentInputRuntime.clear(server, record.agentId());
				ServerPlayer existingPlayer = existing.orElseThrow();
				if (AgentRespawnSpawnPolicy.existingPlayerAction(existingPlayer.isAlive())
						== AgentRespawnSpawnPolicy.ExistingPlayerAction.REMOVE_STALE_PLAYER) {
					OfflineAgentPlayers.remove(existingPlayer);
				}
				pendingPlayerSpawns.put(record.agentId(), attempt.deadlineEpochMs());
			} else {
				requestVanillaRespawnPlayer(attempt, now);
			}
			return attempt;
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
		AgentRespawnSpawnPolicy.Decision decision = AgentRespawnSpawnPolicy.decide(
				attempt.spawnRequested, found.isPresent(), nowEpochMs, attempt.deadlineEpochMs()
		);
		switch (decision) {
			case WAIT_FOR_REMOVAL, WAIT_FOR_SPAWN -> { return false; }
			case REQUEST_SPAWN -> {
				requestVanillaRespawnPlayer(attempt, nowEpochMs);
				return false;
			}
			case TIMED_OUT -> throw new AgentDomainException(
					attempt.spawnRequested ? "PLAYER_SPAWN_TIMEOUT" : "PLAYER_REMOVAL_TIMEOUT",
					attempt.spawnRequested
							? "Respawned player did not appear before the deadline"
							: "Dead player did not leave before the respawn deadline"
			);
			case VERIFY_PLAYER -> { }
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

	private void requestVanillaRespawnPlayer(VanillaRespawnAttempt attempt, long nowEpochMs) {
		AgentRecord record = attempt.deadRecord();
		OfflineAgentPlayers.spawn(
				server,
				record.agentId(),
				record.profile(),
				attempt.target().position(),
				attempt.target().yaw(),
				attempt.target().pitch(),
				attempt.target().level().dimension(),
				attempt.target().gameMode()
		);
		attempt.spawnRequested = true;
		attempt.deadlineEpochMs = nowEpochMs + PLAYER_SPAWN_TIMEOUT_MS;
		pendingPlayerSpawns.put(record.agentId(), attempt.deadlineEpochMs);
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
			AgentEntityLocation location = entityLocation(player);
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
			pendingEntityRecoveries.remove(attempt.deadRecord().agentId());
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
		AgentInputRuntime.clear(server, deadRecord.agentId());
		OfflineAgentPlayers.find(server, deadRecord.agentId(), deadRecord.profile()).ifPresent(OfflineAgentPlayers::remove);
	}

	public static final class VanillaRespawnAttempt {
		private final AgentRecord deadRecord;
		private final OfflineAgentPlayers.VanillaRespawnTarget target;
		private long deadlineEpochMs;
		private boolean spawnRequested;
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
		boolean recoveryAttempted = false;
		tickVerifiedRespawns(now);
		for (AgentId cancelled : cancelledPlayerSpawns.active(now)) {
			try {
				AgentRecord cancelledRecord = savedData.registry().require(cancelled);
				OfflineAgentPlayers.find(server, cancelled, cancelledRecord.profile()).ifPresent(OfflineAgentPlayers::remove);
			} catch (AgentDomainException ignored) {
				// Cancellation can outlive the rolled-back registry entry; no mapped player remains addressable.
			}
		}
		for (AgentRecord record : records()) {
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				pendingEntityRecoveries.remove(record.agentId());
				if (record.respawnPolicy() == dev.agaminggod.arenaagents.agent.RespawnPolicy.RESPAWN_AUTOMATICALLY
						&& !pendingVerifiedRespawns.containsKey(record.agentId())
						&& pendingPlayerSpawns.getOrDefault(record.agentId(), 0L) <= now) {
					try {
						pendingVerifiedRespawns.put(record.agentId(), beginVanillaRespawn(record.agentId()));
					} catch (RuntimeException exception) {
						scheduleRecoveryRetry(record.agentId(), now);
						LOGGER.warn("Could not begin automatic respawn for agent {}", record.agentId(), exception);
					}
				}
				continue;
			}
			Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
			if (player.isPresent() && player.get().isAlive()) {
				pendingPlayerSpawns.remove(record.agentId());
				seenPlayers.add(record.agentId());
				AgentEntityLocation location = entityLocation(player.get());
				AgentRecord attached = record;
				if (record.entityUuid().filter(player.get().getUUID()::equals).isEmpty()
						|| record.entityLocation().filter(location::equals).isEmpty()) {
					attached = savedData.registry().attachEntity(
							record.agentId(),
							player.get().getUUID(),
							location,
							now
					);
				}
				// Identity and status belong in the field console, not as noisy world-space labels.
				player.get().setCustomName(null);
				player.get().setCustomNameVisible(false);
				hideWorldName(player.get());
				trackChunkTicket(record.agentId(), player.get());
				publishPendingRegistration(attached);
				if (pendingEntityRecoveries.remove(record.agentId())
						&& attached.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DISCONNECTED) {
					savedData.registry().resume(record.agentId(), now);
				}
			} else if (player.isPresent()) {
				AgentInputRuntime.clear(server, record.agentId());
				savedData.registry().die(record.agentId(), deathSnapshot(player.get(), now), now);
			} else {
				long deadline = pendingPlayerSpawns.getOrDefault(record.agentId(), 0L);
				if (deadline > now) continue;
				AgentRecord recoveryTarget = record;
				AgentRecord recoveryRecord = record;
				if (record.entityUuid().isPresent()) {
					releaseChunkTicket(record.agentId());
					AgentInputRuntime.clear(server, record.agentId());
					recoveryRecord = savedData.registry().detachEntity(record.agentId(), now);
				}
				if (seenPlayers.contains(record.agentId()) && recoveryRecord.state().isActive()) {
					AgentTransition disconnected = savedData.registry().disconnect(record.agentId(), now);
					pendingEntityRecoveries.add(record.agentId());
					recoveryRecord = disconnected.after();
				}
				if (recoveryAttempted) continue;
				recoveryAttempted = recoverOfflinePlayer(recoveryTarget, now);
			}
		}
	}

	public AgentGroup saveGroup(String name, List<AgentId> memberIds) {
		List<AgentId> checkedIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds must not be null"));
		for (AgentId memberId : checkedIds) savedData.registry().require(memberId);
		return groupSavedData.registry().save(name, checkedIds);
	}

	public AgentGroup deleteGroup(String name) {
		return groupSavedData.registry().delete(name);
	}

	public List<AgentGroup> groups() {
		return groupSavedData.registry().groups();
	}

	public AgentGroupSpawnCoordinator.Result spawnGroup(String name) {
		AgentGroup group = groupSavedData.registry().require(name);
		return AgentGroupSpawnCoordinator.spawn(group, this::ensureGroupMemberPresent);
	}

	public AgentRecord requestRespawn(String selector) {
		AgentRecord record = resolve(selector);
		requestVerifiedRespawn(record.agentId());
		return record;
	}

	private void requestVerifiedRespawn(AgentId agentId) {
		if (pendingVerifiedRespawns.containsKey(agentId)) return;
		long now = System.currentTimeMillis();
		if (pendingPlayerSpawns.getOrDefault(agentId, 0L) > now) {
			throw new AgentDomainException("RESPAWN_ALREADY_PENDING", "The agent player is already respawning");
		}
		pendingVerifiedRespawns.put(agentId, beginVanillaRespawn(agentId));
	}

	private AgentGroupSpawnCoordinator.MemberStatus ensureGroupMemberPresent(AgentId agentId) {
		AgentRecord record;
		try {
			record = savedData.registry().require(agentId);
		} catch (AgentDomainException exception) {
			if ("AGENT_NOT_FOUND".equals(exception.code())) return AgentGroupSpawnCoordinator.MemberStatus.MISSING;
			throw exception;
		}
		Optional<ServerPlayer> player = OfflineAgentPlayers.find(server, agentId, record.profile());
		if (player.filter(ServerPlayer::isAlive).isPresent()) {
			return AgentGroupSpawnCoordinator.MemberStatus.PRESENT;
		}
		if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
			requestVerifiedRespawn(agentId);
			return AgentGroupSpawnCoordinator.MemberStatus.RESTORING;
		}
		recoverOfflinePlayer(record, System.currentTimeMillis());
		return AgentGroupSpawnCoordinator.MemberStatus.RESTORING;
	}

	private void tickVerifiedRespawns(long nowEpochMs) {
		for (Map.Entry<AgentId, VanillaRespawnAttempt> entry : List.copyOf(pendingVerifiedRespawns.entrySet())) {
			AgentId agentId = entry.getKey();
			VanillaRespawnAttempt attempt = entry.getValue();
			try {
				if (!verifyVanillaRespawn(attempt, nowEpochMs)) continue;
				commitVanillaRespawn(attempt, (transition, commit) -> {
					commit.run();
					runtimeHooks.onTransition(transition);
				});
				pendingVerifiedRespawns.remove(agentId, attempt);
			} catch (RuntimeException exception) {
				pendingVerifiedRespawns.remove(agentId, attempt);
				rollbackVanillaRespawn(attempt);
				scheduleRecoveryRetry(agentId, nowEpochMs);
				LOGGER.warn("Could not complete verified respawn for agent {}", agentId, exception);
			}
		}
	}

	private void releasePendingVerifiedRespawns() {
		for (VanillaRespawnAttempt attempt : List.copyOf(pendingVerifiedRespawns.values())) {
			rollbackVanillaRespawn(attempt);
		}
		pendingVerifiedRespawns.clear();
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
		records().stream()
				.filter(record -> OfflineAgentPlayers.offlineUuid(record.agentId(), record.profile()).equals(player.getUUID()))
				.findFirst()
				.ifPresent(record -> AgentInputRuntime.clear(server, record.agentId()));
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

	private boolean recoverOfflinePlayer(AgentRecord record, long now) {
		Optional<RecoverySpawn> recovery;
		try {
			Optional<CodexAgentEntity> legacy = findAgentEntity(record.agentId());
			if (legacy.isPresent() && legacy.get().level() instanceof ServerLevel legacyLevel) {
				Vec3 legacyPosition = legacy.get().position();
				legacy.get().discard();
				AgentRecoverySpawnPolicy.ChunkPosition chunk =
						AgentRecoverySpawnPolicy.chunkContaining(legacyPosition.x, legacyPosition.z);
				recovery = findRecoverySpawn(
						legacyLevel,
						chunk.x(),
						chunk.z(),
						java.util.OptionalInt.of((int) Math.floor(legacyPosition.y))
				);
			} else if (record.entityLocation().isPresent()) {
				AgentEntityLocation location = record.entityLocation().orElseThrow();
				recovery = findLevel(location.dimension())
						.flatMap(level -> findRecoverySpawn(
								level, location.chunkX(), location.chunkZ(), location.blockY()));
			} else {
				recovery = Optional.empty();
			}
			if (recovery.isEmpty()) recovery = findOverworldSpawnRecovery();
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not search for a safe recovery position for agent {}", record.agentId().value(), exception);
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
		if (recovery.isEmpty()) {
			LOGGER.warn("No dry supported recovery position is available for agent {}", record.agentId().value());
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
		ServerLevel level = recovery.orElseThrow().level();
		Vec3 position = recovery.orElseThrow().position();
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
			return true;
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not request a recovery player spawn for agent {}", record.agentId().value(), exception);
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
	}

	private void scheduleRecoveryRetry(AgentId agentId, long now) {
		pendingPlayerSpawns.put(agentId, now + RECOVERY_RETRY_DELAY_MS);
	}

	private Optional<RecoverySpawn> findOverworldSpawnRecovery() {
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getRespawnData().pos();
		return findRecoverySpawn(
				overworld,
				spawn.getX() >> 4,
				spawn.getZ() >> 4,
				java.util.OptionalInt.of(spawn.getY())
		);
	}

	private Optional<RecoverySpawn> findRecoverySpawn(
			ServerLevel level,
			int chunkX,
			int chunkZ,
			java.util.OptionalInt preferredY
	) {
		level.getChunk(chunkX, chunkZ);
		int centerX = (chunkX << 4) + 8;
		int centerZ = (chunkZ << 4) + 8;
		Optional<RecoverySpawn> local = findRecoverySpawnInBounds(
				level,
				centerX,
				centerZ,
				chunkX << 4,
				((chunkX + 1) << 4) - 1,
				chunkZ << 4,
				((chunkZ + 1) << 4) - 1,
				preferredY
		);
		if (local.isPresent()) return local;

		int chunkRadius = 1;
		for (int x = chunkX - chunkRadius; x <= chunkX + chunkRadius; x++) {
			for (int z = chunkZ - chunkRadius; z <= chunkZ + chunkRadius; z++) {
				if (x != chunkX || z != chunkZ) level.getChunk(x, z);
			}
		}
		int minX = (chunkX - chunkRadius) << 4;
		int maxX = ((chunkX + chunkRadius + 1) << 4) - 1;
		int minZ = (chunkZ - chunkRadius) << 4;
		int maxZ = ((chunkZ + chunkRadius + 1) << 4) - 1;
		return findRecoverySpawnInBounds(level, centerX, centerZ, minX, maxX, minZ, maxZ, preferredY);
	}

	private Optional<RecoverySpawn> findRecoverySpawnInBounds(
			ServerLevel level,
			int centerX,
			int centerZ,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			java.util.OptionalInt preferredY
	) {
		return AgentRecoverySpawnPolicy.selectNearestDryPosition(
				centerX, centerZ, minX, maxX, minZ, maxZ,
				(x, z) -> recoveryColumn(level, x, z, preferredY)
		).map(selected -> new RecoverySpawn(
				level,
				Vec3.atBottomCenterOf(new BlockPos(selected.x(), selected.y(), selected.z()))
		));
	}

	private AgentRecoverySpawnPolicy.Column recoveryColumn(
			ServerLevel level,
			int x,
			int z,
			java.util.OptionalInt preferredY
	) {
		if (preferredY.isEmpty()) {
			BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z));
			return recoveryColumnAt(level, x, surface.getY(), z);
		}
		java.util.OptionalInt selected = AgentRecoverySpawnPolicy.selectNearestSafeY(
				preferredY.getAsInt(),
				level.getMinY() + 1,
				level.getMaxY() - 2,
				y -> recoveryColumnAt(level, x, y, z).safe()
		);
		if (selected.isEmpty()) {
			return new AgentRecoverySpawnPolicy.Column(
					preferredY.getAsInt(), false, false, false, false, false, false, false);
		}
		return recoveryColumnAt(level, x, selected.getAsInt(), z);
	}

	private AgentRecoverySpawnPolicy.Column recoveryColumnAt(ServerLevel level, int x, int y, int z) {
		BlockPos feet = new BlockPos(x, y, z);
		BlockPos floor = feet.below();
		BlockPos head = feet.above();
		var floorState = level.getBlockState(floor);
		BlockPos floorSupport = floor.below();
		boolean safeFloor = floorState.isFaceSturdy(level, floor, Direction.UP)
				&& !floorState.is(Blocks.CACTUS)
				&& !floorState.is(Blocks.MAGMA_BLOCK)
				&& !floorState.is(Blocks.CAMPFIRE)
				&& !floorState.is(Blocks.SOUL_CAMPFIRE)
				&& !floorState.is(Blocks.POWDER_SNOW);
		boolean stableFloor = !(floorState.getBlock() instanceof FallingBlock)
				|| level.getBlockState(floorSupport).isFaceSturdy(level, floorSupport, Direction.UP);
		return new AgentRecoverySpawnPolicy.Column(
				feet.getY(),
				safeFloor,
				stableFloor,
				level.getFluidState(floor).isEmpty(),
				level.getBlockState(feet).getCollisionShape(level, feet).isEmpty(),
				level.getFluidState(feet).isEmpty(),
				level.getBlockState(head).getCollisionShape(level, head).isEmpty(),
				level.getFluidState(head).isEmpty()
		);
	}

	private record RecoverySpawn(ServerLevel level, Vec3 position) {
	}

	public void maintainChunkTickets() {
		for (AgentRecord record : records()) {
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				releaseChunkTicket(record.agentId());
				continue;
			}
			restoreChunkTicket(record);
			if (record.entityUuid().isPresent()) {
				findAgentPlayer(record.agentId()).ifPresent(entity -> trackChunkTicket(record.agentId(), entity));
			}
		}
	}

	public AgentRecord remove(String selector) {
		AgentRecord record = resolve(selector);
		Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
		AgentInputRuntime.clear(server, record.agentId());
		long terminalRevision = record.goalRevision() == Long.MAX_VALUE
				? Long.MAX_VALUE
				: record.goalRevision() + 1L;
		AgentRecord removed = AgentRemovalCoordinator.removeRegistryFirst(
				savedData.registry(),
				record.agentId(),
				() -> {
					VanillaRespawnAttempt pendingRespawn = pendingVerifiedRespawns.remove(record.agentId());
					if (pendingRespawn != null) rollbackVanillaRespawn(pendingRespawn);
					releaseChunkTicket(record.agentId());
					player.ifPresent(OfflineAgentPlayers::remove);
					if (pendingPlayerSpawns.remove(record.agentId()) != null) {
						cancelledPlayerSpawns.record(record.agentId(), System.currentTimeMillis());
					}
					pendingAgentRegistrations.remove(record.agentId());
					pendingEntityRecoveries.remove(record.agentId());
					seenPlayers.remove(record.agentId());
				},
				() -> runtimeHooks.onRemoved(record.agentId(), terminalRevision),
				failure -> LOGGER.warn("Post-delete cleanup failed for agent {}", record.agentId(), failure)
		);
		savedData.clearConversationWake(record.agentId());
		return removed;
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
		Objects.requireNonNull(target, "target must not be null");
		return AgentIdentity.displayName(target.agentId(), target.profile());
	}

	public AgentRecord resolve(String selector) {
		return savedData.registry().resolve(selector);
	}

	public List<AgentRecord> records() {
		return savedData.registry().records();
	}

	/** Records already published, or restored through the coordinator handshake, and safe to reference on the wire. */
	public List<AgentRecord> coordinatorVisibleRecords() {
		AgentRegistry registry = savedData.registry();
		synchronized (registry) {
			Set<AgentId> pending = Set.copyOf(pendingAgentRegistrations);
			List<AgentRecord> records = registry.records();
			if (pending.isEmpty()) return records;
			return records.stream().filter(record -> !pending.contains(record.agentId())).toList();
		}
	}

	private void publishPendingRegistration(AgentRecord record) {
		if (!pendingAgentRegistrations.contains(record.agentId())) return;
		try {
			if (runtimeHooks.onCreated(record)) pendingAgentRegistrations.remove(record.agentId());
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish verified agent registration for {}", record.agentId(), exception);
		}
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

	private static AgentEntityLocation entityLocation(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			throw new AgentDomainException("AGENT_LEVEL_INVALID", "Codex agent is not in a server level");
		}
		ChunkPos position = entity.chunkPosition();
		return new AgentEntityLocation(
				level.dimension().identifier().toString(),
				position.x(),
				position.z(),
				java.util.OptionalInt.of((int) Math.floor(entity.getY()))
		);
	}

	private void trackChunkTicket(AgentId agentId, Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			throw new AgentDomainException("AGENT_LEVEL_INVALID", "Codex agent is not in a server level");
		}
		ChunkPos position = entity.chunkPosition();
		AgentChunkTicket current = chunkTickets.get(agentId);
		if (current != null && current.level() == level && current.position().equals(position)) {
			savedData.registry().updateEntityLocation(agentId, entityLocation(entity), System.currentTimeMillis());
			return;
		}
		AgentChunkTicket next = new AgentChunkTicket(level, position);
		retainChunkTicket(next);
		if (current != null) {
			releaseChunkTicket(current);
		}
		chunkTickets.put(agentId, next);
		savedData.registry().updateEntityLocation(agentId, entityLocation(entity), System.currentTimeMillis());
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
