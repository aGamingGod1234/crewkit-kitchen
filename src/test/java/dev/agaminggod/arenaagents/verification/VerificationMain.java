package dev.agaminggod.arenaagents.verification;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.util.List;

public final class VerificationMain {
	private static final long ISSUED_AT_EPOCH_MS = 1_750_000_000_000L;
	private static final String COMMAND_ID = "command-1";
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
		verifyBoundedValidation(codec);
		verifyStrictArguments(codec);
		verifyCommandImmutability();
		verifyCommandEncodingRoundTrip(codec);
		verifyActionResultContract();

		System.out.printf("PASS: %d protocol assertions%n", passedAssertions);
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

	private static void pass(String label) {
		passedAssertions++;
		System.out.println("PASS: " + label);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
