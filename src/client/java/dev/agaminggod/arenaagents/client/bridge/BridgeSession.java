package dev.agaminggod.arenaagents.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeSession implements AutoCloseable {
	public static final int OUTBOUND_QUEUE_CAPACITY = 256;
	public static final int MAX_MESSAGE_IDS_PER_SESSION = 4_096;
	public static final int AUTHENTICATION_TIMEOUT_MS = 1_000;

	private static final long WRITER_POLL_MS = 100L;
	private static final long THREAD_JOIN_MS = 2_000L;
	private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
	private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
	private static final String FIELD_AGENT_ID = "agentId";
	private static final String FIELD_TYPE = "type";
	private static final String FIELD_MESSAGE_ID = "messageId";
	private static final String FIELD_COMMAND = "command";
	private static final String FIELD_CHALLENGE = "challenge";
	private static final String FIELD_NONCE = "nonce";
	private static final String FIELD_PROOF = "proof";
	private static final String FIELD_REPLY_TO = "replyTo";
	private static final String GENERATED_MESSAGE_ID_PREFIX = "server-";
	private static final String MAXIMUM_GENERATED_MESSAGE_ID = GENERATED_MESSAGE_ID_PREFIX
			+ "9".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH - GENERATED_MESSAGE_ID_PREFIX.length());
	private static final List<String> ENVELOPE_FIELDS = List.of(
			FIELD_PROTOCOL_VERSION,
			FIELD_AGENT_ID,
			FIELD_TYPE,
			FIELD_MESSAGE_ID
	);
	private static final Set<String> OUTBOUND_EVENT_TYPES = Set.of(
			"goal_event",
			"observation",
			"action_progress",
			"action_result",
			"significant_event",
			"error"
	);

	private final AgentConfig config;
	private final Socket socket;
	private final ProtocolCodec codec;
	private final Executor callbackExecutor;
	private final BridgeEventSink eventSink;
	private final Runnable closedCallback;
	private final InputStream input;
	private final OutputStream output;
	private final BlockingQueue<String> outbound;
	private final String bridgeNonce = BridgeAuthentication.newNonce();
	private final Set<String> inboundMessageIds = new HashSet<>();
	private final AtomicBoolean closeStarted = new AtomicBoolean();
	private final AtomicLong outboundSequence = new AtomicLong();
	private final CountDownLatch closeCompleted = new CountDownLatch(1);
	private final Object callbackLock = new Object();
	private final Object lifecycleLock = new Object();
	private final Object outputLock = new Object();
	private final long sessionId = NEXT_SESSION_ID.incrementAndGet();

	private volatile SessionState state = SessionState.AWAITING_RESPONSE;
	private boolean started;
	private String challengeMessageId;
	private volatile Thread readerThread;
	private volatile Thread writerThread;

	BridgeSession(
			AgentConfig config,
			Socket socket,
			ProtocolCodec codec,
			Executor callbackExecutor,
			BridgeEventSink eventSink,
			Runnable closedCallback
	) throws IOException {
		this(
				config,
				socket,
				codec,
				callbackExecutor,
				eventSink,
				closedCallback,
				new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY)
		);
	}

	BridgeSession(
			AgentConfig config,
			Socket socket,
			ProtocolCodec codec,
			Executor callbackExecutor,
			BridgeEventSink eventSink,
			Runnable closedCallback,
			BlockingQueue<String> outbound
	) throws IOException {
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.socket = Objects.requireNonNull(socket, "socket must not be null");
		this.codec = Objects.requireNonNull(codec, "codec must not be null");
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
		this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback must not be null");
		this.outbound = Objects.requireNonNull(outbound, "outbound must not be null");
		validateLoopbackPeer(socket);
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(AUTHENTICATION_TIMEOUT_MS);
		this.input = socket.getInputStream();
		this.output = socket.getOutputStream();
	}

	void start() {
		synchronized (lifecycleLock) {
			if (state == SessionState.CLOSED) {
				throw new IllegalStateException("Cannot start a closed bridge session");
			}
			if (started) {
				return;
			}
			started = true;
			String challenge = codec.encode(createChallenge());
			if (!outbound.offer(challenge)) {
				close();
				throw outboundQueueFull();
			}
			writerThread = createDaemon("writer", this::writerLoop);
			readerThread = createDaemon("reader", this::readerLoop);
			writerThread.start();
			readerThread.start();
		}
	}

	boolean isOpen() {
		return state != SessionState.CLOSED;
	}

	boolean isAuthenticated() {
		return state == SessionState.AUTHENTICATED;
	}

	String sendEvent(String type, JsonObject payload) {
		if (!isAuthenticated()) {
			throw new ProtocolException("NO_AUTHENTICATED_SESSION", "No authenticated coordinator session is active");
		}
		validateOutboundEventType(type);
		String messageId = nextOutboundMessageId();
		JsonObject event = createEvent(config, type, messageId, payload);
		String encoded = codec.encode(event);
		boolean queued;
		synchronized (lifecycleLock) {
			if (state != SessionState.AUTHENTICATED) {
				throw new ProtocolException(
						"NO_AUTHENTICATED_SESSION",
						"No authenticated coordinator session is active"
				);
			}
			queued = outbound.offer(encoded);
		}
		if (!queued) {
			close();
			throw outboundQueueFull();
		}
		return messageId;
	}

	static int encodedEventBytesAtMaximumEnvelope(
			AgentConfig config,
			ProtocolCodec codec,
			String type,
			JsonObject payload
	) {
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(codec, "codec must not be null");
		validateOutboundEventType(type);
		String encoded = codec.encode(createEvent(config, type, MAXIMUM_GENERATED_MESSAGE_ID, payload));
		return encoded.getBytes(StandardCharsets.UTF_8).length;
	}

	static void rejectAdditionalSession(Socket socket, AgentConfig config, ProtocolCodec codec) {
		try (socket) {
			validateLoopbackPeer(socket);
			JsonObject error = createEnvelope(config, "error", "server-reject-1");
			error.addProperty("code", "SESSION_ACTIVE");
			error.addProperty("message", "Only one coordinator session may be active");
			codec.writeLine(socket.getOutputStream(), codec.encode(error));
		} catch (IOException | RuntimeException ignored) {
			// The peer is already being rejected; there is no trusted secondary channel for this failure.
		}
	}

	private void readerLoop() {
		try {
			while (isOpen()) {
				String line = codec.readLine(input);
				if (line == null) {
					break;
				}
				handleMessage(line);
			}
		} catch (SocketTimeoutException exception) {
			if (!isAuthenticated()) {
				writeError(new ProtocolException(
						"AUTHENTICATION_TIMEOUT",
						"Bridge authentication response was not received within " + AUTHENTICATION_TIMEOUT_MS + " ms",
						exception
				));
			}
		} catch (ProtocolException exception) {
			writeError(exception);
		} catch (IOException exception) {
			if (isOpen()) {
				writeError(new ProtocolException("BRIDGE_IO", "Bridge input failed: " + exception.getMessage(), exception));
			}
		} finally {
			close();
		}
	}

	private void writerLoop() {
		try {
			while (isOpen() || !outbound.isEmpty()) {
				String message = outbound.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS);
				if (message != null) {
					writeNow(message);
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (IOException exception) {
			if (isOpen()) {
				close();
			}
		}
	}

	private void handleMessage(String line) {
		JsonObject message = parseObject(line);
		validateProtocolVersion(message);
		String agentId = requireBoundedString(message, FIELD_AGENT_ID, ProtocolConstants.MAX_COMMAND_ID_LENGTH);
		if (!config.agentId().equals(agentId)) {
			throw new ProtocolException("AGENT_ID_MISMATCH", "Message agentId does not match this bridge");
		}
		String type = requireBoundedString(message, FIELD_TYPE, ProtocolConstants.MAX_COMMAND_ID_LENGTH);
		String messageId = requireBoundedString(message, FIELD_MESSAGE_ID, ProtocolConstants.MAX_COMMAND_ID_LENGTH);
		rememberMessageId(messageId);

		if (!isAuthenticated() && !"hello_response".equals(type)) {
			throw new ProtocolException("AUTHENTICATION_REQUIRED", "Bridge authentication response is required");
		}

		switch (type) {
			case "hello_response" -> handleHelloResponse(message, messageId);
			case "action_command" -> handleActionCommand(message);
			case "cancel_action" -> handleCancelAction(message);
			case "request_observation" -> handleObservationRequest(message);
			case "shutdown" -> {
				validateFields(message, ENVELOPE_FIELDS);
				close();
			}
			default -> throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Unknown bridge message type '" + type + "'");
		}
	}

	private void handleHelloResponse(JsonObject message, String messageId) {
		validateFields(message, ENVELOPE_FIELDS, FIELD_CHALLENGE, FIELD_NONCE, FIELD_PROOF);
		String challenge = requireAuthenticationToken(message, FIELD_CHALLENGE, true);
		String coordinatorNonce = requireAuthenticationToken(message, FIELD_NONCE, true);
		String suppliedProof = requireAuthenticationToken(message, FIELD_PROOF, false);
		synchronized (lifecycleLock) {
			ensureAwaitingResponse();
			if (!bridgeNonce.equals(challenge)) {
				throw new ProtocolException("AUTHENTICATION_FAILED", "Bridge challenge was not accepted");
			}
			String expectedProof = BridgeAuthentication.coordinatorProof(
					config.bridgeSecret(),
					config.agentId(),
					challengeMessageId,
					messageId,
					bridgeNonce,
					coordinatorNonce
			);
			if (!BridgeAuthentication.proofsMatch(expectedProof, suppliedProof)) {
				throw new ProtocolException("AUTHENTICATION_FAILED", "Coordinator proof was not accepted");
			}
		}
		JsonObject acknowledgement = createEnvelope("hello_ack");
		acknowledgement.addProperty(FIELD_REPLY_TO, messageId);
		acknowledgement.addProperty(
				FIELD_PROOF,
				BridgeAuthentication.bridgeProof(
						config.bridgeSecret(),
						config.agentId(),
						challengeMessageId,
						messageId,
						bridgeNonce,
						coordinatorNonce
				)
		);
		String encoded = codec.encode(acknowledgement);
		resetReadTimeout();
		boolean queued;
		synchronized (lifecycleLock) {
			ensureAwaitingResponse();
			queued = outbound.offer(encoded);
			if (queued) {
				state = SessionState.AUTHENTICATED;
			}
		}
		if (!queued) {
			close();
			throw outboundQueueFull();
		}
	}

	private JsonObject createChallenge() {
		JsonObject challenge = createEnvelope("hello_challenge");
		challenge.addProperty(FIELD_NONCE, bridgeNonce);
		challengeMessageId = challenge.get(FIELD_MESSAGE_ID).getAsString();
		return challenge;
	}

	private void ensureAwaitingResponse() {
		if (state == SessionState.CLOSED) {
			throw new ProtocolException("SESSION_CLOSED", "Bridge session is closed");
		}
		if (state != SessionState.AWAITING_RESPONSE) {
			throw new ProtocolException("ALREADY_AUTHENTICATED", "hello_response is only valid before authentication");
		}
	}

	private void resetReadTimeout() {
		try {
			socket.setSoTimeout(0);
		} catch (SocketException exception) {
			throw new ProtocolException("BRIDGE_IO", "Could not reset bridge read timeout", exception);
		}
	}

	private void handleActionCommand(JsonObject message) {
		validateFields(message, ENVELOPE_FIELDS, FIELD_COMMAND);
		JsonElement commandElement = requireField(message, FIELD_COMMAND);
		if (!commandElement.isJsonObject()) {
			throw invalidField(FIELD_COMMAND, "an object");
		}
		ActionCommand command = codec.decodeCommand(commandElement.toString());
		try {
			callbackExecutor.execute(() -> dispatchAction(command));
		} catch (RuntimeException exception) {
			throw new ProtocolException("CALLBACK_REJECTED", "Client callback executor rejected action", exception);
		}
	}

	private void handleObservationRequest(JsonObject message) {
		validateFields(message, ENVELOPE_FIELDS);
		try {
			callbackExecutor.execute(this::dispatchObservationRequest);
		} catch (RuntimeException exception) {
			throw new ProtocolException("CALLBACK_REJECTED", "Client callback executor rejected observation", exception);
		}
	}

	private void handleCancelAction(JsonObject message) {
		validateFields(message, ENVELOPE_FIELDS, "commandId");
		String commandId = requireBoundedString(
				message,
				"commandId",
				ProtocolConstants.MAX_COMMAND_ID_LENGTH
		);
		try {
			callbackExecutor.execute(() -> dispatchCancel(commandId));
		} catch (RuntimeException exception) {
			throw new ProtocolException("CALLBACK_REJECTED", "Client callback executor rejected cancellation", exception);
		}
	}

	private void dispatchAction(ActionCommand command) {
		boolean callbackFailed = false;
		synchronized (callbackLock) {
			if (!isAuthenticated()) {
				return;
			}
			try {
				eventSink.onActionCommand(sessionId, command);
			} catch (RuntimeException ignored) {
				callbackFailed = true;
			}
		}
		if (callbackFailed) {
			close();
		}
	}

	private void dispatchObservationRequest() {
		boolean callbackFailed = false;
		synchronized (callbackLock) {
			if (!isAuthenticated()) {
				return;
			}
			try {
				eventSink.onObservationRequested(sessionId);
			} catch (RuntimeException ignored) {
				callbackFailed = true;
			}
		}
		if (callbackFailed) {
			close();
		}
	}

	private void dispatchCancel(String commandId) {
		boolean callbackFailed = false;
		synchronized (callbackLock) {
			if (!isAuthenticated()) {
				return;
			}
			try {
				eventSink.onCancelAction(sessionId, commandId);
			} catch (RuntimeException ignored) {
				callbackFailed = true;
			}
		}
		if (callbackFailed) {
			close();
		}
	}

	private void rememberMessageId(String messageId) {
		if (!inboundMessageIds.add(messageId)) {
			throw new ProtocolException("DUPLICATE_MESSAGE_ID", "Duplicate bridge messageId '" + messageId + "'");
		}
		if (inboundMessageIds.size() > MAX_MESSAGE_IDS_PER_SESSION) {
			throw new ProtocolException("SESSION_MESSAGE_LIMIT", "Bridge session message limit exceeded");
		}
	}

	private static ProtocolException outboundQueueFull() {
		return new ProtocolException("OUTBOUND_QUEUE_FULL", "Bridge outbound queue is full");
	}

	private void writeError(ProtocolException exception) {
		try {
			if (!isOpen()) {
				return;
			}
			JsonObject error = createEnvelope("error");
			error.addProperty("code", exception.code());
			error.addProperty("message", exception.getMessage());
			writeNow(codec.encode(error));
		} catch (IOException | RuntimeException ignored) {
			// Closing the failed socket is the only remaining safe response.
		}
	}

	private void writeNow(String message) throws IOException {
		synchronized (outputLock) {
			codec.writeLine(output, message);
		}
	}

	private Thread createDaemon(String role, Runnable task) {
		return Thread.ofPlatform()
				.name(BridgeServer.THREAD_NAME_PREFIX + role + "-" + config.agentId())
				.daemon(true)
				.unstarted(task);
	}

	private String nextOutboundMessageId() {
		while (true) {
			long current = outboundSequence.get();
			if (current == Long.MAX_VALUE) {
				throw new ProtocolException("MESSAGE_ID_EXHAUSTED", "Bridge outbound message ID space is exhausted");
			}
			long next = current + 1L;
			if (outboundSequence.compareAndSet(current, next)) {
				return GENERATED_MESSAGE_ID_PREFIX + next;
			}
		}
	}

	private JsonObject createEnvelope(String type) {
		return createEnvelope(config, type, nextOutboundMessageId());
	}

	private static JsonObject createEvent(AgentConfig config, String type, String messageId, JsonObject payload) {
		JsonObject event = createEnvelope(config, type, messageId);
		if (payload == null) {
			return event;
		}
		for (var entry : payload.entrySet()) {
			if (event.has(entry.getKey())) {
				throw new ProtocolException(
						ProtocolConstants.ERROR_UNKNOWN_FIELD,
						"Outbound payload must not replace envelope field '" + entry.getKey() + "'"
				);
			}
			event.add(entry.getKey(), entry.getValue().deepCopy());
		}
		return event;
	}

	private static void validateOutboundEventType(String type) {
		if (!OUTBOUND_EVENT_TYPES.contains(type)) {
			throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Unknown outbound bridge message type '" + type + "'");
		}
	}

	private static JsonObject createEnvelope(AgentConfig config, String type, String messageId) {
		validateEnvelopeText(type, FIELD_TYPE);
		validateEnvelopeText(messageId, FIELD_MESSAGE_ID);
		JsonObject envelope = new JsonObject();
		envelope.addProperty(FIELD_PROTOCOL_VERSION, ProtocolConstants.PROTOCOL_VERSION);
		envelope.addProperty(FIELD_AGENT_ID, config.agentId());
		envelope.addProperty(FIELD_TYPE, type);
		envelope.addProperty(FIELD_MESSAGE_ID, messageId);
		return envelope;
	}

	private static void validateEnvelopeText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw invalidField(field, "a nonblank string");
		}
		if (value.length() > ProtocolConstants.MAX_COMMAND_ID_LENGTH) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"Bridge field '" + field + "' must not exceed "
							+ ProtocolConstants.MAX_COMMAND_ID_LENGTH + " characters"
			);
		}
	}

	private static JsonObject parseObject(String json) {
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				throw new ProtocolException(ProtocolConstants.ERROR_MALFORMED_JSON, "Bridge message must be a JSON object");
			}
			return parsed.getAsJsonObject();
		} catch (JsonParseException exception) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_MALFORMED_JSON,
					"Malformed bridge JSON: " + exception.getMessage(),
					exception
			);
		}
	}

	private static void validateProtocolVersion(JsonObject object) {
		JsonElement element = requireField(object, FIELD_PROTOCOL_VERSION);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw invalidField(FIELD_PROTOCOL_VERSION, "an integer");
		}
		long version;
		try {
			version = new BigDecimal(element.getAsString()).longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw invalidField(FIELD_PROTOCOL_VERSION, "an integer");
		}
		if (version != ProtocolConstants.PROTOCOL_VERSION) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_UNSUPPORTED_VERSION,
					"Unsupported protocolVersion " + version + "; expected " + ProtocolConstants.PROTOCOL_VERSION
			);
		}
	}

	private static String requireBoundedString(JsonObject object, String field, int maximumLength) {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw invalidField(field, "a string");
		}
		String value = element.getAsString();
		if (value.isBlank()) {
			throw invalidField(field, "a nonblank string");
		}
		if (value.length() > maximumLength) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"Field '" + field + "' must not exceed " + maximumLength + " characters"
			);
		}
		return value;
	}

	private static String requireAuthenticationToken(JsonObject object, String field, boolean nonce) {
		String value = requireBoundedString(object, field, BridgeAuthentication.TOKEN_LENGTH);
		if (nonce ? !BridgeAuthentication.isNonce(value) : !BridgeAuthentication.isProof(value)) {
			throw invalidField(field, nonce ? "a 256-bit base64url nonce" : "a SHA-256 HMAC proof");
		}
		return value;
	}

	private static JsonElement requireField(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_MISSING_FIELD,
					"Required bridge field '" + field + "' is missing"
			);
		}
		return object.get(field);
	}

	private static void validateFields(JsonObject object, List<String> envelopeFields, String... additionalFields) {
		Set<String> allowed = new HashSet<>(envelopeFields);
		allowed.addAll(List.of(additionalFields));
		for (String field : object.keySet()) {
			if (!allowed.contains(field)) {
				throw new ProtocolException(
						ProtocolConstants.ERROR_UNKNOWN_FIELD,
						"Unknown bridge field '" + field + "'"
				);
			}
		}
	}

	private static ProtocolException invalidField(String field, String expected) {
		return new ProtocolException(
				ProtocolConstants.ERROR_INVALID_FIELD,
				"Bridge field '" + field + "' must be " + expected
		);
	}

	private static void validateLoopbackPeer(Socket socket) {
		if (!(socket.getRemoteSocketAddress() instanceof InetSocketAddress remote)
				|| remote.getAddress() == null
				|| !remote.getAddress().isLoopbackAddress()) {
			throw new ProtocolException("LOOPBACK_REQUIRED", "Bridge peers must connect over loopback");
		}
	}

	@Override
	public void close() {
		if (!closeStarted.compareAndSet(false, true)) {
			awaitCloseCompletionWhenSafe();
			return;
		}
		try {
			closeOwnedResources();
		} finally {
			closeCompleted.countDown();
		}
	}

	private void closeOwnedResources() {
		synchronized (lifecycleLock) {
			state = SessionState.CLOSED;
		}
		try {
			socket.close();
		} catch (IOException ignored) {
			// The session is already closing and no recovery is possible for this socket.
		}
		interrupt(writerThread);
		interrupt(readerThread);
		synchronized (callbackLock) {
			// Wait for an in-flight callback after closing the socket so blocked I/O can unwind.
		}
		try {
			callbackExecutor.execute(() -> eventSink.onSessionClosed(sessionId));
		} catch (RuntimeException ignored) {
			// The client executor is already stopping, so no later session can inherit this state.
		}
		try {
			closedCallback.run();
		} catch (RuntimeException ignored) {
			// Session resources must still be joined even if an owner callback is faulty.
		}
		join(writerThread);
		join(readerThread);
	}

	long sessionId() {
		return sessionId;
	}

	private void awaitCloseCompletionWhenSafe() {
		Thread current = Thread.currentThread();
		if (current == readerThread || current == writerThread || Thread.holdsLock(callbackLock)) {
			return;
		}
		boolean interrupted = false;
		while (closeCompleted.getCount() != 0L) {
			try {
				closeCompleted.await();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			current.interrupt();
		}
	}

	boolean isDispatchingCallbackOnCurrentThread() {
		return Thread.holdsLock(callbackLock);
	}

	private static void interrupt(Thread thread) {
		if (thread != null && thread != Thread.currentThread()) {
			thread.interrupt();
		}
	}

	private static void join(Thread thread) {
		if (thread == null || thread == Thread.currentThread()) {
			return;
		}
		try {
			thread.join(THREAD_JOIN_MS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private enum SessionState {
		AWAITING_RESPONSE,
		AUTHENTICATED,
		CLOSED
	}
}
