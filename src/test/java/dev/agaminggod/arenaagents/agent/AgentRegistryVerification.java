package dev.agaminggod.arenaagents.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentRegistryVerification {
	private static final long START_TIME = 1_000L;

	private AgentRegistryVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyLifecycleAndRevisions();
		assertions += verifyQueueAndSteeringBounds();
		assertions += verifyIdentityResolution();
		assertions += verifyPersistenceRecovery();
		assertions += verifyProviderPersistenceAndMigration();
		assertions += verifyEntityLocationPersistenceAndMigration();
		assertions += verifyAutomaticProgressPersistence();
		assertions += verifyEntityRecoveryTarget();
		return assertions;
	}

	private static int verifyLifecycleAndRevisions() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(4, 2, () -> { }, transitions::add);
		assertEquals(4, registry.availableCapacity(), "empty registry exposes all configured capacity");
		registry.requireCapacity(4);
		AgentRecord created = registry.create("gpt-5.6-sol", "HIGH", Optional.of("Builder"), START_TIME);
		assertEquals(3, registry.availableCapacity(), "creating an agent consumes one capacity slot");
		expectFailure(() -> registry.requireCapacity(4), "AGENT_LIMIT_REACHED");
		assertEquals(AgentLifecycleState.IDLE, created.state(), "new agent is idle");

		AgentTransition started = registry.start(created.agentId(), "Build a shelter", START_TIME + 1L);
		assertEquals(AgentLifecycleState.STARTING, started.after().state(), "start state");
		assertEquals(1L, started.after().goalRevision(), "start revision");
		assertTrue(registry.isCurrentActiveRevision(created.agentId(), 1L), "current active revision accepted");
		assertTrue(!registry.isCurrentActiveRevision(created.agentId(), 0L), "stale active revision rejected");
		AgentRecord direct = registry.create("gpt-5.6-sol", "high", Optional.of("Direct"), START_TIME + 1L);
		registry.start(direct.agentId(), "Move now", START_TIME + 2L);
		assertEquals(AgentLifecycleState.ACTING,
				registry.beginAction(direct.agentId(), 1L, START_TIME + 3L).after().state(),
				"a valid action command may atomically acknowledge a delayed planning-state message");

		AgentTransition planning = registry.beginPlanning(created.agentId(), START_TIME + 2L);
		assertEquals(AgentLifecycleState.PLANNING, planning.after().state(), "planning state");
		expectFailure(
				() -> registry.beginAction(created.agentId(), 0L, START_TIME + 3L),
				"STALE_REVISION"
		);
		AgentTransition acting = registry.beginAction(created.agentId(), 1L, START_TIME + 3L);
		assertEquals(AgentLifecycleState.ACTING, acting.after().state(), "acting state");

		AgentTransition stopped = registry.stop(created.agentId(), START_TIME + 4L);
		assertTrue(stopped.cancelAction(), "stop cancels active action");
		assertTrue(stopped.interruptPlanner(), "stop interrupts planner");
		assertEquals(2L, stopped.after().goalRevision(), "stop revision");
		assertEquals(AgentLifecycleState.PAUSED, stopped.after().state(), "stop state");
		assertTrue(!registry.isCurrentActiveRevision(created.agentId(), 2L), "paused current revision rejected");
		assertTrue(!registry.isCurrentActiveRevision(AgentId.random(), 2L), "unknown agent revision rejected");

		AgentTransition stoppedAgain = registry.stop(created.agentId(), START_TIME + 5L);
		assertEquals(3L, stoppedAgain.after().goalRevision(), "repeated stop invalidates late work");
		AgentTransition resumed = registry.resume(created.agentId(), START_TIME + 6L);
		assertEquals(4L, resumed.after().goalRevision(), "resume revision");
		assertEquals(AgentLifecycleState.STARTING, resumed.after().state(), "resume state");
		AgentTransition disconnected = registry.disconnect(created.agentId(), START_TIME + 7L);
		assertEquals(AgentLifecycleState.DISCONNECTED, disconnected.after().state(), "disconnect state");
		AgentTransition resumedAfterDisconnect = registry.resume(created.agentId(), START_TIME + 8L);
		assertEquals(AgentLifecycleState.STARTING, resumedAfterDisconnect.after().state(), "resume after coordinator reconnect");
		return 23;
	}

	private static int verifyQueueAndSteeringBounds() {
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "xhigh", Optional.empty(), START_TIME);
		registry.start(created.agentId(), "Gather wood", START_TIME + 1L);
		AgentTransition queued = registry.queue(created.agentId(), "Build tools", START_TIME + 2L);
		assertEquals(1, queued.after().queuedGoals().size(), "queue append");
		expectFailure(() -> registry.queue(created.agentId(), "Mine stone", START_TIME + 3L), "QUEUE_FULL");

		AgentTransition steered = registry.steer(created.agentId(), "Avoid the ravine", START_TIME + 4L);
		assertEquals(2L, steered.after().goalRevision(), "steer revision");
		assertEquals(1, steered.after().currentGoal().orElseThrow().steeringInstructions().size(), "steer history");
		assertTrue(steered.cancelAction(), "steer cancels active action");

		AgentTransition completed = registry.completeGoal(
				created.agentId(),
				steered.after().goalRevision(),
				START_TIME + 5L
		);
		assertEquals(AgentLifecycleState.STARTING, completed.after().state(), "queued goal promotion state");
		assertEquals("Build tools", completed.after().currentGoal().orElseThrow().prompt(), "promoted goal");
		assertEquals(0, completed.after().queuedGoals().size(), "queue emptied by promotion");
		return 9;
	}

	private static int verifyIdentityResolution() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord named = registry.create("gpt-5.5", "high", Optional.of("Scout"), START_TIME);
		assertEquals(named.agentId(), registry.resolve("scout").agentId(), "case-insensitive name resolution");
		assertEquals(named.agentId(), registry.resolve(named.agentId().shortValue()).agentId(), "short ID resolution");
		expectFailure(
				() -> registry.create("gpt-5.6-sol", "high", Optional.of("SCOUT"), START_TIME + 1L),
				"DUPLICATE_AGENT_NAME"
		);
		return 3;
	}

	private static int verifyPersistenceRecovery() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("Miner"), START_TIME);
		registry.start(created.agentId(), "Mine iron", START_TIME + 1L);
		registry.beginPlanning(created.agentId(), START_TIME + 2L);

		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		String encoded = codec.encode(registry.snapshot());
		AgentRegistry.Snapshot decoded = codec.decode(encoded);
		AgentRegistry recovered = AgentRegistry.restore(decoded, () -> { }, transition -> { }, START_TIME + 3L);
		AgentRecord restored = recovered.require(created.agentId());
		assertEquals(AgentLifecycleState.PAUSED, restored.state(), "active reload state");
		assertEquals(2L, restored.goalRevision(), "reload revision");
		assertEquals("Mine iron", restored.currentGoal().orElseThrow().prompt(), "reload goal");
		return 3;
	}

	private static int verifyProviderPersistenceAndMigration() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentProfile kimi = new AgentProfile("kimi", "kimi-code/k3", "max", Optional.empty(), 2);
		AgentRecord record = AgentRecord.create(AgentId.random(), kimi, START_TIME);
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record)
		);
		AgentProfile decoded = codec.decode(codec.encode(snapshot)).records().getFirst().profile();
		assertEquals("kimi", decoded.provider(), "provider round-trip");
		assertEquals("Kimi K3 Max | Orchid", decoded.nameTag(), "provider and skin aware name tag");

		String legacy = codec.encode(snapshot).replace("\"provider\":\"kimi\",", "");
		assertEquals("codex", codec.decode(legacy).records().getFirst().profile().provider(), "legacy provider migration");
		return 3;
	}

	private static int verifyEntityLocationPersistenceAndMigration() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRecord record = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				START_TIME
		).withEntity(
				Optional.of(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")),
				Optional.of(new AgentEntityLocation("minecraft:the_nether", 12, -8)),
				START_TIME + 1L
		);
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record)
		);

		String encoded = codec.encode(snapshot);
		AgentRecord decoded = codec.decode(encoded).records().getFirst();
		assertEquals(record.entityLocation(), decoded.entityLocation(), "entity location round-trip");

		String legacy = encoded.replace(
				",\"entity_location\":{\"dimension\":\"minecraft:the_nether\",\"chunk_x\":12,\"chunk_z\":-8}",
				""
		);
		AgentRecord migrated = codec.decode(legacy).records().getFirst();
		assertEquals(Optional.empty(), migrated.entityLocation(), "legacy entity location migration");
		assertEquals(record.entityUuid(), migrated.entityUuid(), "legacy entity UUID preserved");
		return 3;
	}

	private static int verifyAutomaticProgressPersistence() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord record = registry.create("gpt-5.6-sol", "high", Optional.empty(), START_TIME);
		assertTrue(record.automaticProgress(), "automatic progress defaults on");
		registry.setAutomaticProgress(record.agentId(), false, START_TIME + 1L);

		String encoded = codec.encode(registry.snapshot());
		assertTrue(encoded.contains("\"automatic_progress\":false"), "automatic progress is persisted");
		assertTrue(!codec.decode(encoded).records().getFirst().automaticProgress(), "disabled automatic progress round-trip");

		String legacy = encoded.replace(",\"automatic_progress\":false", "");
		assertTrue(codec.decode(legacy).records().getFirst().automaticProgress(), "legacy automatic progress defaults on");
		return 4;
	}

	private static int verifyEntityRecoveryTarget() {
		AgentRecord legacy = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				START_TIME
		).withEntityUuid(
				Optional.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
				START_TIME + 1L
		);
		assertEquals(Optional.empty(), AgentEntityRecoveryTarget.from(legacy), "legacy record has no recovery target");

		AgentEntityLocation location = new AgentEntityLocation("minecraft:the_end", -3, 7);
		AgentRecord located = legacy.withEntityLocation(location, START_TIME + 2L);
		AgentEntityRecoveryTarget target = AgentEntityRecoveryTarget.from(located).orElseThrow();
		assertEquals(legacy.entityUuid().orElseThrow(), target.entityUuid(), "recovery target entity UUID");
		assertEquals(location, target.location(), "recovery target location");
		return 3;
	}

	private static void expectFailure(Runnable operation, String expectedCode) {
		try {
			operation.run();
			throw new AssertionError("Expected failure " + expectedCode);
		} catch (AgentDomainException exception) {
			assertEquals(expectedCode, exception.code(), "failure code");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
