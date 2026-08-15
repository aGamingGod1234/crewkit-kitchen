package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;

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
		JsonObject waitArguments = new JsonObject();
		waitArguments.addProperty("durationMs", 25L);
		ServerActionRequest primitive = MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-2",
				actionPayload("wait-1", ActionType.WAIT.wireName(), waitArguments)
		));
		assertEquals(ActionType.WAIT, primitive.type(), "primitive action decodes through the bridge");
		assertEquals("program-1-1", primitive.provenance().programId(), "bridge attaches immutable provenance");
		JsonObject missingProvenance = actionPayload("wait-2", ActionType.WAIT.wireName(), waitArguments);
		missingProvenance.remove("provenance");
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-3", missingProvenance
		)), "MISSING_FIELD");
		JsonObject fightArguments = new JsonObject();
		fightArguments.addProperty("targetSelector", "nearest_hostile");
		fightArguments.addProperty("desiredRange", 2.5D);
		fightArguments.addProperty("timeoutMs", 5_000L);
		JsonObject fightPayload = actionPayload("fight-1", ActionType.FIGHT_TARGET.wireName(), fightArguments);
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-2", fightPayload
		)), "UNSUPPORTED_ARENA_SCRIPT_ACTION");
		return 11;
	}

	private static JsonObject actionPayload(String actionId, String type, JsonObject arguments) {
		JsonObject provenance = new JsonObject();
		provenance.addProperty("provider", "codex");
		provenance.addProperty("model", "gpt-5.6-sol");
		provenance.addProperty("reasoningEffort", "high");
		provenance.addProperty("serviceTier", "priority");
		provenance.addProperty("programId", "program-1-1");
		provenance.addProperty("programVersion", 1L);
		provenance.addProperty("sourceStepId", "step-80-126");
		provenance.addProperty("eventSequence", 4L);
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", 1L);
		payload.addProperty("actionId", actionId);
		payload.addProperty("actionType", type);
		payload.add("arguments", arguments);
		payload.add("provenance", provenance);
		return payload;
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
