package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class BridgeEnvelopeCodec {
	public static final int MAX_LINE_BYTES = 65_536;
	private static final Set<String> FIELDS = Set.of(
			"protocolVersion", "serverInstanceId", "agentId", "type", "messageId", "payload"
	);
	private static final Gson GSON = new Gson();

	public BridgeEnvelope decode(String line) {
		if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Protocol line exceeds " + MAX_LINE_BYTES + " bytes");
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(line);
		} catch (RuntimeException exception) {
			throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line is not valid JSON", exception);
		}
		if (!parsed.isJsonObject()) {
			throw new BridgeProtocolException("INVALID_ENVELOPE", "Protocol envelope must be an object");
		}
		JsonObject object = parsed.getAsJsonObject();
		for (String field : FIELDS) {
			if (!object.has(field)) {
				throw new BridgeProtocolException("MISSING_FIELD", "Missing envelope field: " + field);
			}
		}
		for (String field : object.keySet()) {
			if (!FIELDS.contains(field)) {
				throw new BridgeProtocolException("INVALID_FIELD", "Unknown envelope field: " + field);
			}
		}
		if (!object.get("payload").isJsonObject()) {
			throw new BridgeProtocolException("INVALID_FIELD", "payload must be an object");
		}
		return new BridgeEnvelope(
				object.get("protocolVersion").getAsInt(),
				object.get("serverInstanceId").getAsString(),
				object.get("agentId").getAsString(),
				object.get("type").getAsString(),
				object.get("messageId").getAsString(),
				object.getAsJsonObject("payload")
		);
	}

	public String encode(BridgeEnvelope envelope) {
		JsonObject object = new JsonObject();
		object.addProperty("protocolVersion", envelope.protocolVersion());
		object.addProperty("serverInstanceId", envelope.serverInstanceId());
		object.addProperty("agentId", envelope.agentId());
		object.addProperty("type", envelope.type());
		object.addProperty("messageId", envelope.messageId());
		object.add("payload", envelope.payload());
		String encoded = GSON.toJson(object);
		if (encoded.getBytes(StandardCharsets.UTF_8).length + 1 > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Encoded protocol line exceeds wire limit");
		}
		return encoded + "\n";
	}
}
