package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class CoordinatorStatusVerification {
	private CoordinatorStatusVerification() {
	}

	public static int verify() {
		JsonObject payload = payload();
		CoordinatorStatusSnapshot snapshot = MultiplexedServerBridge.decodeCoordinatorStatus(payload, 1_000L);
		assertTrue(snapshot.reconciled(), "reconciled status decoded");
		assertTrue(snapshot.supports("agent-a", "codex", "gpt-5.6-sol", "high"), "supported identity decoded");
		assertTrue(snapshot.fresh(3_500L, 2_500L), "freshness boundary inclusive");
		assertTrue(snapshot.latencies().size() == 1, "latency health decoded");
		JsonObject legacy = payload();
		legacy.remove("latencies");
		assertTrue(MultiplexedServerBridge.decodeCoordinatorStatus(legacy, 1_000L).latencies().isEmpty(),
				"legacy status without latencies remains compatible");
		JsonObject numericIdentity = payload();
		numericIdentity.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("provider", 7);
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(numericIdentity, 1_000L), "numeric status identity rejected");
		payload.addProperty("prompt", "must never cross this seam");
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(payload, 1_000L), "unknown private field rejected");
		JsonObject invalidLatency = payload();
		invalidLatency.getAsJsonArray("latencies").get(0).getAsJsonObject().addProperty("p95Ms", -1);
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(invalidLatency, 1_000L), "invalid latency rejected");
		return 8;
	}

	private static JsonObject payload() {
		JsonObject payload = new JsonObject();
		payload.addProperty("reconciled", true);
		JsonArray profiles = new JsonArray();
		JsonObject profile = new JsonObject();
		profile.addProperty("agentId", "agent-a");
		profile.addProperty("provider", "codex");
		profile.addProperty("model", "gpt-5.6-sol");
		profile.addProperty("reasoningEffort", "high");
		profiles.add(profile);
		payload.add("profiles", profiles);
		payload.addProperty("supportedProfileCount", 1);
		payload.addProperty("rosterReadyCount", 1);
		payload.addProperty("rosterCount", 1);
		JsonObject scheduler = new JsonObject();
		scheduler.addProperty("active", 0);
		scheduler.addProperty("pending", 0);
		scheduler.addProperty("maxConcurrent", 4);
		scheduler.addProperty("maxPending", 12);
		scheduler.addProperty("warning", false);
		payload.add("scheduler", scheduler);
		JsonArray circuits = new JsonArray();
		JsonObject circuit = new JsonObject();
		circuit.addProperty("provider", "codex");
		circuit.addProperty("model", "gpt-5.6-sol");
		circuit.addProperty("operation", "decide");
		circuit.addProperty("count", 0);
		circuit.addProperty("p50Ms", 0);
		circuit.addProperty("p95Ms", 0);
		circuit.addProperty("failureRate", 0.0);
		circuit.addProperty("circuit", "closed");
		circuits.add(circuit);
		payload.add("circuits", circuits);
		JsonArray latencies = new JsonArray();
		JsonObject latency = new JsonObject();
		latency.addProperty("operation", "observation_to_plan");
		latency.addProperty("count", 8);
		latency.addProperty("p50Ms", 25);
		latency.addProperty("p95Ms", 80);
		latencies.add(latency);
		payload.add("latencies", latencies);
		return payload;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException expected) {
			return;
		}
		throw new AssertionError(label);
	}
}
