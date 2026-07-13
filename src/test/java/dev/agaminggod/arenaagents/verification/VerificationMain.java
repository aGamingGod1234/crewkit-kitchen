package dev.agaminggod.arenaagents.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class VerificationMain {
	private static final long ISSUED_AT_EPOCH_MS = 1_750_000_000_000L;
	private static final String COMMAND_ID = "command-1";
	private static final String AGENT_ID = "agent-test";
	private static final int SOCKET_TIMEOUT_MS = 2_000;
	private static final long ASYNC_TIMEOUT_MS = 3_000L;
	private static int passedAssertions;

	private VerificationMain() {
	}

	public static void main(String[] args) throws Exception {
		ProtocolCodec codec = new ProtocolCodec();

		verifyProtocolConstants();
		verifyActionWireNames();
		verifyValidActionUnion(codec);
		verifyBriefExamples(codec);
		verifyEnvelopeValidation(codec);
		verifyDirectCommandSchemaValidation();
		verifyBoundedValidation(codec);
		verifyStrictArguments(codec);
		verifyCommandImmutability();
		verifyCommandEncodingRoundTrip(codec);
		verifyActionResultContract();
		verifyAgentConfigParsing();
		verifyAgentConfigFiles();
		verifyJsonLineFraming(codec);
		verifyBridgeAuthenticationAndDispatch(codec);
		verifyBridgeCallbackFailure(codec);
		verifyBridgeControlValidation(codec);
		verifyBridgeSingleSessionAndReconnect(codec);
		verifyBridgeOutboundEventsAndShutdown(codec);

		System.out.printf("PASS: %d protocol and bridge assertions%n", passedAssertions);
	}

	private static void verifyProtocolConstants() {
		assertEquals(1, ProtocolConstants.PROTOCOL_VERSION, "protocol version");
		assertEquals(65_536, ProtocolConstants.MAX_LINE_BYTES, "line byte limit");
		assertEquals(600_000L, ProtocolConstants.MAX_DURATION_MS, "duration limit");
	}

	private static void verifyActionWireNames() {
		List<String> expectedWireNames = List.of(
				"move_to",
				"look_at",
				"attack",
				"select_item",
				"use_item",
				"break_block",
				"place_block",
				"chat",
				"wait",
				"complete_goal"
		);
		List<String> actualWireNames = List.of(ActionType.values()).stream()
				.map(ActionType::wireName)
				.toList();

		assertEquals(expectedWireNames, actualWireNames, "exhaustive action wire names");
		assertEquals(ActionType.WAIT, ActionType.fromWireName("wait").orElseThrow(), "wire action lookup");
		assertTrue(ActionType.fromWireName("WAIT").isEmpty(), "wire action lookup is case-sensitive");
		assertTrue(ActionType.fromWireName(null).isEmpty(), "null wire action lookup is empty");
	}

	private static void verifyValidActionUnion(ProtocolCodec codec) throws ProtocolException {
		assertDecodedType(codec, "move_to", "\"x\":1.5,\"y\":64,\"z\":-2.5,\"tolerance\":0.75,\"sprint\":true", ActionType.MOVE_TO);
		assertDecodedType(codec, "look_at", "\"x\":1,\"y\":65.25,\"z\":3", ActionType.LOOK_AT);
		assertDecodedType(codec, "attack", "\"targetSelector\":\"nearest_hostile\",\"timeoutMs\":5000", ActionType.ATTACK);
		assertDecodedType(codec, "select_item", "\"itemId\":\"minecraft:diamond_sword\"", ActionType.SELECT_ITEM);
		assertDecodedType(codec, "use_item", "\"durationMs\":1250", ActionType.USE_ITEM);
		assertDecodedType(codec, "break_block", "\"x\":1,\"y\":64,\"z\":-2,\"timeoutMs\":5000", ActionType.BREAK_BLOCK);
		assertDecodedType(codec, "place_block", "\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:stone\"", ActionType.PLACE_BLOCK);
		assertDecodedType(codec, "chat", "\"message\":\"Ready.\"", ActionType.CHAT);
		assertDecodedType(codec, "wait", "\"durationMs\":250", ActionType.WAIT);
		assertDecodedType(codec, "complete_goal", "\"summary\":\"Reached the arena.\"", ActionType.COMPLETE_GOAL);
	}

	private static void verifyBriefExamples(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"unknown\"}"),
				"UNKNOWN_ACTION",
				"unknown",
				"unknown action"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":600001}"),
				"OUT_OF_RANGE",
				"durationMs",
				"overlong wait"
		);

		ActionCommand command = codec.decodeCommand(commandJson("wait", "\"durationMs\":250"));
		assertEquals(ActionType.WAIT, command.type(), "wait action type");
	}

	private static void verifyEnvelopeValidation(ProtocolCodec codec) {
		expectProtocolException(
				() -> codec.decodeCommand("{not-json"),
				"MALFORMED_JSON",
				"JSON",
				"malformed JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("[]"),
				"MALFORMED_JSON",
				"object",
				"non-object JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"protocolVersion",
				"required protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":2,\"commandId\":\"command-1\",\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"UNSUPPORTED_VERSION",
				"2",
				"unsupported protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"MISSING_FIELD",
				"commandId",
				"required command id"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"type",
				"required action type"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"type\":\"wait\",\"durationMs\":1}"),
				"MISSING_FIELD",
				"issuedAtEpochMs",
				"required issue timestamp"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":1,\"extra\":true")),
				"UNKNOWN_FIELD",
				"extra",
				"unknown action field"
		);
	}

	private static void verifyBoundedValidation(ProtocolCodec codec) {
		String oversizedUtf8Line = "{\"type\":\"chat\",\"message\":\"" + "\u00e9".repeat(ProtocolConstants.MAX_LINE_BYTES / 2) + "\"}";
		expectProtocolException(
				() -> codec.decodeCommand(oversizedUtf8Line),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"UTF-8 line byte limit"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("look_at", "\"x\":1e309,\"y\":64,\"z\":0")),
				"OUT_OF_RANGE",
				"x",
				"finite coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("break_block", "\"x\":1.5,\"y\":64,\"z\":0,\"timeoutMs\":1")),
				"OUT_OF_RANGE",
				"x",
				"integral block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"break_block",
						"\"x\":1.00000000000000001,\"y\":64,\"z\":0,\"timeoutMs\":1"
				)),
				"OUT_OF_RANGE",
				"x",
				"exact break block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"place_block",
						"\"x\":0,\"y\":64,\"z\":-2.00000000000000001,\"face\":\"up\",\"itemId\":\"minecraft:stone\""
				)),
				"OUT_OF_RANGE",
				"z",
				"exact place block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":0")),
				"OUT_OF_RANGE",
				"durationMs",
				"minimum duration"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("chat", "\"message\":\"" + "a".repeat(ProtocolConstants.MAX_CHAT_LENGTH + 1) + "\"")),
				"OUT_OF_RANGE",
				"message",
				"chat text limit"
		);
	}

	private static void verifyStrictArguments(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "")),
				"MISSING_FIELD",
				"durationMs",
				"required action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("move_to", "\"x\":0,\"y\":64,\"z\":0,\"tolerance\":1,\"sprint\":\"yes\"")),
				"INVALID_FIELD",
				"sprint",
				"boolean action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("place_block", "\"x\":0,\"y\":64,\"z\":0,\"face\":\"forward\",\"itemId\":\"minecraft:stone\"")),
				"INVALID_FIELD",
				"face",
				"block face"
		);

		ActionCommand shortestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":1"));
		ActionCommand longestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":" + ProtocolConstants.MAX_DURATION_MS));
		assertEquals(1L, shortestWait.arguments().get("durationMs").getAsLong(), "minimum valid duration");
		assertEquals(ProtocolConstants.MAX_DURATION_MS, longestWait.arguments().get("durationMs").getAsLong(), "maximum valid duration");
	}

	private static void verifyDirectCommandSchemaValidation() {
		JsonObject validArguments = new JsonObject();
		validArguments.addProperty("durationMs", 250);
		ActionCommand validCommand = new ActionCommand(
				COMMAND_ID,
				ActionType.WAIT,
				validArguments,
				ISSUED_AT_EPOCH_MS
		);
		assertEquals(250L, validCommand.arguments().get("durationMs").getAsLong(), "direct valid command arguments");

		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						new JsonObject(),
						ISSUED_AT_EPOCH_MS
				),
				"MISSING_FIELD",
				"durationMs",
				"direct command required argument"
		);

		JsonObject unknownArguments = validArguments.deepCopy();
		unknownArguments.addProperty("extra", true);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						unknownArguments,
						ISSUED_AT_EPOCH_MS
				),
				"UNKNOWN_FIELD",
				"extra",
				"direct command unknown argument"
		);

		JsonObject outOfRangeArguments = new JsonObject();
		outOfRangeArguments.addProperty("durationMs", ProtocolConstants.MAX_DURATION_MS + 1L);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						outOfRangeArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"durationMs",
				"direct command bounded argument"
		);
	}

	private static void verifyCommandImmutability() {
		JsonObject sourceArguments = new JsonObject();
		sourceArguments.addProperty("durationMs", 250);
		ActionCommand command = new ActionCommand(COMMAND_ID, ActionType.WAIT, sourceArguments, ISSUED_AT_EPOCH_MS);

		sourceArguments.addProperty("durationMs", 500);
		JsonObject returnedArguments = command.arguments();
		returnedArguments.addProperty("durationMs", 750);

		assertEquals(250L, command.arguments().get("durationMs").getAsLong(), "action arguments are defensively copied");
		expectProtocolException(
				() -> new ActionCommand(
						"a".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH + 1),
						ActionType.WAIT,
						sourceArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"commandId",
				"record command id limit"
		);
	}

	private static void verifyCommandEncodingRoundTrip(ProtocolCodec codec) throws ProtocolException {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 250);
		ActionCommand original = new ActionCommand(COMMAND_ID, ActionType.WAIT, arguments, ISSUED_AT_EPOCH_MS);

		String encoded = codec.encode(original);
		ActionCommand decoded = codec.decodeCommand(encoded);

		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded command protocol version");
		assertTrue(encoded.contains("\"type\":\"wait\""), "encoded action wire name");
		assertTrue(!encoded.contains("\"arguments\""), "encoded action arguments are flattened");
		assertEquals(original, decoded, "command encoding round trip");
	}

	private static void verifyActionResultContract() throws ProtocolException {
		ActionResult result = new ActionResult(
				COMMAND_ID,
				ActionState.SUCCEEDED,
				"COMPLETED",
				"Goal completed.",
				ISSUED_AT_EPOCH_MS + 250
		);

		assertEquals(ActionState.SUCCEEDED, result.state(), "action result state");
		assertTrue(result.state().isTerminal(), "successful action is terminal");
		assertTrue(!ActionState.RUNNING.isTerminal(), "running action is non-terminal");
		expectProtocolException(
				() -> new ActionResult(COMMAND_ID, ActionState.RUNNING, "RUNNING", "Still running.", ISSUED_AT_EPOCH_MS),
				"INVALID_FIELD",
				"terminal",
				"result rejects non-terminal state"
		);

		String encoded = new ProtocolCodec().encode(result);
		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded result protocol version");
		assertTrue(encoded.contains("\"state\":\"SUCCEEDED\""), "encoded terminal state");
	}

	private static void verifyAgentConfigParsing() {
		String validJson = "{\"agentId\":\"agent-55\",\"bridgePort\":25571,"
				+ "\"observationRadius\":12,\"enabled\":true}";
		AgentConfig config = AgentConfigLoader.parse(validJson);
		assertEquals(25571, config.bridgePort(), "config bridge port");
		AgentConfig loopbackAlias = AgentConfigLoader.parse(validJson.substring(0, validJson.length() - 1)
				+ ",\"host\":\"127.0.0.2\"}");
		assertEquals("agent-55", loopbackAlias.agentId(), "config accepts numeric loopback alias");
		expectProtocolException(
				() -> AgentConfigLoader.parse(validJson.substring(0, validJson.length() - 1)
						+ ",\"host\":\"0.0.0.0\"}"),
				"LOOPBACK_REQUIRED",
				"127.0.0.1",
				"config non-loopback host"
		);
	}

	private static void verifyAgentConfigFiles() throws IOException {
		Path directory = Files.createTempDirectory("arenaagents-config-").toAbsolutePath().normalize();
		Path configPath = directory.resolve("arenaagents.json");
		try {
			AgentConfigLoader.loadOrCreate(configPath);
			String existingJson = "{\"agentId\":\"existing-agent\",\"bridgePort\":25572,"
					+ "\"observationRadius\":8,\"enabled\":true}";
			Files.writeString(configPath, existingJson, StandardCharsets.UTF_8);
			AgentConfig loaded = AgentConfigLoader.loadOrCreate(configPath);
			assertEquals("existing-agent", loaded.agentId(), "existing config loaded");
			assertEquals(existingJson, Files.readString(configPath, StandardCharsets.UTF_8), "existing config not overwritten");
		} finally {
			Files.deleteIfExists(configPath);
			Files.deleteIfExists(directory);
		}
	}

	private static void verifyJsonLineFraming(ProtocolCodec codec) throws IOException {
		String json = "{\"message\":\"ready\"}";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeLine(output, json);
		assertEquals(json, codec.readLine(new ByteArrayInputStream(output.toByteArray())), "UTF-8 JSONL round trip");
		byte[] oversizedLine = ("a".repeat(ProtocolConstants.MAX_LINE_BYTES + 1) + "\n")
				.getBytes(StandardCharsets.UTF_8);
		expectProtocolException(
				() -> codec.readLine(new ByteArrayInputStream(oversizedLine)),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"framing line byte limit"
		);
	}

	private static void verifyBridgeAuthenticationAndDispatch(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		AtomicReference<ActionCommand> received = new AtomicReference<>();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, executor, received::set)) {
			server.start();
			try (Socket client = openClient(port)) {
				writeMessage(codec, client, actionEnvelope("before-hello"));
				assertErrorCode(codec, client, "AUTHENTICATION_REQUIRED", "first message must authenticate");
			}
			try (Socket client = connectAuthenticated(codec, port, "hello-authenticated")) {
				writeMessage(codec, client, actionEnvelope("action-1"));
				awaitCondition(() -> executor.pendingCount() == 1, "action callback queued");
				assertTrue(received.get() == null, "action callback waits for provided executor");
				executor.runNext();
				assertEquals(ActionType.WAIT, received.get().type(), "action callback decoded through protocol codec");
			}
		}
	}

	private static void verifyBridgeSingleSessionAndReconnect(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket first = connectAuthenticated(codec, port, "hello-primary")) {
				try (Socket second = openClient(port)) {
					assertErrorCode(codec, second, "SESSION_ACTIVE", "second live session rejected");
				}
			}
			try (Socket reconnected = connectAuthenticatedWithRetry(codec, port, "hello-reconnected")) {
				assertTrue(reconnected.isConnected(), "session reconnects after clean peer close");
			}
		}
	}

	private static void verifyBridgeCallbackFailure(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		try (BridgeServer server = new BridgeServer(
				enabledConfig(port),
				codec,
				executor,
				command -> { throw new IllegalStateException("callback failed"); }
		)) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-callback-failure")) {
				writeMessage(codec, client, actionEnvelope("action-callback-failure"));
				awaitCondition(() -> executor.pendingCount() == 1, "failing action callback queued");
				assertDoesNotThrow(executor::runNext, "action callback failure contained");
				assertErrorCode(codec, client, "CALLBACK_FAILED", "action callback failure reported");
			}
		}
	}

	private static void verifyBridgeControlValidation(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-control")) {
				writeMessage(
						codec,
						client,
						"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
								+ "\",\"type\":\"cancel_action\",\"messageId\":\"cancel-1\"}"
				);
				assertErrorCode(codec, client, "MISSING_FIELD", "cancel command id required");
			}
		}
	}

	private static void verifyBridgeOutboundEventsAndShutdown(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-outbound")) {
				JsonObject payload = new JsonObject();
				payload.addProperty("event", "bridge_ready");
				server.sendEvent("significant_event", "event-1", payload);
				JsonObject event = readMessage(codec, client);
				assertEquals("significant_event", event.get("type").getAsString(), "thread-safe outbound event type");
				assertEquals(AGENT_ID, event.get("agentId").getAsString(), "outbound event agent identity");
				expectProtocolException(
						() -> server.sendEvent("significant_event", "event-1", payload),
						"DUPLICATE_MESSAGE_ID",
						"event-1",
						"outbound message id uniqueness"
				);
				expectProtocolException(
						() -> server.sendEvent("unknown_event", "event-2", payload),
						"UNKNOWN_MESSAGE_TYPE",
						"unknown_event",
						"outbound message type allowlist"
				);
				expectProtocolException(
						() -> server.sendEvent("significant_event", null, payload),
						"INVALID_FIELD",
						"messageId",
						"outbound message id type"
				);
				expectProtocolException(
						() -> server.sendEvent(
								"significant_event",
								"m".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH + 1),
								payload
						),
						"OUT_OF_RANGE",
						"messageId",
						"outbound message id bound"
				);
			}
		}
		awaitCondition(() -> bridgeThreads().stream().noneMatch(Thread::isAlive), "bridge daemon threads stop after close");
		assertTrue(bridgeThreads().stream().noneMatch(thread -> !thread.isDaemon()), "no non-daemon bridge thread remains");
	}

	private static void assertDecodedType(
			ProtocolCodec codec,
			String wireType,
			String actionFields,
			ActionType expectedType
	) throws ProtocolException {
		ActionCommand command = codec.decodeCommand(commandJson(wireType, actionFields));
		assertEquals(expectedType, command.type(), wireType + " action type");
		assertEquals(COMMAND_ID, command.commandId(), wireType + " command id");
		assertEquals(ISSUED_AT_EPOCH_MS, command.issuedAtEpochMs(), wireType + " issue timestamp");
	}

	private static String commandJson(String wireType, String actionFields) {
		String actionSuffix = actionFields.isEmpty() ? "" : "," + actionFields;
		return "{\"protocolVersion\":1,\"commandId\":\"" + COMMAND_ID
				+ "\",\"type\":\"" + wireType
				+ "\",\"issuedAtEpochMs\":" + ISSUED_AT_EPOCH_MS
				+ actionSuffix + "}";
	}

	private static AgentConfig enabledConfig(int port) {
		return new AgentConfig(AGENT_ID, port, 12, true);
	}

	private static int findAvailablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static Socket openClient(int port) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		return socket;
	}

	private static Socket connectAuthenticated(ProtocolCodec codec, int port, String messageId) throws IOException {
		Socket socket = openClient(port);
		writeMessage(codec, socket, helloEnvelope(messageId));
		JsonObject acknowledgement = readMessage(codec, socket);
		assertEquals("hello_ack", acknowledgement.get("type").getAsString(), "hello acknowledged");
		return socket;
	}

	private static Socket connectAuthenticatedWithRetry(ProtocolCodec codec, int port, String messageId)
			throws Exception {
		long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MS;
		IOException lastFailure = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				return connectAuthenticated(codec, port, messageId);
			} catch (IOException exception) {
				lastFailure = exception;
				Thread.yield();
			}
		}
		throw new AssertionError("session did not reconnect", lastFailure);
	}

	private static void writeMessage(ProtocolCodec codec, Socket socket, String json) throws IOException {
		codec.writeLine(socket.getOutputStream(), json);
	}

	private static JsonObject readMessage(ProtocolCodec codec, Socket socket) throws IOException {
		String line = codec.readLine(socket.getInputStream());
		if (line == null) {
			throw new AssertionError("bridge closed before sending a message");
		}
		return JsonParser.parseString(line).getAsJsonObject();
	}

	private static void assertErrorCode(ProtocolCodec codec, Socket socket, String expectedCode, String label)
			throws IOException {
		JsonObject error = readMessage(codec, socket);
		assertEquals("error", error.get("type").getAsString(), label + " type");
		assertEquals(expectedCode, error.get("code").getAsString(), label + " code");
	}

	private static String helloEnvelope(String messageId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"hello\",\"messageId\":\"" + messageId + "\"}";
	}

	private static String actionEnvelope(String messageId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"action_command\",\"messageId\":\"" + messageId
				+ "\",\"command\":" + commandJson("wait", "\"durationMs\":250") + "}";
	}

	private static void awaitCondition(BooleanSupplier condition, String label) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MS;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}
		assertTrue(condition.getAsBoolean(), label);
	}

	private static List<Thread> bridgeThreads() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> thread.getName().startsWith(BridgeServer.THREAD_NAME_PREFIX))
				.toList();
	}

	private static void expectProtocolException(
			ThrowingRunnable action,
			String expectedCode,
			String expectedMessagePart,
			String label
	) {
		try {
			action.run();
		} catch (ProtocolException exception) {
			assertEquals(expectedCode, exception.code(), label + " code");
			assertTrue(exception.getMessage().contains(expectedMessagePart), label + " message");
			return;
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}

		throw new AssertionError(label + " did not throw ProtocolException");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
		pass(label);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
		pass(label);
	}

	private static void assertDoesNotThrow(ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
		pass(label);
	}

	private static void pass(String label) {
		passedAssertions++;
		System.out.println("PASS: " + label);
	}

	private static final class RecordingExecutor implements Executor {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void execute(Runnable command) {
			tasks.addLast(command);
		}

		private synchronized int pendingCount() {
			return tasks.size();
		}

		private void runNext() {
			Runnable task;
			synchronized (this) {
				task = tasks.removeFirst();
			}
			task.run();
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
