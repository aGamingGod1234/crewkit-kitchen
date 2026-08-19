package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MultiplexedServerBridgeVerification {
	private MultiplexedServerBridgeVerification() {
	}

	public static int verify() {
		String previousBridgePort = System.getProperty("arenaagents.bridgePort");
		try {
			System.setProperty("arenaagents.bridgePort", "25571");
			assertEquals(25_571, MultiplexedServerBridge.configuredPort(), "headless bridge port property");
			System.setProperty("arenaagents.bridgePort", "70000");
			assertThrows(IllegalArgumentException.class, MultiplexedServerBridge::configuredPort, "out-of-range bridge port property");
		} finally {
			if (previousBridgePort == null) System.clearProperty("arenaagents.bridgePort");
			else System.setProperty("arenaagents.bridgePort", previousBridgePort);
		}
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
		AgentRecord terminalBefore = AgentLifecycleReducer.start(registered.getFirst(), "finish", 2_000L).after();
		AgentRecord terminalAfter = terminalBefore.withLifecycle(AgentLifecycleState.COMPLETED, terminalBefore.currentGoal(),
				terminalBefore.goalRevision(), terminalBefore.queuedGoals(), 2_001L, "");
		assertTrue(MultiplexedServerBridge.operation(new AgentTransition(terminalBefore, terminalAfter, true, true)) == null,
				"coordinator-owned terminal completion does not echo goal_control");
		List<String> committed = new ArrayList<>();
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> committed.add("paired-messages-and-commit"),
				() -> { committed.add("action-attempted"); throw new IllegalStateException("telemetry unavailable"); },
				() -> committed.add("state-after-telemetry-failure")
		);
		assertEquals(List.of("paired-messages-and-commit", "action-attempted", "state-after-telemetry-failure"), committed,
				"scenario callback failure cannot escape or roll back committed respawn publication");
		verifyDeathFacts();
		verifyExactTargetObservationLedger(registered.getFirst().agentId());
		verifyObservationPublicationLifecycle(registered.getFirst().agentId());
		verifyRealBridgeSessionLifecycle();
		return 38;
	}

	private static void verifyDeathFacts() {
		AgentDeathSnapshot death = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:the_nether", 12.5D, 64.0D, -3.5D,
				Optional.of("minecraft:overworld"), Optional.of(100.5D), Optional.of(70.0D), Optional.of(-20.5D),
				Optional.of(37.5F), Optional.of(-12.25F), Optional.of(true), "spectator", 2_000L
		);
		JsonObject facts = MultiplexedServerBridge.deathFacts(death);
		assertEquals("minecraft:overworld", facts.get("respawnDimensionId").getAsString(), "death facts expose respawn dimension");
		assertEquals(100.5D, facts.get("respawnX").getAsDouble(), "death facts expose respawn x");
		assertEquals(70.0D, facts.get("respawnY").getAsDouble(), "death facts expose respawn y");
		assertEquals(-20.5D, facts.get("respawnZ").getAsDouble(), "death facts expose respawn z");
		assertEquals(37.5F, facts.get("respawnYaw").getAsFloat(), "death facts expose respawn yaw");
		assertEquals(-12.25F, facts.get("respawnPitch").getAsFloat(), "death facts expose respawn pitch");
		assertEquals(true, facts.get("respawnForced").getAsBoolean(), "death facts expose forced respawn flag");
		assertEquals("spectator", facts.get("gameMode").getAsString(), "death facts expose game mode");
		AgentDeathSnapshot noConfiguredRespawn = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:overworld", 12.5D, 64.0D, -3.5D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), "survival", 2_001L
		);
		JsonObject envelopePayload = new JsonObject();
		envelopePayload.add("death", MultiplexedServerBridge.deathFacts(noConfiguredRespawn));
		BridgeEnvelope roundTrip = new BridgeEnvelopeCodec().decode(new BridgeEnvelopeCodec().encode(
				new BridgeEnvelope(2, "server-instance", "server", "hello_ack", "death-null-check", envelopePayload)
		));
		JsonObject encodedDeath = roundTrip.payload().getAsJsonObject("death");
		for (String field : List.of("respawnDimensionId", "respawnX", "respawnY", "respawnZ", "respawnYaw", "respawnPitch", "respawnForced")) {
			assertTrue(encodedDeath.has(field) && encodedDeath.get(field).isJsonNull(),
					"encoded death facts retain explicit null " + field);
		}
	}

	private static void verifyRealBridgeSessionLifecycle() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			secretFile = Files.createTempFile("arena-agents-bridge-secret-", ".txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			assertTrue(!activeBridge.observationPublicationForVerification().hasActiveSession(),
					"bridge starts without an accepted session");
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, activeBridge.boundPortForVerification())) {
				awaitCondition(activeBridge.observationPublicationForVerification()::hasActiveSession,
						"accept loop activates publication for a connected session");
			}
			awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
					"session close deactivates publication and clears lifecycle ownership");
		} catch (Exception exception) {
			throw new AssertionError("real bridge session lifecycle failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary bridge secret", exception);
				}
			}
		}
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	private static void awaitCondition(java.util.function.BooleanSupplier condition, String label) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertTrue(condition.getAsBoolean(), label);
	}

	private static void verifyObservationPublicationLifecycle(AgentId agent) {
		MultiplexedServerBridge.ObservationPublication publication = new MultiplexedServerBridge.ObservationPublication(16, 16);
		Object oldSession = new Object();
		Object newSession = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, oldSession);
		JsonObject oldObservation = observation("00000000-0000-0000-0000-000000000002", 1_000L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, oldSession, oldObservation, (ignoredAgent, ignoredPayload) -> true),
				"old session writer commits its observation before disconnect");
		publication.offer(agent);
		assertEquals(1, publication.retainedCount(), "old session baseline is retained before reset");
		assertEquals(1, publication.pendingCount(), "old session queue is retained before reset");

		MultiplexedServerBridge.onSessionClosed(publication, oldSession);
		MultiplexedServerBridge.onSessionAccepted(publication, newSession);
		AtomicBoolean staleWriterCalled = new AtomicBoolean();
		JsonObject staleObservation = observation("00000000-0000-0000-0000-000000000003", 1_001L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.STALE_SESSION,
				publication.publish(agent, oldSession, staleObservation, (ignoredAgent, ignoredPayload) -> {
					staleWriterCalled.set(true);
					return true;
				}),
				"old session observation cannot publish after reset activates a new session");
		assertTrue(!staleWriterCalled.get(), "new session writer never receives stale old-session observation");
		assertEquals(0, publication.retainedCount(), "reset clears old delivered baseline before new session");
		assertEquals(0, publication.pendingCount(), "reset clears old queued observation before new session");

		List<JsonObject> freshDeliveries = new ArrayList<>();
		JsonObject freshObservation = observation("00000000-0000-0000-0000-000000000003", 1_002L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, newSession, freshObservation, (ignoredAgent, payload) -> {
					freshDeliveries.add(payload.deepCopy());
					return true;
				}),
				"new session writer commits a fresh observation");
		assertEquals(1, freshDeliveries.size(), "new session receives exactly its fresh observation");
		assertTrue(!freshDeliveries.getFirst().get("attention").getAsBoolean(),
				"fresh observation starts from an empty reset baseline");
		assertEquals(1, publication.retainedCount(), "new session baseline is retained after commit");
	}

	private static JsonObject observation(String targetId, long observedAtEpochMs) {
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", observedAtEpochMs);
		JsonArray entities = new JsonArray();
		entities.add(entity(targetId));
		observation.add("entities", entities);
		return observation;
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
