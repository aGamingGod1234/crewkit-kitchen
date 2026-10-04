package dev.agaminggod.arenaagents.control;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class AgentControlSnapshotCodec {
	private static final Gson GSON = new Gson();
	// A complete schema-7 aggregate must fit, including Gson's worst-case six-byte
	// JSON escapes per UTF-16 unit. Fixed allowances cover keys, punctuation and
	// numeric/boolean fields. This is below the clientbound custom payload's 1 MiB.
	private static final int AGENT_TEXT_UNITS = 36 + AgentConstants.SHORT_ID_LENGTH
			+ AgentControlAgent.MAX_DISPLAY_NAME_LENGTH + AgentConstants.MAX_USER_NAME_LENGTH
			+ 2 * AgentConstants.MAX_REASONING_LENGTH + AgentConstants.MAX_MODEL_LENGTH + 16
			+ AgentControlAgent.MAX_STATE_LENGTH + AgentControlAgent.MAX_CURRENT_GOAL_LENGTH
			+ AgentControlAgent.MAX_LAST_SUMMARY_LENGTH + AgentControlAgent.MAX_LAST_ERROR_LENGTH;
	private static final int MODEL_TEXT_UNITS = 24 + 128 + 96 + 12 * 32 + 8 * 24;
	public static final int MAX_ENCODED_BYTES = 1_024 + 6 * 160
			+ AgentControlSnapshot.MAX_AGENTS * (512 + 6 * AGENT_TEXT_UNITS)
			+ AgentControlGroup.MAX_GROUPS * (64 + 12 * AgentControlGroup.MAX_NAME_CODE_POINTS
					+ 39 * AgentControlGroup.MAX_MEMBERS)
			+ AgentControlModelOption.MAX_OPTIONS * (256 + 6 * MODEL_TEXT_UNITS);
	@Deprecated(forRemoval = false)
	public static final int MAX_ENCODED_LENGTH = MAX_ENCODED_BYTES;

	private AgentControlSnapshotCodec() {
	}

	public static String encode(AgentControlSnapshot snapshot) {
		String encoded = GSON.toJson(Objects.requireNonNull(snapshot, "snapshot must not be null"));
		if (utf8Bytes(encoded) > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		return encoded;
	}

	public static AgentControlSnapshot decode(String encoded) {
		String checked = Objects.requireNonNull(encoded, "encoded must not be null");
		if (utf8Bytes(checked) > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		try {
			JsonObject object = JsonParser.parseString(checked).getAsJsonObject();
			if (schemaVersion(object) != AgentControlSnapshot.SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported control snapshot schema");
			}
			AgentControlSnapshot snapshot = GSON.fromJson(object, AgentControlSnapshot.class);
			if (snapshot == null) {
				throw new IllegalArgumentException("Control snapshot must be a JSON object");
			}
			return new AgentControlSnapshot(
					snapshot.schemaVersion(),
					snapshot.canControl(),
					snapshot.automationAvailable(),
					snapshot.automationStatus(),
					snapshot.generatedAtEpochMs(),
					snapshot.agents(),
					snapshot.groups(),
					snapshot.catalog()
			);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid control snapshot", exception);
		}
	}

	private static int utf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static int schemaVersion(JsonObject object) {
		JsonElement value = object.get("schemaVersion");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("Control snapshot schema must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw new IllegalArgumentException("Control snapshot schema must be an integer", exception);
		}
	}
}
