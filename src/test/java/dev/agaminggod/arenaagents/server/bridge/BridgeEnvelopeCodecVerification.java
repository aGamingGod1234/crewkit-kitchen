package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;

public final class BridgeEnvelopeCodecVerification {
	private BridgeEnvelopeCodecVerification() {
	}

	public static int verify() {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", 7L);
		BridgeEnvelope source = new BridgeEnvelope(2, "server-instance", "agent-id", "observation", "message-1", payload);
		BridgeEnvelope decoded = codec.decode(codec.encode(source).trim());
		assertEquals(source.protocolVersion(), decoded.protocolVersion(), "protocol version");
		assertEquals(source.serverInstanceId(), decoded.serverInstanceId(), "server instance");
		assertEquals(source.agentId(), decoded.agentId(), "agent ID");
		assertEquals(source.type(), decoded.type(), "message type");
		assertEquals(7L, decoded.payload().get("goalRevision").getAsLong(), "payload revision");
		expectFailure(() -> codec.decode("{}"), "MISSING_FIELD");
		expectFailure(() -> codec.decode("x".repeat(BridgeEnvelopeCodec.MAX_LINE_BYTES + 1)), "LINE_TOO_LARGE");
		return 7;
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("Expected failure " + code);
		} catch (BridgeProtocolException exception) {
			assertEquals(code, exception.code(), "failure code");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
