package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentSpec;
import dev.agaminggod.arenaagents.scenario.ScenarioCompletionPolicy;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.ScenarioParticipant;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import dev.agaminggod.arenaagents.scenario.ScenarioSession;
import dev.agaminggod.arenaagents.scenario.ScenarioSessionConfig;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawn;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawnAllocator;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioOperatorMessagePolicy;
import dev.agaminggod.arenaagents.scenario.result.MatchResultV1;
import dev.agaminggod.arenaagents.scenario.result.MatchResultWriter;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicFormatter;
import dev.agaminggod.arenaagents.scenario.result.ScenarioResultPersistence;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.GoalControl;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScenarioRuntimeService {
	private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioRuntimeService.class);
	private static final long ROSTER_READY_TIMEOUT_TICKS = 240L;
	private static final int PUBLIC_EVENT_LIMIT = 4_096;
	private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

	private ScenarioRuntimeService() {
	}

	public static synchronized void launch(ServerPlayer operator, ScenarioLaunchRequest request) {
		if (!GoalControl.mayControl(operator.createCommandSourceStack())) {
			throw new IllegalStateException("You do not have permission to launch Arena Agents scenarios");
		}
		MinecraftServer server = operator.level().getServer();
		RuntimeState state = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
		if (ScenarioActivationFailurePolicy.mayReplacePendingLaunch(
				state.build != null, state.pendingActivation != null,
				state.activation != null || state.activeRun != null || state.pendingResult != null)) {
			state.pendingActivation = null;
			state.buildProgress = null;
		}
		if (state.build != null || state.pendingActivation != null || state.activation != null) {
			throw new IllegalStateException("An arena is already being prepared");
		}
		if (state.activeRun != null || state.pendingResult != null) {
			throw new IllegalStateException("A scenario is already running");
		}
		CodexAgentManager.get(server).registry().requireCapacity(request.roster().size());
		ScenarioPreset preset = ScenarioPresets.require(request.scenarioId());
		ServerLevel level = operator.level();
		BlockPos origin = arenaOrigin(level, preset, operator, request.placementMode());
		long now = System.currentTimeMillis();
		long worldSeed = scenarioSeed(level, preset);
		List<ScenarioParticipant> participants = request.roster().stream()
				.sorted(Comparator.comparingInt(ScenarioAgentSpec::slot))
				.map(agent -> new ScenarioParticipant(
						"slot-" + agent.slot(),
						agent.displayName(),
						agent.team()
				))
				.toList();
		ScenarioSessionConfig config = new ScenarioSessionConfig(
				UUID.randomUUID(),
				preset,
				worldSeed,
				worldSeed ^ 0x6A09E667F3BCC909L,
				preset.defaultDurationTicks(),
				request.deterministicEvents(),
				participants,
				now
		);
		ScenarioArenaBlueprint blueprint = ScenarioArenaBlueprint.create(preset, origin, participants.size());
		removeContestants(CodexAgentManager.get(server), state.agents);
		state.agents = List.of();
		state.participantByAgent.clear();
		state.publicEvents.clear();
		ScenarioSavedData.get(server).clear();
		state.build = new BuildJob(
				operator,
				level,
				request,
				config,
				new ScenarioSession(config),
				blueprint,
				new ScenarioArenaResetJob(blueprint),
				null,
				-1
		);
		state.buildProgress = new ScenarioBuildProgress(
				config.sessionId().toString(), preset.title(), "canonicalizing", state.nextBuildRevision(),
				0, state.build.reset().totalPlacements(), 0,
				origin.getX(), origin.getY(), origin.getZ(), ScenarioBuildProgress.Status.BUILDING,
				"Preparing the arena blueprint at " + coordinates(origin)
		);
		publishOperatorNotice(operator, ScenarioOperatorMessagePolicy.Event.PREPARING,
				"Preparing " + preset.title() + " at " + coordinates(origin) + ". "
						+ blueprint.placements().size() + " blueprint blocks will be checked; "
						+ "construction is happening at this location."
		);
	}

	public static synchronized void tick(MinecraftServer server) {
		RuntimeState state = STATES.get(server);
		if (state == null) return;
		state.runtimeTick++;
		if (state.pendingResult != null) {
			tickPendingResult(state, server);
			return;
		}
		if (state.recovery != null) {
			boolean coordinatorReady = ScenarioRecoveryGate.coordinatorReady(
					CoordinatorStatusStore.latest(server),
					state.recovery.snapshot.boundAgentIds(),
					System.currentTimeMillis(),
					ScenarioPreflight.STATUS_MAXIMUM_AGE_MS
			);
			tickRecovery(state, coordinatorReady);
			return;
		}
		if (state.build != null) {
			tickBuild(state);
			return;
		}
		if (state.pendingActivation != null) {
			tickPendingActivation(state);
			return;
		}
		if (state.activation != null) {
			tickActivation(state);
			return;
		}
		tickActive(state);
	}

	/** Read-only, public-field projection for the spectator presentation publisher. */
	public static synchronized Optional<ArenaSpectatorSnapshot.PublicView> spectatorView(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		RuntimeState state = STATES.get(server);
		if (state == null) return Optional.empty();
		if (state.pendingResult != null) {
			ScenarioRunSnapshot snapshot = state.pendingResult.snapshot;
			return Optional.of(publicViewFromSnapshot(server, state, snapshot, true));
		}
		if (state.activeRun != null) {
			ActiveRun run = state.activeRun;
			ScenarioSessionConfig config = run.session.config();
			long elapsedTick = Math.max(0L, run.clock.snapshot().elapsedTick());
			String phaseTitle = config.preset().phaseAt(Math.min(elapsedTick, config.durationTicks() - 1L))
					.map(dev.agaminggod.arenaagents.scenario.ScenarioPhase::title)
					.orElse(run.session.state().name().toLowerCase(java.util.Locale.ROOT));
			return Optional.of(new ArenaSpectatorSnapshot.PublicView(
					config.sessionId().toString(),
					config.preset().id(),
					config.preset().title(),
					phaseTitle,
					elapsedTick,
					config.durationTicks(),
					false,
					config.preset().mapVersion(),
					config.worldSeed(),
					config.eventSeed(),
					"",
					run.origin.getX(), run.origin.getY(), run.origin.getZ(),
					publicParticipants(
							server,
							config.participants(),
							run.session.scores(),
							state.participantByAgent,
							run.origin,
							Map.of()
					),
					state.publicEvents
			));
		}
		if (state.recovery != null) {
			return Optional.of(publicViewFromSnapshot(server, state, state.recovery.snapshot, false));
		}
		if (state.activation != null) {
			ActivationJob activation = state.activation;
			Map<String, String> providers = new LinkedHashMap<>();
			for (PendingContestant contestant : activation.contestants) {
				providers.put("slot-" + contestant.spec.slot(), contestant.spec.provider());
			}
			return Optional.of(publicViewFromConfig(
					server, activation.config, activation.origin, "Waiting for contestants",
					activation.session.scores(), state, providers
			));
		}
		if (state.pendingActivation != null) {
			BuildJob build = state.pendingActivation;
			Map<String, String> providers = new LinkedHashMap<>();
			for (ScenarioAgentSpec spec : build.request.roster()) providers.put("slot-" + spec.slot(), spec.provider());
			return Optional.of(publicViewFromConfig(
					server, build.config, build.blueprint.origin(), "Waiting for coordinator",
					build.session.scores(), state, providers
			));
		}
		if (state.build != null) {
			BuildJob build = state.build;
			Map<String, String> providers = new LinkedHashMap<>();
			for (ScenarioAgentSpec spec : build.request.roster()) providers.put("slot-" + spec.slot(), spec.provider());
			return Optional.of(publicViewFromConfig(
					server, build.config, build.blueprint.origin(), buildProgressTitle(build),
					build.session.scores(), state, providers
			));
		}
		return Optional.empty();
	}

	/** Read-only build status projection for the operator command center and HUD. */
	public static synchronized Optional<ScenarioBuildProgress> buildProgress(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		RuntimeState state = STATES.get(server);
		return state == null ? Optional.empty() : Optional.ofNullable(state.buildProgress);
	}

	private static ArenaSpectatorSnapshot.PublicView publicViewFromConfig(
			MinecraftServer server,
			ScenarioSessionConfig config,
			BlockPos origin,
			String phaseTitle,
			Map<String, Double> scores,
			RuntimeState state,
			Map<String, String> providerFallbacks
	) {
		return new ArenaSpectatorSnapshot.PublicView(
				config.sessionId().toString(),
				config.preset().id(),
				config.preset().title(),
				phaseTitle,
				0L,
				config.durationTicks(),
				false,
				config.preset().mapVersion(),
				config.worldSeed(),
				config.eventSeed(),
				"",
				origin.getX(), origin.getY(), origin.getZ(),
				publicParticipants(
						server, config.participants(), scores, state.participantByAgent, origin, providerFallbacks),
				state.publicEvents
		);
	}

	private static ArenaSpectatorSnapshot.PublicView publicViewFromSnapshot(
			MinecraftServer server,
			RuntimeState state,
			ScenarioRunSnapshot snapshot,
			boolean terminal
	) {
		ScenarioPreset preset = ScenarioPresets.require(snapshot.scenarioId());
		BlockPos origin = new BlockPos(snapshot.origin().x(), snapshot.origin().y(), snapshot.origin().z());
		LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
		for (int index = 0; index < snapshot.participants().size(); index++) {
			bindings.put(snapshot.boundAgentIds().get(index), snapshot.participants().get(index).id());
		}
		List<ScenarioParticipant> participants = snapshot.participants().stream()
				.map(value -> new ScenarioParticipant(value.id(), value.displayName(), value.team()))
				.toList();
		long elapsedTick = Math.max(0L, snapshot.clock().elapsedTick());
		String phaseTitle = terminal
				? (snapshot.state() == dev.agaminggod.arenaagents.scenario.ScenarioSessionState.FINISHED
						? "Finished" : "Failed")
				: "Recovery paused";
		return new ArenaSpectatorSnapshot.PublicView(
				snapshot.sessionId().toString(),
				snapshot.scenarioId(),
				preset.title(),
				phaseTitle,
				elapsedTick,
				snapshot.durationTicks(),
				terminal,
				snapshot.mapVersion(),
				snapshot.worldSeed(),
				snapshot.eventSeed(),
				terminal ? resultFromSnapshot(snapshot).canonicalSha256() : "",
				origin.getX(), origin.getY(), origin.getZ(),
				publicParticipants(server, participants, snapshot.scores(), bindings, origin, Map.of()),
				snapshot.publicEvents()
		);
	}

	private static List<ArenaSpectatorSnapshot.ParticipantView> publicParticipants(
			MinecraftServer server,
			List<ScenarioParticipant> participants,
			Map<String, Double> scores,
			Map<String, String> participantByAgent,
			BlockPos origin,
			Map<String, String> providerFallbacks
	) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		Map<String, AgentRecord> recordsById = manager.records().stream().collect(java.util.stream.Collectors.toMap(
				record -> record.agentId().toString(),
				record -> record,
				(first, ignored) -> first,
				LinkedHashMap::new
		));
		LinkedHashMap<String, AgentRecord> recordsByParticipant = new LinkedHashMap<>();
		for (Map.Entry<String, String> binding : participantByAgent.entrySet()) {
			AgentRecord record = recordsById.get(binding.getKey());
			if (record != null) recordsByParticipant.put(binding.getValue(), record);
		}
		ArrayList<ArenaSpectatorSnapshot.ParticipantView> result = new ArrayList<>();
		for (ScenarioParticipant participant : participants) {
			AgentRecord record = recordsByParticipant.get(participant.id());
			ServerPlayer player = null;
			if (record != null) {
				try {
					player = manager.findAgentPlayer(record.agentId()).orElse(null);
				} catch (RuntimeException ignored) {
					// A removed contestant remains a public unavailable row until the run snapshot advances.
				}
			}
			String provider = record == null
					? providerFallbacks.getOrDefault(participant.id(), "unknown")
					: record.profile().provider();
			String status = record == null
					? "preparing" : record.state().name().toLowerCase(java.util.Locale.ROOT);
			int healthPercent = player == null
					? (record != null && record.state() == AgentLifecycleState.DEAD ? 0 : 100)
					: Math.clamp(Math.round(100.0F * player.getHealth() / Math.max(1.0F, player.getMaxHealth())), 0, 100);
			double x = player == null ? origin.getX() : player.getX();
			double y = player == null ? origin.getY() : player.getY();
			double z = player == null ? origin.getZ() : player.getZ();
			result.add(new ArenaSpectatorSnapshot.ParticipantView(
					participant.id(), participant.modelLabel(), provider,
					scores.getOrDefault(participant.id(), 0.0D), healthPercent, status, x, y, z
			));
		}
		return List.copyOf(result);
	}

	/**
	 * Runs before manager reconciliation. False means unsafe stale bindings remain and the
	 * caller must skip reconciliation/action work for this tick.
	 */
	public static synchronized boolean restorePersistedState(MinecraftServer server) {
		RuntimeState state = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
		if (state.cleanup != null) return retryFailedRecoveryCleanup(state, server);
		if (state.restoreAttempted) return true;
		state.restoreAttempted = true;
		Optional<ScenarioRunSnapshot> saved = ScenarioSavedData.get(server).snapshot();
		if (saved.isEmpty()) return true;
		ScenarioRunSnapshot snapshot = saved.orElseThrow();
		if (snapshot.state().terminal()) {
			beginResultPersistence(state, server, snapshot);
			return true;
		}
		Set<String> dimensions = new java.util.HashSet<>();
		ServerLevel savedLevel = null;
		for (ServerLevel level : server.getAllLevels()) {
			String dimensionId = level.dimension().identifier().toString();
			dimensions.add(dimensionId);
			if (dimensionId.equals(snapshot.dimensionId())) savedLevel = level;
		}
		ScenarioRecoveryDecision decision = ScenarioRecoveryDecision.evaluate(snapshot, dimensions);
		if (!decision.recoverable() || savedLevel == null) {
			state.cleanup = new CleanupJob(
					snapshot.failed(decision.failureReason()),
					decision.boundAgentsToRemove(),
					0
			);
			LOGGER.error("Scenario {} recovery failed closed: {}", snapshot.sessionId(), decision.failureReason());
			return retryFailedRecoveryCleanup(state, server);
		}
		ScenarioSession session = snapshot.restoreSession();
		ScenarioRuntimeClock clock = ScenarioRuntimeClock.restore(session, snapshot.clock());
		state.publicEvents.clear();
		state.publicEvents.addAll(snapshot.publicEvents());
		state.recovery = new RecoveryJob(snapshot, session, clock, savedLevel, 0L);
		return true;
	}

	private static void tickRecovery(RuntimeState state, boolean coordinatorReady) {
		RecoveryJob recovery = state.recovery;
		if (!coordinatorReady) {
			// Coordinator startup/reconnect time must never consume the player-roster timeout.
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(recovery.level.getServer());
		ArrayList<AgentRecord> records = new ArrayList<>();
		ArrayList<ScenarioRecoveryGate.AgentStatus> statuses = new ArrayList<>();
		for (String agentId : recovery.snapshot.boundAgentIds()) {
			Optional<AgentRecord> record = manager.records().stream()
					.filter(candidate -> candidate.agentId().toString().equals(agentId))
					.findFirst();
			if (record.isEmpty()) break;
			AgentRecord found = record.orElseThrow();
			records.add(found);
			statuses.add(new ScenarioRecoveryGate.AgentStatus(
					agentId,
					found.state(),
					manager.findAgentPlayer(found.agentId()).isPresent()
			));
		}
		if (statuses.size() == recovery.snapshot.boundAgentIds().size()) {
			ScenarioRecoveryGate.Decision gate = ScenarioRecoveryGate.evaluate(coordinatorReady, statuses);
			for (String agentId : gate.resumeAgentIds()) {
				try {
					manager.resume(agentId);
				} catch (RuntimeException exception) {
					LOGGER.warn("Recovered scenario agent {} could not resume yet", agentId, exception);
				}
			}
			if (!gate.resumeAgentIds().isEmpty()) {
				records.clear();
				statuses.clear();
				for (String agentId : recovery.snapshot.boundAgentIds()) {
					AgentRecord refreshed = manager.records().stream()
							.filter(candidate -> candidate.agentId().toString().equals(agentId))
							.findFirst().orElse(null);
					if (refreshed == null) break;
					records.add(refreshed);
					statuses.add(new ScenarioRecoveryGate.AgentStatus(
							agentId, refreshed.state(), manager.findAgentPlayer(refreshed.agentId()).isPresent()
					));
				}
				if (statuses.size() == recovery.snapshot.boundAgentIds().size()) {
					gate = ScenarioRecoveryGate.evaluate(coordinatorReady, statuses);
				}
			}
			if (gate.ready()) {
				recovery.session.resumeRecovery(Math.max(
						recovery.session.lastElapsedTick(), recovery.clock.snapshot().elapsedTick()
				));
				state.agents = List.copyOf(records);
				state.participantByAgent.clear();
				for (int index = 0; index < recovery.snapshot.boundAgentIds().size(); index++) {
					state.participantByAgent.put(
							recovery.snapshot.boundAgentIds().get(index),
							recovery.snapshot.participants().get(index).id()
					);
				}
				ScenarioParkourRunState parkour = restoreParkourRunState(
						recovery.session.config(), recovery.snapshot);
				state.activeRun = new ActiveRun(
						recovery.session,
						recovery.clock,
						recovery.snapshot.operatorId(),
						recovery.level,
						new BlockPos(recovery.snapshot.origin().x(), recovery.snapshot.origin().y(), recovery.snapshot.origin().z()),
						recovery.snapshot.reset(),
						parkour
				);
				state.recovery = null;
				persistActiveRun(state);
				return;
			}
		}
		if (recovery.elapsedTicks >= ROSTER_READY_TIMEOUT_TICKS) {
			String reason = "RECOVERY_ROSTER_TIMEOUT";
			state.cleanup = new CleanupJob(
					recovery.snapshot.failed(reason),
					recovery.snapshot.boundAgentIds(),
					0
			);
			state.recovery = null;
			state.agents = List.of();
			state.participantByAgent.clear();
			return;
		}
		state.recovery = recovery.nextTick();
	}

	private static ScenarioParkourRunState restoreParkourRunState(
			ScenarioSessionConfig config,
			ScenarioRunSnapshot snapshot
	) {
		if (config.preset().category() != dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
			return null;
		}
		Map<String, Integer> laneByParticipant = new LinkedHashMap<>();
		for (ScenarioSpawn spawn : new ScenarioSpawnAllocator().allocate(config)) {
			laneByParticipant.put(spawn.participantId(), spawn.slotIndex());
		}
		LinkedHashMap<String, Integer> laneByAgent = new LinkedHashMap<>();
		for (int index = 0; index < snapshot.boundAgentIds().size(); index++) {
			String participantId = snapshot.participants().get(index).id();
			Integer lane = laneByParticipant.get(participantId);
			if (lane == null) throw new IllegalStateException("parkour lane is unavailable for " + participantId);
			laneByAgent.put(snapshot.boundAgentIds().get(index), lane);
		}
		return new ScenarioParkourRunState(
				ScenarioParkourCourse.create(config.participants().size()), laneByAgent,
				snapshot.parkourCheckpoints());
	}

	private static void tickBuild(RuntimeState state) {
		BuildJob build = state.build;
		try {
			ScenarioArenaResetJob.Tick progress = build.reset.tick(build.level);
			boolean phaseChanged = progress.phase() != build.reportedPhase;
			int percent = progress.total() == 0
					? 100 : (int) (100L * progress.completed() / progress.total());
			boolean terminal = progress.phase() == ScenarioArenaResetJob.Phase.COMPLETE
					|| progress.phase() == ScenarioArenaResetJob.Phase.FAILED;
			boolean advanced = progress.worked() > 0 || progress.completed() != build.reportedCompleted;
			if (!terminal && (phaseChanged || advanced)) {
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
						state.nextBuildRevision(), buildProgressDetail(progress));
				build.operator.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
						"Arena at " + coordinates(build.blueprint.origin()) + ": "
								+ progress.phase().displayName() + " " + progress.completed() + " / "
								+ progress.total() + " (" + Math.min(100, percent) + "%)"
				)));
				build = build.withReportedProgress(progress.phase(), progress.completed());
			}
			state.build = build;
			if (progress.phase() == ScenarioArenaResetJob.Phase.FAILED) {
				String reason = progress.failureReason().isBlank() ? "RESET_VERIFICATION_FAILED" : progress.failureReason();
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
						state.nextBuildRevision(), resetFailureMessage(reason));
				LOGGER.error(
						"Arena reset failed at {}: reason={}, mismatches={}, samples={}",
						coordinates(build.blueprint.origin()), reason,
						build.reset.mismatchCount(), build.reset.mismatchSamples()
				);
				build.session.fail(0L, "Arena preparation failed: " + reason);
				publishOperatorNotice(build.operator, ScenarioOperatorMessagePolicy.Event.FAILURE,
						"Arena launch failed: " + resetFailureMessage(reason));
				state.build = null;
			} else if (progress.phase() == ScenarioArenaResetJob.Phase.COMPLETE) {
				populateArenaContainers(build);
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(),
						build.blueprint.origin().getZ(), state.nextBuildRevision(),
						"Arena ready at " + coordinates(build.blueprint.origin()) + ". Waiting to start agents.");
				state.build = null;
				if (CodexAgentServerRuntime.automationAvailable(build.level.getServer())) {
					beginActivationOrWait(state, build);
				} else {
					waitForCoordinator(state, build);
				}
			}
		} catch (RuntimeException exception) {
			int total = Math.max(0, build.reset.totalPlacements());
			int completed = Math.clamp(build.reset.completedWork(), 0, total);
			ScenarioArenaResetJob.Tick failedTick = new ScenarioArenaResetJob.Tick(
					ScenarioArenaResetJob.Phase.FAILED, 0, completed, total,
					Math.clamp(build.reset.changedBlocks(), 0, ScenarioBuildProgress.MAX_TOTAL_WORK),
					build.reset.receipt(), safeMessage(exception));
			state.buildProgress = ScenarioBuildProgress.failed(
					build.config.sessionId().toString(), build.config.preset().title(), failedTick,
					build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
					state.nextBuildRevision(), safeMessage(exception));
			build.session.fail(0L, "Arena preparation failed: " + safeMessage(exception));
			publishOperatorNotice(build.operator, ScenarioOperatorMessagePolicy.Event.FAILURE,
					"Arena launch failed: " + safeMessage(exception));
			state.build = null;
		}
	}

	private static void tickPendingActivation(RuntimeState state) {
		BuildJob build = state.pendingActivation;
		if (!CodexAgentServerRuntime.automationAvailable(build.level.getServer())) {
			if (state.runtimeTick % 40L == 0L) {
				build.operator.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
						"Arena ready | connecting agent coordinator..."
				)));
			}
			return;
		}
		state.pendingActivation = null;
		beginActivationOrWait(state, build);
	}

	private static void beginActivationOrWait(RuntimeState state, BuildJob build) {
		try {
			beginActivation(state, build);
		} catch (RuntimeException exception) {
			if (ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(exception)) {
				waitForCoordinator(state, build);
				return;
			}
			build.session.fail(0L, "Contestant activation failed: " + safeMessage(exception));
			state.buildProgress = activationFailureProgress(state, build.config, build.blueprint.origin(),
					"Agents could not start: " + safeMessage(exception));
			publishOperatorNotice(build.operator, ScenarioOperatorMessagePolicy.Event.FAILURE,
					"Arena is ready, but agents could not start: " + safeMessage(exception));
		}
	}

	private static void waitForCoordinator(RuntimeState state, BuildJob build) {
		state.pendingActivation = build;
		build.operator.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
				"Arena ready | waiting for agent coordinator"
		)));
	}

	private static String buildProgressDetail(ScenarioArenaResetJob.Tick progress) {
		return switch (progress.phase()) {
			case CANONICALIZE -> "Preparing the arena blueprint";
			case LOAD_CHUNKS -> "Loading " + progress.completed() + " of " + progress.total() + " arena chunks";
			case CLEAR -> "Cleared " + progress.completed() + " of " + progress.total()
					+ " vertical cells; " + progress.changedBlocks() + " blocks removed";
			case APPLY -> "Processed " + progress.completed() + " of " + progress.total()
					+ " blueprint blocks; " + progress.changedBlocks() + " world changes made";
			case VERIFY -> "Checking " + progress.completed() + " of " + progress.total() + " arena blocks";
			case REPAIR -> "Corrected " + progress.completed() + " of " + progress.total()
					+ " mismatched arena blocks";
			case COMPLETE -> "Arena verified";
			case FAILED -> progress.failureReason().isBlank() ? "Arena build failed" : progress.failureReason();
		};
	}

	private static String buildProgressTitle(BuildJob build) {
		String label = "Build " + coordinates(build.blueprint.origin()) + " | " + build.reset.progressLabel();
		return label.length() <= 80 ? label : build.reset.progressLabel();
	}

	private static String coordinates(BlockPos position) {
		return position.getX() + ", " + position.getY() + ", " + position.getZ();
	}

	private static String resetFailureMessage(String reason) {
		return switch (reason) {
			case "UNLOADED_MANAGED_CHUNK" -> "a managed arena chunk could not be loaded; retry the launch";
			case "RESET_VERIFICATION_MISMATCH", "RESET_VERIFICATION_FAILED",
					"RESET_VERIFICATION_DID_NOT_CONVERGE" ->
					"the arena remained unstable after three automatic correction passes";
			default -> reason;
		};
	}

	private static void tickActivation(RuntimeState state) {
		ActivationJob activation = state.activation;
		CodexAgentManager manager = CodexAgentManager.get(activation.level.getServer());
		LinkedHashMap<String, ServerPlayer> readyPlayers = new LinkedHashMap<>();
		for (PendingContestant contestant : activation.contestants) {
			manager.findAgentPlayer(contestant.record.agentId()).ifPresent(player ->
					readyPlayers.put(contestant.record.agentId().toString(), player)
			);
		}
		ScenarioRosterActivator activator = new ScenarioRosterActivator();
		activator.protect(readyPlayers.values().stream().toList(), player -> player.setInvulnerable(true));
		ScenarioPreflight.Verdict preflight = ScenarioPreflight.assess(new ScenarioPreflight.Input(
				CoordinatorStatusStore.latest(activation.level.getServer()),
				activation.contestants.stream().map(contestant -> new ScenarioPreflight.RequiredProfile(
						contestant.record.agentId().toString(), contestant.spec.provider(),
						contestant.spec.model(), contestant.spec.reasoning()
				)).toList(),
				activation.expectedDimension,
				activation.level.dimension().identifier().toString(),
				activation.resetReceipt.blueprintSha256(),
				activation.resetReceipt.managedVolumeSha256(),
				activation.resetReceipt.verified(),
				activation.firstWaveStartedAtEpochMs,
				System.currentTimeMillis()
		));
		if (preflight.status() == ScenarioPreflight.Status.FAILED) {
			failActivation(state, activation, manager, "preflight " + preflight.code());
			return;
		}
		if (preflight.status() == ScenarioPreflight.Status.WAITING) {
			state.activation = activation.nextTick();
			return;
		}
		ScenarioRosterReadinessBarrier.Assessment assessment = activation.barrier.assess(
				activation.elapsedTicks,
				readyPlayers.keySet()
		);
		if (assessment.status() == ScenarioRosterReadinessBarrier.Status.WAITING) {
			state.activation = activation.nextTick();
			return;
		}
		if (assessment.status() == ScenarioRosterReadinessBarrier.Status.TIMED_OUT) {
			failActivation(
					state,
					activation,
					manager,
					assessment.missingIds().size() + " of " + activation.contestants.size()
							+ " offline contestants did not become ready"
			);
			return;
		}

		try {
			List<ReadyContestant> ready = activation.contestants.stream()
					.map(contestant -> new ReadyContestant(
							contestant,
							readyPlayers.get(contestant.record.agentId().toString())
					))
					.toList();
			ScenarioLoadoutService loadouts = new ScenarioLoadoutService();
			activator.activate(
					ready,
					contestant -> contestant.player.setInvulnerable(false),
					contestant -> loadouts.apply(
							contestant.player,
							activation.config.preset().category(),
							ScenarioParticipantPolicy.effectiveGameMode(
									activation.config.preset().category(), contestant.pending.spec.gameMode())
					),
					contestant -> manager.startSubjective(
							contestant.pending.record.agentId().toString(),
							contestantPrompt(
									activation.config.preset(),
									contestant.pending.spec,
									activation.config,
									contestant.pending.laneIndex,
									activation.origin
							)
					)
			);
			activation.session.start(0L);
			state.agents = activation.contestants.stream().map(PendingContestant::record).toList();
			state.participantByAgent.clear();
			for (PendingContestant contestant : activation.contestants) {
				state.participantByAgent.put(
						contestant.record.agentId().toString(),
						"slot-" + contestant.spec.slot()
				);
			}
			ScenarioParkourRunState parkour = null;
			if (activation.config.preset().category() == dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
				LinkedHashMap<String, Integer> laneByAgent = new LinkedHashMap<>();
				for (PendingContestant contestant : activation.contestants) {
					laneByAgent.put(contestant.record.agentId().toString(), contestant.laneIndex);
				}
				parkour = new ScenarioParkourRunState(
						ScenarioParkourCourse.create(activation.config.participants().size()), laneByAgent);
			}
			state.activeRun = new ActiveRun(
					activation.session,
					new ScenarioRuntimeClock(activation.session),
					activation.operator.getUUID(),
					activation.level,
					activation.origin,
					activation.resetReceipt,
					parkour
			);
			state.activation = null;
			persistActiveRun(state);
			BlockPos operatorSpawn = ScenarioArenaBlueprint.operatorSpawn(activation.origin);
			activation.operator.teleportTo(
					operatorSpawn.getX() + 0.5D,
					operatorSpawn.getY(),
					operatorSpawn.getZ() + 0.5D
			);
			publishOperatorNotice(activation.operator, ScenarioOperatorMessagePolicy.Event.STARTED,
					"GO | " + activation.config.preset().title() + " launched with " + ready.size()
							+ " independently controlled offline players."
			);
		} catch (RuntimeException exception) {
			failActivation(state, activation, manager, safeMessage(exception));
		}
	}

	private static void tickActive(RuntimeState state) {
		ActiveRun run = state.activeRun;
		if (run == null) return;
		tickParkourParticipants(state, run);
		ScenarioRuntimeClock.Update update = run.clock.tick();
		if (!run.session.state().terminal()) {
			completionReason(state, run).ifPresent(reason -> run.session.finish(update.elapsedTick(), reason));
		}
		if (run.clock.snapshot().elapsedTick() % 20L == 0L) persistActiveRun(state);
		if (!run.session.state().terminal()) return;
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		for (AgentRecord agent : state.agents) {
			try {
				manager.stop(agent.agentId().toString());
			} catch (RuntimeException ignored) {
				// A contestant may already have completed, failed, or died.
			}
		}
		publishOperatorNotice(
				run.level.getServer().getPlayerList().getPlayer(run.operatorId),
				ScenarioOperatorMessagePolicy.Event.FINISHED,
				"FINISHED | " + run.session.config().preset().title() + ": "
						+ run.session.completionReason().orElse("Scenario complete")
		);
		ScenarioRunSnapshot terminalSnapshot = persistActiveRun(state);
		beginResultPersistence(state, run.level.getServer(), terminalSnapshot);
	}

	/**
	 * Vanilla death is never intercepted by a scenario. A selected model may later
	 * choose the coordinate-free vanilla respawn primitive for its dead agent.
	 */
	public static synchronized boolean recoverParkourDeath(ServerPlayer player) {
		Objects.requireNonNull(player, "player must not be null");
		return false;
	}

	private static void tickParkourParticipants(RuntimeState state, ActiveRun run) {
		if (run.parkour == null) return;
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		for (AgentRecord agent : state.agents) {
			ServerPlayer player = manager.findAgentPlayer(agent.agentId()).orElse(null);
			if (player == null || !player.isAlive()) continue;
			player.setGameMode(GameType.ADVENTURE);
			ScenarioParkourRecovery.Decision decision = run.parkour.evaluate(
					agent.agentId().toString(),
					player.getX() - run.origin.getX(),
					player.getY() - run.origin.getY(),
					player.getZ() - run.origin.getZ()
			);
			ScenarioParkourRecovery.Target target = decision.target();
			BlockPos respawn = BlockPos.containing(
					run.origin.getX() + target.x(),
					run.origin.getY() + target.y(),
					run.origin.getZ() + target.z()
			);
			player.setRespawnPosition(new ServerPlayer.RespawnConfig(
					LevelData.RespawnData.of(run.level.dimension(), respawn, player.getYRot(), player.getXRot()),
					true
			), false);
		}
	}

	private static Optional<String> completionReason(RuntimeState state, ActiveRun run) {
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		Map<String, AgentRecord> currentRecords = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) {
			currentRecords.put(record.agentId().toString(), record);
		}
		ArrayList<ScenarioCompletionPolicy.ParticipantState> participants = new ArrayList<>();
		for (AgentRecord boundAgent : state.agents) {
			String agentId = boundAgent.agentId().toString();
			String participantId = state.participantByAgent.get(agentId);
			if (participantId == null) continue;
			AgentRecord current = currentRecords.get(agentId);
			ScenarioParticipant participant = run.session.config().requireParticipant(participantId);
			boolean alive = current != null
					&& manager.findAgentPlayer(boundAgent.agentId()).map(ServerPlayer::isAlive).orElse(false);
			participants.add(new ScenarioCompletionPolicy.ParticipantState(
				agentId, participant.team(),
					current == null ? AgentLifecycleState.DISCONNECTED : current.state(), alive
			));
		}
		if (participants.size() != run.session.config().participants().size()) return Optional.empty();
		return ScenarioCompletionPolicy.finishReason(
				run.session.config().preset().category(), participants,
				run.parkour != null && run.parkour.allFinished()
		);
	}

	private static void publishOperatorNotice(
			ServerPlayer operator,
			ScenarioOperatorMessagePolicy.Event event,
			String message
	) {
		if (operator == null || ScenarioOperatorMessagePolicy.surface(event)
				!= ScenarioOperatorMessagePolicy.Surface.ACTION_BAR) return;
		operator.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(message)));
	}

	private static ScenarioRunSnapshot persistActiveRun(RuntimeState state) {
		ActiveRun run = state.activeRun;
		if (run == null) throw new IllegalStateException("active scenario is unavailable");
		ScenarioRunSnapshot snapshot = ScenarioRunSnapshot.capture(
				run.session,
				run.clock,
				run.level.dimension().identifier().toString(),
				run.operatorId,
				state.agents.stream().map(agent -> agent.agentId().toString()).toList(),
				run.parkour == null ? Map.of() : run.parkour.checkpoints(),
				new ScenarioRunSnapshot.Origin(run.origin.getX(), run.origin.getY(), run.origin.getZ()),
				run.resetReceipt,
				state.publicEvents
		);
		ScenarioSavedData.get(run.level.getServer()).setSnapshot(snapshot);
		return snapshot;
	}

	private static MatchResultV1 resultFromSnapshot(ScenarioRunSnapshot snapshot) {
		String outcome = snapshot.state() == dev.agaminggod.arenaagents.scenario.ScenarioSessionState.FINISHED
				? "completed" : "failed";
		List<MatchResultV1.Standing> standings = snapshot.participants().stream()
				.map(participant -> new MatchResultV1.Standing(
						participant.id(),
						participant.displayName(),
						snapshot.scores().getOrDefault(participant.id(), 0.0D),
						outcome
				))
				.toList();
		MatchResultV1 result = new MatchResultV1(
				snapshot.sessionId().toString(),
				snapshot.scenarioId(),
				snapshot.mapVersion(),
				snapshot.worldSeed(),
				snapshot.eventSeed(),
				standings,
				snapshot.publicEvents(),
				snapshot.reset(),
				""
		);
		return result;
	}

	private static void beginResultPersistence(
			RuntimeState state,
			MinecraftServer server,
			ScenarioRunSnapshot snapshot
	) {
		if (!snapshot.state().terminal()) {
			throw new IllegalArgumentException("only terminal scenarios can produce match results");
		}
		if (state.pendingResult != null) return;
		MatchResultV1 result = resultFromSnapshot(snapshot);
		MatchResultWriter writer = new MatchResultWriter(
				server.getServerDirectory().resolve("runtime").resolve("match-results")
		);
		state.pendingResult = new PendingResult(
				snapshot,
				new ScenarioResultPersistence(
						result,
						value -> writer.writeAsync(value, java.util.concurrent.ForkJoinPool.commonPool())
				)
		);
	}

	private static void tickPendingResult(RuntimeState state, MinecraftServer server) {
		PendingResult pending = state.pendingResult;
		if (pending == null) return;
		ScenarioResultPersistence.Status status = pending.persistence.poll(state.runtimeTick);
		if (status.durable()) {
			// Scenario contestants are run-owned. Leaving their durable registry rows
			// behind makes the next run collide with the same roster display names.
			List<String> boundAgentIds = ScenarioOwnedAgentIds.forCleanup(state.agents.stream()
					.map(agent -> agent.agentId().toString())
					.toList(), pending.snapshot.boundAgentIds());
			if (!removeBoundAgentsStrict(CodexAgentManager.get(server), boundAgentIds)) return;
			ScenarioSavedData.get(server).clear();
			state.pendingResult = null;
			state.activeRun = null;
			state.agents = List.of();
			state.participantByAgent.clear();
			return;
		}
		if (!status.lastFailure().isBlank() && status.attempts() > pending.loggedAttempts) {
			pending.loggedAttempts = status.attempts();
			LOGGER.error(
					"Canonical match result {} is not durable yet; retry {} scheduled: {}",
					pending.snapshot.sessionId(), status.attempts(), status.lastFailure()
			);
		}
	}

	public static synchronized void onAgentEvent(MinecraftServer server, ScenarioAgentEvent event) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(event, "event must not be null");
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null
				|| state.activeRun.session.state() != dev.agaminggod.arenaagents.scenario.ScenarioSessionState.RUNNING) {
			return;
		}
		boolean participantExists = state.activeRun.session.config().participants().stream()
				.anyMatch(participant -> participant.id().equals(event.participantId()));
		if (!participantExists) return;
		ScenarioPublicEvent publicEvent = new ScenarioPublicFormatter().format(event);
		if (state.publicEvents.size() == PUBLIC_EVENT_LIMIT) state.publicEvents.removeFirst();
		state.publicEvents.add(publicEvent);
	}

	public static synchronized void onAgentAction(
			MinecraftServer server,
			String agentId,
			String actionWireName,
			boolean succeeded
	) {
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null) return;
		String participantId = state.participantByAgent.get(agentId);
		if (participantId == null) return;
		ScenarioParticipant participant = state.activeRun.session.config().requireParticipant(participantId);
		onAgentEvent(server, new ScenarioAgentEvent(
				state.activeRun.clock.snapshot().elapsedTick(),
				participant.id(),
				participant.modelLabel(),
				succeeded ? ScenarioAgentEvent.Kind.ACTION_COMPLETED : ScenarioAgentEvent.Kind.ACTION_FAILED,
				actionFamily(actionWireName),
				0.0D,
				succeeded ? ScenarioAgentEvent.PublicState.ACTING : ScenarioAgentEvent.PublicState.RECOVERING
		));
	}

	public static synchronized void onAgentState(
			MinecraftServer server,
			String agentId,
			ScenarioAgentEvent.PublicState publicState
	) {
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null) return;
		String participantId = state.participantByAgent.get(agentId);
		if (participantId == null) return;
		ScenarioParticipant participant = state.activeRun.session.config().requireParticipant(participantId);
		onAgentEvent(server, new ScenarioAgentEvent(
				state.activeRun.clock.snapshot().elapsedTick(),
				participant.id(),
				participant.modelLabel(),
				ScenarioAgentEvent.Kind.STATE_CHANGED,
				ScenarioAgentEvent.ActionFamily.OTHER,
				0.0D,
				publicState
		));
	}

	private static ScenarioAgentEvent.ActionFamily actionFamily(String wireName) {
		if (wireName == null) return ScenarioAgentEvent.ActionFamily.OTHER;
		return switch (wireName) {
			case "move_to", "navigate_to", "follow_entity", "look_at" -> ScenarioAgentEvent.ActionFamily.MOVEMENT;
			case "break_block", "pick_up_item" -> ScenarioAgentEvent.ActionFamily.HARVEST;
			case "place_block" -> ScenarioAgentEvent.ActionFamily.BUILD;
			case "craft_inventory", "craft_table", "furnace_transaction", "transfer_container",
					"equip_item", "select_tool", "select_item" -> ScenarioAgentEvent.ActionFamily.CRAFT;
			case "attack", "fight_target", "block_with_shield", "use_ranged" -> ScenarioAgentEvent.ActionFamily.COMBAT;
			case "flee_from", "use_item" -> ScenarioAgentEvent.ActionFamily.SURVIVAL;
			case "chat" -> ScenarioAgentEvent.ActionFamily.COMMUNICATION;
			default -> ScenarioAgentEvent.ActionFamily.OTHER;
		};
	}

	public static synchronized void release(MinecraftServer server) {
		RuntimeState state = STATES.remove(server);
		if (state != null && state.build != null) state.build.reset.close(state.build.level);
		if (state != null && state.activeRun != null && state.pendingResult == null) persistActiveRun(state);
	}

	private static void beginActivation(RuntimeState state, BuildJob build) {
		CodexAgentManager manager = CodexAgentManager.get(build.level.getServer());
		Map<String, ScenarioAgentSpec> specs = new LinkedHashMap<>();
		for (ScenarioAgentSpec spec : build.request.roster()) {
			specs.put("slot-" + spec.slot(), spec);
		}
		List<ScenarioSpawn> spawns = new ScenarioSpawnAllocator().allocate(build.config);
		ArrayList<PendingContestant> contestants = new ArrayList<>();
		try {
			for (ScenarioSpawn spawn : spawns) {
				ScenarioAgentSpec spec = specs.get(spawn.participantId());
				Vec3 position = new Vec3(
						build.blueprint.origin().getX() + spawn.x() + 0.5D,
						build.blueprint.origin().getY() + spawn.y(),
						build.blueprint.origin().getZ() + spawn.z() + 0.5D
				);
				var effectiveGameMode = ScenarioParticipantPolicy.effectiveGameMode(
						build.config.preset().category(), spec.gameMode());
				AgentRecord record = manager.summon(
						build.level,
						position,
						spec.provider(),
						spec.model(),
						spec.reasoning(),
						spec.serviceTier(),
						Optional.of(spec.displayName()),
						effectiveGameMode
				);
				contestants.add(new PendingContestant(record, spec, spawn.slotIndex()));
			}
		} catch (RuntimeException exception) {
			removeContestants(manager, contestants.stream().map(PendingContestant::record).toList());
			throw exception;
		}
		build.session.markReady(0L);
		build.session.beginCountdown(0L);
		List<PendingContestant> pending = List.copyOf(contestants);
		state.activation = new ActivationJob(
				build.session,
				build.operator,
				build.level,
				build.config,
				build.blueprint.origin(),
				build.reset.receipt().orElseThrow(),
				pending,
				new ScenarioRosterReadinessBarrier(
						pending.stream().map(contestant -> contestant.record.agentId().toString()).toList(),
						ROSTER_READY_TIMEOUT_TICKS
				),
				build.level.dimension().identifier().toString(),
				System.currentTimeMillis(),
				0L
		);
		publishOperatorNotice(build.operator, ScenarioOperatorMessagePolicy.Event.READY,
				"Arena ready | waiting for " + pending.size() + " offline contestants.");
	}

	private static void failActivation(
			RuntimeState state,
			ActivationJob activation,
			CodexAgentManager manager,
			String reason
	) {
		activation.session.fail(0L, "Contestant activation failed: " + reason);
		state.buildProgress = activationFailureProgress(
				state, activation.config, activation.origin, "Agents could not start: " + reason);
		publishOperatorNotice(activation.operator, ScenarioOperatorMessagePolicy.Event.FAILURE,
				"Arena launch failed: " + reason);
		removeContestants(manager, activation.contestants.stream().map(PendingContestant::record).toList());
		state.activation = null;
		state.activeRun = null;
		state.agents = List.of();
	}

	private static ScenarioBuildProgress activationFailureProgress(
			RuntimeState state,
			ScenarioSessionConfig config,
			BlockPos origin,
			String detail
	) {
		return ScenarioBuildProgress.rejected(
				config.sessionId().toString(), config.preset().title(), state.nextBuildRevision(),
				origin.getX(), origin.getY(), origin.getZ(), detail);
	}

	private static void removeContestants(CodexAgentManager manager, List<AgentRecord> contestants) {
		for (AgentRecord contestant : contestants.reversed()) {
			try {
				manager.remove(contestant.agentId().toString());
			} catch (RuntimeException ignored) {
				// A contestant may still be spawning or may already have been removed manually.
			}
		}
	}

	private static boolean removeBoundAgentsStrict(CodexAgentManager manager, List<String> agentIds) {
		boolean clean = true;
		for (String agentId : agentIds.reversed()) {
			try {
				manager.remove(agentId);
			} catch (AgentDomainException exception) {
				if (agentRegistryContains(manager, agentId)) {
					clean = false;
					LOGGER.error("Failed to delete stale scenario agent {}: {}", agentId, exception.code(), exception);
				} else if (!"AGENT_NOT_FOUND".equals(exception.code())) {
					LOGGER.warn(
							"Scenario agent {} was deleted from the registry despite cleanup error {}",
							agentId, exception.code(), exception
					);
				}
			} catch (RuntimeException exception) {
				if (agentRegistryContains(manager, agentId)) {
					clean = false;
					LOGGER.error("Failed to delete stale scenario agent {}", agentId, exception);
				} else {
					LOGGER.warn("Scenario agent {} registry deletion completed before cleanup failed", agentId, exception);
				}
			}
		}
		for (String agentId : agentIds) {
			if (agentRegistryContains(manager, agentId)) clean = false;
		}
		return clean;
	}

	private static boolean agentRegistryContains(CodexAgentManager manager, String agentId) {
		return manager.records().stream().anyMatch(record -> record.agentId().toString().equals(agentId));
	}

	private static boolean retryFailedRecoveryCleanup(RuntimeState state, MinecraftServer server) {
		CleanupJob cleanup = state.cleanup;
		if (cleanup == null) return true;
		if (!removeBoundAgentsStrict(CodexAgentManager.get(server), cleanup.agentIds)) {
			state.cleanup = cleanup.nextAttempt();
			return false;
		}
		ScenarioSavedData.get(server).setSnapshot(cleanup.failedSnapshot);
		state.cleanup = null;
		beginResultPersistence(state, server, cleanup.failedSnapshot);
		return true;
	}

	private static String contestantPrompt(
			ScenarioPreset preset,
			ScenarioAgentSpec spec,
			ScenarioSessionConfig config,
			int laneIndex,
			BlockPos origin
	) {
		String arenaSpecific = "";
		if (preset.category() == dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
			ScenarioParkourCourse.Lane lane = ScenarioParkourCourse.create(config.participants().size())
					.lanes().get(laneIndex);
			ScenarioParkourCourse.Platform start = lane.platforms().getFirst();
			ScenarioParkourCourse.Platform finish = lane.platforms().getLast();
			arenaSpecific = "Your dedicated lane is " + lane.index() + " at world x approximately "
					+ (origin.getX() + start.centerX()) + ". Stay in that lane; do not jump to another contestant's course. "
					+ "Advance toward increasing world z from " + (origin.getZ() + start.centerZ()) + " to "
					+ (origin.getZ() + finish.centerZ())
					+ ". Glowing platforms are checkpoints. Death remains a normal vanilla death; choose respawn only when appropriate.";
		}
		return """
				You are contestant %s in the Minecraft AI Arena scenario "%s".
				Primary objective: %s
				Map landmarks: %s
				Dynamic pressures: %s
				Your model's decisions are the point of the comparison. Act immediately, visibly, and autonomously.
				Use navigation/combat/flee/follow controller actions for sustained behavior. Preserve yourself in Survival,
				but do not invent an objective beyond this brief. Other contestants are independently controlled.
				Session seed: %d. Team: %s.
				%s
				""".formatted(
				spec.displayName(),
				preset.title(),
				preset.objective(),
				String.join(", ", preset.landmarks()),
				String.join(", ", preset.dynamicEvents()),
				config.worldSeed(),
				spec.team().orElse("solo"),
				arenaSpecific
		).trim();
	}

	private static void populateArenaContainers(BuildJob build) {
		if (build.config.preset().category() != dev.agaminggod.arenaagents.scenario.ScenarioCategory.PVP) return;
		for (ScenarioArenaBlueprint.Placement placement :
				ScenarioArenaResetJob.canonicalize(build.blueprint.placements())) {
			if (placement.state().getBlock() != Blocks.CHEST && placement.state().getBlock() != Blocks.BARREL) continue;
			if (!(build.level.getBlockEntity(placement.position()) instanceof Container container)) {
				throw new IllegalStateException("MISSING_LOOT_CONTAINER_AT_" + placement.position().toShortString());
			}
			ScenarioLootManifest manifest = ScenarioLootManifest.forContainer(
					build.blueprint.origin(), placement.position(), build.config.worldSeed());
			Map<Integer, ItemStack> expected = new java.util.HashMap<>();
			for (ScenarioLootManifest.Entry entry : manifest.entries()) {
				if (entry.slot() >= container.getContainerSize()) {
					throw new IllegalStateException("LOOT_SLOT_OUT_OF_RANGE_AT_" + placement.position().toShortString());
				}
				expected.put(entry.slot(), lootStack(entry));
			}
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack wanted = expected.getOrDefault(slot, ItemStack.EMPTY);
				if (!sameStack(container.getItem(slot), wanted)) container.setItem(slot, wanted.copy());
			}
			container.setChanged();
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack wanted = expected.getOrDefault(slot, ItemStack.EMPTY);
				if (!sameStack(container.getItem(slot), wanted)) {
					throw new IllegalStateException("LOOT_VERIFICATION_FAILED_AT_"
							+ placement.position().toShortString() + "_SLOT_" + slot);
				}
			}
		}
	}

	private static ItemStack lootStack(ScenarioLootManifest.Entry entry) {
		Identifier identifier = Identifier.tryParse(entry.itemId());
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new IllegalStateException("UNKNOWN_LOOT_ITEM_" + entry.itemId());
		}
		return new ItemStack(BuiltInRegistries.ITEM.getValue(identifier), entry.count());
	}

	private static boolean sameStack(ItemStack actual, ItemStack expected) {
		if (actual.isEmpty() || expected.isEmpty()) return actual.isEmpty() && expected.isEmpty();
		return actual.getItem() == expected.getItem() && actual.getCount() == expected.getCount();
	}

	static BlockPos arenaOrigin(
			ServerLevel level,
			ScenarioPreset preset,
			ServerPlayer operator,
			ScenarioPlacementMode placementMode
	) {
		if (placementMode != ScenarioPlacementMode.FIXED_LANE) {
			int distance = placementMode == ScenarioPlacementMode.IN_FRONT_OF_PLAYER ? 80 : 0;
			int x = operator.getBlockX() + operator.getDirection().getStepX() * distance;
			int z = operator.getBlockZ() + operator.getDirection().getStepZ() * distance;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			return new BlockPos(x, surface - 1, z);
		}
		int lane = switch (preset.category()) {
			case SURVIVAL -> 0;
			case BUILDING -> 1;
			case PVP -> 2;
			case PARKOUR -> 3;
		};
		int x = 192 + lane * 160;
		int z = 320;
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, Math.max(80, surface + 3), z);
	}

	private static long scenarioSeed(ServerLevel level, ScenarioPreset preset) {
		return level.getSeed() ^ ((long) preset.id().hashCode() << 32) ^ preset.mapVersion().hashCode();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	private static final class RuntimeState {
		private BuildJob build;
		private BuildJob pendingActivation;
		private ScenarioBuildProgress buildProgress;
		private long buildProgressRevision;
		private ActivationJob activation;
		private RecoveryJob recovery;
		private CleanupJob cleanup;
		private ActiveRun activeRun;
		private PendingResult pendingResult;
		private List<AgentRecord> agents = List.of();
		private final ArrayList<ScenarioPublicEvent> publicEvents = new ArrayList<>();
		private final LinkedHashMap<String, String> participantByAgent = new LinkedHashMap<>();
		private long runtimeTick;
		private boolean restoreAttempted;

		private long nextBuildRevision() {
			return ++buildProgressRevision;
		}
	}

	private record CleanupJob(
			ScenarioRunSnapshot failedSnapshot,
			List<String> agentIds,
			int attempts
	) {
		private CleanupJob {
			Objects.requireNonNull(failedSnapshot, "failedSnapshot must not be null");
			agentIds = List.copyOf(Objects.requireNonNull(agentIds, "agentIds must not be null"));
			if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
		}

		private CleanupJob nextAttempt() {
			return new CleanupJob(failedSnapshot, agentIds, attempts + 1);
		}
	}

	private static final class PendingResult {
		private final ScenarioRunSnapshot snapshot;
		private final ScenarioResultPersistence persistence;
		private int loggedAttempts;

		private PendingResult(ScenarioRunSnapshot snapshot, ScenarioResultPersistence persistence) {
			this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
			this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
		}
	}

	private record RecoveryJob(
			ScenarioRunSnapshot snapshot,
			ScenarioSession session,
			ScenarioRuntimeClock clock,
			ServerLevel level,
			long elapsedTicks
	) {
		private RecoveryJob nextTick() {
			return new RecoveryJob(snapshot, session, clock, level, elapsedTicks + 1L);
		}
	}

	private record PendingContestant(AgentRecord record, ScenarioAgentSpec spec, int laneIndex) {
	}

	private record ReadyContestant(PendingContestant pending, ServerPlayer player) {
	}

	private record ActivationJob(
			ScenarioSession session,
			ServerPlayer operator,
			ServerLevel level,
			ScenarioSessionConfig config,
			BlockPos origin,
			ScenarioResetReceipt resetReceipt,
			List<PendingContestant> contestants,
			ScenarioRosterReadinessBarrier barrier,
			String expectedDimension,
			long firstWaveStartedAtEpochMs,
			long elapsedTicks
	) {
		private ActivationJob nextTick() {
			return new ActivationJob(
					session,
					operator,
					level,
					config,
					origin,
					resetReceipt,
					contestants,
					barrier,
					expectedDimension,
					firstWaveStartedAtEpochMs,
					elapsedTicks + 1L
			);
		}
	}

	private record ActiveRun(
			ScenarioSession session,
			ScenarioRuntimeClock clock,
			UUID operatorId,
			ServerLevel level,
			BlockPos origin,
			ScenarioResetReceipt resetReceipt,
			ScenarioParkourRunState parkour
	) {
	}

	private record BuildJob(
			ServerPlayer operator,
			ServerLevel level,
			ScenarioLaunchRequest request,
			ScenarioSessionConfig config,
			ScenarioSession session,
			ScenarioArenaBlueprint blueprint,
			ScenarioArenaResetJob reset,
			ScenarioArenaResetJob.Phase reportedPhase,
			int reportedCompleted
	) {
		private BuildJob withReportedProgress(ScenarioArenaResetJob.Phase phase, int value) {
			return new BuildJob(operator, level, request, config, session, blueprint, reset, phase, value);
		}
	}
}
