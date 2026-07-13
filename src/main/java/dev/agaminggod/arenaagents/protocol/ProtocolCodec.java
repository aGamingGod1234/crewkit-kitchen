package dev.agaminggod.arenaagents.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProtocolCodec {
	private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
	private static final String FIELD_COMMAND_ID = "commandId";
	private static final String FIELD_TYPE = "type";
	private static final String FIELD_ISSUED_AT_EPOCH_MS = "issuedAtEpochMs";
	private static final String FIELD_X = "x";
	private static final String FIELD_Y = "y";
	private static final String FIELD_Z = "z";
	private static final String FIELD_TOLERANCE = "tolerance";
	private static final String FIELD_SPRINT = "sprint";
	private static final String FIELD_TARGET_SELECTOR = "targetSelector";
	private static final String FIELD_TIMEOUT_MS = "timeoutMs";
	private static final String FIELD_ITEM_ID = "itemId";
	private static final String FIELD_DURATION_MS = "durationMs";
	private static final String FIELD_FACE = "face";
	private static final String FIELD_MESSAGE = "message";
	private static final String FIELD_SUMMARY = "summary";

	private static final List<String> ENVELOPE_FIELDS = List.of(
			FIELD_PROTOCOL_VERSION,
			FIELD_COMMAND_ID,
			FIELD_TYPE,
			FIELD_ISSUED_AT_EPOCH_MS
	);
	private static final List<String> BLOCK_FACES = List.of("down", "up", "north", "south", "west", "east");
	private static final Map<ActionType, List<String>> ACTION_FIELDS = createActionFields();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public ActionCommand decodeCommand(String json) throws ProtocolException {
		enforceLineLimit(json);
		JsonObject commandObject = parseObject(json);
		ActionType actionType = requireActionType(commandObject);
		JsonObject arguments = validateActionArguments(
				actionType,
				copyActionArguments(commandObject, actionType)
		);

		validateProtocolVersion(commandObject);
		String commandId = requireBoundedText(
				commandObject,
				FIELD_COMMAND_ID,
				ProtocolConstants.MAX_COMMAND_ID_LENGTH,
				false
		);
		long issuedAtEpochMs = requirePositiveLong(commandObject, FIELD_ISSUED_AT_EPOCH_MS);
		validateKnownFields(commandObject, actionType);

		return new ActionCommand(commandId, actionType, arguments, issuedAtEpochMs);
	}

	public String encode(Object value) throws ProtocolException {
		if (value == null) {
			throw invalidField("Protocol value must not be null");
		}

		JsonObject encodedObject = value instanceof ActionCommand command
				? encodeCommand(command)
				: ensureProtocolVersion(encodeObject(value));
		String json = serialize(encodedObject);
		enforceLineLimit(json);
		return json;
	}

	public String readLine(InputStream input) throws IOException, ProtocolException {
		if (input == null) {
			throw invalidField("Input stream must not be null");
		}

		ByteArrayOutputStream line = new ByteArrayOutputStream();
		while (true) {
			int next = input.read();
			if (next == -1) {
				if (line.size() == 0) {
					return null;
				}
				throw new ProtocolException("INCOMPLETE_FRAME", "JSON line ended before a newline delimiter");
			}
			if (next == '\n') {
				return decodeUtf8Line(line.toByteArray());
			}
			if (line.size() >= ProtocolConstants.MAX_LINE_BYTES) {
				throw new ProtocolException(
						ProtocolConstants.ERROR_LINE_TOO_LARGE,
						"JSON line exceeds maximum of " + ProtocolConstants.MAX_LINE_BYTES + " UTF-8 bytes"
				);
			}
			line.write(next);
		}
	}

	public void writeLine(OutputStream output, String json) throws IOException, ProtocolException {
		if (output == null) {
			throw invalidField("Output stream must not be null");
		}
		enforceLineLimit(json);
		output.write(json.getBytes(StandardCharsets.UTF_8));
		output.write('\n');
		output.flush();
	}

	private static String decodeUtf8Line(byte[] bytes) throws ProtocolException {
		int length = bytes.length;
		if (length > 0 && bytes[length - 1] == '\r') {
			length--;
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, 0, length))
					.toString();
		} catch (CharacterCodingException exception) {
			throw new ProtocolException("INVALID_ENCODING", "JSON line must be valid UTF-8", exception);
		}
	}

	private static JsonObject ensureProtocolVersion(JsonObject encoded) throws ProtocolException {
		if (encoded.has(FIELD_PROTOCOL_VERSION)) {
			validateProtocolVersion(encoded);
			return encoded;
		}

		JsonObject versioned = new JsonObject();
		versioned.addProperty(FIELD_PROTOCOL_VERSION, ProtocolConstants.PROTOCOL_VERSION);
		for (Map.Entry<String, JsonElement> entry : encoded.entrySet()) {
			versioned.add(entry.getKey(), entry.getValue().deepCopy());
		}
		return versioned;
	}

	private static String serialize(JsonObject encoded) throws ProtocolException {
		try {
			return GSON.toJson(encoded);
		} catch (RuntimeException exception) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_ENCODING_FAILED,
					"Could not encode protocol value as JSON: " + exception.getMessage(),
					exception
			);
		}
	}

	private static JsonObject encodeCommand(ActionCommand command) throws ProtocolException {
		JsonObject encoded = new JsonObject();
		encoded.addProperty(FIELD_PROTOCOL_VERSION, ProtocolConstants.PROTOCOL_VERSION);
		encoded.addProperty(FIELD_COMMAND_ID, command.commandId());
		encoded.addProperty(FIELD_TYPE, command.type().wireName());
		encoded.addProperty(FIELD_ISSUED_AT_EPOCH_MS, command.issuedAtEpochMs());

		for (Map.Entry<String, JsonElement> entry : command.arguments().entrySet()) {
			if (encoded.has(entry.getKey())) {
				throw unknownField(entry.getKey(), command.type());
			}
			encoded.add(entry.getKey(), entry.getValue().deepCopy());
		}

		validateActionArguments(command.type(), command.arguments());
		validateKnownFields(encoded, command.type());
		return encoded;
	}

	private static JsonObject encodeObject(Object value) throws ProtocolException {
		try {
			JsonElement element = GSON.toJsonTree(value);
			if (!element.isJsonObject()) {
				throw invalidField("Encoded protocol value must be a JSON object");
			}
			return element.getAsJsonObject();
		} catch (ProtocolException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_ENCODING_FAILED,
					"Could not encode protocol value as JSON: " + exception.getMessage(),
					exception
			);
		}
	}

	private static JsonObject parseObject(String json) throws ProtocolException {
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				throw malformedJson("Command JSON must be an object", null);
			}
			return parsed.getAsJsonObject();
		} catch (JsonParseException exception) {
			throw malformedJson("Malformed JSON command: " + exception.getMessage(), exception);
		}
	}

	private static void enforceLineLimit(String json) throws ProtocolException {
		if (json == null) {
			throw invalidField("Command JSON must not be null");
		}
		int byteLength = json.getBytes(StandardCharsets.UTF_8).length;
		if (byteLength > ProtocolConstants.MAX_LINE_BYTES) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_LINE_TOO_LARGE,
					"Command line is " + byteLength + " UTF-8 bytes; maximum is "
							+ ProtocolConstants.MAX_LINE_BYTES
			);
		}
	}

	private static ActionType requireActionType(JsonObject command) throws ProtocolException {
		String wireName = requireString(command, FIELD_TYPE);
		if (wireName.isBlank()) {
			throw invalidField("Field '" + FIELD_TYPE + "' must not be blank");
		}
		return ActionType.fromWireName(wireName)
				.orElseThrow(() -> new ProtocolException(
						ProtocolConstants.ERROR_UNKNOWN_ACTION,
						"Unknown action type '" + wireName + "'"
				));
	}

	private static void validateProtocolVersion(JsonObject command) throws ProtocolException {
		long version = requireIntegralLong(command, FIELD_PROTOCOL_VERSION);
		if (version != ProtocolConstants.PROTOCOL_VERSION) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_UNSUPPORTED_VERSION,
					"Unsupported protocolVersion " + version + "; expected " + ProtocolConstants.PROTOCOL_VERSION
			);
		}
	}

	static JsonObject validateActionArguments(ActionType actionType, JsonObject arguments)
			throws ProtocolException {
		if (actionType == null) {
			throw invalidField("Action type must not be null");
		}
		if (arguments == null) {
			throw invalidField("Action arguments must not be null");
		}

		switch (actionType) {
			case MOVE_TO -> validateMoveTo(arguments);
			case LOOK_AT -> validateCoordinates(arguments, false);
			case ATTACK -> validateAttack(arguments);
			case SELECT_ITEM -> requireIdentifier(arguments, FIELD_ITEM_ID);
			case USE_ITEM, WAIT -> requireDuration(arguments, FIELD_DURATION_MS);
			case BREAK_BLOCK -> validateBreakBlock(arguments);
			case PLACE_BLOCK -> validatePlaceBlock(arguments);
			case CHAT -> requireBoundedText(arguments, FIELD_MESSAGE, ProtocolConstants.MAX_CHAT_LENGTH, false);
			case COMPLETE_GOAL -> requireBoundedText(
					arguments,
					FIELD_SUMMARY,
					ProtocolConstants.MAX_SUMMARY_LENGTH,
					false
			);
		}
		validateKnownArgumentFields(arguments, actionType);

		JsonObject validatedArguments = new JsonObject();
		for (String field : ACTION_FIELDS.get(actionType)) {
			validatedArguments.add(field, arguments.get(field).deepCopy());
		}
		return validatedArguments;
	}

	private static JsonObject copyActionArguments(JsonObject command, ActionType actionType) {
		JsonObject arguments = new JsonObject();
		for (String field : ACTION_FIELDS.get(actionType)) {
			if (command.has(field)) {
				arguments.add(field, command.get(field).deepCopy());
			}
		}
		return arguments;
	}

	private static void validateMoveTo(JsonObject command) throws ProtocolException {
		validateCoordinates(command, false);
		double tolerance = requireFiniteNumber(command, FIELD_TOLERANCE);
		if (tolerance < ProtocolConstants.MIN_MOVEMENT_TOLERANCE
				|| tolerance > ProtocolConstants.MAX_MOVEMENT_TOLERANCE) {
			throw outOfRange(
					FIELD_TOLERANCE,
					ProtocolConstants.MIN_MOVEMENT_TOLERANCE + " to "
							+ ProtocolConstants.MAX_MOVEMENT_TOLERANCE
			);
		}
		requireBoolean(command, FIELD_SPRINT);
	}

	private static void validateAttack(JsonObject command) throws ProtocolException {
		requireBoundedText(
				command,
				FIELD_TARGET_SELECTOR,
				ProtocolConstants.MAX_TARGET_SELECTOR_LENGTH,
				false
		);
		requireDuration(command, FIELD_TIMEOUT_MS);
	}

	private static void validateBreakBlock(JsonObject command) throws ProtocolException {
		validateCoordinates(command, true);
		requireDuration(command, FIELD_TIMEOUT_MS);
	}

	private static void validatePlaceBlock(JsonObject command) throws ProtocolException {
		validateCoordinates(command, true);
		String face = requireString(command, FIELD_FACE);
		if (!BLOCK_FACES.contains(face)) {
			throw invalidField(
					"Field '" + FIELD_FACE + "' must be one of " + String.join(", ", BLOCK_FACES)
			);
		}
		requireIdentifier(command, FIELD_ITEM_ID);
	}

	private static void validateCoordinates(JsonObject command, boolean integral) throws ProtocolException {
		for (String field : List.of(FIELD_X, FIELD_Y, FIELD_Z)) {
			if (integral) {
				requireIntegralBlockCoordinate(command, field);
			} else {
				requireFiniteNumber(command, field);
			}
		}
	}

	private static int requireIntegralBlockCoordinate(JsonObject object, String field) throws ProtocolException {
		JsonPrimitive primitive = requireNumber(object, field);
		requireFiniteNumber(primitive, field);
		try {
			return new BigDecimal(primitive.getAsString()).intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw outOfRange(field, "an integral 32-bit block coordinate");
		}
	}

	private static String requireIdentifier(JsonObject command, String field) throws ProtocolException {
		return requireBoundedText(command, field, ProtocolConstants.MAX_IDENTIFIER_LENGTH, false);
	}

	private static long requireDuration(JsonObject command, String field) throws ProtocolException {
		long duration = requireIntegralLong(command, field);
		if (duration < ProtocolConstants.MIN_DURATION_MS || duration > ProtocolConstants.MAX_DURATION_MS) {
			throw outOfRange(
					field,
					ProtocolConstants.MIN_DURATION_MS + " to " + ProtocolConstants.MAX_DURATION_MS + " milliseconds"
			);
		}
		return duration;
	}

	private static long requirePositiveLong(JsonObject object, String field) throws ProtocolException {
		long value = requireIntegralLong(object, field);
		if (value <= 0L) {
			throw outOfRange(field, "a positive integer");
		}
		return value;
	}

	private static long requireIntegralLong(JsonObject object, String field) throws ProtocolException {
		JsonPrimitive primitive = requireNumber(object, field);
		try {
			return new BigDecimal(primitive.getAsString()).longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw outOfRange(field, "a 64-bit integer");
		}
	}

	private static double requireFiniteNumber(JsonObject object, String field) throws ProtocolException {
		JsonPrimitive primitive = requireNumber(object, field);
		return requireFiniteNumber(primitive, field);
	}

	private static double requireFiniteNumber(JsonPrimitive primitive, String field) throws ProtocolException {
		double value;
		try {
			value = primitive.getAsDouble();
		} catch (NumberFormatException exception) {
			throw invalidField("Field '" + field + "' must be a number");
		}
		if (!Double.isFinite(value)) {
			throw outOfRange(field, "a finite number");
		}
		return value;
	}

	private static JsonPrimitive requireNumber(JsonObject object, String field) throws ProtocolException {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw invalidField("Field '" + field + "' must be a number");
		}
		return element.getAsJsonPrimitive();
	}

	private static boolean requireBoolean(JsonObject object, String field) throws ProtocolException {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw invalidField("Field '" + field + "' must be a boolean");
		}
		return element.getAsBoolean();
	}

	private static String requireBoundedText(
			JsonObject object,
			String field,
			int maximumLength,
			boolean emptyAllowed
	) throws ProtocolException {
		String value = requireString(object, field);
		if (!emptyAllowed && value.isBlank()) {
			throw invalidField("Field '" + field + "' must not be blank");
		}
		if (value.length() > maximumLength) {
			throw outOfRange(field, "at most " + maximumLength + " characters");
		}
		return value;
	}

	private static String requireString(JsonObject object, String field) throws ProtocolException {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw invalidField("Field '" + field + "' must be a string");
		}
		return element.getAsString();
	}

	private static JsonElement requireField(JsonObject object, String field) throws ProtocolException {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_MISSING_FIELD,
					"Required field '" + field + "' is missing"
			);
		}
		return object.get(field);
	}

	private static void validateKnownFields(JsonObject command, ActionType actionType) throws ProtocolException {
		Set<String> allowedFields = new HashSet<>(ENVELOPE_FIELDS);
		allowedFields.addAll(ACTION_FIELDS.get(actionType));
		for (String field : command.keySet()) {
			if (!allowedFields.contains(field)) {
				throw unknownField(field, actionType);
			}
		}
	}

	private static void validateKnownArgumentFields(JsonObject arguments, ActionType actionType)
			throws ProtocolException {
		List<String> allowedFields = ACTION_FIELDS.get(actionType);
		for (String field : arguments.keySet()) {
			if (!allowedFields.contains(field)) {
				throw unknownField(field, actionType);
			}
		}
	}

	private static ProtocolException unknownField(String field, ActionType actionType) {
		return new ProtocolException(
				ProtocolConstants.ERROR_UNKNOWN_FIELD,
				"Unknown field '" + field + "' for action '" + actionType.wireName() + "'"
		);
	}

	private static ProtocolException invalidField(String message) {
		return new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, message);
	}

	private static ProtocolException outOfRange(String field, String expectedRange) {
		return new ProtocolException(
				ProtocolConstants.ERROR_OUT_OF_RANGE,
				"Field '" + field + "' must be " + expectedRange
		);
	}

	private static ProtocolException malformedJson(String message, Throwable cause) {
		return new ProtocolException(ProtocolConstants.ERROR_MALFORMED_JSON, message, cause);
	}

	private static Map<ActionType, List<String>> createActionFields() {
		Map<ActionType, List<String>> fields = new EnumMap<>(ActionType.class);
		fields.put(ActionType.MOVE_TO, List.of(FIELD_X, FIELD_Y, FIELD_Z, FIELD_TOLERANCE, FIELD_SPRINT));
		fields.put(ActionType.LOOK_AT, List.of(FIELD_X, FIELD_Y, FIELD_Z));
		fields.put(ActionType.ATTACK, List.of(FIELD_TARGET_SELECTOR, FIELD_TIMEOUT_MS));
		fields.put(ActionType.SELECT_ITEM, List.of(FIELD_ITEM_ID));
		fields.put(ActionType.USE_ITEM, List.of(FIELD_DURATION_MS));
		fields.put(ActionType.BREAK_BLOCK, List.of(FIELD_X, FIELD_Y, FIELD_Z, FIELD_TIMEOUT_MS));
		fields.put(ActionType.PLACE_BLOCK, List.of(FIELD_X, FIELD_Y, FIELD_Z, FIELD_FACE, FIELD_ITEM_ID));
		fields.put(ActionType.CHAT, List.of(FIELD_MESSAGE));
		fields.put(ActionType.WAIT, List.of(FIELD_DURATION_MS));
		fields.put(ActionType.COMPLETE_GOAL, List.of(FIELD_SUMMARY));
		return Map.copyOf(fields);
	}
}
