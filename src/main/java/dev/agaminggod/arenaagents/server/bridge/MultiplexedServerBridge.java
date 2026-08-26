package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import dev.agaminggod.arenaagents.server.AgentRuntimeHooks;
import dev.agaminggod.arenaagents.server.AgentRuntimeRouter;
import dev.agaminggod.arenaagents.server.AgentActivityPresentation;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.AgentVerboseChat;
import dev.agaminggod.arenaagents.server.AgentVerboseState;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.conversation.ConversationAudience;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.ConversationKind;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import dev.agaminggod.arenaagents.server.perception.AttentionFactDelta;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.perception.ServerObservationWireBudget;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionContract;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.level.ServerPlayer;

public final class MultiplexedServerBridge implements AgentRuntimeHooks, AutoCloseable {
	public static final String LOOPBACK_HOST = "127.0.0.1";
	public static final int DEFAULT_PORT = 25_570;
	public static final int CONNECTION_QUEUE_CAP = 256;
	public static final int AGENT_QUEUE_CAP = 32;
	private static final int OBSERVATIONS_PER_TICK = AgentConstants.DEFAULT_AGENT_LIMIT;
	private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
	private static final int MIN_SECRET_LENGTH = 32;
	private static final int MAX_SECRET_LENGTH = 512;
	private static final int MAX_TRACKED_IDS = 4_096;
	private static final int SERVER_TASK_CAP = 4_096;
	private static final int SERVER_TASKS_PER_TICK = 256;
	private static final int OBSERVATION_HISTORY_CAPACITY = 4_096;
	private static final int MAX_TARGET_IDS_PER_OBSERVATION = 64;
	private static final int MAX_CONVERSATION_SOURCES_PER_AGENT = 16;
	private static final String MAX_OBSERVATION_MESSAGE_ID = "m".repeat(128);
	private static final String COORDINATOR_OFFLINE_MESSAGE =
			"AI agent coordinator is offline; check logs/arena-agents-coordinator-error.log for the startup cause";
	private static final Logger LOGGER = LoggerFactory.getLogger(MultiplexedServerBridge.class);
	private static final Set<String> INBOUND_TYPES = Set.of(
			"hello", "catalog_snapshot", "coordinator_status", "agent_ready", "planning_state", "goal_completed", "conversation_wake_ack", "request_observation", "action_command", "action_cancel", "agent_error", "verbose_event", "heartbeat"
	);

	private final CodexAgentManager manager;
	private final AgentRuntimeRouter router;
	private final ServerActionExecutor actionExecutor;
	private final ServerAgentConversationRouter conversationRouter;
	private final ServerObservationCollector observations;
	private final BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
	private final String serverInstanceId = UUID.randomUUID().toString();
	private final ObservationPublication observationPublication = new ObservationPublication(
			AgentConstants.DEFAULT_AGENT_LIMIT,
			OBSERVATIONS_PER_TICK,
			(agentId, payload) -> ServerObservationWireBudget.fit(payload, candidate ->
					codec.encodedBytes(new BridgeEnvelope(
							2, serverInstanceId, agentId.toString(), "observation",
							MAX_OBSERVATION_MESSAGE_ID, candidate
					)) <= BridgeEnvelopeCodec.MAX_LINE_BYTES)
	);
	private final String secret;
	private final int port;
	private final AgentVerboseState verboseState;
	private final BoundedServerTaskQueue serverTasks = new BoundedServerTaskQueue(SERVER_TASK_CAP);
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean coordinatorDisconnectPending = new AtomicBoolean();
	private final AtomicBoolean activeDisconnectPending = new AtomicBoolean();
	private final AtomicLong messageIds = new AtomicLong();
	private final AtomicLong registryPublicationRevision = new AtomicLong();
	private final ProgramActionLedger programActions = new ProgramActionLedger();
	private final Object publicationLock = new Object();
	private final Set<AgentId> protocolKnownAgentIds = new HashSet<>();
	private final Set<AgentId> coordinatorReadyAgentIds = new HashSet<>();
	private boolean disconnectInProgress;
	private volatile Session session;
	private volatile ServerSocket serverSocket;
	private volatile Set<String> catalogProfiles = Set.of();
	private volatile List<AgentControlModelOption> catalogModels = AgentControlCatalog.fallbackOptions();
	private volatile boolean catalogLoaded;

	public MultiplexedServerBridge(CodexAgentManager manager) {
		this(manager, configuredPort(), configuredSecretPath(), new AgentVerboseState());
	}

	public MultiplexedServerBridge(CodexAgentManager manager, AgentVerboseState verboseState) {
		this(manager, configuredPort(), configuredSecretPath(), verboseState);
	}

	public MultiplexedServerBridge(CodexAgentManager manager, Path secretPath) {
		this(manager, DEFAULT_PORT, secretPath, new AgentVerboseState());
	}

	public MultiplexedServerBridge(CodexAgentManager manager, Path secretPath, AgentVerboseState verboseState) {
		this(manager, DEFAULT_PORT, secretPath, verboseState);
	}

	public MultiplexedServerBridge(CodexAgentManager manager, int port, Path secretPath) {
		this(manager, port, secretPath, new AgentVerboseState());
	}

	public MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			AgentVerboseState verboseState
	) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.router = new AgentRuntimeRouter(manager);
		this.port = port;
		this.secret = readSecret(secretPath);
		this.verboseState = Objects.requireNonNull(verboseState, "verboseState must not be null");
		this.conversationRouter = new ServerAgentConversationRouter(manager, this::publishConversationEvent);
		this.actionExecutor = new ServerActionExecutor(
				manager, this::sendActionResult, this::sendActionProgress,
				dev.agaminggod.arenaagents.server.runtime.ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR,
				this::sendRespawnResultBeforeControl,
				conversationRouter
		);
		this.observations = new ServerObservationCollector(manager, actionExecutor);
	}

	public synchronized void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			ServerSocket socket = new ServerSocket();
			socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), 1);
			serverSocket = socket;
			manager.setRuntimeHooks(this);
			Thread.ofPlatform().daemon().name("arenaagents-v2-accept").start(this::acceptLoop);
		} catch (IOException exception) {
			running.set(false);
			throw new BridgeProtocolException("BRIDGE_BIND_FAILED", "Could not bind " + LOOPBACK_HOST + ":" + port, exception);
		}
	}

	public void tick() {
		publishPendingDisconnects();
		serverTasks.drain(SERVER_TASKS_PER_TICK, task -> {
			try {
				task.run();
			} catch (RuntimeException exception) {
				LOGGER.error("Codex bridge server task failed", exception);
			}
		});
		actionExecutor.tick();
		List<AgentId> observationAgents = registeredObservationIds(manager.coordinatorVisibleRecords());
		for (AgentId agentId : observations.changedActiveAgents()) {
			if (!observationAgents.contains(agentId)) continue;
			observationPublication.markAttention(agentId);
			queueUrgentObservation(agentId);
		}
		observationPublication.scheduleIdleHeartbeat(observationAgents);
		observationPublication.drain(this::sendObservation);
	}

	static List<AgentId> registeredObservationIds(List<AgentRecord> records) {
		Objects.requireNonNull(records, "records must not be null");
		// The product protocol is hard-capped at 16 agents even if restored data is malformed.
		return records.stream()
				.map(AgentRecord::agentId)
				.limit(AgentConstants.DEFAULT_AGENT_LIMIT)
				.toList();
	}

	public boolean authenticated() {
		Session active = session;
		return active != null && active.open.get() && active.authenticated.get();
	}

	public void setVerbose(boolean enabled) {
		verboseState.setEnabled(enabled);
		Session active = session;
		if (active == null || !active.authenticated.get()) return;
		try {
			active.enqueue(verboseControlEnvelope());
		} catch (BridgeProtocolException exception) {
			LOGGER.debug("Verbose control will be restored by the next authenticated session: {}", exception.getMessage());
		}
	}

	ObservationPublication observationPublicationForVerification() {
		return observationPublication;
	}

	boolean coordinatorReadyForVerification(AgentId agentId) {
		return coordinatorReadyAgentIds.contains(agentId);
	}

	/** Server ticks serialize input leases with observation publication at this boundary. */
	static ObservationPublication.Result publishObservationWithInputGuard(
			ObservationPublication publication,
			Optional<LeasedServerInputController> inputController,
			AgentId agentId,
			Object sourceSession,
			JsonObject observation,
			ObservationPublication.Writer writer,
			boolean allowUnchanged) {
		Objects.requireNonNull(publication, "publication must not be null");
		Objects.requireNonNull(inputController, "input controller must not be null");
		long revision = inputController.map(LeasedServerInputController::mutationRevision).orElse(-1L);
		try {
			return publication.publish(agentId, sourceSession, observation, writer, allowUnchanged);
		} finally {
			if (inputController.isPresent() && inputController.get().mutationRevision() != revision) {
				throw new BridgeProtocolException(
						"INPUT_MUTATED_DURING_OBSERVATION", "Observation publication changed input state");
			}
		}
	}

	int boundPortForVerification() {
		ServerSocket active = serverSocket;
		if (active == null) throw new IllegalStateException("bridge is not started");
		return active.getLocalPort();
	}

	public List<AgentControlModelOption> catalogModels() {
		return catalogModels;
	}

	public DeliveryReceipt sendPlayerDirectMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerMessage(source, recipientAgentId, text);
	}

	public DeliveryReceipt sendNativePlayerDirectMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerMessageFromNativeWhisper(source, recipientAgentId, text);
	}

	public DeliveryReceipt sendPlayerProximitySpeech(ServerPlayer source, String text, boolean whispering) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerProximitySpeech(source, text, whispering ? 16.0D : 48.0D);
	}

	@Override
	public void validateProfile(AgentProfile profile) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		if (!catalogLoaded) {
			throw new AgentDomainException("MODEL_CATALOG_UNAVAILABLE", "AI provider model catalog has not loaded yet");
		}
		Set<String> catalog = catalogProfiles;
		String requestedProfile = profile.provider() + "\u0000" + profile.model() + "\u0000" + profile.reasoning();
		if (!catalog.contains(requestedProfile)) {
			long providerProfileCount = catalog.stream()
					.filter(candidate -> candidate.startsWith(profile.provider() + "\u0000"))
					.count();
			throw new AgentDomainException(
					"UNSUPPORTED_MODEL_PROFILE",
					"Coordinator catalog rejected " + profile.provider() + "/" + profile.model()
							+ "/" + profile.reasoning() + " (provider profiles: " + providerProfileCount + ")"
			);
		}
	}

	@Override
	public boolean onCreated(AgentRecord record) {
		registryPublicationRevision.incrementAndGet();
		synchronized (publicationLock) {
			if (protocolKnownAgentIds.contains(record.agentId())) return true;
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) return false;
			active.enqueue(new BridgeEnvelope(
					2, serverInstanceId, record.agentId().toString(), "agent_registered",
					"server-" + messageIds.incrementAndGet(), registeredPayload(record)
			));
			protocolKnownAgentIds.add(record.agentId());
			return true;
		}
	}

	@Override
	public void onTransition(AgentTransition transition) {
		registryPublicationRevision.incrementAndGet();
		ScenarioRuntimeService.onAgentState(
				manager.server(),
				transition.after().agentId().toString(),
				publicState(transition.after().state())
		);
		if (transition.cancelAction()) {
			actionExecutor.cancel(transition.after().agentId(), "Lifecycle changed to " + transition.after().state());
		}
		synchronized (publicationLock) {
			if (!authenticated()) {
				if (transition.after().state().isActive()) {
					requestActiveDisconnect();
				}
				return;
			}
			if (!protocolKnownAgentIds.contains(transition.after().agentId())) return;
			observationPublication.markAttention(transition.after().agentId());
			queueUrgentObservation(transition.after().agentId());
			String operation = operation(transition);
			if (operation == null) return;
			send("goal_control", transition.after().agentId().toString(), goalControlPayload(transition, operation));
		}
	}

	@Override
	public void onRemoved(AgentId agentId, long terminalRevision) {
		registryPublicationRevision.incrementAndGet();
		actionExecutor.cancel(agentId, "Agent removed");
		programActions.remove(agentId);
		observationPublication.remove(agentId);
		synchronized (publicationLock) {
			coordinatorReadyAgentIds.remove(agentId);
			if (!protocolKnownAgentIds.contains(agentId)) return;
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) {
				if (active != null) active.close();
				return;
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("goalRevision", terminalRevision);
			try {
				active.enqueue(new BridgeEnvelope(
						2, serverInstanceId, agentId.toString(), "agent_removed",
						"server-" + messageIds.incrementAndGet(), payload
				));
				protocolKnownAgentIds.remove(agentId);
			} catch (RuntimeException exception) {
				active.close();
				throw exception;
			}
		}
	}

	@Override
	public <T> T withinPublicationBoundary(java.util.function.Supplier<T> publication) {
		registryPublicationRevision.incrementAndGet();
		try {
			synchronized (publicationLock) {
				return AgentRuntimeHooks.super.withinPublicationBoundary(publication);
			}
		} finally {
			registryPublicationRevision.incrementAndGet();
		}
	}

	@Override
	public void onServerStopping() {
		JsonObject payload = new JsonObject();
		payload.addProperty("reason", "server_stopping");
		send("shutdown", "server", payload);
		close();
	}

	@Override
	public synchronized void close() {
		running.set(false);
		verboseState.clearActivity();
		CoordinatorStatusStore.clear(manager.server());
		Session active = session;
		if (active != null) {
			active.close();
		}
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		} catch (IOException ignored) {
		}
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				Socket socket = serverSocket.accept();
				if (!socket.getInetAddress().isLoopbackAddress()) {
					socket.close();
					continue;
				}
				Session accepted = new Session(socket);
				boolean admitted;
				synchronized (publicationLock) {
					admitted = session == null;
					if (admitted) {
						session = accepted;
						onSessionAccepted(observationPublication, accepted);
					}
				}
				if (!admitted) {
					accepted.close();
					continue;
				}
				accepted.start();
			} catch (IOException exception) {
				if (running.get()) {
					LOGGER.error("Codex bridge accept failed", exception);
				}
			}
		}
	}

	static void onSessionAccepted(ObservationPublication publication, Object session) {
		publication.activate(session);
	}

	static void onSessionClosed(ObservationPublication publication, Object session) {
		publication.deactivate(session);
	}

	private void accept(BridgeEnvelope envelope, Session source) {
		if (!INBOUND_TYPES.contains(envelope.type())) {
			throw new BridgeProtocolException("UNKNOWN_MESSAGE_TYPE", "Unsupported coordinator message: " + envelope.type());
		}
		if (!source.authenticated.get()) {
			acceptHello(envelope, source);
			return;
		}
		if (!serverInstanceId.equals(envelope.serverInstanceId())) {
			throw new BridgeProtocolException("SERVER_INSTANCE_MISMATCH", "Authenticated session changed serverInstanceId");
		}
		if (!serverTasks.offer(() -> {
			synchronized (publicationLock) {
				if (session != source || !source.open.get() || !source.authenticated.get()) return;
				routeAuthenticated(envelope);
			}
		})) {
			throw new BridgeProtocolException("SERVER_TASK_QUEUE_FULL", "Coordinator exceeded the bounded server task queue");
		}
	}

	private void acceptHello(BridgeEnvelope envelope, Session source) {
		if (!"hello".equals(envelope.type()) || !"server".equals(envelope.agentId())) {
			throw new BridgeProtocolException("HANDSHAKE_REQUIRED", "hello must be the first coordinator message");
		}
		String supplied = requiredString(envelope.payload(), "secret");
		if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
			throw new BridgeProtocolException("AUTHENTICATION_FAILED", "Bridge secret did not match");
		}
		while (true) {
			awaitDisconnectPublication();
			long snapshotRevision = registryPublicationRevision.get();
			List<AgentRecord> visibleRecords = manager.coordinatorVisibleRecords();
			List<PendingConversationWake> pendingWakes = manager.pendingConversationWakes();
			JsonObject payload = new JsonObject();
			payload.addProperty("replyTo", envelope.messageId());
			payload.addProperty("authenticated", true);
			JsonArray registry = new JsonArray();
			Set<AgentId> handshakeKnownAgentIds = new HashSet<>();
			for (AgentRecord record : visibleRecords) {
				registry.add(registeredPayload(record));
				handshakeKnownAgentIds.add(record.agentId());
			}
			payload.add("registry", registry);
			ArrayList<BridgeEnvelope> handshake = new ArrayList<>();
			handshake.add(new BridgeEnvelope(
					2, serverInstanceId, "server", "hello_ack", "server-" + messageIds.incrementAndGet(), payload
			));
			handshake.add(verboseControlEnvelope());
			for (PendingConversationWake wake : pendingWakes) {
				handshake.add(conversationWakeEnvelope(wake));
			}
			synchronized (publicationLock) {
				if (disconnectInProgress || snapshotRevision != registryPublicationRevision.get()) continue;
				if (session != source || !source.open.get()) {
					throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during authentication");
				}
				resetObservationPublication();
				verboseState.clearActivity();
				catalogProfiles = Set.of();
				catalogModels = AgentControlCatalog.fallbackOptions();
				catalogLoaded = false;
				protocolKnownAgentIds.clear();
				protocolKnownAgentIds.addAll(handshakeKnownAgentIds);
				coordinatorReadyAgentIds.clear();
				try {
					source.completeHandshake(handshake);
					coordinatorDisconnectPending.set(false);
				} catch (RuntimeException exception) {
					protocolKnownAgentIds.clear();
					coordinatorReadyAgentIds.clear();
					throw exception;
				}
				return;
			}
		}
	}

	private BridgeEnvelope verboseControlEnvelope() {
		JsonObject payload = new JsonObject();
		payload.addProperty("enabled", verboseState.enabled());
		return new BridgeEnvelope(
				2, serverInstanceId, "server", "verbose_control",
				"server-" + messageIds.incrementAndGet(), payload
		);
	}

	private void routeAuthenticated(BridgeEnvelope envelope) {
		switch (envelope.type()) {
			case "catalog_snapshot" -> acceptCatalog(envelope.payload());
			case "coordinator_status" -> acceptCoordinatorStatus(envelope.payload());
			case "agent_ready", "planning_state" -> plannerReady(envelope);
			case "goal_completed" -> acceptGoalCompleted(envelope);
			case "conversation_wake_ack" -> acceptConversationWakeAck(envelope);
			case "request_observation" -> acceptObservationRequest(envelope);
			case "action_command" -> acceptAction(envelope);
			case "action_cancel" -> acceptActionCancel(envelope);
			case "agent_error" -> acceptAgentError(envelope);
			case "verbose_event" -> acceptVerboseEvent(envelope);
			case "heartbeat" -> send("heartbeat", "server", new JsonObject());
			default -> throw new BridgeProtocolException("UNKNOWN_MESSAGE_TYPE", envelope.type());
		}
	}

	private void acceptObservationRequest(BridgeEnvelope envelope) {
		AgentId id = AgentId.parse(envelope.agentId());
		AgentRecord record = manager.registry().require(id);
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision"), "request_observation");
		if (requiredLong(payload, "goalRevision") != record.goalRevision()) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator observation request revision is stale");
		}
		observations.invalidate(id);
		observationPublication.markAttention(id);
		queueUrgentObservation(id);
	}

	private void acceptCoordinatorStatus(JsonObject payload) {
		CoordinatorStatusStore.update(manager.server(), decodeCoordinatorStatus(payload, System.currentTimeMillis()));
	}

	static CoordinatorStatusSnapshot decodeCoordinatorStatus(JsonObject payload, long receivedAtEpochMs) {
		try {
		Set<String> legacyKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits");
		Set<String> latencyKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits", "latencies");
		if (!payload.keySet().equals(legacyKeys) && !payload.keySet().equals(latencyKeys)) {
			throw new BridgeProtocolException("INVALID_FIELD", "coordinator_status");
		}
		JsonArray profileValues = requiredArray(payload, "profiles", CoordinatorStatusSnapshot.MAX_PROFILES);
		ArrayList<CoordinatorStatusSnapshot.SupportedProfile> profiles = new ArrayList<>();
		for (var element : profileValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "profile must be an object");
			JsonObject profile = element.getAsJsonObject();
			requireKeys(profile, Set.of("agentId", "provider", "model", "reasoningEffort"), "profile");
			profiles.add(new CoordinatorStatusSnapshot.SupportedProfile(
					requiredStatusString(profile, "agentId"), requiredStatusString(profile, "provider"),
					requiredStatusString(profile, "model"), requiredStatusString(profile, "reasoningEffort")
			));
		}
		JsonObject scheduler = requiredObject(payload, "scheduler");
		Set<String> schedulerRequiredKeys = Set.of("active", "pending", "maxConcurrent", "maxPending", "warning");
		Set<String> schedulerAllowedKeys = Set.of(
				"active", "pending", "maxConcurrent", "maxPending", "warning", "mode", "configuredTarget", "target",
				"minConcurrency", "maxConcurrency", "urgentReserve", "ordinaryActiveLimit", "activeOrdinary", "activeUrgent",
				"pendingOrdinary", "pendingUrgent", "growthCount", "backoffCount", "lastChangeReason", "healthyCompletions",
				"ordinaryReservationRejections", "urgentReservationRejections"
		);
		if (!scheduler.keySet().containsAll(schedulerRequiredKeys) || !schedulerAllowedKeys.containsAll(scheduler.keySet())) {
			throw new BridgeProtocolException("INVALID_FIELD", "scheduler");
		}
		CoordinatorStatusSnapshot.SchedulerStatus schedulerStatus = new CoordinatorStatusSnapshot.SchedulerStatus(
				requiredInt(scheduler, "active"), requiredInt(scheduler, "pending"),
				requiredInt(scheduler, "maxConcurrent"), requiredInt(scheduler, "maxPending"), requiredBoolean(scheduler, "warning"),
				scheduler.has("mode") && "adaptive".equals(requiredString(scheduler, "mode")) && scheduler.has("maxConcurrency")
						? requiredInt(scheduler, "maxConcurrency")
						: requiredInt(scheduler, "maxConcurrent")
		);
		JsonArray circuitValues = requiredArray(payload, "circuits", CoordinatorStatusSnapshot.MAX_CIRCUITS);
		ArrayList<CoordinatorStatusSnapshot.CircuitHealth> circuits = new ArrayList<>();
		for (var element : circuitValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "circuit must be an object");
			JsonObject circuit = element.getAsJsonObject();
			requireKeys(circuit, Set.of("provider", "model", "operation", "count", "p50Ms", "p95Ms", "failureRate", "circuit"), "circuit");
			circuits.add(new CoordinatorStatusSnapshot.CircuitHealth(
					requiredStatusString(circuit, "provider"), requiredStatusString(circuit, "model"), requiredStatusString(circuit, "operation"),
					requiredInt(circuit, "count"), requiredInt(circuit, "p50Ms"), requiredInt(circuit, "p95Ms"),
					requiredDouble(circuit, "failureRate"), requiredStatusString(circuit, "circuit")
			));
		}
		JsonArray latencyValues = payload.has("latencies")
				? requiredArray(payload, "latencies", CoordinatorStatusSnapshot.MAX_LATENCIES)
				: new JsonArray();
		ArrayList<CoordinatorStatusSnapshot.LatencyHealth> latencies = new ArrayList<>();
		for (var element : latencyValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "latency must be an object");
			JsonObject latency = element.getAsJsonObject();
			requireKeys(latency, Set.of("operation", "count", "p50Ms", "p95Ms"), "latency");
			latencies.add(new CoordinatorStatusSnapshot.LatencyHealth(
					requiredStatusString(latency, "operation"), requiredInt(latency, "count"),
					requiredNonNegativeDouble(latency, "p50Ms"), requiredNonNegativeDouble(latency, "p95Ms")
			));
		}
			return new CoordinatorStatusSnapshot(
					requiredBoolean(payload, "reconciled"), profiles, requiredInt(payload, "supportedProfileCount"),
					requiredInt(payload, "rosterReadyCount"), requiredInt(payload, "rosterCount"), schedulerStatus, circuits, latencies,
					receivedAtEpochMs
			);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", exception.getMessage(), exception);
		}
	}

	private void acceptAgentError(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		long goalRevision = requiredLong(envelope.payload(), "goalRevision");
		if (!router.isCurrentActiveRevision(agentId, goalRevision)) {
			return;
		}
		String code = requiredString(envelope.payload(), "code");
		String message = requiredString(envelope.payload(), "message");
		AgentTransition transition = router.plannerFailed(agentId, goalRevision, message);
		reportRawAgentError(verboseState,
				() -> AgentChatReporter.failed(manager, transition.after(), code, message));
	}

	static void reportRawAgentError(AgentVerboseState verboseState, Runnable reporter) {
		if (verboseState.standardActivityEnabled()) reporter.run();
	}

	private void acceptVerboseEvent(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		VerboseEvent event = decodeVerboseEvent(envelope.payload());
		AgentRecord record = manager.registry().require(agentId);
		if (event.goalRevision() != record.goalRevision()) return;
		AgentVerboseChat.report(manager, verboseState, record, event.stage(), event.message());
	}

	static VerboseEvent decodeVerboseEvent(JsonObject payload) {
		requireKeys(payload, Set.of("goalRevision", "stage", "message"), "verbose_event");
		long goalRevision = requiredLong(payload, "goalRevision");
		String stage = requiredString(payload, "stage");
		if (!AgentVerboseChat.allowedStage(stage)) {
			throw new BridgeProtocolException("INVALID_VERBOSE_EVENT", "verbose_event.stage is not supported");
		}
		String message = requiredString(payload, "message");
		if (message.length() > AgentVerboseChat.MAX_MESSAGE_LENGTH
				|| message.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new BridgeProtocolException(
					"INVALID_VERBOSE_EVENT", "verbose_event.message must be plain text with at most 256 characters"
			);
		}
		return new VerboseEvent(goalRevision, stage, message);
	}

	static record VerboseEvent(long goalRevision, String stage, String message) { }

	private void acceptGoalCompleted(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		if (!payload.has("completionContract")) throw new BridgeProtocolException("CONTRACT_REQUIRED", "goal_completed requires a factual completionContract");
		requireKeys(payload, Set.of("goalRevision", "completionContract", "traceId", "profile", "contractHash"), "goal_completed");
		long goalRevision = requiredLong(payload, "goalRevision");
		AgentRecord record = manager.registry().require(agentId);
		String traceId = requiredTraceId(payload, "traceId");
		String contractHash = requiredString(payload, "contractHash");
		JsonObject profile = requiredObject(payload, "profile");
		requireKeys(profile, Set.of("provider", "model", "reasoningEffort", "serviceTier"), "goal_completed.profile");
		if (!record.profile().provider().equals(requiredString(profile, "provider"))
				|| !record.profile().model().equals(requiredString(profile, "model"))
				|| !record.profile().reasoning().equals(requiredString(profile, "reasoningEffort"))
				|| !record.profile().serviceTier().equals(requiredString(profile, "serviceTier"))) {
			throw new AgentDomainException("STALE_PROVENANCE", "Completion profile does not match the selected model profile");
		}
		JsonElement contractElement = payload.get("completionContract");
		if (!contractElement.isJsonObject()) throw new BridgeProtocolException("MALFORMED_CONTRACT", "completionContract must be an object");
		GoalCompletionContract contract = GoalCompletionContract.parse(contractElement.getAsJsonObject());
		if (contract.goalRevision() != goalRevision || !contractHash.equals(hashContract(contract))) {
			throw new BridgeProtocolException("STALE_CONTRACT", "Completion contract revision or hash does not match");
		}
		GoalCompletionVerifier.VerificationResult verification = new GoalCompletionVerifier().verify(
				record, manager.findAgentPlayer(agentId).orElse(null), contract, actionExecutor.actionSuccessLedger());
		VerboseEvent feedback = completionVerboseEvent(goalRevision, verification);
		if (!verification.verified()) {
			AgentVerboseChat.report(manager, verboseState, record, feedback.stage(), feedback.message());
		}
		JsonObject result = completionResultPayload(goalRevision, traceId, contractHash, verification);
		send("goal_completion_result", agentId.toString(), result);
		if (verification.verified()) {
			AgentRecord completed = router.coordinatorCompleted(agentId, goalRevision);
			AgentVerboseChat.report(manager, verboseState, completed, feedback.stage(), feedback.message());
		}
	}

	static VerboseEvent completionVerboseEvent(
			long goalRevision,
			GoalCompletionVerifier.VerificationResult verification
	) {
		Objects.requireNonNull(verification, "verification must not be null");
		return verification.verified()
				? new VerboseEvent(goalRevision, "result", "Task complete.")
				: new VerboseEvent(
						goalRevision, "retry", "Goal completion could not be verified. Continuing the task.");
	}

	static JsonObject completionResultPayload(long goalRevision, String traceId, String contractHash, GoalCompletionVerifier.VerificationResult verification) {
		JsonObject result = verification.toJson();
		result.addProperty("goalRevision", goalRevision);
		result.addProperty("traceId", traceId);
		result.addProperty("contractHash", contractHash);
		return result;
	}

	private static String hashContract(GoalCompletionContract contract) {
		return contract.fingerprint();
	}

	private void acceptConversationWakeAck(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("transactionId", "goalRevision"), "conversation_wake_ack");
		UUID transactionId;
		try {
			transactionId = UUID.fromString(requiredString(payload, "transactionId"));
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_FIELD", "conversation_wake_ack.transactionId", exception);
		}
		manager.acknowledgeConversationWake(transactionId, agentId, requiredLong(payload, "goalRevision"));
	}

	private void requestActiveDisconnect() {
		activeDisconnectPending.set(true);
	}

	private void publishPendingDisconnects() {
		boolean coordinatorDisconnect;
		boolean activeDisconnect;
		synchronized (publicationLock) {
			coordinatorDisconnect = coordinatorDisconnectPending.getAndSet(false);
			activeDisconnect = activeDisconnectPending.getAndSet(false) && !authenticated();
			if (!coordinatorDisconnect && !activeDisconnect) return;
			disconnectInProgress = true;
		}
		try {
			if (coordinatorDisconnect) actionExecutor.coordinatorDisconnected();
			disconnectActiveAgents();
		} finally {
			synchronized (publicationLock) {
				disconnectInProgress = false;
				publicationLock.notifyAll();
			}
		}
	}

	private void awaitDisconnectPublication() {
		synchronized (publicationLock) {
			while (disconnectInProgress) {
				try {
					publicationLock.wait();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new BridgeProtocolException(
							"COORDINATOR_DISCONNECTED", "Bridge authentication was interrupted", exception
					);
				}
			}
		}
	}

	private void disconnectActiveAgents() {
		long now = System.currentTimeMillis();
		for (AgentRecord record : manager.records()) {
			if (!record.state().isActive()) continue;
			Optional<PendingConversationWake> wake = manager.pendingConversationWake(record.agentId());
			if (wake.isPresent() && wake.orElseThrow().matches(record)) {
				manager.rearmConversationWake(wake.orElseThrow());
				continue;
			}
			AgentTransition transition = manager.registry().disconnect(record.agentId(), now);
			AgentChatReporter.disconnected(manager, transition.after());
		}
	}

	static List<AgentControlModelOption> decodeCatalog(JsonObject payload) {
		try {
			requireKeys(payload, Set.of("refreshedAtEpochMs", "models"), "catalog_snapshot");
			requiredLong(payload, "refreshedAtEpochMs");
			JsonArray models = requiredArray(payload, "models", AgentControlModelOption.MAX_OPTIONS);
			if (models.isEmpty()) throw new BridgeProtocolException("INVALID_MODEL_CATALOG", "models must not be empty");
			ArrayList<AgentControlModelOption> decoded = new ArrayList<>(models.size());
			for (var element : models) {
				if (!element.isJsonObject()) {
					throw new BridgeProtocolException("INVALID_MODEL_CATALOG", "model must be an object");
				}
				JsonObject model = element.getAsJsonObject();
				requireKeys(model, Set.of("provider", "id", "model", "displayName", "reasoningEfforts", "serviceTiers"),
						"catalog model");
				decoded.add(new AgentControlModelOption(
						requiredStatusString(model, "provider"),
						requiredStatusString(model, "id"),
						requiredStatusString(model, "displayName"),
						stringList(model, "reasoningEfforts", 12),
						stringList(model, "serviceTiers", 8)
				));
			}
			return List.copyOf(decoded);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new BridgeProtocolException("INVALID_MODEL_CATALOG", exception.getMessage(), exception);
		}
	}

	private void acceptCatalog(JsonObject payload) {
		List<AgentControlModelOption> decoded = decodeCatalog(payload);
		JsonArray models = payload.getAsJsonArray("models");
		HashSet<String> profiles = new HashSet<>();
		for (var element : models) {
			JsonObject model = element.getAsJsonObject();
			String provider = requiredStatusString(model, "provider");
			String id = requiredStatusString(model, "id");
			String wireModel = requiredStatusString(model, "model");
			JsonArray efforts = model.getAsJsonArray("reasoningEfforts");
			for (var effort : efforts) {
				String normalizedEffort = effort.getAsString().toLowerCase();
				profiles.add(provider + "\u0000" + id + "\u0000" + normalizedEffort);
				profiles.add(provider + "\u0000" + wireModel + "\u0000" + normalizedEffort);
			}
		}
		catalogProfiles = Set.copyOf(profiles);
		catalogModels = decoded;
		catalogLoaded = true;
	}

	private void plannerReady(BridgeEnvelope envelope) {
		AgentId id = AgentId.parse(envelope.agentId());
		long revision = requiredLong(envelope.payload(), "goalRevision");
		AgentRecord record;
		try {
			record = manager.registry().require(id);
		} catch (AgentDomainException exception) {
			if ("AGENT_NOT_FOUND".equals(exception.code())) return;
			throw exception;
		}
		if (revision != record.goalRevision()) {
			if (revision < record.goalRevision()) return;
			throw new AgentDomainException("STALE_REVISION", "Coordinator planning revision is stale");
		}
		if ("agent_ready".equals(envelope.type())) coordinatorReadyAgentIds.add(id);
		boolean reconciledReconnect = "agent_ready".equals(envelope.type())
				&& envelope.payload().has("reconciled")
				&& requiredBoolean(envelope.payload(), "reconciled")
				&& record.state() == AgentLifecycleState.DISCONNECTED
				&& record.currentGoal().isPresent();
		if (reconciledReconnect) {
			manager.registry().resume(id, System.currentTimeMillis());
		}
		if (record.state() == AgentLifecycleState.STARTING) {
			router.plannerStarted(id);
		}
		if ("agent_ready".equals(envelope.type())) {
			observationPublication.markAttention(id);
			queueUrgentObservation(id);
		}
	}

	private void acceptAction(BridgeEnvelope envelope) {
		try {
			ServerActionRequest request = decodeActionRequest(envelope);
			validateActionProvenance(request);
			actionExecutor.submitProgramPrimitive(request);
		} catch (BridgeProtocolException | AgentDomainException exception) {
			if (sendRejectedAction(envelope, exception)) return;
			throw exception;
		}
	}

	private void acceptActionCancel(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision", "actionId"), "action_cancel");
		long goalRevision = requiredLong(payload, "goalRevision");
		String actionId = requiredString(payload, "actionId");
		if (!actionExecutor.cancel(agentId, goalRevision, actionId, "Cancelled by explicit model decision")) {
			throw new AgentDomainException("ACTION_NOT_ACTIVE", "The referenced action is no longer active");
		}
	}

	static ServerActionRequest decodeActionRequest(BridgeEnvelope envelope) {
		JsonObject payload = envelope.payload();
		requireActionCommandKeys(payload);
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject arguments = requiredObject(payload, "arguments");
		String type = requiredString(payload, "actionType");
		ActionType actionType = ActionType.fromWireName(type).orElseThrow(
				() -> new BridgeProtocolException("UNKNOWN_ACTION", "Unknown action type '" + type + "'")
		);
		if (!ServerActionExecutor.isArenaScriptPrimitive(actionType)) {
			throw new BridgeProtocolException("UNSUPPORTED_ARENA_SCRIPT_ACTION", "ArenaScript cannot invoke '" + type + "'");
		}
		try {
			JsonObject validatedArguments = ProtocolCodec.validateActionArguments(actionType, arguments);
			String traceId = requiredTraceId(payload, "traceId");
			ActionProvenance provenance = decodeActionProvenance(payload);
			if (provenance.traceId() == null || !provenance.traceId().equals(traceId)) {
				throw new BridgeProtocolException("INVALID_TRACE_ID", "provenance.traceId must match traceId");
			}
			return new ServerActionRequest(agentId, requiredLong(payload, "goalRevision"), requiredString(payload, "actionId"), actionType, validatedArguments, provenance, traceId);
		} catch (ProtocolException exception) {
			throw new BridgeProtocolException(exception.code(), exception.getMessage(), exception);
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_ACTION_REQUEST", exception.getMessage(), exception);
		}
	}

	private static void requireActionCommandKeys(JsonObject payload) {
		Set<String> expected = Set.of("traceId", "goalRevision", "actionId", "actionType", "arguments", "provenance");
		for (String field : Set.of("goalRevision", "actionId", "actionType", "arguments", "provenance")) if (!payload.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		for (String field : payload.keySet()) if (!expected.contains(field)) throw new BridgeProtocolException("INVALID_FIELD", "action_command");
	}

	private boolean sendRejectedAction(BridgeEnvelope envelope, RuntimeException exception) {
		JsonObject payload = envelope.payload();
		RejectionIdentity identity = rejectionIdentity(envelope);
		if (identity == null) return false;
		JsonObject result = new JsonObject();
		result.addProperty("goalRevision", identity.goalRevision());
		result.addProperty("actionId", identity.actionId());
		result.addProperty("commandId", identity.actionId());
		result.addProperty("actionType", identity.actionType());
		result.addProperty("traceId", identity.traceId());
		result.addProperty("state", "FAILED");
		result.addProperty("reasonCode", exception instanceof BridgeProtocolException protocol ? protocol.code() : ((AgentDomainException) exception).code());
		result.addProperty("message", boundedRejectionMessage(exception.getMessage()));
		result.addProperty("elapsedMs", 0L);
		result.addProperty("observedAtEpochMs", System.currentTimeMillis());
		result.addProperty("executionStarted", false);
		result.addProperty("physicalAttempted", false);
		send("action_result", identity.agentId(), result);
		reportVerbose(AgentId.parse(identity.agentId()), "error",
				"Action rejected: " + boundedRejectionMessage(exception.getMessage()));
		return true;
	}

	private static RejectionIdentity rejectionIdentity(BridgeEnvelope envelope) {
		JsonObject payload = envelope.payload();
		try {
			AgentId.parse(envelope.agentId());
			return new RejectionIdentity(envelope.agentId(), requiredLong(payload, "goalRevision"), requiredString(payload, "actionId"), requiredString(payload, "actionType"), requiredTraceId(payload, "traceId"));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private record RejectionIdentity(String agentId, long goalRevision, String actionId, String actionType, String traceId) { }

	static String boundedRejectionMessage(String message) {
		String fallback = "Action rejected";
		if (message == null || message.isBlank()) return fallback;
		int end = Math.min(message.length(), ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH);
		if (end < message.length() && end > 0 && Character.isHighSurrogate(message.charAt(end - 1))
				&& Character.isLowSurrogate(message.charAt(end))) end -= 1;
		String bounded = message.substring(0, end);
		return bounded.isBlank() ? fallback : bounded;
	}

	private void validateActionProvenance(ServerActionRequest request) {
		AgentRecord record = manager.registry().require(request.agentId());
		boolean respawn = request.type() == ActionType.RESPAWN;
		if ((respawn && (record.state() != AgentLifecycleState.DEAD || request.goalRevision() != record.goalRevision()))
				|| (!respawn && !record.acceptsRevision(request.goalRevision()))) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator action revision is stale");
		}
		ActionProvenance provenance = request.provenance();
		AgentProfile profile = record.profile();
		if (!profile.provider().equals(provenance.provider()) || !profile.model().equals(provenance.model())
				|| !profile.reasoning().equals(provenance.reasoningEffort()) || !profile.serviceTier().equals(provenance.serviceTier())) {
			throw new AgentDomainException("STALE_PROVENANCE", "Action provenance does not match the selected model profile");
		}
		if (request.type() == ActionType.ATTACK || request.type() == ActionType.USE_RANGED
				|| request.type() == ActionType.INTERACT_ENTITY) {
		observationPublication.requireObservedTarget(
					request.agentId(),
					provenance.eventSequence(),
					request.arguments().get("targetId").getAsString()
			);
		}
		if (request.type() == ActionType.CHAT
				&& ConversationAudience.parse(nullableString(request.arguments(), "audience")) == ConversationAudience.DIRECT) {
			observationPublication.requireDirectMessageRecipient(
					request.agentId(),
					provenance.eventSequence(),
					requiredString(request.arguments(), "recipientId")
			);
		}
		programActions.accept(request);
	}

	private static ActionProvenance decodeActionProvenance(JsonObject payload) {
		JsonObject provenance = requiredObject(payload, "provenance");
		Set<String> expected = Set.of(
				"provider", "model", "reasoningEffort", "serviceTier", "programId", "programVersion", "sourceStepId", "eventSequence", "traceId", "watcherId"
		);
		for (String field : provenance.keySet()) if (!expected.contains(field)) throw new BridgeProtocolException("INVALID_FIELD", "provenance");
		for (String field : Set.of("provider", "model", "reasoningEffort", "serviceTier", "programId", "programVersion", "sourceStepId", "eventSequence")) {
			if (!provenance.has(field)) throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		if (provenance.has("watcherId") && !provenance.has("traceId")) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance.traceId");
		}
		try {
			return new ActionProvenance(
					requiredProvenanceString(provenance, "provider"),
					requiredProvenanceString(provenance, "model"),
					requiredProvenanceString(provenance, "reasoningEffort"),
					requiredProvenanceString(provenance, "serviceTier"),
					requiredProvenanceString(provenance, "programId"),
					requiredSafeLong(provenance, "programVersion"),
					requiredProvenanceString(provenance, "sourceStepId"),
					requiredSafeLong(provenance, "eventSequence"),
					provenance.has("traceId") ? requiredTraceId(provenance, "traceId") : null,
					provenance.has("watcherId") ? requiredProvenanceString(provenance, "watcherId") : null
			);
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_PROVENANCE", exception.getMessage(), exception);
		}
	}

	private void sendActionResult(ServerActionResult result) {
		programActions.terminal(result);
		observations.invalidate(result.agentId());
		ScenarioRuntimeService.onAgentAction(
				manager.server(),
				result.agentId().toString(),
				result.actionType().wireName(),
				result.state() == dev.agaminggod.arenaagents.server.runtime.ServerActionState.SUCCEEDED
						&& !"TARGET_ALREADY_SATISFIED".equals(result.reasonCode())
		);
		send("action_result", result.agentId().toString(), actionResultPayload(result));
		reportVerbose(result.agentId(), AgentActivityPresentation.verboseResultStage(result), verboseResult(result));
		verboseState.finishAction(result);
		observationPublication.markAttention(result.agentId());
		queueUrgentObservation(result.agentId());
	}

	void publishConversationEvent(ConversationEvent event, Optional<dev.agaminggod.arenaagents.agent.goal.GoalSpec> wakeGoal) {
		Objects.requireNonNull(event, "event must not be null");
		Optional<dev.agaminggod.arenaagents.agent.goal.GoalSpec> checkedWakeGoal = Objects.requireNonNull(wakeGoal, "wakeGoal must not be null");
		try {
			if (checkedWakeGoal.isEmpty()) {
				synchronized (publicationLock) {
					Session active = requireConversationSession(event.agentId());
					active.enqueue(new BridgeEnvelope(
							2, serverInstanceId, event.agentId().toString(), "conversation_event",
							"server-" + messageIds.incrementAndGet(), conversationEventPayload(event)
					));
				}
			} else {
				AgentTransition transition = manager.startConversationWakeAtomically(
						event, checkedWakeGoal.orElseThrow(),
						(wake, commit) -> {
							synchronized (publicationLock) {
								Session active = requireConversationSession(event.agentId());
								active.enqueueAtomically(conversationWakeEnvelope(wake), commit);
							}
						}
				);
				publishConversationWakeScenarioState(transition);
			}
		} catch (BridgeProtocolException exception) {
			throw new AgentDomainException(exception.code(), "Agent conversation delivery failed: " + exception.getMessage());
		}
		conversationPublished(event);
	}

	private Session requireConversationSession(AgentId agentId) {
		Session active = session;
		if (active == null || !active.open.get() || !active.authenticated.get()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		if (!protocolKnownAgentIds.contains(agentId)) {
			throw new AgentDomainException(
					"AGENT_NOT_READY", "AI agent is still registering with the coordinator; retry shortly"
			);
		}
		if (!coordinatorReadyAgentIds.contains(agentId)) {
			throw new AgentDomainException(
					"AGENT_NOT_READY", "AI agent is waiting for coordinator readiness; retry shortly"
			);
		}
		return active;
	}

	private static JsonObject conversationEventPayload(ConversationEvent event) {
		JsonObject payload = new JsonObject();
		payload.addProperty("sequence", event.sequence());
		payload.addProperty("kind", event.kind().wireName());
		payload.addProperty("sourceId", event.sourceId());
		payload.addProperty("recipientId", event.recipientId());
		payload.addProperty("scope", event.audience().wireName());
		payload.addProperty("text", event.text());
		payload.addProperty("goalRevision", event.goalRevision());
		payload.addProperty("observedAtEpochMs", event.observedAtEpochMs());
		return payload;
	}

	private BridgeEnvelope conversationWakeEnvelope(PendingConversationWake wake) {
		return new BridgeEnvelope(
				2, serverInstanceId, wake.event().agentId().toString(), "conversation_wake",
				"server-" + messageIds.incrementAndGet(), conversationWakePayload(wake)
		);
	}

	private static JsonObject conversationWakePayload(PendingConversationWake wake) {
		JsonObject payload = new JsonObject();
		payload.addProperty("transactionId", wake.transactionId().toString());
		payload.add("event", conversationEventPayload(wake.event()));
		JsonObject control = new JsonObject();
		control.addProperty("operation", "start");
		control.addProperty("goalRevision", wake.goalRevision());
		control.addProperty("updatedAtEpochMs", wake.updatedAtEpochMs());
		control.addProperty("goal", plannerGoal(wake.goal()));
		payload.add("control", control);
		return payload;
	}

	private void conversationPublished(ConversationEvent event) {
		if (event.kind() == ConversationKind.PLAYER_MESSAGE) {
			observationPublication.retainConversationSource(event.agentId(), event.sourceId());
		}
		observationPublication.markAttention(event.agentId());
		queueUrgentObservation(event.agentId());
	}

	private void publishConversationWakeScenarioState(AgentTransition transition) {
		try {
			ScenarioRuntimeService.onAgentState(
					manager.server(), transition.after().agentId().toString(), publicState(transition.after().state())
			);
		} catch (RuntimeException exception) {
			LOGGER.warn("Conversation wake scenario telemetry failed after committed publication", exception);
		}
	}

	private void sendRespawnResultBeforeControl(ServerActionResult result, AgentTransition transition, Runnable commit) {
		Session active = session;
		if (active == null || !active.authenticated.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Respawn result has no authenticated coordinator");
		BridgeEnvelope resultEnvelope = new BridgeEnvelope(2, serverInstanceId, result.agentId().toString(), "action_result",
				"server-" + messageIds.incrementAndGet(), actionResultPayload(result));
		BridgeEnvelope controlEnvelope = new BridgeEnvelope(2, serverInstanceId, transition.after().agentId().toString(), "goal_control",
				"server-" + messageIds.incrementAndGet(), goalControlPayload(transition, "respawn"));
		publishRespawnScenarioEvents(
				() -> active.enqueuePair(resultEnvelope, controlEnvelope, commit),
				() -> ScenarioRuntimeService.onAgentAction(manager.server(), result.agentId().toString(), result.actionType().wireName(), true),
				() -> ScenarioRuntimeService.onAgentState(manager.server(), transition.after().agentId().toString(), publicState(transition.after().state()))
		);
		programActions.terminal(result);
		observations.invalidate(result.agentId());
		reportVerbose(result.agentId(), AgentActivityPresentation.verboseResultStage(result), verboseResult(result));
		verboseState.finishAction(result);
		observationPublication.markAttention(result.agentId());
		queueUrgentObservation(result.agentId());
	}

	static void publishRespawnScenarioEvents(Runnable publication, Runnable actionEvent, Runnable stateEvent) {
		publication.run();
		try {
			actionEvent.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Respawn action scenario telemetry failed after committed publication", exception);
		}
		try {
			stateEvent.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Respawn state scenario telemetry failed after committed publication", exception);
		}
	}

	private static JsonObject actionResultPayload(ServerActionResult result) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", result.goalRevision());
		payload.addProperty("actionId", result.actionId());
		payload.addProperty("commandId", result.actionId());
		payload.addProperty("actionType", result.actionType().wireName());
		if (result.traceId() != null) payload.addProperty("traceId", result.traceId());
		payload.addProperty("state", result.state().name());
		payload.addProperty("reasonCode", result.reasonCode());
		payload.addProperty("message", result.message());
		payload.addProperty("elapsedMs", result.elapsedMs());
		payload.addProperty("observedAtEpochMs", result.observedAtEpochMs());
		payload.addProperty("executionStarted", result.executionStarted());
		payload.addProperty("physicalAttempted", result.physicalAttempted());
		return payload;
	}

	private static String verboseResult(ServerActionResult result) {
		return AgentVerboseChat.sanitizeMessage(AgentActivityPresentation.verboseResult(result));
	}

	private static JsonObject goalControlPayload(AgentTransition transition, String operation) {
		JsonObject payload = new JsonObject();
		payload.addProperty("operation", operation);
		payload.addProperty("goalRevision", transition.after().goalRevision());
		payload.addProperty("updatedAtEpochMs", transition.after().updatedAtEpochMs());
		if ("queue".equals(operation)) {
			List<AgentGoal> queue = transition.after().queuedGoals();
			payload.addProperty("goal", queue.get(queue.size() - 1).prompt());
		} else if ("start".equals(operation) || "steer".equals(operation)) {
			transition.after().currentGoal().ifPresent(goal -> payload.addProperty("goal", plannerGoal(goal)));
		}
		if ("respawn".equals(operation) && transition.after().state() == AgentLifecycleState.STARTING) {
			payload.addProperty("resumeGoal", true);
		}
		if ("dead".equals(operation)) transition.after().deathSnapshot().ifPresent(death -> payload.add("death", deathFacts(death)));
		return payload;
	}

	static JsonObject deathFacts(dev.agaminggod.arenaagents.agent.AgentDeathSnapshot death) {
		JsonObject facts = new JsonObject();
		facts.addProperty("cause", death.cause());
		facts.addProperty("dimensionId", death.dimensionId());
		facts.addProperty("x", death.x());
		facts.addProperty("y", death.y());
		facts.addProperty("z", death.z());
		death.respawnDimensionId().ifPresentOrElse(
				value -> facts.addProperty("respawnDimensionId", value),
				() -> facts.add("respawnDimensionId", JsonNull.INSTANCE)
		);
		death.respawnX().ifPresentOrElse(
				value -> facts.addProperty("respawnX", value),
				() -> facts.add("respawnX", JsonNull.INSTANCE)
		);
		death.respawnY().ifPresentOrElse(
				value -> facts.addProperty("respawnY", value),
				() -> facts.add("respawnY", JsonNull.INSTANCE)
		);
		death.respawnZ().ifPresentOrElse(
				value -> facts.addProperty("respawnZ", value),
				() -> facts.add("respawnZ", JsonNull.INSTANCE)
		);
		death.respawnYaw().ifPresentOrElse(
				value -> facts.addProperty("respawnYaw", value),
				() -> facts.add("respawnYaw", JsonNull.INSTANCE)
		);
		death.respawnPitch().ifPresentOrElse(
				value -> facts.addProperty("respawnPitch", value),
				() -> facts.add("respawnPitch", JsonNull.INSTANCE)
		);
		death.respawnForced().ifPresentOrElse(
				value -> facts.addProperty("respawnForced", value),
				() -> facts.add("respawnForced", JsonNull.INSTANCE)
		);
		facts.addProperty("gameMode", death.gameMode());
		facts.addProperty("diedAtEpochMs", death.diedAtEpochMs());
		return facts;
	}

	private void sendActionProgress(ServerActionProgress progress) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", progress.goalRevision());
		payload.addProperty("actionId", progress.actionId());
		payload.addProperty("commandId", progress.actionId());
		payload.addProperty("actionType", progress.actionType().wireName());
		if (progress.traceId() != null) payload.addProperty("traceId", progress.traceId());
		payload.addProperty("state", "RUNNING");
		payload.addProperty("progress", progress.progress());
		payload.addProperty("elapsedMs", progress.elapsedMs());
		payload.addProperty("observedAtEpochMs", progress.observedAtEpochMs());
		send("action_progress", progress.agentId().toString(), payload);
		verboseState.progressMilestone(progress).ifPresent(milestone ->
				reportVerbose(progress.agentId(), "progress",
						AgentActivityPresentation.progress(progress.actionType(), milestone)));
		queueObservation(progress.agentId());
	}

	private void reportVerbose(AgentId agentId, String stage, String message) {
		try {
			AgentRecord record = manager.registry().require(agentId);
			AgentVerboseChat.report(manager, verboseState, record, stage, message);
		} catch (RuntimeException exception) {
			LOGGER.debug("Verbose {} event was unavailable for {}: {}", stage, agentId, exception.getMessage());
		}
	}

	private void queueObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (!observationPublication.offer(agentId)) {
			LOGGER.debug("Observation request coalesced or deferred for {}", agentId);
		}
	}

	private void queueUrgentObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (!observationPublication.offerUrgent(agentId)) {
			LOGGER.debug("Urgent observation request deferred for {}", agentId);
		}
	}

	private void sendObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (manager.server() == null) return;
		Session source = session;
		if (source == null || !source.authenticated.get()) {
			return;
		}
		boolean heartbeat = observationPublication.takeHeartbeat(agentId);
		final JsonObject observation;
		try {
			observation = observations.collect(agentId);
			ObservationPublication.Result result = publishObservationWithInputGuard(
					observationPublication,
					AgentInputRuntime.existingController(manager.server()),
					agentId,
					source,
					observation,
					(ignoredAgent, payload) -> sendObservationEnvelope(source, ignoredAgent, payload),
					heartbeat
			);
			if (result == ObservationPublication.Result.DELIVERY_RETRY) retryObservation(agentId, heartbeat);
			return;
		} catch (AgentDomainException exception) {
			LOGGER.debug("Dropping observation for removed agent {}: {}", agentId, exception.code());
		} catch (BridgeProtocolException exception) {
			if (isTransientObservationDelivery(exception)) retryObservation(agentId, heartbeat);
			else LOGGER.warn("Dropping observation delivery for {}: {}", agentId, exception.getMessage());
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not collect observation for {}: {}", agentId, exception.getMessage());
		}
	}

	private void retryObservation(AgentId agentId) {
		retryObservation(agentId, false);
	}

	private void retryObservation(AgentId agentId, boolean heartbeat) {
		try {
			manager.registry().require(agentId);
		} catch (AgentDomainException exception) {
			return;
		}
		if (!observationPublication.markDirty(agentId)) return;
		try {
			if (heartbeat) observationPublication.offerHeartbeat(agentId);
			else observationPublication.offer(agentId);
		} catch (RuntimeException exception) {
			LOGGER.debug("Could not retain observation retry for {}: {}", agentId, exception.getMessage());
		}
	}

	private void resetObservationPublication() {
		observationPublication.reset();
	}

	private static boolean isTransientObservationDelivery(BridgeProtocolException exception) {
		return "AGENT_BACKPRESSURE".equals(exception.code()) || "CONNECTION_BACKPRESSURE".equals(exception.code());
	}

	private static ScenarioAgentEvent.PublicState publicState(AgentLifecycleState state) {
		return switch (state) {
			case IDLE -> ScenarioAgentEvent.PublicState.IDLE;
			case STARTING, PLANNING -> ScenarioAgentEvent.PublicState.THINKING;
			case ACTING -> ScenarioAgentEvent.PublicState.ACTING;
			case PAUSED, DISCONNECTED -> ScenarioAgentEvent.PublicState.RECOVERING;
			case COMPLETED -> ScenarioAgentEvent.PublicState.DONE;
			case ERROR -> ScenarioAgentEvent.PublicState.FAILED;
			case DEAD -> ScenarioAgentEvent.PublicState.DEAD;
		};
	}

	private void send(String type, String agentId, JsonObject payload) {
		Session active = session;
		if (active == null || !active.authenticated.get()) {
			return;
		}
		active.enqueue(new BridgeEnvelope(2, serverInstanceId, agentId, type,
				"server-" + messageIds.incrementAndGet(), payload));
	}

	private boolean sendObservationEnvelope(Session source, AgentId agentId, JsonObject payload) {
		if (session != source || !source.open.get() || !source.authenticated.get()) return false;
		BridgeEnvelope envelope = new BridgeEnvelope(2, serverInstanceId, agentId.toString(), "observation",
				"server-" + messageIds.incrementAndGet(), payload);
		if (codec.encodedBytes(envelope) > BridgeEnvelopeCodec.MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Fitted observation exceeds the actual wire envelope");
		}
		source.enqueue(envelope);
		return true;
	}

	private static JsonObject registeredPayload(AgentRecord record) {
		JsonObject payload = new JsonObject();
		payload.addProperty("schemaVersion", record.schemaVersion());
		payload.addProperty("agentId", record.agentId().toString());
		record.entityUuid().ifPresent(value -> payload.addProperty("entityUuid", value.toString()));
		record.profile().userName().ifPresent(value -> payload.addProperty("name", value));
		payload.addProperty("provider", record.profile().provider());
		payload.addProperty("model", record.profile().model());
		payload.addProperty("reasoningEffort", record.profile().reasoning());
		payload.addProperty("serviceTier", record.profile().serviceTier());
		payload.addProperty("gameMode", record.profile().gameMode().wireName());
		payload.addProperty("skinVariant", "variant-" + record.profile().skinVariant());
		payload.addProperty("state", record.state().name());
		record.currentGoal().ifPresent(goal -> payload.addProperty("currentGoal", plannerGoal(goal)));
		payload.addProperty("goalRevision", record.goalRevision());
		JsonArray queue = new JsonArray();
		for (AgentGoal goal : record.queuedGoals()) {
			queue.add(goal.prompt());
		}
		payload.add("queue", queue);
		if (!record.lastSummary().isBlank()) {
			payload.addProperty("lastSummary", record.lastSummary());
		}
		record.deathSnapshot().ifPresent(death -> payload.add("death", deathFacts(death)));
		payload.addProperty("createdAtEpochMs", record.createdAtEpochMs());
		payload.addProperty("updatedAtEpochMs", record.updatedAtEpochMs());
		if (!record.lastError().isBlank()) {
			JsonObject error = new JsonObject();
			error.addProperty("code", "AGENT_ERROR");
			error.addProperty("message", record.lastError());
			payload.add("lastError", error);
		}
		return payload;
	}

	private static String operation(AgentTransition transition) {
		if (transition.after().queuedGoals().size() > transition.before().queuedGoals().size()) return "queue";
		if (transition.after().goalRevision() <= transition.before().goalRevision()) return null;
		if (transition.before().state() == AgentLifecycleState.DEAD
				&& transition.after().state() != AgentLifecycleState.DEAD) return "respawn";
		if (transition.before().currentGoal().isPresent()
				&& transition.after().state() == AgentLifecycleState.IDLE
				&& transition.after().currentGoal().isEmpty()) return "complete";
		if (transition.after().state() == AgentLifecycleState.PAUSED) return "stop";
		if (transition.after().state() == AgentLifecycleState.COMPLETED) return "complete";
		if (transition.after().state() == AgentLifecycleState.ERROR) return "fail";
		if (transition.after().state() == AgentLifecycleState.DEAD) return "dead";
		if (transition.after().state() == AgentLifecycleState.DISCONNECTED) return "disconnect";
		if ((transition.before().state() == AgentLifecycleState.PAUSED
				|| transition.before().state() == AgentLifecycleState.DISCONNECTED)
				&& transition.after().state() == AgentLifecycleState.STARTING) return "resume";
		if (transition.after().state() != AgentLifecycleState.STARTING) return null;
		if (transition.before().currentGoal().isPresent() && transition.after().currentGoal().isPresent()
				&& transition.before().currentGoal().get().goalId().equals(transition.after().currentGoal().get().goalId())) {
			return "steer";
		}
		return "start";
	}

	private static String plannerGoal(AgentGoal goal) {
		if (goal.steeringInstructions().isEmpty()) {
			return goal.prompt();
		}
		String steering = "\n\nSteering instruction: "
				+ goal.steeringInstructions().get(goal.steeringInstructions().size() - 1);
		if (steering.length() >= AgentConstants.MAX_PROMPT_LENGTH) {
			return steering.substring(steering.length() - AgentConstants.MAX_PROMPT_LENGTH);
		}
		int promptLimit = AgentConstants.MAX_PROMPT_LENGTH - steering.length();
		String prompt = goal.prompt().length() <= promptLimit ? goal.prompt() : goal.prompt().substring(0, promptLimit);
		return prompt + steering;
	}

	private static Path configuredSecretPath() {
		String configured = System.getProperty("arenaagents.bridgeSecretFile");
		if (configured == null || configured.isBlank()) configured = System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE");
		return configured == null || configured.isBlank() ? Paths.get("runtime", "bridge-secret.txt") : Paths.get(configured);
	}

	static int configuredPort() {
		String configured = System.getProperty("arenaagents.bridgePort");
		if (configured == null || configured.isBlank()) return DEFAULT_PORT;
		final int parsed;
		try {
			parsed = Integer.parseInt(configured);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("arenaagents.bridgePort must be an integer from 1 to 65535", exception);
		}
		if (parsed < 1 || parsed > 65_535) {
			throw new IllegalArgumentException("arenaagents.bridgePort must be an integer from 1 to 65535");
		}
		return parsed;
	}

	private static String readSecret(Path path) {
		try {
			if (!Files.isRegularFile(path)) {
				throw new BridgeProtocolException("BRIDGE_SECRET_MISSING", "Bridge secret file does not exist: " + path.toAbsolutePath());
			}
			String value = Files.readString(path, StandardCharsets.UTF_8).trim();
			if (value.length() < MIN_SECRET_LENGTH || value.length() > MAX_SECRET_LENGTH) {
				throw new BridgeProtocolException(
						"BRIDGE_SECRET_INVALID",
						"Bridge secret must contain " + MIN_SECRET_LENGTH + "-" + MAX_SECRET_LENGTH + " characters"
				);
			}
			return value;
		} catch (IOException exception) {
			throw new BridgeProtocolException("BRIDGE_SECRET_READ_FAILED", "Could not read bridge secret: " + path.toAbsolutePath(), exception);
		}
	}

	private static String requiredString(JsonObject object, String field) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON string");
		}
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > 256) throw new BridgeProtocolException("INVALID_FIELD", field + " must be nonblank and bounded");
		return value;
	}

	private static String requiredTraceId(JsonObject object, String field) {
		String value = requiredString(object, field);
		if (value.getBytes(StandardCharsets.UTF_8).length > 128
				|| value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new BridgeProtocolException("INVALID_TRACE_ID", field + " must be at most 128 UTF-8 bytes");
		}
		return value;
	}

	private static String nullableString(JsonObject object, String field) {
		return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
	}

	private static String requiredStatusString(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", field + " must be a string");
		}
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > 256) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", field + " must be nonblank and at most 256 characters");
		}
		return value;
	}

	private static String requiredProvenanceString(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		return object.get(field).getAsString();
	}

	private static JsonObject requiredObject(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonObject()) throw new BridgeProtocolException("MISSING_FIELD", field);
		return object.getAsJsonObject(field);
	}

	private static JsonArray requiredArray(JsonObject object, String field, int maximum) {
		if (!object.has(field) || !object.get(field).isJsonArray()) throw new BridgeProtocolException("MISSING_FIELD", field);
		JsonArray value = object.getAsJsonArray(field);
		if (value.size() > maximum) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	private static List<String> stringList(JsonObject object, String field, int maximum) {
		JsonArray values = requiredArray(object, field, maximum);
		ArrayList<String> result = new ArrayList<>(values.size());
		for (var value : values) {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
				throw new BridgeProtocolException("INVALID_MODEL_CATALOG", field + " entries must be strings");
			}
			result.add(value.getAsString());
		}
		return List.copyOf(result);
	}

	private static boolean requiredBoolean(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isBoolean()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		return object.get(field).getAsBoolean();
	}

	private static int requiredInt(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value) || value < 0.0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
			throw new BridgeProtocolException("INVALID_FIELD", field);
		}
		return (int) value;
	}

	private static double requiredDouble(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value)) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	private static double requiredNonNegativeDouble(JsonObject object, String field) {
		double value = requiredDouble(object, field);
		if (value < 0.0D) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	/** Serializes observation delivery with session identity, queue, and baseline lifecycle. */
	static final class ObservationPublication {
		enum Result { COMMITTED, SUPPRESSED, STALE_SESSION, DELIVERY_RETRY }

		@FunctionalInterface
		interface Writer {
			boolean send(AgentId agentId, JsonObject payload);
		}

		private final Object lifecycleLock = new Object();
		private final ObservationDispatchQueue<AgentId> queue;
		private final PublishedObservationState published;
		private final int queueCapacity;
		private final int perTickLimit;
		private final java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter;
		private final LinkedHashSet<AgentId> urgent = new LinkedHashSet<>();
		private final Set<AgentId> heartbeatPending = new HashSet<>();
		private final AtomicLong sequences = new AtomicLong();
		private Object activeSession;
		private int heartbeatCursor;

		ObservationPublication(int queueCapacity, int perTickLimit) {
			this(queueCapacity, perTickLimit,
					(agentId, observation) -> new ServerObservationWireBudget.Fitted(observation, List.of()));
		}

		ObservationPublication(
				int queueCapacity,
				int perTickLimit,
				java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter
		) {
			this.queueCapacity = queueCapacity;
			this.perTickLimit = perTickLimit;
			queue = new ObservationDispatchQueue<>(queueCapacity, perTickLimit);
			published = new PublishedObservationState(queueCapacity);
			this.fitter = Objects.requireNonNull(fitter, "fitter must not be null");
		}

		void activate(Object session) {
			synchronized (lifecycleLock) {
				if (activeSession != null && activeSession != session) clearLocked();
				activeSession = Objects.requireNonNull(session, "session must not be null");
			}
		}

		void deactivate(Object session) {
			synchronized (lifecycleLock) {
				if (activeSession == session) {
					activeSession = null;
					clearLocked();
				}
			}
		}

		void reset() {
			synchronized (lifecycleLock) {
				clearLocked();
			}
		}

		boolean offer(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				heartbeatPending.remove(agentId);
				try {
					return queue.offer(agentId);
				} catch (IllegalStateException exception) {
					return false;
				}
			}
		}

		boolean offerHeartbeat(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				try {
					if (queue.offer(agentId) || queue.contains(agentId)) {
						heartbeatPending.add(agentId);
						return true;
					}
				} catch (IllegalStateException ignored) {
					// Backpressure remains bounded; the rotating heartbeat will retry later.
				}
				return false;
			}
		}

		boolean offerUrgent(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				if (urgent.size() >= queueCapacity) return false;
				queue.remove(agentId);
				heartbeatPending.remove(agentId);
				urgent.add(agentId);
				return true;
			}
		}

		/** Queues one heartbeat per call and rotates fairly across the supplied roster. */
		void scheduleIdleHeartbeat(List<AgentId> agents) {
			Objects.requireNonNull(agents, "agents must not be null");
			List<AgentId> roster = agents.stream()
					.map(Objects::requireNonNull)
					.distinct()
					.toList();
			synchronized (lifecycleLock) {
				if (roster.isEmpty()) {
					heartbeatCursor = 0;
					return;
				}
				heartbeatCursor %= roster.size();
				AgentId agentId = roster.get(heartbeatCursor++);
				if (urgent.contains(agentId)) return;
				try {
					if (queue.offer(agentId)) heartbeatPending.add(agentId);
				} catch (IllegalStateException ignored) {
					// A full coalescing queue defers this heartbeat to a later rotation.
				}
			}
		}

		boolean takeHeartbeat(AgentId agentId) {
			synchronized (lifecycleLock) {
				return heartbeatPending.remove(agentId);
			}
		}

		void drain(java.util.function.Consumer<AgentId> consumer) {
			Objects.requireNonNull(consumer, "consumer must not be null");
			for (int emitted = 0; emitted < perTickLimit; emitted++) {
				AgentId agentId;
				synchronized (lifecycleLock) {
					var iterator = urgent.iterator();
					if (iterator.hasNext()) {
						agentId = iterator.next();
						iterator.remove();
					} else {
						agentId = queue.poll();
					}
					if (agentId == null) return;
				}
				consumer.accept(agentId);
			}
		}

		void remove(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				queue.remove(agentId);
				urgent.remove(agentId);
				heartbeatPending.remove(agentId);
				published.remove(agentId);
			}
		}
		boolean markDirty(AgentId agentId) { return published.markDirty(agentId); }
		boolean markAttention(AgentId agentId) { return published.markAttention(agentId); }
		void requireObservedTarget(AgentId agentId, long eventSequence, String targetId) {
			published.requireObservedTarget(agentId, eventSequence, targetId);
		}
		void retainConversationSource(AgentId agentId, String sourceId) {
			published.retainConversationSource(agentId, sourceId);
		}
		void requireDirectMessageRecipient(AgentId agentId, long eventSequence, String recipientId) {
			published.requireDirectMessageRecipient(agentId, eventSequence, recipientId);
		}
		int pendingCount() {
			synchronized (lifecycleLock) {
				return queue.pendingCount() + urgent.size();
			}
		}
		int retainedCount() { return published.retainedCount(); }
		boolean hasActiveSession() {
			synchronized (lifecycleLock) {
				return activeSession != null;
			}
		}

		Result publish(AgentId agentId, Object sourceSession, JsonObject observation, Writer writer) {
			return publish(agentId, sourceSession, observation, writer, false);
		}

		Result publish(AgentId agentId, Object sourceSession, JsonObject observation, Writer writer, boolean allowUnchanged) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(sourceSession, "sourceSession must not be null");
			Objects.requireNonNull(observation, "observation must not be null");
			Objects.requireNonNull(writer, "writer must not be null");
			synchronized (lifecycleLock) {
				if (activeSession != sourceSession) return Result.STALE_SESSION;
				long eventSequence = sequences.incrementAndGet();
				long observedAtEpochMs = observation.get("observedAtEpochMs").getAsLong();
				AttentionFactDelta delta = published.delta(agentId, observation, eventSequence, observedAtEpochMs);
				if (!allowUnchanged && published.hasDelivered(agentId) && !delta.attention()) return Result.SUPPRESSED;
				JsonObject delivery = observation.deepCopy();
				attachDelta(delivery, delta);
				delivery = fitter.apply(agentId, delivery).observation();
				delta = published.delta(agentId, delivery, eventSequence, observedAtEpochMs);
				attachDelta(delivery, delta);
				delivery = fitter.apply(agentId, delivery).observation();
				attachDelta(delivery, published.delta(agentId, delivery, eventSequence, observedAtEpochMs));
				if (!writer.send(agentId, delivery)) return Result.DELIVERY_RETRY;
				published.commit(agentId, delivery);
				return Result.COMMITTED;
			}
		}

		private static void attachDelta(JsonObject observation, AttentionFactDelta delta) {
			observation.addProperty("eventSequence", delta.eventSequence());
			observation.addProperty("attention", delta.attention());
			JsonArray changedFacts = new JsonArray();
			delta.changedFacts().forEach(changedFacts::add);
			observation.add("changedFacts", changedFacts);
		}

		private void clearLocked() {
			queue.clear();
			urgent.clear();
			heartbeatPending.clear();
			heartbeatCursor = 0;
			published.clear();
		}
	}

	/** Retains only successfully delivered baselines and a bounded retry marker. */
	public static final class PublishedObservationState {
		private final int retryCapacity;
		private final Map<AgentId, JsonObject> delivered = new HashMap<>();
		private final Set<AgentId> dirty = new HashSet<>();
		private final Set<AgentId> forcedAttention = new HashSet<>();
		private final Map<AgentId, ObservationTargetHistory> targetHistory = new java.util.LinkedHashMap<>();
		private final Map<AgentId, java.util.LinkedHashSet<String>> conversationSources = new java.util.LinkedHashMap<>();

		public PublishedObservationState(int retryCapacity) {
			if (retryCapacity < 1) throw new IllegalArgumentException("retryCapacity must be positive");
			this.retryCapacity = retryCapacity;
		}

		public synchronized AttentionFactDelta delta(AgentId agentId, JsonObject current, long eventSequence, long observedAtEpochMs) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			AttentionFactDelta delta = AttentionFactDelta.between(delivered.get(agentId), current, eventSequence, observedAtEpochMs);
			return forcedAttention.contains(agentId) && !delta.attention()
					? new AttentionFactDelta(delta.eventSequence(), true, delta.changedFacts(), delta.observedAtEpochMs())
					: delta;
		}

		public synchronized void commit(AgentId agentId, JsonObject deliveredObservation) {
			delivered.put(agentId, deliveredObservation.deepCopy());
			if (deliveredObservation.has("eventSequence") && deliveredObservation.get("eventSequence").isJsonPrimitive()
					&& deliveredObservation.get("eventSequence").getAsJsonPrimitive().isNumber()) {
				long eventSequence = deliveredObservation.get("eventSequence").getAsLong();
				if (!targetHistory.containsKey(agentId) && targetHistory.size() >= AgentConstants.DEFAULT_AGENT_LIMIT) {
					targetHistory.remove(targetHistory.keySet().iterator().next());
				}
				targetHistory.computeIfAbsent(agentId, ignored -> new ObservationTargetHistory())
						.retain(eventSequence, observedTargetIds(deliveredObservation));
			}
			dirty.remove(agentId);
			forcedAttention.remove(agentId);
		}

		public synchronized boolean hasDelivered(AgentId agentId) {
			return delivered.containsKey(Objects.requireNonNull(agentId, "agentId must not be null"));
		}

		/** Requires a target id to be present in the exact bounded observation selected by provenance. */
		public synchronized void requireObservedTarget(AgentId agentId, long eventSequence, String targetId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(targetId, "targetId must not be null");
			ObservationTargetHistory history = targetHistory.get(agentId);
			if (history == null || !history.contains(eventSequence)) {
				if (history != null && history.isOlderThanRetained(eventSequence)) {
					throw new AgentDomainException("STALE_FACTS", "Action facts event sequence is no longer retained");
				}
				throw new AgentDomainException("TARGET_NOT_OBSERVED", "Target was not present in the delivered observation");
			}
			if (!history.targets(eventSequence).contains(targetId)) {
				throw new AgentDomainException("TARGET_NOT_OBSERVED", "Target was not present in the delivered observation");
			}
		}

		public synchronized boolean markDirty(AgentId agentId) {
			if (dirty.contains(agentId)) return true;
			if (dirty.size() >= retryCapacity) return false;
			dirty.add(agentId);
			return true;
		}

		/** Allows direct replies to authenticated player messages even after the sender leaves view. */
		public synchronized void retainConversationSource(AgentId agentId, String sourceId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			UUID.fromString(Objects.requireNonNull(sourceId, "sourceId must not be null"));
			if (!conversationSources.containsKey(agentId)
					&& conversationSources.size() >= AgentConstants.DEFAULT_AGENT_LIMIT) {
				conversationSources.remove(conversationSources.keySet().iterator().next());
			}
			java.util.LinkedHashSet<String> sources = conversationSources.computeIfAbsent(
					agentId, ignored -> new java.util.LinkedHashSet<>()
			);
			sources.remove(sourceId);
			sources.add(sourceId);
			while (sources.size() > MAX_CONVERSATION_SOURCES_PER_AGENT) {
				sources.remove(sources.iterator().next());
			}
		}

		public synchronized void requireDirectMessageRecipient(AgentId agentId, long eventSequence, String recipientId) {
			Set<String> trustedSources = conversationSources.get(agentId);
			if (trustedSources != null && trustedSources.contains(recipientId)) return;
			requireObservedTarget(agentId, eventSequence, recipientId);
		}

		public synchronized boolean markAttention(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			if (forcedAttention.contains(agentId)) return true;
			if (forcedAttention.size() >= retryCapacity) return false;
			forcedAttention.add(agentId);
			return true;
		}

		public synchronized void remove(AgentId agentId) {
			delivered.remove(agentId);
			dirty.remove(agentId);
			forcedAttention.remove(agentId);
			targetHistory.remove(agentId);
			conversationSources.remove(agentId);
		}

		public synchronized int retainedCount() {
			return delivered.size() + dirty.size();
		}

		public synchronized void clear() {
			delivered.clear();
			dirty.clear();
			forcedAttention.clear();
			targetHistory.clear();
			conversationSources.clear();
		}

		private static Set<String> observedTargetIds(JsonObject observation) {
			if (!observation.has("entities") || !observation.get("entities").isJsonArray()) return Set.of();
			HashSet<String> result = new HashSet<>();
			for (var element : observation.getAsJsonArray("entities")) {
				if (!element.isJsonObject()) continue;
				JsonObject entity = element.getAsJsonObject();
				String targetId = null;
				if (entity.has("uuid") && entity.get("uuid").isJsonPrimitive() && entity.get("uuid").getAsJsonPrimitive().isString()) {
					targetId = entity.get("uuid").getAsString();
				} else if (entity.has("stableId") && entity.get("stableId").isJsonPrimitive() && entity.get("stableId").getAsJsonPrimitive().isString()) {
					targetId = entity.get("stableId").getAsString();
				}
				if (targetId != null && result.size() < MAX_TARGET_IDS_PER_OBSERVATION) result.add(targetId);
			}
			return Set.copyOf(result);
		}

		private static final class ObservationTargetHistory {
			private final java.util.LinkedHashMap<Long, Set<String>> observations = new java.util.LinkedHashMap<>();
			private Set<String> lastTargets = Set.of();
			private boolean hasLastTargets;

			void retain(long eventSequence, Set<String> targetIds) {
				if (!hasLastTargets || !lastTargets.equals(targetIds)) {
					lastTargets = Set.copyOf(targetIds);
					hasLastTargets = true;
				}
				observations.put(eventSequence, lastTargets);
				while (observations.size() > OBSERVATION_HISTORY_CAPACITY) {
					observations.remove(observations.keySet().iterator().next());
				}
			}

			boolean contains(long eventSequence) { return observations.containsKey(eventSequence); }
			Set<String> targets(long eventSequence) { return observations.getOrDefault(eventSequence, Set.of()); }
			boolean isOlderThanRetained(long eventSequence) {
				return !observations.isEmpty() && eventSequence < observations.keySet().iterator().next();
			}
		}
	}

	private static void requireKeys(JsonObject object, Set<String> expected, String field) {
		if (!object.keySet().equals(expected)) throw new BridgeProtocolException("INVALID_FIELD", field);
	}

	private static long requiredLong(JsonObject object, String field) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON number");
		}
		try {
			long value = object.get(field).getAsBigDecimal().longValueExact();
			if (value < 0L || value > ActionProvenance.MAX_SAFE_INTEGER) throw new ArithmeticException();
			return value;
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_GOAL_REVISION", field + " must be a nonnegative safe integer", exception);
		}
	}

	private static long requiredSafeLong(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		try {
			long value = object.get(field).getAsBigDecimal().longValueExact();
			if (value < 0L || value > ActionProvenance.MAX_SAFE_INTEGER) throw new ArithmeticException();
			return value;
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_PROVENANCE", "provenance." + field + " must be a nonnegative safe integer");
		}
	}

	private final class Session implements AutoCloseable {
		private final Socket socket;
		private final ArrayBlockingQueue<BridgeEnvelope> outbound = new ArrayBlockingQueue<>(CONNECTION_QUEUE_CAP);
		private final Map<String, Integer> queuedByAgent = new HashMap<>();
		private final Set<String> inboundIds = new java.util.LinkedHashSet<>();
		private final AtomicBoolean open = new AtomicBoolean(true);
		private final AtomicBoolean authenticated = new AtomicBoolean();
		private volatile Thread readerThread;
		private volatile Thread writerThread;

		Session(Socket socket) throws IOException {
			this.socket = socket;
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
		}

		void start() {
			readerThread = Thread.ofPlatform().daemon().name("arenaagents-v2-reader").start(this::readLoop);
			writerThread = Thread.ofPlatform().daemon().name("arenaagents-v2-writer").start(this::writeLoop);
		}

		synchronized void completeHandshake(List<BridgeEnvelope> envelopes) {
			if (!open.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during authentication");
			if (authenticated.get()) throw new BridgeProtocolException("DUPLICATE_HANDSHAKE", "Bridge session is already authenticated");
			List<BridgeEnvelope> ordered = List.copyOf(Objects.requireNonNull(envelopes, "envelopes must not be null"));
			if (ordered.isEmpty() || !"hello_ack".equals(ordered.getFirst().type())) {
				throw new IllegalArgumentException("handshake must begin with hello_ack");
			}
			if (outbound.remainingCapacity() < ordered.size()) {
				throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot publish handshake replay batch");
			}
			Map<String, Integer> additions = new HashMap<>();
			for (BridgeEnvelope envelope : ordered) {
				int added = additions.merge(envelope.agentId(), 1, Integer::sum);
				if (queuedByAgent.getOrDefault(envelope.agentId(), 0) + added > AGENT_QUEUE_CAP) {
					throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
				}
			}
			authenticated.set(true);
			for (BridgeEnvelope envelope : ordered) {
				if (!outbound.offer(envelope)) throw new IllegalStateException("preflighted handshake queue rejected an envelope");
				queuedByAgent.merge(envelope.agentId(), 1, Integer::sum);
			}
		}

		synchronized void enqueue(BridgeEnvelope envelope) {
			if (!open.get() || !authenticated.get()) {
				throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before publication");
			}
			int agentQueued = queuedByAgent.getOrDefault(envelope.agentId(), 0);
			if (agentQueued >= AGENT_QUEUE_CAP) throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
			if (!outbound.offer(envelope)) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue is full");
			queuedByAgent.put(envelope.agentId(), agentQueued + 1);
		}

		synchronized void enqueuePair(BridgeEnvelope first, BridgeEnvelope second, Runnable beforeEnqueue) {
			if (!open.get() || !authenticated.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before paired publication");
			if (!first.agentId().equals(second.agentId())) throw new IllegalArgumentException("paired envelopes must belong to one agent");
			int agentQueued = queuedByAgent.getOrDefault(first.agentId(), 0);
			if (agentQueued > AGENT_QUEUE_CAP - 2) throw new BridgeProtocolException("AGENT_BACKPRESSURE", first.agentId());
			if (outbound.remainingCapacity() < 2) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot atomically publish paired messages");
			beforeEnqueue.run();
			if (!open.get() || !authenticated.get()) {
				throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during paired publication");
			}
			if (!outbound.offer(first) || !outbound.offer(second)) {
				outbound.remove(first);
				outbound.remove(second);
				throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Atomic paired publication failed");
			}
			queuedByAgent.put(first.agentId(), agentQueued + 2);
		}

		synchronized void enqueueAtomically(BridgeEnvelope envelope, Runnable beforeEnqueue) {
			if (!open.get() || !authenticated.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before atomic publication");
			int agentQueued = queuedByAgent.getOrDefault(envelope.agentId(), 0);
			if (agentQueued >= AGENT_QUEUE_CAP) throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
			if (outbound.remainingCapacity() < 1) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot publish transaction");
			beforeEnqueue.run();
			if (!open.get() || !authenticated.get()) {
				throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during atomic publication");
			}
			if (!outbound.offer(envelope)) {
				throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Atomic publication failed");
			}
			queuedByAgent.put(envelope.agentId(), agentQueued + 1);
		}

		private void readLoop() {
			try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
				while (open.get()) {
					String line = readLine(input);
					if (line == null) break;
					BridgeEnvelope envelope = codec.decode(line);
					synchronized (this) {
						if (!inboundIds.add(envelope.messageId())) throw new BridgeProtocolException("DUPLICATE_MESSAGE", envelope.messageId());
						while (inboundIds.size() > MAX_TRACKED_IDS) inboundIds.remove(inboundIds.iterator().next());
					}
					accept(envelope, this);
					if (authenticated.get()) socket.setSoTimeout(0);
				}
			} catch (SocketTimeoutException exception) {
				LOGGER.warn("Codex bridge authentication timed out");
			} catch (RuntimeException | IOException exception) {
				if (open.get()) LOGGER.warn("Codex bridge session closed: {}", exception.getMessage());
			} finally {
				close();
			}
		}

		private void writeLoop() {
			try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
				while (open.get()) {
					BridgeEnvelope envelope = outbound.take();
					byte[] bytes = codec.encode(envelope).getBytes(StandardCharsets.UTF_8);
					output.write(bytes);
					output.flush();
					synchronized (this) {
						queuedByAgent.computeIfPresent(envelope.agentId(), (id, count) -> count <= 1 ? null : count - 1);
					}
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (IOException exception) {
				if (open.get()) LOGGER.warn("Codex bridge writer failed: {}", exception.getMessage());
			} finally {
				close();
			}
		}

		@Override
		public void close() {
			boolean wasAuthenticated;
			synchronized (this) {
				if (!open.compareAndSet(true, false)) return;
				wasAuthenticated = authenticated.get();
			}
			interruptPeer(readerThread);
			interruptPeer(writerThread);
			try { socket.close(); } catch (IOException ignored) { }
			synchronized (publicationLock) {
				if (session != this) return;
				verboseState.clearActivity();
				MultiplexedServerBridge.onSessionClosed(observationPublication, this);
				protocolKnownAgentIds.clear();
				coordinatorReadyAgentIds.clear();
				catalogProfiles = Set.of();
				catalogModels = AgentControlCatalog.fallbackOptions();
				catalogLoaded = false;
				CoordinatorStatusStore.clear(manager.server());
				if (wasAuthenticated) coordinatorDisconnectPending.set(true);
				session = null;
			}
		}

		private static void interruptPeer(Thread thread) {
			if (thread != null && thread != Thread.currentThread()) thread.interrupt();
		}
	}

	private static String readLine(BufferedInputStream input) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (true) {
			int value = input.read();
			if (value < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
			if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
			if (value != '\r') bytes.write(value);
			if (bytes.size() > BridgeEnvelopeCodec.MAX_LINE_BYTES) throw new BridgeProtocolException("LINE_TOO_LARGE", "Inbound line exceeds limit");
		}
	}
}
