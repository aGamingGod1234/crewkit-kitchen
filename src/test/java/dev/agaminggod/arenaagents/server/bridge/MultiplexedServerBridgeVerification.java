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
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.AgentVerboseState;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

public final class MultiplexedServerBridgeVerification {
	private MultiplexedServerBridgeVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("MultiplexedServerBridgeVerification assertions=" + verify());
	}

	public static int verify() {
		verifyPendingRegistrationBoundary();
		verifyRemovalBackpressureForcesReconciliation();
		verifyHandshakeWaitsForPendingMarker();
		verifyReplacementHandshakeSupersedesPendingDisconnect();
		verifyAuthenticatedReconnectRecovery();
		verifyObsoletePlannerReadinessIsIgnored();
		verifyAgentErrorRevisionGate();
		verifyRespawnContinuationPayload();
		verifyVerboseTelemetryCannotSuppressProgress();
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
		return 123;
	}

	private static void verifyObsoletePlannerReadinessIsIgnored() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			secretFile = Files.createTempFile("arena-agents-obsolete-ready-secret-", ".txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			CodexAgentManager manager = uninitializedManager();
			AgentRecord created = manager.registry().create("gpt-5.6-sol", "high", Optional.of("LateReady"), 1_700L);
			AgentRecord started = manager.registry().start(created.agentId(), "keep working", 1_701L).after();
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			JsonObject stalePayload = new JsonObject();
			stalePayload.addProperty("goalRevision", started.goalRevision() - 1L);
			invokePlannerReady(bridge, new BridgeEnvelope(
					2, "coordinator", started.agentId().toString(), "agent_ready", "late-stale-ready", stalePayload
			));
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(started.agentId()).state(),
					"an obsolete readiness frame cannot mutate the current lifecycle");

			manager.registry().remove(started.agentId());
			JsonObject removedPayload = new JsonObject();
			removedPayload.addProperty("goalRevision", started.goalRevision());
			invokePlannerReady(bridge, new BridgeEnvelope(
					2, "coordinator", started.agentId().toString(), "agent_ready", "late-removed-ready", removedPayload
			));
			assertEquals(0, manager.registry().records().size(),
					"readiness for a removed agent is an idempotent no-op");
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not prepare obsolete readiness verification", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove obsolete readiness secret", exception);
				}
			}
		}
	}

	private static void verifyHandshakeWaitsForPendingMarker() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		Thread creator = null;
		CountDownLatch releaseCreation = new CountDownLatch(1);
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-atomic-pending-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			Set<AgentId> pending = pendingRegistrations(manager);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			manager.setRuntimeHooks(bridge);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			CountDownLatch recordVisible = new CountDownLatch(1);
			AtomicReference<AgentRecord> created = new AtomicReference<>();
			creator = Thread.ofPlatform().start(() -> activeBridge.withinPublicationBoundary(() -> {
				AgentRecord record = manager.registry().create(
						"gpt-5.6-sol", "high", Optional.of("AtomicSpawn"), 1_250L
				);
				created.set(record);
				recordVisible.countDown();
				awaitLatch(releaseCreation, "pending marker release");
				pending.add(record.agentId());
				return null;
			}));
			awaitLatch(recordVisible, "logical record creation");

			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject hello = new JsonObject();
				hello.addProperty("secret", secret);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-during-pending-marker", hello
				));
				Thread.sleep(50L);
				assertEquals(0, socket.getInputStream().available(),
						"handshake cannot snapshot a logical record before its pending marker is installed");
				releaseCreation.countDown();
				BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
				assertEquals(0, acknowledgement.payload().getAsJsonArray("registry").size(),
						"handshake excludes the atomically marked pending spawn");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"atomic pending fixture consumes verbose control");
			}
			creator.join(2_000L);
			assertTrue(!creator.isAlive(), "pending creation boundary finishes after the handshake snapshot is released");
			assertTrue(pending.contains(created.get().agentId()), "pending marker survives the serialized creation boundary");
		} catch (Exception exception) {
			throw new AssertionError("atomic pending creation verification failed", exception);
		} finally {
			releaseCreation.countDown();
			if (creator != null) {
				try {
					creator.join(2_000L);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove atomic-pending secret", exception);
				}
			}
		}
	}

	private static void verifyVerboseTelemetryCannotSuppressProgress() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-observational-verbose-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentVerboseState verboseState = new AgentVerboseState();
			verboseState.setEnabled(true);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile, verboseState);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject helloPayload = new JsonObject();
				helloPayload.addProperty("secret", secret);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-observational-verbose", helloPayload
				));
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
						"observational verbose fixture authenticates the bridge");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"observational verbose fixture consumes enabled control");
				AgentId removedAgent = AgentId.parse("00000000-0000-0000-0000-000000000401");
				invokeActionProgress(bridge, new ServerActionProgress(
						removedAgent, 1L, "action-progress-1", ActionType.WAIT, "trace-progress-1",
						0.5D, 25L, 4_000L
				));
				assertEquals("action_progress", codec.decode(reader.readLine()).type(),
						"missing verbose record cannot suppress the authoritative progress frame");
			}
		} catch (Exception exception) {
			throw new AssertionError("observational verbose progress verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove observational verbose secret", exception);
				}
			}
		}
	}

	private static void verifyRespawnContinuationPayload() {
		ServerActionResult respawnResult = new ServerActionResult(
				AgentId.parse("00000000-0000-0000-0000-000000000300"), 3L, "respawn-result",
				ActionType.RESPAWN, ServerActionState.SUCCEEDED, "VANILLA_RESPAWNED",
				"Respawned", 42L, 3_000L
		);
		assertEquals("Respawned.", invokeVerboseResult(respawnResult),
				"respawn verbose result uses readable player-facing copy");
		ServerActionResult technicalResult = new ServerActionResult(
				respawnResult.agentId(), 3L, "respawn-result-technical", ActionType.RESPAWN,
				ServerActionState.FAILED, "RESPAWN_REJECTED",
				"Result payload {\"action\":\"respawn-result-technical\"}", 42L, 3_001L
		);
		assertEquals("Technical details hidden.", invokeVerboseResult(technicalResult),
				"typed action result messages pass through the technical-output defense");
		CodexAgentManager activeManager = uninitializedManager();
		AgentRecord active = activeManager.registry().create("gpt-5.6-sol", "high", Optional.of("ActiveDeath"), 3_000L);
		activeManager.registry().start(active.agentId(), "continue after respawn", 3_001L);
		activeManager.registry().die(active.agentId(), deathSnapshot(3_002L), 3_002L);
		AgentTransition activeRespawn = activeManager.registry().respawn(
				active.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000301"), 3_003L
		);
		JsonObject activePayload = invokeGoalControlPayload(activeRespawn, "respawn");
		assertTrue(activePayload.has("resumeGoal") && activePayload.get("resumeGoal").getAsBoolean(),
				"active-at-death respawn tells the coordinator to continue the unfinished goal");

		CodexAgentManager pausedManager = uninitializedManager();
		AgentRecord paused = pausedManager.registry().create("gpt-5.6-sol", "high", Optional.of("PausedDeath"), 3_010L);
		pausedManager.registry().start(paused.agentId(), "remain paused after respawn", 3_011L);
		pausedManager.registry().stop(paused.agentId(), 3_012L);
		pausedManager.registry().die(paused.agentId(), deathSnapshot(3_013L), 3_013L);
		AgentTransition pausedRespawn = pausedManager.registry().respawn(
				paused.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000302"), 3_014L
		);
		assertTrue(!invokeGoalControlPayload(pausedRespawn, "respawn").has("resumeGoal"),
				"explicitly paused-at-death respawn omits automatic goal continuation");
	}

	private static AgentDeathSnapshot deathSnapshot(long diedAtEpochMs) {
		return new AgentDeathSnapshot(
				"verification", "minecraft:overworld", 0.0D, 64.0D, 0.0D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), "survival", diedAtEpochMs
		);
	}

	private static void verifyPendingRegistrationBoundary() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-pending-registration-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord registered = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Luna"), 1_000L);
			AgentRecord pending = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Sol"), 1_001L);
			Set<AgentId> pendingRegistrations = pendingRegistrations(manager);
			pendingRegistrations.add(pending.agentId());
			assertEquals(List.of(registered), manager.coordinatorVisibleRecords(),
					"a logical record remains coordinator-invisible until its verified registration is published");

			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			manager.setRuntimeHooks(bridge);
			invokePendingRegistrationPublication(manager, pending);
			assertTrue(pendingRegistrations.contains(pending.agentId()),
					"an unauthenticated publication attempt retains the pending marker for retry");
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject helloPayload = new JsonObject();
				helloPayload.addProperty("secret", secret);
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-pending-registration", helloPayload
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();

				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "pending-registration fixture authenticates the bridge");
				JsonArray registry = helloAck.payload().getAsJsonArray("registry");
				assertEquals(1, registry.size(), "handshake excludes the unregistered logical record");
				assertEquals(registered.agentId().toString(), registry.get(0).getAsJsonObject().get("agentId").getAsString(),
						"handshake retains the previously registered agent");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"verbose control follows the authenticated handshake");

				invokeUrgentObservation(bridge, pending.agentId());
				assertEquals(0, bridge.observationPublicationForVerification().pendingCount(),
						"pending registration cannot enter the urgent observation queue");
				bridge.tick();
				bridge.tick();
				assertTrue(bridge.observationPublicationForVerification().takeHeartbeat(registered.agentId()),
						"registered agent remains eligible for heartbeat publication");
				assertTrue(!bridge.observationPublicationForVerification().takeHeartbeat(pending.agentId()),
						"pending registration cannot enter the heartbeat queue");
				AgentRecord beforeConversation = manager.registry().require(pending.agentId());
				ConversationEvent pendingMessage = new ConversationEvent(
						pending.agentId(), "00000000-0000-0000-0000-000000000098", pending.agentId().toString(),
						ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Can you respond while joining?",
						0L, 1_002L, 1L, "minecraft:overworld"
				);
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(pendingMessage, Optional.of("Respond after registration.")),
						"AGENT_NOT_READY"
				);
				assertEquals(beforeConversation, manager.registry().require(pending.agentId()),
						"pending online direct message cannot commit STARTING before registration");
				assertTrue(manager.pendingConversationWakes().isEmpty(),
						"rejected pending direct message leaves no durable conversation wake");
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(pendingMessage, Optional.empty()),
						"AGENT_NOT_READY"
				);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-conversation", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"pending direct message emits neither conversation_event nor conversation_wake");

				AgentTransition pendingStart = manager.registry().start(
						pending.agentId(), "must register before lifecycle", 1_003L
				);
				bridge.onTransition(pendingStart);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-transition", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"a pending agent cannot publish lifecycle frames before agent_registered");

				bridge.onRemoved(pending.agentId(), pendingStart.after().goalRevision() + 1L);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-removal", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"removing a never-registered pending agent emits no unknown agent_removed frame");

				invokePendingRegistrationPublication(manager, pendingStart.after());
				BridgeEnvelope registration = codec.decode(reader.readLine());
				assertEquals("agent_registered", registration.type(),
						"verified registration is the first frame published for the new agent");
				assertEquals(pending.agentId().toString(), registration.agentId(),
						"verified registration carries the new stable agent ID");
				assertTrue(!pendingRegistrations.contains(pending.agentId()),
						"manager clears pending registration only after the hook publishes it");
				ConversationEvent awaitingCoordinatorReady = new ConversationEvent(
						pending.agentId(), "00000000-0000-0000-0000-000000000099", pending.agentId().toString(),
						ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Do not lose this while registering.",
						pendingStart.after().goalRevision(), 1_004L, 2L, "minecraft:overworld"
				);
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(awaitingCoordinatorReady, Optional.empty()),
						"AGENT_NOT_READY"
				);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-before-agent-ready", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"queued registration does not accept conversation frames before coordinator readiness");

				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", pendingStart.after().goalRevision());
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), pending.agentId().toString(), "agent_ready",
						"pending-registration-ready", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(pending.agentId()).state() == AgentLifecycleState.PLANNING;
				}, "coordinator readiness completes the pending registration boundary");
				activeBridge.publishConversationEvent(awaitingCoordinatorReady, Optional.empty());
				assertEquals("conversation_event", codec.decode(reader.readLine()).type(),
						"conversation publication begins after coordinator readiness is acknowledged");
				bridge.onTransition(pendingStart);
				BridgeEnvelope lifecycle = codec.decode(reader.readLine());
				assertEquals("goal_control", lifecycle.type(),
						"lifecycle publication follows the successful agent_registered frame");
				assertEquals(List.of(registered, manager.registry().require(pending.agentId())), manager.coordinatorVisibleRecords(),
						"successful registration makes the new agent observation-eligible");
				invokeUrgentObservation(bridge, pending.agentId());
				assertEquals(1, bridge.observationPublicationForVerification().pendingCount(),
						"the first observation may queue only after registration publication succeeds");
			}
		} catch (Exception exception) {
			throw new AssertionError("pending registration boundary verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove pending-registration bridge secret", exception);
				}
			}
		}
	}

	private static void verifyRemovalBackpressureForcesReconciliation() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-removal-backpressure-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord removed = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Removed"), 1_100L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, "hello-before-removal-backpressure");
				manager.registry().remove(removed.agentId());
				saturateAgentQueue(bridge, removed.agentId());
				assertThrowsBridgeCode(
						() -> activeBridge.onRemoved(removed.agentId(), 1L), "AGENT_BACKPRESSURE",
						"failed agent_removed enqueue retains reconciliation responsibility"
				);
				awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
						"agent_removed backpressure closes the stale coordinator session");
			}

			try (Socket replacement = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(replacement.getInputStream(), StandardCharsets.UTF_8))) {
				replacement.setSoTimeout(2_000);
				BridgeEnvelope acknowledgement = authenticate(
						replacement, reader, codec, secret, "hello-after-removal-backpressure"
				);
				assertEquals(0, acknowledgement.payload().getAsJsonArray("registry").size(),
						"replacement handshake reconciles the removed agent out of coordinator state");
			}
		} catch (Exception exception) {
			throw new AssertionError("agent removal backpressure verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove removal-backpressure secret", exception);
				}
			}
		}
	}

	private static void verifyReplacementHandshakeSupersedesPendingDisconnect() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-replacement-handshake-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord active = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Replacement"), 1_500L);
			AgentTransition started = manager.registry().start(active.agentId(), "survive fast reconnect", 1_501L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket first = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader firstReader = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
				first.setSoTimeout(2_000);
				BridgeEnvelope firstAck = authenticate(first, firstReader, codec, secret, "hello-before-replacement");
				assertEquals(started.after().agentId().toString(),
						firstAck.payload().getAsJsonArray("registry").get(0).getAsJsonObject().get("agentId").getAsString(),
						"first session knows the active agent");
			}
			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
					"old authenticated session closes before its disconnect tick");

			try (Socket replacement = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader replacementReader = new BufferedReader(new InputStreamReader(replacement.getInputStream(), StandardCharsets.UTF_8))) {
				replacement.setSoTimeout(2_000);
				BridgeEnvelope replacementAck = authenticate(
						replacement, replacementReader, codec, secret, "hello-fast-replacement"
				);
				assertEquals(started.after().goalRevision(),
						replacementAck.payload().getAsJsonArray("registry").get(0).getAsJsonObject().get("goalRevision").getAsLong(),
						"replacement handshake snapshots the still-active authoritative revision");
				bridge.tick();
				assertEquals(AgentLifecycleState.STARTING, manager.registry().require(active.agentId()).state(),
						"replacement authentication consumes the old session disconnect without disconnecting the agent");

				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", started.after().goalRevision());
				writeEnvelope(replacement, codec, new BridgeEnvelope(
						2, replacementAck.serverInstanceId(), active.agentId().toString(), "agent_ready",
						"ready-after-fast-replacement", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(active.agentId()).state() == AgentLifecycleState.PLANNING;
				}, "replacement agent_ready for the snapshotted revision remains current");
			}
		} catch (Exception exception) {
			throw new AssertionError("replacement handshake disconnect ordering verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove replacement-handshake secret", exception);
				}
			}
		}
	}

	private static void verifyAuthenticatedReconnectRecovery() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-reconnect-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord disconnected = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Recovering"), 2_000L);
			manager.registry().start(disconnected.agentId(), "finish the interrupted task", 2_001L);
			disconnected = manager.registry().disconnect(disconnected.agentId(), 2_002L).after();
			AgentId disconnectedId = disconnected.agentId();
			long disconnectedRevision = disconnected.goalRevision();
			AgentRecord paused = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Paused"), 2_003L);
			manager.registry().start(paused.agentId(), "stay explicitly paused", 2_004L);
			paused = manager.registry().stop(paused.agentId(), 2_005L).after();
			long pausedRevision = paused.goalRevision();

			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				JsonObject helloPayload = new JsonObject();
				helloPayload.addProperty("secret", secret);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, "coordinator", "server", "hello", "hello-reconnect", helloPayload
				));
				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "reconnect fixture authenticates the bridge");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"reconnect fixture consumes verbose control");

				JsonObject reconnectReady = new JsonObject();
				reconnectReady.addProperty("goalRevision", disconnectedRevision);
				reconnectReady.addProperty("reconciled", true);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), disconnected.agentId().toString(), "agent_ready",
						"ready-reconnected", reconnectReady
				));
				BridgeEnvelope resume = pollBridgeResponse(bridge, socket, reader, codec);
				assertEquals("goal_control", resume.type(), "reconciliation publishes a lifecycle recovery control");
				assertEquals(disconnected.agentId().toString(), resume.agentId(),
						"recovery control retains the disconnected agent identity");
				assertEquals("resume", resume.payload().get("operation").getAsString(),
						"authenticated reconciliation resumes rather than steering the unfinished goal");
				assertEquals(disconnectedRevision + 1L, resume.payload().get("goalRevision").getAsLong(),
						"automatic reconnect recovery advances the authoritative goal revision once");
				assertEquals(AgentLifecycleState.STARTING, manager.registry().require(disconnected.agentId()).state(),
						"disconnected goal re-enters STARTING after authenticated reconciliation");
				assertEquals("finish the interrupted task",
						manager.registry().require(disconnected.agentId()).currentGoal().orElseThrow().prompt(),
						"automatic reconnect recovery preserves the unfinished goal");

				JsonObject resumedReady = new JsonObject();
				resumedReady.addProperty("goalRevision", disconnectedRevision + 1L);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), disconnected.agentId().toString(), "agent_ready",
						"ready-resumed", resumedReady
				));
				MultiplexedServerBridge activeBridge = bridge;
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(disconnectedId).state() == AgentLifecycleState.PLANNING;
				}, "fresh coordinator readiness advances the recovered goal to planning");

				JsonObject pausedReady = new JsonObject();
				pausedReady.addProperty("goalRevision", pausedRevision);
				pausedReady.addProperty("reconciled", true);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), paused.agentId().toString(), "agent_ready",
						"ready-explicitly-paused", pausedReady
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat", "heartbeat-after-paused-ready", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"explicitly paused reconciliation emits no automatic resume control");
				assertEquals(AgentLifecycleState.PAUSED, manager.registry().require(paused.agentId()).state(),
						"explicitly paused agent remains paused after authenticated reconciliation");
			}
		} catch (Exception exception) {
			throw new AssertionError("authenticated reconnect recovery verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove reconnect bridge secret", exception);
				}
			}
		}
	}

	private static void verifyAgentErrorRevisionGate() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-error-revision-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentVerboseState verboseState = new AgentVerboseState();
			verboseState.setEnabled(true);
			AgentRecord active = manager.registry().create(
					"gpt-5.6-sol", "high", Optional.of("Error gate"), 2_100L
			);
			AgentTransition started = manager.registry().start(
					active.agentId(), "fail the interrupted task", 2_101L
			);
			long currentRevision = started.after().goalRevision();

			bridge = new MultiplexedServerBridge(manager, 0, secretFile, verboseState);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(socket, reader, codec, secret, "hello-error-revision");

				JsonObject staleError = new JsonObject();
				staleError.addProperty("goalRevision", currentRevision - 1L);
				staleError.addProperty("code", "STALE_PLANNER_FAILED");
				staleError.addProperty("message", "Stale planner failure must be ignored");
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), active.agentId().toString(), "agent_error",
						"agent-error-stale", staleError
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-stale-agent-error", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponse(bridge, socket, reader, codec).type(),
						"stale agent_error leaves the authenticated bridge usable");

				AgentRecord afterStale = manager.registry().require(active.agentId());
				assertEquals(AgentLifecycleState.STARTING, afterStale.state(),
						"stale agent_error leaves the active Java lifecycle unchanged");
				assertEquals(currentRevision, afterStale.goalRevision(),
						"stale agent_error cannot manufacture a revision");
				assertEquals("", afterStale.lastError(),
						"stale agent_error cannot write a terminal planner message");

				JsonObject currentError = new JsonObject();
				currentError.addProperty("goalRevision", currentRevision);
				currentError.addProperty("code", "PLANNER_FAILED");
				currentError.addProperty("message", "Planner could not continue");
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), active.agentId().toString(), "agent_error",
						"agent-error-current", currentError
				));

				BridgeEnvelope failure = pollBridgeResponse(bridge, socket, reader, codec);
				assertEquals("goal_control", failure.type(),
						"current agent_error publishes a lifecycle failure control");
				assertEquals("fail", failure.payload().get("operation").getAsString(),
						"current agent_error publishes the fail operation");
				assertEquals(currentRevision + 1L, failure.payload().get("goalRevision").getAsLong(),
						"current agent_error advances the authoritative revision exactly once");

				AgentRecord failed = manager.registry().require(active.agentId());
				assertEquals(AgentLifecycleState.ERROR, failed.state(),
						"current agent_error aligns the Java lifecycle with ERROR");
				assertEquals(currentRevision + 1L, failed.goalRevision(),
						"current agent_error stores the single terminal revision increment");
				assertEquals("Planner could not continue", failed.lastError(),
						"current agent_error stores the terminal planner message");
			}
		} catch (Exception exception) {
			throw new AssertionError("agent_error revision gate verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove agent_error revision gate secret", exception);
				}
			}
		}
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
		MultiplexedServerBridge.VerboseEvent rejected = invokeCompletionVerboseEvent(7L, verification);
		assertEquals(7L, rejected.goalRevision(), "rejected completion feedback retains the guarded revision");
		assertEquals("retry", rejected.stage(), "rejected completion feedback uses the Problem stage exactly once");
		assertEquals("Goal completion could not be verified. Continuing the task.", rejected.message(),
				"rejected completion feedback explains that work will continue");
		GoalCompletionVerifier.VerificationResult verified = new GoalCompletionVerifier.VerificationResult(
				true, 7L, "VERIFIED", List.of()
		);
		MultiplexedServerBridge.VerboseEvent completed = invokeCompletionVerboseEvent(7L, verified);
		assertEquals(7L, completed.goalRevision(), "successful completion feedback retains the guarded revision");
		assertEquals("result", completed.stage(), "successful completion feedback uses Result instead of Lifecycle");
		assertEquals("Task complete.", completed.message(), "successful completion feedback is concise");
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
			dev.agaminggod.arenaagents.agent.AgentDomainException disconnected = assertThrowsDomain(
					() -> activeBridge.publishConversationEvent(event, Optional.of("Respond to the player message.")),
					"COORDINATOR_DISCONNECTED"
			);
			assertEquals(
					"AI agent coordinator is offline; check logs/arena-agents-coordinator-error.log for the startup cause",
					disconnected.getMessage(),
					"disconnected delivery reports the actual coordinator state instead of guessing authentication"
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
				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "conversation fixture authenticates the bridge");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"conversation fixture consumes handshake verbose control before wake replay");
				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", idle.goalRevision());
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), idle.agentId().toString(), "agent_ready", "ready-before-atomic-wake", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return activeBridge.coordinatorReadyForVerification(idle.agentId());
				}, "conversation fixture acknowledges coordinator readiness before publishing a wake");

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
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"replay fixture consumes handshake verbose control before wake replay");
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
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), new LinkedHashSet<AgentId>());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<AgentId> pendingRegistrations(CodexAgentManager manager) {
		try {
			Field field = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			field.setAccessible(true);
			Set<AgentId> registrations = (Set<AgentId>) field.get(manager);
			return registrations;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not access pending agent registrations", exception);
		}
	}

	private static void invokeUrgentObservation(MultiplexedServerBridge bridge, AgentId agentId) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("queueUrgentObservation", AgentId.class);
			method.setAccessible(true);
			method.invoke(bridge, agentId);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke urgent observation boundary", exception);
		}
	}

	private static void invokePlannerReady(MultiplexedServerBridge bridge, BridgeEnvelope envelope) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("plannerReady", BridgeEnvelope.class);
			method.setAccessible(true);
			method.invoke(bridge, envelope);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			throw new AssertionError("obsolete planner readiness was not ignored", exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke planner readiness", exception);
		}
	}

	private static void invokePendingRegistrationPublication(CodexAgentManager manager, AgentRecord record) {
		try {
			var method = CodexAgentManager.class.getDeclaredMethod("publishPendingRegistration", AgentRecord.class);
			method.setAccessible(true);
			method.invoke(manager, record);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke pending registration publication", exception);
		}
	}

	private static JsonObject invokeGoalControlPayload(AgentTransition transition, String operation) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("goalControlPayload", AgentTransition.class, String.class);
			method.setAccessible(true);
			return (JsonObject) method.invoke(null, transition, operation);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not serialize goal control payload", exception);
		}
	}

	private static String invokeVerboseResult(ServerActionResult result) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("verboseResult", ServerActionResult.class);
			method.setAccessible(true);
			return (String) method.invoke(null, result);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not format verbose action result", exception);
		}
	}

	private static MultiplexedServerBridge.VerboseEvent invokeCompletionVerboseEvent(
			long goalRevision,
			GoalCompletionVerifier.VerificationResult verification
	) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod(
					"completionVerboseEvent", long.class, GoalCompletionVerifier.VerificationResult.class);
			method.setAccessible(true);
			return (MultiplexedServerBridge.VerboseEvent) method.invoke(null, goalRevision, verification);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not format goal completion verbose feedback", exception);
		}
	}

	private static void invokeActionProgress(MultiplexedServerBridge bridge, ServerActionProgress progress) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("sendActionProgress", ServerActionProgress.class);
			method.setAccessible(true);
			method.invoke(bridge, progress);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not publish action progress", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static void saturateAgentQueue(MultiplexedServerBridge bridge, AgentId agentId) {
		try {
			Field sessionField = MultiplexedServerBridge.class.getDeclaredField("session");
			sessionField.setAccessible(true);
			Object session = sessionField.get(bridge);
			Field queuedField = session.getClass().getDeclaredField("queuedByAgent");
			queuedField.setAccessible(true);
			synchronized (session) {
				Map<String, Integer> queued = (Map<String, Integer>) queuedField.get(session);
				queued.put(agentId.toString(), MultiplexedServerBridge.AGENT_QUEUE_CAP);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not saturate the agent publication queue", exception);
		}
	}

	private static void awaitCondition(java.util.function.BooleanSupplier condition, String label) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertTrue(condition.getAsBoolean(), label);
	}

	private static void awaitLatch(CountDownLatch latch, String label) {
		try {
			assertTrue(latch.await(2L, TimeUnit.SECONDS), label);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " interrupted", exception);
		}
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

	private static BridgeEnvelope authenticate(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String messageId
	) throws Exception {
		JsonObject hello = new JsonObject();
		hello.addProperty("secret", secret);
		writeEnvelope(socket, codec, new BridgeEnvelope(
				2, "coordinator", "server", "hello", messageId, hello
		));
		BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
		assertEquals("hello_ack", acknowledgement.type(), "replacement fixture authenticates the session");
		assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
				"replacement fixture consumes verbose control");
		return acknowledgement;
	}

	private static void writeEnvelope(Socket socket, BridgeEnvelopeCodec codec, BridgeEnvelope envelope) throws Exception {
		socket.getOutputStream().write(codec.encode(envelope).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
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
		assertThrowsDomain(action, code);
	}

	private static dev.agaminggod.arenaagents.agent.AgentDomainException assertThrowsDomain(
			Runnable action,
			String code) {
		try {
			action.run();
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), "target observation rejection code");
			return exception;
		}
		throw new AssertionError("expected " + code);
	}

	private static void assertThrowsBridgeCode(Runnable action, String code, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException exception) {
			assertEquals(code, exception.code(), label);
			return;
		}
		throw new AssertionError(label + " did not throw " + code);
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
