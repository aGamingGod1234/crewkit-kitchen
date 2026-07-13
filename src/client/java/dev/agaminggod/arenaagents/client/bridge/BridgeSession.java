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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeSession implements AutoCloseable {
	public static final int OUTBOUND_QUEUE_CAPACITY = 256;
	public static final int MAX_MESSAGE_IDS_PER_SESSION = 4_096;

	private static final long WRITER_POLL_MS = 100L;
	private static final long THREAD_JOIN_MS = 2_000L;
	private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
	private static final String FIELD_AGENT_ID = "agentId";
	private static final String FIELD_TYPE = "type";
	private static final String FIELD_MESSAGE_ID = "messageId";
	private static final String FIELD_COMMAND = "command";
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
	private final ArrayBlockingQueue<String> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
	private final Set<String> inboundMessageIds = new HashSet<>();
	private final Set<String> outboundMessageIds = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean authenticated = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicLong outboundSequence = new AtomicLong();
	private final Object outputLock = new Object();

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
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.socket = Objects.requireNonNull(socket, "socket must not be null");
		this.codec = Objects.requireNonNull(codec, "codec must not be null");
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
		this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback must not be null");
		validateLoopbackPeer(socket);
		socket.setTcpNoDelay(true);
		this.input = socket.getInputStream();
		this.output = socket.getOutputStream();
	}

	void start() {
		if (closed.get()) {
			throw new IllegalStateException("Cannot start a closed bridge session");
		}
		writerThread = startDaemon("writer", this::writerLoop);
		readerThread = startDaemon("reader", this::readerLoop);
	}

	boolean isOpen() {
		return !closed.get();
	}

	boolean isAuthenticated() {
		return authenticated.get() && isOpen();
	}

	void sendEvent(String type, String messageId, JsonObject payload) {
		if (!isAuthenticated()) {
			throw new ProtocolException("NO_AUTHENTICATED_SESSION", "No authenticated coordinator session is active");
		}
		if (!OUTBOUND_EVENT_TYPES.contains(type)) {
			throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Unknown outbound bridge message type '" + type + "'");
		}
		JsonObject event = createEnvelope(type, messageId);
		if (payload != null) {
			for (var entry : payload.entrySet()) {
				if (event.has(entry.getKey())) {
					throw new ProtocolException(
							ProtocolConstants.ERROR_UNKNOWN_FIELD,
							"Outbound payload must not replace envelope field '" + entry.getKey() + "'"
					);
				}
				event.add(entry.getKey(), entry.getValue().deepCopy());
			}
		}
		enqueue(codec.encode(event));
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
			while (!closed.get()) {
				String line = codec.readLine(input);
				if (line == null) {
					break;
				}
				handleMessage(line);
			}
		} catch (ProtocolException exception) {
			writeError(exception);
		} catch (IOException exception) {
			if (!closed.get()) {
				writeError(new ProtocolException("BRIDGE_IO", "Bridge input failed: " + exception.getMessage(), exception));
			}
		} finally {
			close();
		}
	}

	private void writerLoop() {
		try {
			while (!closed.get() || !outbound.isEmpty()) {
				String message = outbound.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS);
				if (message != null) {
					writeNow(message);
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (IOException exception) {
			if (!closed.get()) {
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

		if (!authenticated.get() && !"hello".equals(type)) {
			throw new ProtocolException("AUTHENTICATION_REQUIRED", "First bridge message must be hello");
		}

		switch (type) {
			case "hello" -> handleHello(message, messageId);
			case "action_command" -> handleActionCommand(message);
			case "cancel_action" -> {
				validateFields(message, ENVELOPE_FIELDS, "commandId");
				requireBoundedString(message, "commandId", ProtocolConstants.MAX_COMMAND_ID_LENGTH);
			}
			case "request_observation" -> validateFields(message, ENVELOPE_FIELDS);
			case "shutdown" -> {
				validateFields(message, ENVELOPE_FIELDS);
				close();
			}
			default -> throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Unknown bridge message type '" + type + "'");
		}
	}

	private void handleHello(JsonObject message, String messageId) {
		validateFields(message, ENVELOPE_FIELDS);
		if (!authenticated.compareAndSet(false, true)) {
			throw new ProtocolException("ALREADY_AUTHENTICATED", "hello is only valid as the first message");
		}
		JsonObject acknowledgement = createEnvelope("hello_ack", nextOutboundMessageId());
		acknowledgement.addProperty("replyTo", messageId);
		enqueue(codec.encode(acknowledgement));
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

	private void dispatchAction(ActionCommand command) {
		try {
			eventSink.onActionCommand(command);
		} catch (RuntimeException exception) {
			writeError(new ProtocolException("CALLBACK_FAILED", "Client action callback failed", exception));
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

	private void enqueue(String message) {
		if (closed.get()) {
			throw new ProtocolException("SESSION_CLOSED", "Bridge session is closed");
		}
		if (!outbound.offer(message)) {
			close();
			throw new ProtocolException("OUTBOUND_QUEUE_FULL", "Bridge outbound queue is full");
		}
	}

	private void writeError(ProtocolException exception) {
		if (closed.get()) {
			return;
		}
		JsonObject error = createEnvelope("error", nextOutboundMessageId());
		error.addProperty("code", exception.code());
		error.addProperty("message", exception.getMessage());
		try {
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

	private Thread startDaemon(String role, Runnable task) {
		return Thread.ofPlatform()
				.name(BridgeServer.THREAD_NAME_PREFIX + role + "-" + config.agentId())
				.daemon(true)
				.start(task);
	}

	private String nextOutboundMessageId() {
		return "server-" + outboundSequence.incrementAndGet();
	}

	private JsonObject createEnvelope(String type, String messageId) {
		JsonObject envelope = createEnvelope(config, type, messageId);
		if (!outboundMessageIds.add(messageId)) {
			throw new ProtocolException("DUPLICATE_MESSAGE_ID", "Duplicate bridge messageId '" + messageId + "'");
		}
		return envelope;
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
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			socket.close();
		} catch (IOException ignored) {
			// The session is already closing and no recovery is possible for this socket.
		}
		interrupt(writerThread);
		interrupt(readerThread);
		closedCallback.run();
		join(writerThread);
		join(readerThread);
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
}
