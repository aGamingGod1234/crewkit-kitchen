package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
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
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import dev.agaminggod.arenaagents.server.AgentRuntimeHooks;
import dev.agaminggod.arenaagents.server.AgentRuntimeRouter;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiplexedServerBridge implements AgentRuntimeHooks, AutoCloseable {
	public static final String LOOPBACK_HOST = "127.0.0.1";
	public static final int DEFAULT_PORT = 25_570;
	public static final int CONNECTION_QUEUE_CAP = 256;
	public static final int AGENT_QUEUE_CAP = 32;
	private static final int OBSERVATIONS_PER_TICK = 8;
	private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
	private static final int MIN_SECRET_LENGTH = 32;
	private static final int MAX_SECRET_LENGTH = 512;
	private static final int MAX_TRACKED_IDS = 4_096;
	private static final Logger LOGGER = LoggerFactory.getLogger(MultiplexedServerBridge.class);
	private static final Set<String> INBOUND_TYPES = Set.of(
			"hello", "catalog_snapshot", "coordinator_status", "agent_ready", "planning_state", "action_command", "action_cancel", "agent_error", "heartbeat"
	);

	private final CodexAgentManager manager;
	private final AgentRuntimeRouter router;
	private final ServerActionExecutor actionExecutor;
	private final ServerObservationCollector observations;
	private final ObservationDispatchQueue<AgentId> observationQueue =
			new ObservationDispatchQueue<>(AgentConstants.DEFAULT_AGENT_LIMIT, OBSERVATIONS_PER_TICK);
	private final BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
	private final String serverInstanceId = UUID.randomUUID().toString();
	private final String secret;
	private final int port;
	private final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong messageIds = new AtomicLong();
	private final ProgramActionLedger programActions = new ProgramActionLedger();
	private volatile Session session;
	private volatile ServerSocket serverSocket;
	private volatile Set<String> catalogProfiles = Set.of();
	private volatile List<AgentControlModelOption> catalogModels = AgentControlCatalog.fallbackOptions();
	private volatile boolean catalogLoaded;

	public MultiplexedServerBridge(CodexAgentManager manager) {
		this(manager, DEFAULT_PORT, configuredSecretPath());
	}

	public MultiplexedServerBridge(CodexAgentManager manager, int port, Path secretPath) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.router = new AgentRuntimeRouter(manager);
		this.port = port;
		this.secret = readSecret(secretPath);
		this.actionExecutor = new ServerActionExecutor(manager, this::sendActionResult, this::sendActionProgress);
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
		Runnable task;
		while ((task = serverTasks.poll()) != null) {
			try {
				task.run();
			} catch (RuntimeException exception) {
				LOGGER.error("Codex bridge server task failed", exception);
			}
		}
		actionExecutor.tick();
		for (AgentId agentId : observations.changedActiveAgents()) {
			queueObservation(agentId);
		}
		observationQueue.drain(this::sendObservation);
	}

	public boolean authenticated() {
		Session active = session;
		return active != null && active.authenticated.get();
	}

	public List<AgentControlModelOption> catalogModels() {
		return catalogModels;
	}

	@Override
	public void validateProfile(AgentProfile profile) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", "AI agent coordinator is not authenticated");
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
	public void onCreated(AgentRecord record) {
		send("agent_registered", record.agentId().toString(), registeredPayload(record));
	}

	@Override
	public void onTransition(AgentTransition transition) {
		ScenarioRuntimeService.onAgentState(
				manager.server(),
				transition.after().agentId().toString(),
				publicState(transition.after().state())
		);
		if (transition.cancelAction()) {
			actionExecutor.cancel(transition.after().agentId(), "Lifecycle changed to " + transition.after().state());
		}
		if (!authenticated()) {
			if (transition.after().state().isActive()) {
				serverTasks.add(this::disconnectActiveAgents);
			}
			return;
		}
		String operation = operation(transition);
		if (operation == null) {
			return;
		}
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
		send("goal_control", transition.after().agentId().toString(), payload);
	}

	@Override
	public void onRemoved(AgentId agentId, long terminalRevision) {
		actionExecutor.cancel(agentId, "Agent removed");
		programActions.remove(agentId);
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", terminalRevision);
		send("agent_removed", agentId.toString(), payload);
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
				if (!socket.getInetAddress().isLoopbackAddress() || session != null) {
					socket.close();
					continue;
				}
				Session accepted = new Session(socket);
				session = accepted;
				accepted.start();
			} catch (IOException exception) {
				if (running.get()) {
					LOGGER.error("Codex bridge accept failed", exception);
				}
			}
		}
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
		serverTasks.add(() -> {
			if (session != source || !source.open.get() || !source.authenticated.get()) return;
			routeAuthenticated(envelope);
		});
	}

	private void acceptHello(BridgeEnvelope envelope, Session source) {
		if (!"hello".equals(envelope.type()) || !"server".equals(envelope.agentId())) {
			throw new BridgeProtocolException("HANDSHAKE_REQUIRED", "hello must be the first coordinator message");
		}
		String supplied = requiredString(envelope.payload(), "secret");
		if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
			throw new BridgeProtocolException("AUTHENTICATION_FAILED", "Bridge secret did not match");
		}
		source.authenticated.set(true);
		catalogProfiles = Set.of();
		catalogModels = AgentControlCatalog.fallbackOptions();
		catalogLoaded = false;
		JsonObject payload = new JsonObject();
		payload.addProperty("replyTo", envelope.messageId());
		payload.addProperty("authenticated", true);
		JsonArray registry = new JsonArray();
		for (AgentRecord record : manager.records()) {
			registry.add(registeredPayload(record));
		}
		payload.add("registry", registry);
		send("hello_ack", "server", payload);
	}

	private void routeAuthenticated(BridgeEnvelope envelope) {
		switch (envelope.type()) {
			case "catalog_snapshot" -> acceptCatalog(envelope.payload());
			case "coordinator_status" -> acceptCoordinatorStatus(envelope.payload());
			case "agent_ready", "planning_state" -> plannerReady(envelope);
			case "action_command" -> acceptAction(envelope);
			case "action_cancel" -> acceptActionCancel(envelope);
			case "agent_error" -> acceptAgentError(envelope);
			case "heartbeat" -> send("heartbeat", "server", new JsonObject());
			default -> throw new BridgeProtocolException("UNKNOWN_MESSAGE_TYPE", envelope.type());
		}
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
		requireKeys(scheduler, Set.of("active", "pending", "maxConcurrent", "maxPending", "warning"), "scheduler");
		CoordinatorStatusSnapshot.SchedulerStatus schedulerStatus = new CoordinatorStatusSnapshot.SchedulerStatus(
				requiredInt(scheduler, "active"), requiredInt(scheduler, "pending"),
				requiredInt(scheduler, "maxConcurrent"), requiredInt(scheduler, "maxPending"), requiredBoolean(scheduler, "warning")
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
					requiredInt(latency, "p50Ms"), requiredInt(latency, "p95Ms")
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
		String message = requiredString(envelope.payload(), "message");
		AgentTransition transition = router.plannerFailed(agentId, goalRevision, message);
		AgentChatReporter.failed(manager, transition.after(), message);
	}

	private void disconnectActiveAgents() {
		for (AgentTransition transition : router.coordinatorDisconnected()) {
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
		AgentRecord record = manager.registry().require(id);
		long revision = requiredLong(envelope.payload(), "goalRevision");
		if (revision != record.goalRevision()) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator planning revision is stale");
		}
		if (record.state() == AgentLifecycleState.STARTING) {
			router.plannerStarted(id);
		}
		if ("agent_ready".equals(envelope.type())) {
			queueObservation(id);
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
		JsonObject validatedArguments = ProtocolCodec.validateActionArguments(actionType, arguments);
		return new ServerActionRequest(
				agentId,
				requiredLong(payload, "goalRevision"),
				requiredString(payload, "actionId"),
				actionType,
				validatedArguments,
				decodeActionProvenance(payload)
		);
	}

	private static void requireActionCommandKeys(JsonObject payload) {
		Set<String> expected = Set.of("goalRevision", "actionId", "actionType", "arguments", "provenance");
		for (String field : expected) if (!payload.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		for (String field : payload.keySet()) if (!expected.contains(field)) throw new BridgeProtocolException("INVALID_FIELD", "action_command");
	}

	private boolean sendRejectedAction(BridgeEnvelope envelope, RuntimeException exception) {
		JsonObject payload = envelope.payload();
		if (!payload.has("goalRevision") || !payload.has("actionId") || !payload.has("actionType")
				|| !payload.get("goalRevision").isJsonPrimitive() || !payload.get("goalRevision").getAsJsonPrimitive().isNumber()
				|| !payload.get("actionId").isJsonPrimitive() || !payload.get("actionId").getAsJsonPrimitive().isString()
				|| !payload.get("actionType").isJsonPrimitive() || !payload.get("actionType").getAsJsonPrimitive().isString()) return false;
		long goalRevision = payload.get("goalRevision").getAsLong();
		String actionId = payload.get("actionId").getAsString();
		String actionType = payload.get("actionType").getAsString();
		if (goalRevision < 0L || actionId.isBlank() || actionType.isBlank()) return false;
		JsonObject result = new JsonObject();
		result.addProperty("goalRevision", goalRevision);
		result.addProperty("actionId", actionId);
		result.addProperty("commandId", actionId);
		result.addProperty("actionType", actionType);
		result.addProperty("state", "FAILED");
		result.addProperty("reasonCode", exception instanceof BridgeProtocolException protocol ? protocol.code() : ((AgentDomainException) exception).code());
		result.addProperty("message", exception.getMessage() == null ? "Action rejected" : exception.getMessage());
		result.addProperty("elapsedMs", 0L);
		result.addProperty("observedAtEpochMs", System.currentTimeMillis());
		send("action_result", envelope.agentId(), result);
		return true;
	}

	private void validateActionProvenance(ServerActionRequest request) {
		AgentRecord record = manager.registry().require(request.agentId());
		if (!record.acceptsRevision(request.goalRevision())) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator action revision is stale");
		}
		ActionProvenance provenance = request.provenance();
		AgentProfile profile = record.profile();
		if (!profile.provider().equals(provenance.provider()) || !profile.model().equals(provenance.model())
				|| !profile.reasoning().equals(provenance.reasoningEffort()) || !profile.serviceTier().equals(provenance.serviceTier())) {
			throw new AgentDomainException("STALE_PROVENANCE", "Action provenance does not match the selected model profile");
		}
		programActions.accept(request);
	}

	private static ActionProvenance decodeActionProvenance(JsonObject payload) {
		JsonObject provenance = requiredObject(payload, "provenance");
		requireKeys(provenance, Set.of(
				"provider", "model", "reasoningEffort", "serviceTier", "programId", "programVersion", "sourceStepId", "eventSequence"
		), "provenance");
		try {
			return new ActionProvenance(
					requiredProvenanceString(provenance, "provider"),
					requiredProvenanceString(provenance, "model"),
					requiredProvenanceString(provenance, "reasoningEffort"),
					requiredProvenanceString(provenance, "serviceTier"),
					requiredProvenanceString(provenance, "programId"),
					requiredSafeLong(provenance, "programVersion"),
					requiredProvenanceString(provenance, "sourceStepId"),
					requiredSafeLong(provenance, "eventSequence")
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
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", result.goalRevision());
		payload.addProperty("actionId", result.actionId());
		payload.addProperty("commandId", result.actionId());
		payload.addProperty("actionType", result.actionType().wireName());
		payload.addProperty("state", result.state().name());
		payload.addProperty("reasonCode", result.reasonCode());
		payload.addProperty("message", result.message());
		payload.addProperty("elapsedMs", result.elapsedMs());
		payload.addProperty("observedAtEpochMs", result.observedAtEpochMs());
		send("action_result", result.agentId().toString(), payload);
		if (result.actionType() != ActionType.COMPLETE_GOAL) {
			queueObservation(result.agentId());
		}
	}

	private void sendActionProgress(ServerActionProgress progress) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", progress.goalRevision());
		payload.addProperty("actionId", progress.actionId());
		payload.addProperty("commandId", progress.actionId());
		payload.addProperty("actionType", progress.actionType().wireName());
		payload.addProperty("state", "RUNNING");
		payload.addProperty("progress", progress.progress());
		payload.addProperty("elapsedMs", progress.elapsedMs());
		payload.addProperty("observedAtEpochMs", progress.observedAtEpochMs());
		send("action_progress", progress.agentId().toString(), payload);
		queueObservation(progress.agentId());
	}

	private void queueObservation(AgentId agentId) {
		observationQueue.offer(agentId);
	}

	private void sendObservation(AgentId agentId) {
		if (!authenticated()) return;
		try {
			send("observation", agentId.toString(), observations.collect(agentId));
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not collect observation for {}: {}", agentId, exception.getMessage());
		}
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
		if (transition.before().state() == AgentLifecycleState.PAUSED
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
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value) || value != Math.rint(value) || value < 0.0D || value > ActionProvenance.MAX_SAFE_INTEGER) {
			throw new BridgeProtocolException("INVALID_PROVENANCE", "provenance." + field + " must be a nonnegative safe integer");
		}
		return (long) value;
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

		synchronized void enqueue(BridgeEnvelope envelope) {
			int agentQueued = queuedByAgent.getOrDefault(envelope.agentId(), 0);
			if (agentQueued >= AGENT_QUEUE_CAP) throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
			if (!outbound.offer(envelope)) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue is full");
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
			if (!open.compareAndSet(true, false)) return;
			boolean wasAuthenticated = authenticated.get();
			interruptPeer(readerThread);
			interruptPeer(writerThread);
			try { socket.close(); } catch (IOException ignored) { }
			if (session == this) {
				session = null;
				catalogProfiles = Set.of();
				catalogModels = AgentControlCatalog.fallbackOptions();
				catalogLoaded = false;
				CoordinatorStatusStore.clear(manager.server());
			}
			if (wasAuthenticated) serverTasks.add(MultiplexedServerBridge.this::disconnectActiveAgents);
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
