package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import java.util.Objects;

public record BridgeEnvelope(
		int protocolVersion,
		String serverInstanceId,
		String agentId,
		String type,
		String messageId,
		JsonObject payload
) {
	public BridgeEnvelope {
		if (protocolVersion != 2) {
			throw new BridgeProtocolException("UNSUPPORTED_VERSION", "Expected protocol version 2");
		}
		serverInstanceId = identifier(serverInstanceId, "serverInstanceId");
		agentId = identifier(agentId, "agentId");
		type = identifier(type, "type");
		messageId = text(messageId, "messageId", 128);
		payload = Objects.requireNonNull(payload, "payload must not be null").deepCopy();
	}

	@Override
	public JsonObject payload() {
		return payload.deepCopy();
	}

	private static String identifier(String value, String field) {
		return text(value, field, 256);
	}

	private static String text(String value, String field, int maximum) {
		if (value == null || value.isBlank() || value.length() > maximum) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must contain at most " + maximum + " characters");
		}
		return value;
	}
}
