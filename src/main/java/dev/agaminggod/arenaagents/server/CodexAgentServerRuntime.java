package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusStore;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, BridgeSlot> BRIDGE_SLOTS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, CoordinatorProcessSupervisor> COORDINATORS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, VoiceInitializationGate> VOICE_GATES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<String, Long>> PLANNING_UPDATES = new ConcurrentHashMap<>();
	private static final long PLANNING_UPDATE_INTERVAL_MS = 30_000L;
	private static final long COORDINATOR_STATUS_MAXIMUM_AGE_MS = 2_500L;
	private static boolean registered;

	private CodexAgentServerRuntime() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CodexAgentServerRuntime::start);
		ServerTickEvents.END_SERVER_TICK.register(CodexAgentServerRuntime::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CodexAgentServerRuntime::stop);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				VoiceConsentRegistry.revoke(server, handler.getPlayer().getUUID()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
			if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) return true;
			return AgentDeathCapture.allowVanillaDeath(
					ScenarioRuntimeService.recoverParkourDeath(player),
					() -> CodexAgentManager.get(player.level().getServer()).captureDeath(player, source)
			);
		});
		registered = true;
	}

	private static void start(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
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
		try {
			tryStartBridge(server, manager, supervisor);
		} catch (RuntimeException exception) {
			LOGGER.error(
					"Codex agent bridge is unavailable; summoned agents will remain locally controllable but autonomous planning is disabled",
					exception
			);
		}
	}

	private static void tryStartBridge(
			MinecraftServer server,
			CodexAgentManager manager,
			CoordinatorProcessSupervisor supervisor
	) {
		BridgeSlot slot = BRIDGE_SLOTS.computeIfAbsent(server, ignored -> new BridgeSlot(System::currentTimeMillis));
		reconcileBridgeConfiguration(slot, manager, supervisor);
	}

	static void reconcileBridgeConfiguration(
			BridgeSlot slot,
			CodexAgentManager manager,
			CoordinatorProcessSupervisor supervisor
	) {
		java.util.Objects.requireNonNull(slot, "bridge slot must not be null");
		java.util.Objects.requireNonNull(manager, "manager must not be null");
		String preparedSecret = supervisor == null ? null : supervisor.bridgeSecret();
		if (preparedSecret != null) {
			int port = supervisor.bridgePort();
			slot.reconcile(supervisor.bridgeRevision(),
					() -> MultiplexedServerBridge.withPreparedSecret(manager, port, preparedSecret));
			return;
		}
		if (supervisor != null && supervisor.snapshot().state() == CoordinatorRecoveryState.STOPPED) {
			slot.reconcile(explicitBridgeRevision(), () -> new MultiplexedServerBridge(manager));
		}
	}

	private static long explicitBridgeRevision() {
		String configuredPath = System.getProperty("arenaagents.bridgeSecretFile");
		if (configuredPath == null || configuredPath.isBlank()) configuredPath = System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE");
		Path path = configuredPath == null || configuredPath.isBlank()
				? Path.of("runtime", "bridge-secret.txt")
				: Path.of(configuredPath);
		String port = System.getProperty("arenaagents.bridgePort", Integer.toString(MultiplexedServerBridge.DEFAULT_PORT));
		try {
			Path normalized = path.toAbsolutePath().normalize();
			return java.util.Objects.hash(port, normalized, Files.size(normalized), Files.getLastModifiedTime(normalized).toMillis());
		} catch (java.io.IOException | RuntimeException unavailable) {
			return java.util.Objects.hash(port, path.toAbsolutePath().normalize(), unavailable.getClass().getName());
		}
	}

	static void reconcilePreparedBridge(
			BridgeSlot slot,
			long revision,
			String preparedSecret,
			Function<String, MultiplexedServerBridge> factory
	) {
		java.util.Objects.requireNonNull(slot, "bridge slot must not be null");
		java.util.Objects.requireNonNull(factory, "bridge factory must not be null");
		if (preparedSecret != null) slot.reconcile(revision, () -> factory.apply(preparedSecret));
	}

	private static void tick(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (supervisor != null) {
			boolean coordinatorReady = bridge != null && bridge.authenticated()
					&& CoordinatorStatusStore.latest(server)
							.map(status -> coordinatorStatusReady(status, System.currentTimeMillis()))
							.orElse(false);
			supervisor.tick(
					bridge != null && bridge.authenticated(),
					bridge == null ? null : bridge.authenticatedLaunchId(),
					bridge == null ? 0L : bridge.authenticatedSessionGeneration(),
					coordinatorReady
			);
			tryStartBridge(server, manager, supervisor);
			bridge = bridge(server);
			reconcileVoice(server, supervisor);
		}
		if (!ScenarioRuntimeService.restorePersistedState(server)) {
			VoiceSubsystemRuntime.tick(server);
			if (bridge != null) bridge.tick();
			return;
		}
		manager.reconcileDeaths();
		manager.maintainChunkTickets();
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

	public static String automationStatus(MinecraftServer server) {
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor != null && supervisor.failureCode() != null) {
			return startupFailureStatus(supervisor.failureCode());
		}
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) {
			return "Automation is offline. Restart Minecraft after checking the bridge setup.";
		}
		return bridge.authenticated() ? "Automation ready" : "Waiting for the agent coordinator...";
	}

	private static String startupFailureStatus(String code) {
		if (code.startsWith("NODE_RUNTIME")) {
			return "Automation is offline. Node.js 22+ was not found; set -Darenaagents.nodePath to an absolute executable or install the bundled profile runtime.";
		}
		if ("BRIDGE_SECRET_PATH_CONFLICT".equals(code)) {
			return "Automation is offline. Bridge and voice secret paths must point to the prepared runtime secret.";
		}
		if ("COORDINATOR_RESTART_EXHAUSTED".equals(code)) {
			return "Automation is offline. The coordinator stopped repeatedly; check the coordinator error log and restart Minecraft.";
		}
		return "Automation is offline. Restart Minecraft after checking the coordinator setup.";
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

	private static void stop(MinecraftServer server) {
		PLANNING_UPDATES.remove(server);
		VOICE_GATES.remove(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.remove(server);
		BridgeSlot bridgeSlot = BRIDGE_SLOTS.remove(server);
		try {
			VoiceSubsystemRuntime.close(server);
			VoiceConsentRegistry.clear(server);
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			if (bridgeSlot != null) bridgeSlot.close();
			if (supervisor != null) supervisor.close();
		}
	}

	/** Starts voice only after the supervisor has published the prepared secret/config paths. */
	private static void reconcileVoice(MinecraftServer server, CoordinatorProcessSupervisor supervisor) {
		if (supervisor == null) return;
		boolean prepared = voiceConfigurationPrepared(supervisor);
		if (!prepared) return;
		long revision = voiceConfigurationRevision(supervisor);
		VoiceInitializationGate gate = VOICE_GATES.computeIfAbsent(server, ignored ->
				new VoiceInitializationGate(
						() -> VoiceSubsystemRuntime.start(server),
						() -> VoiceSubsystemRuntime.close(server)
				)
		);
		try {
			gate.reconcile(true, revision);
		} catch (RuntimeException failure) {
			LOGGER.warn("Voice subsystem initialization will retry after coordinator paths are prepared", failure);
		}
	}

	static boolean coordinatorStatusReady(CoordinatorStatusSnapshot status, long nowEpochMs) {
		return status != null && status.reconciled()
				&& status.fresh(nowEpochMs, COORDINATOR_STATUS_MAXIMUM_AGE_MS);
	}

	static boolean voiceConfigurationPrepared(CoordinatorProcessSupervisor supervisor) {
		return supervisor != null && (supervisor.configured()
				|| (supervisor.snapshot().state() == CoordinatorRecoveryState.STOPPED
						&& (propertyPresent("arenaagents.voiceSecretFile")
								|| propertyPresent("arenaagents.bridgeSecretFile"))));
	}

	static long voiceConfigurationRevision(CoordinatorProcessSupervisor supervisor) {
		return java.util.Objects.hash(
				supervisor.bridgeRevision(), supervisor.secretPath(),
				configuredVoiceSecretFile(), System.getProperty("arenaagents.voiceUrl")
		);
	}

	private static String configuredVoiceSecretFile() {
		return System.getProperty(
				"arenaagents.voiceSecretFile",
				System.getProperty("arenaagents.bridgeSecretFile", "runtime/bridge-secret.txt")
		);
	}

	private static boolean propertyPresent(String name) {
		String value = System.getProperty(name);
		return value != null && !value.isBlank();
	}

	/** Small lifecycle seam that keeps unprepared startup from permanently selecting NoVoice. */
	static final class VoiceInitializationGate {
		private static final long UNINITIALIZED = Long.MIN_VALUE;
		private final Runnable starter;
		private final Runnable closer;
		private long activeRevision = UNINITIALIZED;

		VoiceInitializationGate(Runnable starter, Runnable closer) {
			this.starter = java.util.Objects.requireNonNull(starter, "voice starter must not be null");
			this.closer = java.util.Objects.requireNonNull(closer, "voice closer must not be null");
		}

		synchronized boolean reconcile(boolean prepared, long desiredRevision) {
			if (!prepared || activeRevision == desiredRevision) return false;
			if (activeRevision != UNINITIALIZED) {
				activeRevision = UNINITIALIZED;
				closer.run();
			}
			starter.run();
			activeRevision = desiredRevision;
			return true;
		}
	}

	private static MultiplexedServerBridge bridge(MinecraftServer server) {
		BridgeSlot slot = BRIDGE_SLOTS.get(server);
		return slot == null ? null : slot.bridge();
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
				bridge.closeAndDrainDisconnect();
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
			return clock.getAsLong() >= nextRetryEpochMs;
		}

		synchronized void recordSuccess() {
			budget.resetAfterStability();
			nextRetryEpochMs = 0L;
			failureCode = null;
			failureMessage = null;
		}

		synchronized void recordFailure(String code, String message) {
			budget.recordUnexpectedExit();
			nextRetryEpochMs = clock.getAsLong() + budget.nextDelayMs();
			failureCode = code == null || code.isBlank() ? "JAVA_BRIDGE_START_FAILED" : code;
			failureMessage = message;
		}

		synchronized String failureCode() { return failureCode; }
		synchronized String failureMessage() { return failureMessage; }
		synchronized long nextRetryEpochMs() { return nextRetryEpochMs; }
	}
}
