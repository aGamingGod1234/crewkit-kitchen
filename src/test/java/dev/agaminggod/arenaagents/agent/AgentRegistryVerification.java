package dev.agaminggod.arenaagents.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class AgentRegistryVerification {
	private static final long START_TIME = 1_000L;

	private AgentRegistryVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyLifecycleAndRevisions();
		assertions += verifyCoordinatorCompletion();
		assertions += verifyQueueAndSteeringBounds();
		assertions += verifyIdentityResolution();
		assertions += verifyIdentityUniqueness();
		assertions += verifyCanonicalIdentityKeys();
		assertions += verifyPersistenceRecovery();
		assertions += verifyProviderPersistenceAndMigration();
		assertions += verifyEntityLocationPersistenceAndMigration();
		assertions += verifyAutomaticProgressPersistence();
		assertions += verifyEntityRecoveryTarget();
		assertions += verifyDeathSnapshotPersistenceAndRespawn();
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

	private static int verifyCoordinatorCompletion() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transitions::add);
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("Coordinator"), START_TIME);
		registry.start(created.agentId(), "Finish this task", START_TIME + 1L);
		registry.beginPlanning(created.agentId(), START_TIME + 2L);
		AgentRecord completed = registry.coordinatorCompleted(created.agentId(), 1L, START_TIME + 3L);
		assertEquals(AgentLifecycleState.COMPLETED, completed.state(), "coordinator completion state");
		assertEquals(1L, completed.goalRevision(), "coordinator completion preserves goal revision");
		assertEquals("Finish this task", completed.currentGoal().orElseThrow().prompt(), "coordinator completion preserves current goal");
		assertEquals(3, transitions.size(), "coordinator completion dispatches a local lifecycle transition");
		assertEquals(AgentLifecycleState.COMPLETED, transitions.getLast().after().state(), "coordinator completion transition exposes DONE locally");
		assertEquals(AgentLifecycleState.COMPLETED, registry.coordinatorCompleted(created.agentId(), 1L, START_TIME + 4L).state(), "repeated coordinator completion is idempotent");
		expectFailure(() -> registry.coordinatorCompleted(created.agentId(), 0L, START_TIME + 5L), "STALE_REVISION");

		AgentRecord queued = registry.create("gpt-5.6-sol", "high", Optional.of("Queued"), START_TIME + 6L);
		registry.start(queued.agentId(), "First task", START_TIME + 7L);
		registry.queue(queued.agentId(), "Second task", START_TIME + 8L);
		AgentRecord promoted = registry.coordinatorCompleted(queued.agentId(), 1L, START_TIME + 9L);
		assertEquals(AgentLifecycleState.STARTING, promoted.state(), "coordinator completion promotes queued work");
		assertEquals("Second task", promoted.currentGoal().orElseThrow().prompt(), "coordinator completion installs the queued goal");
		assertEquals(2L, promoted.goalRevision(), "queued promotion advances the goal revision");
		assertEquals(0, promoted.queuedGoals().size(), "queued promotion consumes the queue head");
		return 11;
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
		AgentRecord unnamed = registry.create("kimi", "kimi-code/k3-256k", "max", Optional.empty(), START_TIME + 1L);
		String generatedName = AgentIdentity.displayName(unnamed.agentId(), unnamed.profile());
		assertEquals(unnamed.agentId(), registry.resolve(generatedName.toUpperCase()).agentId(),
				"generated operator name resolves case-insensitively");
		assertTrue(registry.selectors().contains(generatedName), "generated operator name is offered as a selector");
		expectFailure(
				() -> registry.create("codex", "gpt-5.6-sol", "high", Optional.of(generatedName.toUpperCase()),
						START_TIME + 2L),
				"DUPLICATE_AGENT_NAME"
		);
		expectFailure(
				() -> registry.create("gpt-5.6-sol", "high", Optional.of("SCOUT"), START_TIME + 3L),
				"DUPLICATE_AGENT_NAME"
		);
		for (int index = 0; index < 8; index++) {
			registry.create("codex", "gpt-5.6-sol", "high", Optional.empty(), START_TIME + 4L + index);
		}
		assertEquals(registry.records().size(), new HashSet<>(registry.records().stream()
				.map(record -> AgentIdentity.displayName(record.agentId(), record.profile()).toLowerCase(Locale.ROOT))
				.toList()).size(), "create preserves case-insensitive display-name uniqueness");
		assertEquals(registry.records().size(), new HashSet<>(registry.records().stream()
				.map(record -> AgentIdentity.playerName(record.agentId(), record.profile()).toLowerCase(Locale.ROOT))
				.toList()).size(), "create preserves case-insensitive technical-name uniqueness");
		return 8;
	}

	private static int verifyIdentityUniqueness() {
		AgentId first = new AgentId(UUID.fromString("193a9add-1111-1111-9abc-123456789abc"));
		AgentId second = new AgentId(UUID.fromString("193a9add-2222-2222-9abc-123456789abc"));
		AgentProfile unnamed = new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0);
		AgentRecord firstRecord = AgentRecord.create(first, unnamed, START_TIME);
		AgentRecord secondRecord = AgentRecord.create(second, unnamed, START_TIME + 1L);
		assertTrue(!AgentIdentity.displayName(first, unnamed).equalsIgnoreCase(
				AgentIdentity.displayName(second, unnamed)), "full IDs distinguish same-prefix generated names");
		assertEquals(AgentIdentity.playerName(first, unnamed), AgentIdentity.playerName(second, unnamed),
				"technical collision fixture shares its lossy transport name");
		expectFailure(
				() -> AgentRegistry.restore(snapshot(firstRecord, secondRecord), () -> { }, transition -> { },
						START_TIME + 2L),
				"DUPLICATE_AGENT_PLAYER_NAME"
		);

		String generatedName = AgentIdentity.displayName(first, unnamed);
		AgentId namedId = new AgentId(UUID.fromString("abcdef01-3333-3333-9abc-123456789abc"));
		AgentProfile named = new AgentProfile(
				"cursor", "composer-2.5", "high", "fast", Optional.of(generatedName.toUpperCase()), 1,
				AgentGameMode.SURVIVAL);
		expectFailure(
				() -> AgentRegistry.restore(
						snapshot(firstRecord, AgentRecord.create(namedId, named, START_TIME + 1L)),
						() -> { }, transition -> { }, START_TIME + 2L),
				"DUPLICATE_AGENT_NAME"
		);
		return 4;
	}

	private static AgentRegistry.Snapshot snapshot(AgentRecord... records) {
		return new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(records)
		);
	}

	private static int verifyCanonicalIdentityKeys() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord named = registry.create(
				"codex", "gpt-5.6-sol", "high", Optional.of("\u0130"), START_TIME);
		assertEquals(named.agentId(), registry.resolve("i\u0307").agentId(),
				"selector matching uses the canonical identity key");
		expectFailure(
				() -> registry.create("codex", "gpt-5.6-sol", "high", Optional.of("i\u0307"),
						START_TIME + 1L),
				"DUPLICATE_AGENT_NAME"
		);
		registry.create("kimi", "kimi-code/k3", "max", Optional.empty(), START_TIME + 2L);
		AgentRegistry restored = AgentRegistry.restore(
				registry.snapshot(), () -> { }, transition -> { }, START_TIME + 3L);
		assertEquals(registry.records(), restored.records(),
				"every successfully created identity remains valid when its snapshot restores");
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
		AgentId persistedId = new AgentId(UUID.fromString("abcdef01-1234-5678-9abc-123456789abc"));
		AgentProfile kimi = new AgentProfile(
				"kimi", "kimi-code/k3-256k", "max", "priority", Optional.of("Rook"), 2,
				AgentGameMode.CREATIVE);
		AgentRecord record = AgentRecord.create(persistedId, kimi, START_TIME);
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record)
		);
		AgentProfile decoded = codec.decode(codec.encode(snapshot)).records().getFirst().profile();
		assertEquals("kimi", decoded.provider(), "provider round-trip");
		assertEquals("kimi-code/k3-256k", decoded.model(), "model round-trip");
		assertEquals("max", decoded.reasoning(), "reasoning round-trip");
		assertEquals("priority", decoded.serviceTier(), "service tier round-trip");
		assertEquals(Optional.of("Rook"), decoded.userName(), "friendly name round-trip");
		assertEquals(AgentGameMode.CREATIVE, decoded.gameMode(), "game mode round-trip");
		assertEquals(
				AgentVisualIdentity.resolve(kimi.provider(), kimi.model(), kimi.skinVariant()),
				AgentVisualIdentity.resolve(decoded.provider(), decoded.model(), decoded.skinVariant()),
				"resolved visual and transport identity round-trip");
		assertEquals("Rook", AgentIdentity.displayName(persistedId, decoded),
				"resolved operator identity round-trip");
		assertEquals(Optional.of("☾ Rook · K3 256K"), AgentIdentity.worldTag(decoded),
				"resolved friendly world identity round-trip");

		String legacy = codec.encode(snapshot).replace("\"provider\":\"kimi\",", "");
		assertEquals("codex", codec.decode(legacy).records().getFirst().profile().provider(), "legacy provider migration");
		AgentProfile cursor = new AgentProfile(
				"cursor", "composer-2.5", "high", "fast", Optional.empty(), 1, AgentGameMode.SURVIVAL);
		AgentRegistry.Snapshot cursorSnapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(AgentRecord.create(AgentId.random(), cursor, START_TIME))
		);
		AgentProfile decodedCursor = codec.decode(codec.encode(cursorSnapshot)).records().getFirst().profile();
		assertEquals("cursor", decodedCursor.provider(), "Cursor provider round-trip");
		assertEquals("composer-2.5", decodedCursor.model(), "Cursor model round-trip");
		assertEquals("fast", decodedCursor.serviceTier(), "Cursor native fast mode round-trip");
		return 13;
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

	private static int verifyDeathSnapshotPersistenceAndRespawn() {
		AgentRecord active = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.of("Miner"), 0),
				START_TIME
		);
		active = AgentLifecycleReducer.start(active, "Mine iron", START_TIME + 1L).after();
		AgentDeathSnapshot death = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:the_nether", 12.5D, 64.0D, -3.5D,
				Optional.of("minecraft:overworld"), Optional.of(100.5D), Optional.of(70.0D), Optional.of(-20.5D),
				Optional.of(37.5F), Optional.of(-12.25F), Optional.of(true), "spectator",
				START_TIME + 2L
		);
		AgentTransition died = AgentLifecycleReducer.die(active, death, START_TIME + 3L);
		assertEquals(AgentLifecycleState.DEAD, died.after().state(), "death enters persistent dead state");
		assertEquals(active.currentGoal(), died.after().currentGoal(), "death retains current goal");
		assertEquals(active.queuedGoals(), died.after().queuedGoals(), "death retains queued goals");
		assertEquals(active.profile(), died.after().profile(), "death retains selected model profile");
		assertEquals(Optional.of(death), died.after().deathSnapshot(), "death retains exact factual snapshot");
		expectFailure(() -> AgentLifecycleReducer.start(died.after(), "Restart", START_TIME + 4L), "INVALID_TRANSITION");

		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION, AgentConstants.DEFAULT_AGENT_LIMIT, AgentConstants.DEFAULT_QUEUE_LIMIT, List.of(died.after())
		);
		String encoded = codec.encode(snapshot);
		AgentRecord roundTrip = codec.decode(encoded).records().getFirst();
		assertEquals(died.after().deathSnapshot(), roundTrip.deathSnapshot(), "death snapshot persistence round-trip");
		assertEquals("spectator", roundTrip.deathSnapshot().orElseThrow().gameMode(), "actual live game mode survives restart");
		assertEquals(Optional.of(true), roundTrip.deathSnapshot().orElseThrow().respawnForced(), "forced vanilla respawn flag survives restart");
		AgentDeathSnapshot legacyShape = new AgentDeathSnapshot(
				"legacy death", "minecraft:overworld", 1.0D, 64.0D, 1.0D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), START_TIME + 2L
		);
		assertEquals("survival", legacyShape.gameMode(), "legacy death constructor defaults to survival");
		String legacy = encoded.replaceFirst(",\\\"death_snapshot\\\":\\{[^}]*\\}", "");
		assertEquals(Optional.empty(), codec.decode(legacy).records().getFirst().deathSnapshot(), "legacy saves default death snapshot absent");

		AgentRegistry registry = AgentRegistry.restore(snapshot, () -> { }, transition -> { }, START_TIME + 4L);
		AgentRecord exactDead = registry.require(active.agentId());
		try {
			registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 5L, (transition, commit) -> {
				throw new AgentDomainException("RESPAWN_BARRIER_FAILED", "result/control publication failed");
			});
			throw new AssertionError("Expected transactional respawn barrier failure");
		} catch (AgentDomainException exception) {
			assertEquals("RESPAWN_BARRIER_FAILED", exception.code(), "respawn barrier failure code");
		}
		assertEquals(exactDead, registry.require(active.agentId()), "failed respawn retains the exact DEAD record and snapshot");
		try {
			registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 5L, (transition, commit) -> {
				commit.run();
				throw new AgentDomainException("RESPAWN_PUBLICATION_FAILED", "publication failed after commit");
			});
			throw new AssertionError("Expected post-commit respawn barrier failure");
		} catch (AgentDomainException exception) {
			assertEquals("RESPAWN_PUBLICATION_FAILED", exception.code(), "post-commit barrier failure code");
		}
		assertEquals(exactDead, registry.require(active.agentId()), "post-commit barrier failure restores the exact DEAD record");
		AgentTransition respawned = registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 6L, (transition, commit) -> commit.run());
		assertEquals(Optional.empty(), respawned.after().deathSnapshot(), "only successful respawn clears death snapshot");
		return 17;
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
