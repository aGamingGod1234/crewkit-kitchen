package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntime;
import dev.agaminggod.arenaagents.server.goal.GoalSafetyController;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, BridgeSlot> BRIDGE_SLOTS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, CoordinatorProcessSupervisor> COORDINATORS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, VoiceStartGate> VOICE_STARTS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<String, Long>> PLANNING_UPDATES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, GoalVerificationRuntime> GOAL_VERIFIERS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, GoalSafetyController> GOAL_SAFETY = new ConcurrentHashMap<>();
	private static final long PLANNING_UPDATE_INTERVAL_MS = 30_000L;
	private static boolean registered;

	private CodexAgentServerRuntime() {
	}

	public static void confirmCurrentGoal(MinecraftServer server, AgentId agentId) {
		GoalVerificationRuntime runtime = GOAL_VERIFIERS.get(server);
		if (runtime == null) {
			throw new dev.agaminggod.arenaagents.agent.AgentDomainException(
					"GOAL_VERIFIER_UNAVAILABLE", "Goal verification is not running"
			);
		}
		var goal = CodexAgentManager.get(server).registry().require(agentId).currentGoal()
				.orElseThrow(() -> new dev.agaminggod.arenaagents.agent.AgentDomainException(
						"NO_CURRENT_GOAL", "Agent has no current goal to confirm"
				));
		if (!containsOperatorConfirmation(goal.spec().completion())) {
			throw new dev.agaminggod.arenaagents.agent.AgentDomainException(
					"FACTUAL_GOAL_NOT_CONFIRMABLE", "Minecraft verifies this goal from in-game facts"
			);
		}
		runtime.confirm(agentId, goal.goalId());
	}

	private static boolean containsOperatorConfirmation(dev.agaminggod.arenaagents.agent.goal.GoalPredicate predicate) {
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.OperatorConfirmed) return true;
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.AllOf all) {
			return all.predicates().stream().anyMatch(CodexAgentServerRuntime::containsOperatorConfirmation);
		}
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.AnyOf any) {
			return any.predicates().stream().anyMatch(CodexAgentServerRuntime::containsOperatorConfirmation);
		}
		return false;
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CodexAgentServerRuntime::start);
		ServerTickEvents.END_SERVER_TICK.register(CodexAgentServerRuntime::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CodexAgentServerRuntime::stop);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				VoiceConsentRegistry.clearPlayer(server, handler.getPlayer().getUUID()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
			if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) return true;
			return AgentDeathCapture.allowVanillaDeath(
					ScenarioRuntimeService.recoverParkourDeath(player),
					() -> CodexAgentManager.get(player.level().getServer()).captureDeath(player, source)
			);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(CodexAgentServerRuntime::recordAttributedKill);
		registered = true;
	}

	private static void start(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		GOAL_SAFETY.computeIfAbsent(server, ignored -> new GoalSafetyController(manager));
		GoalVerificationRuntime goalVerifier = GOAL_VERIFIERS.computeIfAbsent(server, ignored -> new GoalVerificationRuntime(
				manager.registry(),
				agentId -> manager.findAgentPlayer(agentId).map(dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier::minecraftFacts),
				server::getTickCount,
				System::currentTimeMillis
		));
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor == null) {
			CoordinatorProcessSupervisor candidate = new CoordinatorProcessSupervisor();
			CoordinatorProcessSupervisor previous = COORDINATORS.putIfAbsent(server, candidate);
			if (previous == null) supervisor = candidate;
			else {
				candidate.close();
				supervisor = previous;
			}
		}
		VOICE_STARTS.computeIfAbsent(server, ignored -> new VoiceStartGate());
		tryStartBridge(server, manager, supervisor, goalVerifier);
	}

	private static void tryStartBridge(
			MinecraftServer server,
			CodexAgentManager manager,
			CoordinatorProcessSupervisor supervisor,
			GoalVerificationRuntime goalVerifier
	) {
		BridgeSlot slot = BRIDGE_SLOTS.computeIfAbsent(server, ignored -> new BridgeSlot(System::currentTimeMillis));
		String preparedSecret = supervisor == null ? null : supervisor.bridgeSecret();
		long secretRevision = supervisor == null ? 0L : supervisor.bridgeRevision();
		String previousFailure = slot.retry().failureCode();
		reconcilePreparedBridge(slot, secretRevision, preparedSecret, secret ->
				MultiplexedServerBridge.withPreparedSecret(
						manager, secret, AgentVerboseState.forServer(server), goalVerifier
				));
		String currentFailure = slot.retry().failureCode();
		if (currentFailure != null && !java.util.Objects.equals(previousFailure, currentFailure)) {
			LOGGER.warn("Arena Agents Minecraft bridge is recovering [{}]; next bind attempt is scheduled: {}",
					currentFailure, slot.retry().failureMessage());
		}
	}

	static void reconcilePreparedBridge(
			BridgeSlot slot,
			long secretRevision,
			String preparedSecret,
			Function<String, MultiplexedServerBridge> factory
	) {
		java.util.Objects.requireNonNull(slot, "bridge slot must not be null");
		java.util.Objects.requireNonNull(factory, "bridge factory must not be null");
		if (preparedSecret == null) return;
		slot.reconcile(secretRevision, () -> factory.apply(preparedSecret));
	}

	private static void tick(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		GoalVerificationRuntime bridgeGoalVerifier = GOAL_VERIFIERS.get(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (supervisor != null) {
			supervisor.tick(
					bridge != null && bridge.authenticated(),
					bridge == null ? null : bridge.authenticatedLaunchId(),
					bridge == null ? 0L : bridge.authenticatedSessionGeneration()
			);
			if (bridgeGoalVerifier != null) {
				tryStartBridge(server, manager, supervisor, bridgeGoalVerifier);
				bridge = bridge(server);
			}
			VoiceStartGate voiceStart = VOICE_STARTS.computeIfAbsent(server, ignored -> new VoiceStartGate());
			try {
				voiceStart.startIfPrepared(supervisor, configuration -> {
					return VoiceSubsystemRuntime.start(server, new VoiceSubsystemConfiguration(
							configuration.endpoint(), configuration.secret()
					));
				}, () -> VoiceSubsystemRuntime.available(server), () -> VoiceSubsystemRuntime.close(server));
			} catch (RuntimeException exception) {
				LOGGER.warn("Arena Agents voice startup is degraded ({}); coordinator and Minecraft bridge recovery continue",
						exception.getClass().getSimpleName());
			}
		}
		if (!ScenarioRuntimeService.restorePersistedState(server)) {
			VoiceSubsystemRuntime.tick(server);
			if (bridge != null) bridge.tick();
			return;
		}
		manager.reconcileDeaths();
		manager.maintainChunkTickets();
		GoalSafetyController safety = GOAL_SAFETY.get(server);
		if (safety != null) safety.tick();
		GoalVerificationRuntime goalVerifier = GOAL_VERIFIERS.get(server);
		if (goalVerifier != null) {
			for (var transition : goalVerifier.tick()) {
				transition.after().currentGoal().flatMap(dev.agaminggod.arenaagents.agent.AgentGoal::evidence)
						.ifPresent(evidence -> reportProactiveGoalVerification(manager, transition.after(), evidence));
			}
		}
		VoiceSubsystemRuntime.tick(server);
		maintainPlanningProgress(manager);
		if (bridge != null) {
			bridge.tick();
		}
		ScenarioRuntimeService.tick(server);
	}

	private static void maintainPlanningProgress(CodexAgentManager manager) {
		long now = System.currentTimeMillis();
		Map<String, Long> lastUpdates = PLANNING_UPDATES.computeIfAbsent(manager.server(), ignored -> new HashMap<>());
		java.util.Set<String> planning = new java.util.HashSet<>();
		for (var record : manager.records()) {
			if (record.state() != AgentLifecycleState.PLANNING) continue;
			String agentId = record.agentId().toString();
			planning.add(agentId);
			long last = lastUpdates.getOrDefault(agentId, record.updatedAtEpochMs());
			if (now - last >= PLANNING_UPDATE_INTERVAL_MS) {
				AgentChatReporter.stillPlanning(manager, record);
				lastUpdates.put(agentId, now);
			}
		}
		lastUpdates.keySet().retainAll(planning);
	}

	public static boolean automationAvailable(MinecraftServer server) {
		MultiplexedServerBridge bridge = bridge(server);
		return bridge != null && bridge.authenticated();
	}

	public static void setVerbose(MinecraftServer server, boolean enabled) {
		AgentVerboseState state = AgentVerboseState.forServer(server);
		state.setEnabled(enabled);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge != null) bridge.setVerbose(enabled);
	}

	public static String automationStatus(MinecraftServer server) {
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge != null && bridge.authenticated()) return "Automation ready";
		if (bridge == null) {
			BridgeSlot slot = BRIDGE_SLOTS.get(server);
			BridgeRetry retry = slot == null ? null : slot.retry();
			if (retry != null && retry.failureCode() != null) {
				return "Automation recovery state BLOCKED_RETRYABLE at java_bridge [" + retry.failureCode()
						+ "]. Next retry at " + retry.nextRetryEpochMs() + ".";
			}
			return "Automation recovery state STARTING at java_bridge. Next retry is immediate.";
		}
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor == null) return "Automation recovery state AUTHENTICATING at external_coordinator.";
		CoordinatorRecoverySnapshot recovery = supervisor.snapshot();
		String boundary = recovery.failingBoundary();
		if (boundary == null) {
			boundary = switch (recovery.state()) {
				case STARTING -> "process_start";
				case AUTHENTICATING -> "bridge_authentication";
				case DEGRADED -> "bridge_reconnect";
				case STOPPED -> "coordinator_autostart";
				default -> "coordinator";
			};
		}
		long nextAction = recovery.nextRetryEpochMs();
		if (nextAction == 0L) nextAction = recovery.authenticationDeadlineEpochMs();
		if (nextAction == 0L) nextAction = recovery.reconnectDeadlineEpochMs();
		boolean currentFailure = recovery.state() == CoordinatorRecoveryState.BACKOFF
				|| recovery.state() == CoordinatorRecoveryState.BLOCKED_RETRYABLE;
		String code = !currentFailure || recovery.failureCode() == null ? "" : " [" + recovery.failureCode() + "]";
		String retry = nextAction == 0L ? "" : " Next retry or deadline at " + nextAction + ".";
		return "Automation recovery state " + recovery.state() + " at " + boundary + code + "." + retry;
	}

	public static List<AgentControlModelOption> modelCatalog(MinecraftServer server) {
		MultiplexedServerBridge bridge = bridge(server);
		return bridge == null ? AgentControlCatalog.fallbackOptions() : bridge.catalogModels();
	}

	public static void requireAutomation(MinecraftServer server) {
		if (!automationAvailable(server)) {
			throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		}
	}

	public static DeliveryReceipt sendDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendPlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt sendNativeDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendNativePlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt deliverHumanSpeech(
			MinecraftServer server,
			UUID sourcePlayerId,
			String transcript,
			boolean whispering
	) {
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null || !bridge.authenticated()) return new DeliveryReceipt(List.of(), List.of());
		ServerPlayer source = server.getPlayerList().getPlayer(sourcePlayerId);
		if (source == null) return new DeliveryReceipt(List.of(), List.of());
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(sourcePlayerId::equals).isPresent()) {
				return new DeliveryReceipt(List.of(), List.of());
			}
		}
		return bridge.sendPlayerProximitySpeech(source, transcript, whispering);
	}

	public static boolean hasVoiceConsent(MinecraftServer server, UUID playerId) {
		return VoiceConsentRegistry.granted(server, playerId);
	}

	private static void reportProactiveGoalVerification(
			CodexAgentManager manager,
			dev.agaminggod.arenaagents.agent.AgentRecord record,
			dev.agaminggod.arenaagents.agent.goal.GoalEvidence evidence
	) {
		AgentVerboseState verbose = AgentVerboseState.forServer(manager.server());
		if (!verbose.goalVerificationChanged(record.agentId(), record.goalRevision(), true, evidence.facts())) return;
		AgentChatReporter.goalVerified(manager, record, evidence.facts());
		String detail = evidence.facts().isEmpty()
				? "Goal verified."
				: "Goal verified: " + evidence.facts().getFirst().expectedValue() + ".";
		AgentVerboseChat.report(manager, verbose, record, "result", detail);
	}

	private static void recordAttributedKill(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		if (!(source.getEntity() instanceof ServerPlayer responsible)) return;
		MinecraftServer server = responsible.level().getServer();
		GoalVerificationRuntime runtime = GOAL_VERIFIERS.get(server);
		if (runtime == null) return;
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(responsible.getUUID()::equals).isEmpty()) continue;
			runtime.recordKill(record.agentId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			return;
		}
	}

	private static MultiplexedServerBridge bridge(MinecraftServer server) {
		BridgeSlot slot = BRIDGE_SLOTS.get(server);
		return slot == null ? null : slot.bridge();
	}

	private static void stop(MinecraftServer server) {
		PLANNING_UPDATES.remove(server);
		GOAL_VERIFIERS.remove(server);
		BridgeSlot bridgeSlot = BRIDGE_SLOTS.remove(server);
		VoiceStartGate voiceStart = VOICE_STARTS.remove(server);
		GoalSafetyController safety = GOAL_SAFETY.remove(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.remove(server);
		try {
			if (safety != null) safety.close();
			if (voiceStart != null) voiceStart.close(() -> VoiceSubsystemRuntime.close(server));
			VoiceConsentRegistry.clear(server);
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			if (bridgeSlot != null) bridgeSlot.close();
			if (supervisor != null) supervisor.close();
			AgentVerboseState.release(server);
		}
	}

	static final class VoiceStartGate {
		private final LongSupplier clock;
		private final CoordinatorLaunchPolicy.RestartBudget retryBudget =
				new CoordinatorLaunchPolicy.RestartBudget();
		private boolean started;
		private boolean closed;
		private long nextRetryEpochMs;
		private long activeConfigurationRevision = Long.MIN_VALUE;

		VoiceStartGate() {
			this(System::currentTimeMillis);
		}

		VoiceStartGate(LongSupplier clock) {
			this.clock = java.util.Objects.requireNonNull(clock, "voice retry clock must not be null");
		}

		synchronized void startIfPrepared(
				CoordinatorProcessSupervisor supervisor,
				Function<CoordinatorProcessSupervisor.VoiceConfiguration, Boolean> starter
		) {
			startIfPrepared(supervisor, starter, () -> true, () -> { });
		}

		synchronized void startIfPrepared(
				CoordinatorProcessSupervisor supervisor,
				Function<CoordinatorProcessSupervisor.VoiceConfiguration, Boolean> starter,
				java.util.function.BooleanSupplier healthy,
				Runnable closer
		) {
			java.util.Objects.requireNonNull(supervisor, "coordinator supervisor must not be null");
			java.util.Objects.requireNonNull(starter, "voice starter must not be null");
			java.util.Objects.requireNonNull(healthy, "voice health probe must not be null");
			java.util.Objects.requireNonNull(closer, "voice closer must not be null");
			CoordinatorProcessSupervisor.VoiceConfiguration configuration = supervisor.voiceConfiguration();
			long configurationRevision = supervisor.voiceConfigurationRevision();
			long now = clock.getAsLong();
			if (closed || configuration == null) return;
			if (started) {
				if (activeConfigurationRevision != configurationRevision) {
					closeActive(closer);
					retryBudget.resetAfterStability();
					nextRetryEpochMs = 0L;
				} else if (safelyHealthy(healthy)) {
					return;
				} else {
					closeActive(closer);
					retryBudget.recordUnexpectedExit();
					nextRetryEpochMs = now + retryBudget.nextDelayMs();
					return;
				}
			}
			if (now < nextRetryEpochMs) return;
			try {
				if (Boolean.TRUE.equals(starter.apply(configuration))) {
					started = true;
					activeConfigurationRevision = configurationRevision;
					retryBudget.resetAfterStability();
					nextRetryEpochMs = 0L;
					return;
				}
			} catch (RuntimeException exception) {
				LOGGER.warn("Arena Agents voice startup is degraded ({}); coordinator and Minecraft bridge recovery continue",
						exception.getClass().getSimpleName());
			}
			retryBudget.recordUnexpectedExit();
			nextRetryEpochMs = now + retryBudget.nextDelayMs();
		}

		private boolean safelyHealthy(java.util.function.BooleanSupplier healthy) {
			try {
				return healthy.getAsBoolean();
			} catch (RuntimeException exception) {
				LOGGER.warn("Arena Agents voice health probe failed ({}); recovery will retry",
						exception.getClass().getSimpleName());
				return false;
			}
		}

		private void closeActive(Runnable closer) {
			started = false;
			activeConfigurationRevision = Long.MIN_VALUE;
			try {
				closer.run();
			} catch (RuntimeException exception) {
				LOGGER.warn("Arena Agents voice cleanup failed ({}); recovery will continue",
						exception.getClass().getSimpleName());
			}
		}

		synchronized void close(Runnable closer) {
			java.util.Objects.requireNonNull(closer, "voice closer must not be null");
			if (closed) return;
			closed = true;
			if (started) closeActive(closer);
		}
	}

	static final class BridgeSlot implements AutoCloseable {
		private final BridgeRetry retry;
		private MultiplexedServerBridge bridge;
		private long activeRevision = Long.MIN_VALUE;
		private long attemptedRevision = Long.MIN_VALUE;
		private boolean closed;

		BridgeSlot(LongSupplier clock) {
			retry = new BridgeRetry(clock);
		}

		synchronized void reconcile(long desiredRevision, Supplier<MultiplexedServerBridge> factory) {
			java.util.Objects.requireNonNull(factory, "bridge factory must not be null");
			if (closed || bridge != null && activeRevision == desiredRevision) return;
			if (bridge != null) {
				bridge.close();
				bridge = null;
				activeRevision = Long.MIN_VALUE;
			}
			if (attemptedRevision == desiredRevision && !retry.canAttempt()) return;
			attemptedRevision = desiredRevision;
			MultiplexedServerBridge candidate = null;
			try {
				candidate = java.util.Objects.requireNonNull(factory.get(), "bridge factory returned no bridge");
				candidate.start();
				bridge = candidate;
				activeRevision = desiredRevision;
				retry.recordSuccess();
			} catch (RuntimeException exception) {
				if (candidate != null) candidate.close();
				String code = exception instanceof BridgeProtocolException protocol
						? protocol.code()
						: "JAVA_BRIDGE_START_FAILED";
				retry.recordFailure(code, exception.getMessage());
			}
		}

		synchronized MultiplexedServerBridge bridge() {
			return bridge;
		}

		BridgeRetry retry() {
			return retry;
		}

		@Override
		public synchronized void close() {
			if (closed) return;
			closed = true;
			if (bridge != null) bridge.close();
			bridge = null;
		}
	}

	static final class BridgeRetry {
		private final LongSupplier clock;
		private final CoordinatorLaunchPolicy.RestartBudget budget = new CoordinatorLaunchPolicy.RestartBudget();
		private long nextRetryEpochMs;
		private String failureCode;
		private String failureMessage;

		BridgeRetry(LongSupplier clock) {
			this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
		}

		synchronized boolean canAttempt() {
			return nextRetryEpochMs == 0L || now() >= nextRetryEpochMs;
		}

		synchronized void recordFailure(String code, String message) {
			budget.recordUnexpectedExit();
			nextRetryEpochMs = now() + budget.nextDelayMs();
			failureCode = java.util.Objects.requireNonNull(code, "failure code must not be null");
			failureMessage = message;
		}

		synchronized void recordSuccess() {
			budget.resetAfterStability();
			nextRetryEpochMs = 0L;
			failureCode = null;
			failureMessage = null;
		}

		synchronized long nextRetryEpochMs() {
			return nextRetryEpochMs;
		}

		synchronized String failureCode() {
			return failureCode;
		}

		synchronized String failureMessage() {
			return failureMessage;
		}

		private long now() {
			long value = clock.getAsLong();
			if (value < 0L) throw new IllegalStateException("bridge retry clock must not be negative");
			return value;
		}
	}
}
