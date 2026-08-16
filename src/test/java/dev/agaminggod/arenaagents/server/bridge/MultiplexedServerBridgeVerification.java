package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MultiplexedServerBridgeVerification {
	private MultiplexedServerBridgeVerification() {
	}

	public static int verify() {
		List<AgentRecord> registered = new ArrayList<>();
		for (int index = 0; index <= AgentConstants.DEFAULT_AGENT_LIMIT; index++) {
			registered.add(AgentRecord.create(
					AgentId.parse(String.format("00000000-0000-0000-0000-%012d", index + 1)),
					new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), index),
					1_000L + index
			));
		}
		List<AgentId> candidates = MultiplexedServerBridge.registeredObservationIds(registered);
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, candidates.size(),
				"publication remains capped at sixteen despite a malformed seventeen-agent registry");
		ObservationDispatchQueue<AgentId> publicationQueue = new ObservationDispatchQueue<>(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_AGENT_LIMIT
		);
		candidates.forEach(publicationQueue::offer);
		List<AgentId> published = new ArrayList<>();
		publicationQueue.drain(published::add);
		assertEquals(candidates, published, "all sixteen registered agents publish within one server tick");
		assertEquals(0, publicationQueue.pendingCount(), "one-tick publication drains the bounded queue");
		AgentId idleAgent = registered.getFirst().agentId();
		assertTrue(idleAgent.equals(published.getFirst()),
				"an idle registered agent without an active action is sampled and published");

		List<String> events = new ArrayList<>();
		assertThrows(IllegalStateException.class, () -> MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> { throw new IllegalStateException("publication failed"); },
				() -> events.add("action"),
				() -> events.add("state")
		), "failed respawn publication emits no scenario success or state");
		assertTrue(events.isEmpty(), "failed respawn publication leaves scenario records untouched");
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> events.add("publication"),
				() -> events.add("action"),
				() -> events.add("state")
		);
		assertEquals(List.of("publication", "action", "state"), events,
				"respawn scenario success and PAUSED/IDLE state follow committed paired publication");
		List<String> committed = new ArrayList<>();
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> committed.add("paired-messages-and-commit"),
				() -> { committed.add("action-attempted"); throw new IllegalStateException("telemetry unavailable"); },
				() -> committed.add("state-after-telemetry-failure")
		);
		assertEquals(List.of("paired-messages-and-commit", "action-attempted", "state-after-telemetry-failure"), committed,
				"scenario callback failure cannot escape or roll back committed respawn publication");
		verifyExactTargetObservationLedger(registered.getFirst().agentId());
		return 14;
	}

	private static void verifyExactTargetObservationLedger(AgentId agent) {
		MultiplexedServerBridge.PublishedObservationState state = new MultiplexedServerBridge.PublishedObservationState(16);
		JsonObject observation = new JsonObject();
		observation.addProperty("eventSequence", 1L);
		JsonArray entities = new JsonArray();
		entities.add(entity("00000000-0000-0000-0000-000000000002"));
		entities.add(entity("00000000-0000-0000-0000-000000000001"));
		observation.add("entities", entities);
		state.commit(agent, observation);
		state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001");
		assertThrowsCode(() -> state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000003"), "TARGET_NOT_OBSERVED");
		for (long sequence = 2; sequence <= 65; sequence++) {
			JsonObject next = new JsonObject();
			next.addProperty("eventSequence", sequence);
			next.add("entities", new JsonArray());
			state.commit(agent, next);
		}
		assertThrowsCode(() -> state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001"), "STALE_FACTS");
		state.remove(agent);
		assertThrowsCode(() -> state.requireObservedTarget(agent, 65L, "00000000-0000-0000-0000-000000000001"), "TARGET_NOT_OBSERVED");
	}

	private static JsonObject entity(String uuid) {
		JsonObject entity = new JsonObject();
		entity.addProperty("uuid", uuid);
		return entity;
	}

	private static void assertThrowsCode(Runnable action, String code) {
		try {
			action.run();
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), "target observation rejection code");
			return;
		}
		throw new AssertionError("expected " + code);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + type.getSimpleName());
	}
}
