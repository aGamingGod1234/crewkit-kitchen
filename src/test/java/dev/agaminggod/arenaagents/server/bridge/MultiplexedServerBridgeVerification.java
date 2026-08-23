package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.conversation.ConversationAudience;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.ConversationKind;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWakeCodec;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

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
		verifyDeathFacts();
		verifyTraceWireValidation();
		verifyExactTargetObservationLedger(registered.getFirst().agentId());
		verifyConversationAttention(registered.getFirst().agentId());
		verifyObservationCadence(candidates);
		verifyObservationPublicationLifecycle(registered.getFirst().agentId());
		verifyRealBridgeSessionLifecycle();
		verifyAtomicConversationWakePublication();
		verifyCompletionResultFacts();
		return 54;
	}

	private static void verifyCompletionResultFacts() {
		GoalCompletionVerifier.VerificationResult verification = new GoalCompletionVerifier.VerificationResult(
				false,
				7L,
				"PREDICATE_FAILED",
				List.of(
						new GoalCompletionVerifier.Fact(0, "inventory_min", false, "0"),
						new GoalCompletionVerifier.Fact(1, "position_within", true, "1.25")
				)
		);
		JsonObject payload = MultiplexedServerBridge.completionResultPayload(7L, "trace-java-1", "sha256:contract", verification);
		assertEquals(2, payload.getAsJsonArray("facts").size(), "completion result retains every verifier fact");
		assertEquals(0, payload.getAsJsonArray("facts").get(0).getAsJsonObject().get("predicateIndex").getAsInt(), "completion result retains failed predicate index");
		assertEquals("0", payload.getAsJsonArray("facts").get(0).getAsJsonObject().get("observedValue").getAsString(), "completion result retains observed value");
	}

	private static void verifyTraceWireValidation() {
		AgentId agent = AgentId.parse("00000000-0000-0000-0000-000000000001");
		String traceId = "trace-java-1";
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-1-1", 1L, "step-1", 1L
		);
		ActionProvenance tracedProvenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-1-1", 1L, "step-1", 1L, traceId
		);
		JsonObject payload = new JsonObject();
		payload.addProperty("traceId", traceId);
		payload.addProperty("goalRevision", 1L);
		payload.addProperty("actionId", "action-1");
		payload.addProperty("actionType", "wait");
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 1L);
		payload.add("arguments", arguments);
		JsonObject wireProvenance = new JsonObject();
		wireProvenance.addProperty("provider", "codex");
		wireProvenance.addProperty("model", "gpt-5.6-sol");
		wireProvenance.addProperty("reasoningEffort", "high");
		wireProvenance.addProperty("serviceTier", "priority");
		wireProvenance.addProperty("programId", "program-1-1");
		wireProvenance.addProperty("programVersion", 1L);
		wireProvenance.addProperty("sourceStepId", "step-1");
		wireProvenance.addProperty("eventSequence", 1L);
		wireProvenance.addProperty("traceId", traceId);
		wireProvenance.addProperty("watcherId", "watcher-0");
		payload.add("provenance", wireProvenance);
		ServerActionRequest request = MultiplexedServerBridge.decodeActionRequest(
				new BridgeEnvelope(2, "server-instance", agent.toString(), "action_command", "message-1", payload)
		);
		assertEquals(traceId, request.traceId(), "action request retains the trace ID");
		assertEquals("watcher-0", request.provenance().watcherId(), "action request retains watcher provenance");
		ServerActionProgress progress = new ServerActionProgress(agent, 1L, "action-1", ActionType.WAIT, traceId, 0.5D, 1L, 2L);
		ServerActionResult result = new ServerActionResult(agent, 1L, "action-1", ActionType.WAIT, traceId, ServerActionState.SUCCEEDED, "DONE", "", 2L, 3L);
		assertEquals(traceId, progress.traceId(), "first progress retains the action trace ID");
		assertEquals(traceId, result.traceId(), "terminal result retains the action trace ID");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, tracedProvenance, "trace-other"),
				"direct request construction rejects mismatched top-level and provenance traces");
		ServerActionRequest legacy = new ServerActionRequest(agent, 1L, "action-legacy", ActionType.WAIT, arguments, provenance);
		assertThrowsCode(() -> new ServerActionExecutor(uninitializedManager(), ignored -> { }).submitProgramPrimitive(legacy), "MISSING_TRACE_ID");
		JsonObject mismatched = payload.deepCopy();
		mismatched.getAsJsonObject("provenance").addProperty("traceId", "trace-other");
		assertThrows(BridgeProtocolException.class, () -> MultiplexedServerBridge.decodeActionRequest(
				new BridgeEnvelope(2, "server-instance", agent.toString(), "action_command", "message-mismatch", mismatched)),
				"mismatched wire trace IDs fail closed");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, provenance, ""), "blank trace ID is typed validation");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, provenance, "🙂".repeat(40)), "overlong UTF-8 trace ID is typed validation");
	}

	private static void verifyAtomicConversationWakePublication() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-conversation-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord idle = manager.registry().create("gpt-5.6-sol", "high", Optional.of("WakeTarget"), 1_000L);
			ConversationEvent event = new ConversationEvent(
					idle.agentId(), "00000000-0000-0000-0000-000000000099", idle.agentId().toString(),
					ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Can you respond?",
					0L, 1_001L, 1L, "minecraft:overworld"
			);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			MultiplexedServerBridge activeBridge = bridge;
			assertThrowsCode(
					() -> activeBridge.publishConversationEvent(event, Optional.of("Respond to the player message.")),
					"COORDINATOR_DISCONNECTED"
			);
			assertEquals(idle, manager.registry().require(idle.agentId()),
					"disconnected conversation publication leaves the exact idle record");

			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			String transactionId;
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject helloPayload = new JsonObject();
				helloPayload.addProperty("secret", secret);
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-atomic-wake", helloPayload
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(), "conversation fixture authenticates the bridge");

				bridge.publishConversationEvent(event, Optional.of("Respond to the player message."));
				BridgeEnvelope wake = codec.decode(reader.readLine());
				assertEquals("conversation_wake", wake.type(), "conversation and lifecycle start cross the wire as one transaction");
				transactionId = wake.payload().get("transactionId").getAsString();
				assertEquals(
						manager.pendingConversationWakes().getFirst(),
						new PendingConversationWakeCodec().decode(
								new PendingConversationWakeCodec().encode(manager.pendingConversationWakes().getFirst())
						),
						"durable conversation wake round-trips without losing transaction identity or message context"
				);
				AgentSavedData restored = roundTripSavedData(savedData(manager));
				assertEquals(manager.pendingConversationWakes(), restored.conversationWakes(),
						"Minecraft SavedData round-trip retains the durable wake outbox");
				assertEquals(AgentLifecycleState.STARTING, restored.registry().require(idle.agentId()).state(),
						"Minecraft SavedData restore re-arms the pending wake at the same lifecycle boundary");
				assertEquals(1L, restored.registry().require(idle.agentId()).goalRevision(),
						"Minecraft SavedData restore preserves the pending wake revision");
				JsonObject conversation = wake.payload().getAsJsonObject("event");
				JsonObject control = wake.payload().getAsJsonObject("control");
				assertEquals(0L, conversation.get("goalRevision").getAsLong(), "conversation event retains the prior goal revision");
				assertEquals("start", control.get("operation").getAsString(), "conversation wake publishes a start operation");
				assertEquals(1L, control.get("goalRevision").getAsLong(), "conversation wake control advances the goal revision once");
				assertEquals("Respond to the player message.", control.get("goal").getAsString(), "conversation wake carries the generated response goal");
				assertEquals(AgentLifecycleState.STARTING, manager.registry().require(idle.agentId()).state(),
						"durable publication commits STARTING only after the transaction is staged");
			}
			MultiplexedServerBridge activeBridgeAfterDisconnect = bridge;
			awaitCondition(() -> !activeBridgeAfterDisconnect.observationPublicationForVerification().hasActiveSession(),
					"unacknowledged wake fixture observes its failed connection");
			bridge.tick();
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(idle.agentId()).state(),
					"an unacknowledged wake remains armed across coordinator disconnect");
			assertEquals(1L, manager.registry().require(idle.agentId()).goalRevision(),
					"disconnect recovery does not manufacture another goal revision");

			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject helloPayload = new JsonObject();
				helloPayload.addProperty("secret", secret);
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-replay-wake", helloPayload
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "replay fixture authenticates the bridge");
				BridgeEnvelope replay = codec.decode(reader.readLine());
				assertEquals("conversation_wake", replay.type(), "unacknowledged conversation wake is replayed after reconnect");
				assertEquals(transactionId, replay.payload().get("transactionId").getAsString(),
						"replay retains the stable transaction identity");
				assertEquals(1L, replay.payload().getAsJsonObject("control").get("goalRevision").getAsLong(),
						"replay retains the original goal revision");
				JsonObject acknowledgement = new JsonObject();
				acknowledgement.addProperty("transactionId", transactionId);
				acknowledgement.addProperty("goalRevision", 1L);
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, helloAck.serverInstanceId(), idle.agentId().toString(), "conversation_wake_ack",
						"ack-replayed-wake", acknowledgement
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
				MultiplexedServerBridge activeBridgeAfterAck = bridge;
				awaitCondition(() -> {
					activeBridgeAfterAck.tick();
					return manager.pendingConversationWakes().getFirst().acknowledged();
				}, "matching coordinator acknowledgement is persisted");
				assertEquals(1, manager.pendingConversationWakes().size(),
						"acknowledgement retains replay context until the wake goal is terminal");
				manager.registry().coordinatorCompleted(idle.agentId(), 1L, System.currentTimeMillis());
				assertTrue(manager.pendingConversationWakes().isEmpty(),
						"terminal wake goal clears its durable replay context");
			}
		} catch (Exception exception) {
			throw new AssertionError("atomic conversation wake publication failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary conversation bridge secret", exception);
				}
			}
		}
	}

	private static void verifyConversationAttention(AgentId agentId) {
		MultiplexedServerBridge.PublishedObservationState state =
				new MultiplexedServerBridge.PublishedObservationState(AgentConstants.DEFAULT_AGENT_LIMIT);
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", 1_000L);
		assertTrue(state.markAttention(agentId), "conversation attention is retained for publication");
		var forced = state.delta(agentId, observation, 1L, 1_000L);
		assertTrue(forced.attention() && forced.changedFacts().isEmpty(),
				"conversation triggers attention without inventing factual changes");
		observation.addProperty("eventSequence", 1L);
		state.commit(agentId, observation);
		assertTrue(!state.delta(agentId, observation, 2L, 1_001L).attention(),
				"committed conversation attention is exact once");
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

	private static BridgeEnvelope pollBridgeResponse(
			MultiplexedServerBridge bridge,
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec
	) throws Exception {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < deadline) {
			bridge.tick();
			if (socket.getInputStream().available() > 0) return codec.decode(reader.readLine());
			Thread.sleep(5L);
		}
		throw new AssertionError("bridge did not publish a response");
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

	private static void verifyObservationCadence(List<AgentId> agents) {
		MultiplexedServerBridge.ObservationPublication publication =
				new MultiplexedServerBridge.ObservationPublication(16, 16);
		List<AgentId> heartbeats = new ArrayList<>();
		for (int tick = 0; tick < agents.size(); tick++) {
			publication.scheduleIdleHeartbeat(agents);
			List<AgentId> emitted = new ArrayList<>();
			publication.drain(emitted::add);
			assertEquals(1, emitted.size(), "idle heartbeat is bounded to one agent per tick");
			heartbeats.addAll(emitted);
		}
		assertEquals(agents, heartbeats, "idle heartbeat rotates through all registered agents");

		Object session = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, session);
		AgentId agent = agents.getFirst();
		JsonObject first = observation("00000000-0000-0000-0000-000000000002", 1_000L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, first, (ignoredAgent, ignoredPayload) -> true, false),
				"the first urgent observation establishes a delivered baseline");
		JsonObject unchanged = first.deepCopy();
		unchanged.addProperty("observedAtEpochMs", 1_001L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.SUPPRESSED,
				publication.publish(agent, session, unchanged, (ignoredAgent, ignoredPayload) -> true, false),
				"unchanged event-driven observations are suppressed");
		assertTrue(publication.markAttention(agent), "urgent attention can bypass unchanged suppression");
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, unchanged, (ignoredAgent, ignoredPayload) -> true, false),
				"urgent attention publishes even when facts are unchanged");
		JsonObject heartbeat = unchanged.deepCopy();
		heartbeat.addProperty("observedAtEpochMs", 1_002L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, heartbeat, (ignoredAgent, ignoredPayload) -> true, true),
				"a scheduled heartbeat publishes unchanged facts");
		publication.scheduleIdleHeartbeat(agents);
		assertTrue(publication.pendingCount() > 0, "heartbeat remains queued before a session reset");
		MultiplexedServerBridge.onSessionClosed(publication, session);
		assertEquals(0, publication.pendingCount(), "session cleanup clears pending idle heartbeats");
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
		state.retainConversationSource(agent, "00000000-0000-0000-0000-000000000004");
		state.requireDirectMessageRecipient(agent, 1L, "00000000-0000-0000-0000-000000000004");
		assertThrowsCode(() -> state.requireDirectMessageRecipient(agent, 1L, "00000000-0000-0000-0000-000000000003"), "TARGET_NOT_OBSERVED");
		for (long sequence = 2; sequence <= 2_401; sequence++) {
			JsonObject next = new JsonObject();
			next.addProperty("eventSequence", sequence);
			next.add("entities", new JsonArray());
			state.commit(agent, next);
		}
		state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001");
		for (long sequence = 2_402; sequence <= 4_097; sequence++) {
			JsonObject next = new JsonObject();
			next.addProperty("eventSequence", sequence);
			next.add("entities", new JsonArray());
			state.commit(agent, next);
		}
		assertThrowsCode(() -> state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001"), "STALE_FACTS");
		state.remove(agent);
		assertThrowsCode(() -> state.requireObservedTarget(agent, 4_097L, "00000000-0000-0000-0000-000000000001"), "TARGET_NOT_OBSERVED");
		assertThrowsCode(() -> state.requireDirectMessageRecipient(agent, 4_097L, "00000000-0000-0000-0000-000000000004"), "TARGET_NOT_OBSERVED");
	}

	private static AgentSavedData savedData(CodexAgentManager manager) {
		try {
			Field field = CodexAgentManager.class.getDeclaredField("savedData");
			field.setAccessible(true);
			return (AgentSavedData) field.get(manager);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not read manager SavedData", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTripSavedData(AgentSavedData data) {
		try {
			Field field = AgentSavedData.class.getDeclaredField("CODEC");
			field.setAccessible(true);
			Codec<AgentSavedData> codec = (Codec<AgentSavedData>) field.get(null);
			Tag encoded = codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
			return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not round-trip Minecraft SavedData", exception);
		}
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
