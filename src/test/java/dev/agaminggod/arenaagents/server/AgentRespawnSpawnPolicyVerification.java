package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.LinkedHashMap;
import java.util.Optional;

public final class AgentRespawnSpawnPolicyVerification {
	private AgentRespawnSpawnPolicyVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("PASS: " + verify() + " respawn and lifecycle assertions");
	}

	public static int verify() {
		long deadline = 2_000L;
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_REMOVAL,
				AgentRespawnSpawnPolicy.decide(false, true, 1_999L, deadline),
				"old fake player must leave before a replacement is requested"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, deadline, deadline),
				"stuck fake-player removal times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.REQUEST_SPAWN,
				AgentRespawnSpawnPolicy.decide(false, false, 1_000L, deadline),
				"replacement is requested only after absence is confirmed"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_SPAWN,
				AgentRespawnSpawnPolicy.decide(true, false, 1_999L, deadline),
				"accepted Carpet spawn remains pending until the player appears"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.VERIFY_PLAYER,
				AgentRespawnSpawnPolicy.decide(true, true, 1_500L, deadline),
				"physical player presence advances to verification"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(true, false, deadline, deadline),
				"accepted spawn without a physical player times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.RESPAWN_CONNECTED_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true, false),
				"a retained dead Carpet player respawns through its existing connection"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.RESPAWN_CONNECTED_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true, true),
				"Carpet's temporary post-death health reset cannot turn a retained death into a second disconnect"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.WAIT_FOR_NATURAL_REMOVAL,
				AgentRespawnSpawnPolicy.existingPlayerAction(false, false),
				"an unretained dead Carpet player may finish its stock disconnect"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.REMOVE_STALE_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(false, true),
				"an unrelated stale live player must be removed before replacement"
		);
		CodexAgentManager.RespawnRemovalDecision beforeGrace = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 999L, 1_000L, deadline);
		assertFalse(
				beforeGrace.requestRemoval(),
				"respawn waits for the fixed removal grace deadline"
		);
		CodexAgentManager.RespawnRemovalDecision firstRemoval = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 1_000L, 1_000L, deadline);
		assertTrue(
				firstRemoval.requestRemoval(),
				"respawn requests stale-player removal once after grace"
		);
		assertEquals(deadline, firstRemoval.deadlineEpochMs(), "stale-player removal retains the original deadline");
		CodexAgentManager.RespawnRemovalDecision repeatedRemoval = CodexAgentManager.respawnRemovalDecision(
				false, true, true, 3_000L, 1_000L, firstRemoval.deadlineEpochMs());
		assertFalse(
				repeatedRemoval.requestRemoval(),
				"respawn does not request removal again while waiting for the fixed deadline"
		);
		assertEquals(deadline, repeatedRemoval.deadlineEpochMs(), "waiting does not extend the removal deadline");
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, 2_000L, deadline),
				"one removal request cannot extend the original removal deadline"
		);

		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger cleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException primary = new IllegalStateException("runtime hook failed");
		IllegalArgumentException secondary = new IllegalArgumentException("ticket cleanup failed");
		RuntimeException observed = expectRuntimeFailure(() -> CodexAgentManager.releaseOnce(
				released,
				() -> {
					cleanupCalls.incrementAndGet();
					throw primary;
				},
				cleanupCalls::incrementAndGet,
				() -> {
					cleanupCalls.incrementAndGet();
					throw secondary;
				}
		));
		assertSame(primary, observed, "shutdown reports the first cleanup failure");
		assertEquals(3, cleanupCalls.get(), "shutdown attempts every owned cleanup after a failure");
		assertEquals(1, observed.getSuppressed().length, "shutdown retains later cleanup failures");
		assertSame(secondary, observed.getSuppressed()[0], "shutdown suppresses the later failure on the primary");
		CodexAgentManager.releaseOnce(released, cleanupCalls::incrementAndGet);
		assertEquals(3, cleanupCalls.get(), "repeated shutdown is idempotent after a failed first release");

		java.util.concurrent.atomic.AtomicInteger physicalCleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger durableDeleteCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException cleanupFailure = new IllegalStateException("player removal failed");
		assertSame(cleanupFailure, expectRuntimeFailure(() -> CodexAgentManager.deleteAfterRequiredCleanup(
				() -> {
					physicalCleanupCalls.incrementAndGet();
					throw cleanupFailure;
				},
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		)), "failed physical cleanup is reported");
		assertEquals(0, durableDeleteCalls.get(), "failed player cleanup leaves the registry record retryable");
		assertEquals("removed", CodexAgentManager.deleteAfterRequiredCleanup(
				physicalCleanupCalls::incrementAndGet,
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		), "successful retry reaches durable deletion");
		assertEquals(2, physicalCleanupCalls.get(), "retry performs physical cleanup again");
		assertEquals(1, durableDeleteCalls.get(), "durable deletion happens once after cleanup succeeds");

		java.util.Set<String> currentNames = java.util.Set.of("c00_11111111", "k20_22222222");
		assertEquals(
				java.util.List.of("c01_DEADBEEF", "legacy_33333333"),
				CodexAgentManager.staleHiddenTeamMembers(
						java.util.List.of("c00_11111111", "c01_DEADBEEF", "k20_22222222", "legacy_33333333"),
						currentNames
				),
				"startup pruning selects only hidden-team identities absent from the current registry"
		);
		assertEquals(java.util.List.of(), CodexAgentManager.staleHiddenTeamMembers(currentNames, currentNames),
				"repeated hidden-team cleanup is idempotent");
		assertEquals(java.util.List.of("c00_11111111"), CodexAgentManager.staleHiddenTeamMembers(
				java.util.List.of("c00_11111111"), java.util.Set.of()),
				"removing the final registry record marks its hidden-team membership stale");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(Long.MIN_VALUE, 10_000L, false),
				"the first exact location is persisted immediately");
		assertFalse(CodexAgentManager.shouldPersistEntityLocation(10_000L, 10_999L, false),
				"the hot tick loop does not dirty saved data before the one-second bound");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(10_000L, 11_000L, false),
				"exact location persistence runs at the one-second bound");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(10_999L, 10_999L, true),
				"shutdown forces the final exact position and view write");

		AgentEntityLocation exactLocation = AgentEntityLocation.exact(
				"minecraft:overworld", -2, 2, -16.25D, 70.75D, 32.5D, 120.0F, -15.0F
		);
		java.util.concurrent.atomic.AtomicInteger safetyChecks = new java.util.concurrent.atomic.AtomicInteger();
		CodexAgentManager.ExactRecoveryCoordinates exact = CodexAgentManager.exactRecoveryCoordinates(
				exactLocation,
				(x, y, z) -> {
					safetyChecks.incrementAndGet();
					assertEquals(-16.25D, x, "exact recovery checks the saved body X coordinate");
					assertEquals(70.75D, y, "exact recovery checks the saved body Y coordinate");
					assertEquals(32.5D, z, "exact recovery checks the saved body Z coordinate");
					return true;
				}
		).orElseThrow();
		assertEquals(1, safetyChecks.get(), "exact recovery checks the full saved position once");
		assertEquals(-16.25D, exact.x(), "safe recovery preserves exact X");
		assertEquals(70.75D, exact.y(), "safe recovery preserves exact Y");
		assertEquals(32.5D, exact.z(), "safe recovery preserves exact Z");
		assertEquals(120.0F, exact.yaw(), "safe recovery preserves yaw");
		assertEquals(-15.0F, exact.pitch(), "safe recovery preserves pitch");
		assertTrue(CodexAgentManager.exactRecoveryCoordinates(exactLocation, (x, y, z) -> false).isEmpty(),
				"an unsafe exact column yields to the bounded nearby fallback");
		assertTrue(CodexAgentManager.exactRecoveryCoordinates(
				new AgentEntityLocation("minecraft:overworld", 0, 0),
				(x, y, z) -> {
					throw new AssertionError("coarse snapshots must skip exact recovery");
				}
		).isEmpty(), "coarse snapshots continue directly to bounded recovery");

		var inFlight = new java.util.LinkedHashMap<dev.agaminggod.arenaagents.agent.AgentId, Object>();
		var agentId = dev.agaminggod.arenaagents.agent.AgentId.random();
		var starts = new java.util.concurrent.atomic.AtomicInteger();
		Object first = CodexAgentManager.singleFlight(inFlight, agentId, () -> {
			starts.incrementAndGet();
			return new Object();
		});
		Object second = CodexAgentManager.singleFlight(inFlight, agentId, () -> {
			starts.incrementAndGet();
			return new Object();
		});
		assertSame(first, second, "automatic and coordinator respawn requests share one attempt");
		assertEquals(1, starts.get(), "only one physical respawn attempt can start per agent");
		assertEquals(1, inFlight.size(), "single-flight ownership keeps one pending respawn entry");

		AgentId abortId = AgentId.random();
		AgentRecord deadRecord = AgentRecord.create(
				abortId, new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0), 1_000L);
		CodexAgentManager.VanillaRespawnAttempt attempt = vanillaRespawnAttempt(deadRecord);
		var pending = new LinkedHashMap<AgentId, CodexAgentManager.VanillaRespawnAttempt>();
		pending.put(abortId, attempt);
		assertTrue(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"cancellation removes the manager-owned pending respawn");
		assertTrue(pending.isEmpty(), "a cancelled respawn cannot remain eligible to commit");
		assertFalse(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"a second cancel cannot abort a respawn that already left the map");
		CodexAgentManager.VanillaRespawnAttempt replacement = vanillaRespawnAttempt(deadRecord);
		pending.put(abortId, replacement);
		assertFalse(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"cancellation cannot abort a replacement attempt");
		assertSame(replacement, pending.get(abortId), "a newer pending respawn stays in flight");

		assertTrue(CodexAgentManager.shouldRetryConnectedRespawn(true, true, true),
				"publication failure keeps a healthy connected replacement for retry");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(true, false, true),
				"a missing connected replacement falls back to recovery");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(false, true, true),
				"legacy createFake failures retain their existing rollback semantics");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(true, true, false),
				"a superseded lifecycle cannot replay an old connected respawn commit");
		return 56 + verifyRemovedDelayedRespawn() + AgentSummonNameVerification.verify() + AgentShutdownLocationVerification.verify()
				+ verifyRecoveryBodySafety();
	}

	/** Exercises the real recovery selector and Minecraft collision iterator, with only world storage stubbed. */
	static int verifyRecoveryBodySafety() {
		try {
			var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			var allocator = (sun.misc.Unsafe) field.get(null);
			var manager = (CodexAgentManager) allocator.allocateInstance(CodexAgentManager.class);
			var select = CodexAgentManager.class.getDeclaredMethod("findRecoverySpawn",
					net.minecraft.server.level.ServerLevel.class, AgentEntityLocation.class);
			select.setAccessible(true);
			var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			var cases = java.util.List.of(
					new RecoveryCase("adjacent wall", .95, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(1,70,0), stone,
							new net.minecraft.core.BlockPos(1,71,0), stone), false),
					new RecoveryCase("fractional ceiling", .5, 70.75, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,72,0), stone), false),
					new RecoveryCase("safe fractional position", .4, 70.1, .4, java.util.Map.of(), true),
					new RecoveryCase("integer Y below ceiling", .5, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,72,0), stone), true),
					new RecoveryCase("blocked head", .5, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,71,0), stone), false),
					new RecoveryCase("feet fire", .5, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,69,0), net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState(),
							new net.minecraft.core.BlockPos(0,70,0), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState()), false),
					new RecoveryCase("feet soul fire", .5, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,69,0), net.minecraft.world.level.block.Blocks.SOUL_SOIL.defaultBlockState(),
							new net.minecraft.core.BlockPos(0,70,0), net.minecraft.world.level.block.Blocks.SOUL_FIRE.defaultBlockState()), false),
					new RecoveryCase("head fire", .5, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(0,71,0), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState()), false),
					new RecoveryCase("neighboring fire", .95, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(1,70,0), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState()), false),
					new RecoveryCase("neighboring fluid", .95, 70, .5, java.util.Map.of(
							new net.minecraft.core.BlockPos(1,70,0), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()), false));
			var failures = new java.util.ArrayList<String>();
			for (var scenario : cases) {
				RecoveryFixtureLevel level = recoveryFixture(allocator);
				level.blocks.putAll(scenario.blocks());
				var location = AgentEntityLocation.exact("minecraft:overworld", 0, 0,
						scenario.x(), scenario.y(), scenario.z(), 120f, -15f);
				Object result = ((Optional<?>) select.invoke(manager, level, location)).orElseThrow();
				var position = result.getClass().getDeclaredMethod("position");
				position.setAccessible(true);
				var selected = (net.minecraft.world.phys.Vec3) position.invoke(result);
				boolean exact = selected.equals(new net.minecraft.world.phys.Vec3(scenario.x(), scenario.y(), scenario.z()));
				var body = net.minecraft.world.entity.EntityType.PLAYER.getDimensions().makeBoundingBox(selected);
				boolean unsafe = false;
				// Independent oracle examines all configured shapes, not the production query's sampled cells.
				for (var entry : level.blocks.entrySet()) {
					var at = entry.getKey();
					var state = entry.getValue();
					var shape = state.getCollisionShape(level, at).move(at.getX(), at.getY(), at.getZ());
					unsafe |= net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
							net.minecraft.world.phys.shapes.Shapes.create(body), shape, net.minecraft.world.phys.shapes.BooleanOp.AND);
					if (state.is(net.minecraft.world.level.block.Blocks.FIRE)
							|| state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE) || !state.getFluidState().isEmpty()) {
						unsafe |= body.intersects(new net.minecraft.world.phys.AABB(at));
					}
				}
				if (exact != scenario.exact() || unsafe) failures.add(scenario.name() + " selected=" + selected + " unsafe=" + unsafe);
				var yaw = result.getClass().getDeclaredMethod("yaw");
				var pitch = result.getClass().getDeclaredMethod("pitch");
				yaw.setAccessible(true);
				pitch.setAccessible(true);
				assertEquals(120f, yaw.invoke(result), "recovery retains yaw");
				assertEquals(-15f, pitch.invoke(result), "recovery retains pitch");
			}
			assertTrue(failures.isEmpty(), "unsafe recovery selections: " + failures);
			RecoveryFixtureLevel boundary = recoveryFixture(allocator);
			boundary.unloadedChunkX = 1;
			assertTrue(boundary.hasChunk(1, 0), "ticket eligibility alone cannot prove a completed chunk");
			assertFalse(CodexAgentManager.recoveryBodySafe(boundary, 15.95, 70, .5),
					"unknown neighboring body space cannot be accepted");
			assertEquals(0, boundary.chunkAcquisitions, "body safety never acquires the unloaded neighbor");
			assertEquals(0, boundary.worldReads, "unloaded halo is rejected before world reads");
			boundary.unloadedChunkX = Integer.MIN_VALUE;
			assertTrue(CodexAgentManager.recoveryBodySafe(boundary, 15.95, 70, .5),
					"the same clear cross-chunk body is safe once its surrounding chunks are resident");
			assertEquals(0, boundary.chunkAcquisitions, "resident body safety does not acquire chunks either");
			boundary.border.setCenter(0, 0);
			boundary.border.setSize(20);
			assertFalse(CodexAgentManager.recoveryBodySafe(boundary, 9.95, 70, .5), "the whole body must fit the world border");
			assertTrue(CodexAgentManager.recoveryBodySafe(boundary, 9.5, 70, .5), "clear body inside the world border remains valid");
			double halfWidth = net.minecraft.world.entity.EntityType.PLAYER.getDimensions().width() / 2.0D;
			assertTrue(CodexAgentManager.recoveryBodySafe(boundary, 10 - halfWidth, 70, .5),
					"a body touching the border without crossing it remains valid");
			assertFalse(CodexAgentManager.recoveryBodySafe(boundary, .5, 319, .5), "body cannot cross the build ceiling");
			assertFalse(CodexAgentManager.recoveryBodySafe(boundary, .5, -64.1, .5), "body cannot cross the build floor");

			RecoveryFixtureLevel nearby = recoveryFixture(allocator);
			var column = CodexAgentManager.class.getDeclaredMethod("recoveryColumn",
					net.minecraft.server.level.ServerLevel.class, int.class, int.class, java.util.OptionalInt.class);
			column.setAccessible(true);
			var selectedColumn = (AgentRecoverySpawnPolicy.Column) column.invoke(manager, nearby, 8, 8, java.util.OptionalInt.of(70));
			assertTrue(selectedColumn.safe(), "preferred-height success returns a safe column");
			assertEquals(70, selectedColumn.feetY(), "preferred-height success retains the selected height");
			assertEquals(1, nearby.bodyQueries, "successful preferred-height column is sampled only once");
			nearby.bodyQueries = 0;
			var surfaceColumn = (AgentRecoverySpawnPolicy.Column) column.invoke(manager, nearby, 8, 8, java.util.OptionalInt.empty());
			assertTrue(surfaceColumn.safe(), "heightmap recovery still accepts a clear supported surface");
			assertEquals(70, surfaceColumn.feetY(), "heightmap recovery retains its surface height");
			assertEquals(1, nearby.bodyQueries, "heightmap recovery checks its candidate once");
			return cases.size() * 3 + 17;
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("recovery world fixture failed", failure);
		}
	}

	private static RecoveryFixtureLevel recoveryFixture(sun.misc.Unsafe allocator) throws InstantiationException {
		var level = (RecoveryFixtureLevel) allocator.allocateInstance(RecoveryFixtureLevel.class);
		level.blocks = new java.util.HashMap<>();
		level.border = new net.minecraft.world.level.border.WorldBorder();
		level.unloadedChunkX = Integer.MIN_VALUE;
		level.chunks = (RecoveryFixtureChunks) allocator.allocateInstance(RecoveryFixtureChunks.class);
		level.chunks.level = level;
		level.chunks.resident = (net.minecraft.world.level.chunk.LevelChunk) allocator.allocateInstance(net.minecraft.world.level.chunk.LevelChunk.class);
		for (int x = -2; x <= 18; x++) for (int z = -2; z <= 18; z++) {
			level.blocks.put(new net.minecraft.core.BlockPos(x,69,z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		}
		return level;
	}

	private record RecoveryCase(String name, double x, double y, double z,
			java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> blocks, boolean exact) {}

	private static final class RecoveryFixtureLevel extends net.minecraft.server.level.ServerLevel {
		java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> blocks;
		net.minecraft.world.level.border.WorldBorder border;
		int unloadedChunkX;
		int chunkAcquisitions;
		int worldReads;
		int bodyQueries;
		RecoveryFixtureChunks chunks;
		private RecoveryFixtureLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
		@Override public net.minecraft.world.level.chunk.LevelChunk getChunk(int x, int z) { chunkAcquisitions++; return null; }
		@Override public boolean hasChunk(int x, int z) { return true; }
		@Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return chunks; }
		@Override public net.minecraft.world.level.BlockGetter getChunkForCollisions(int x, int z) {
			if (x == unloadedChunkX) throw new AssertionError("collision query reached an unloaded chunk");
			return this;
		}
		@Override public boolean noBlockCollision(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB body) {
			bodyQueries++;
			return super.noBlockCollision(entity, body);
		}
		@Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return border; }
		@Override public net.minecraft.core.BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types type,
				net.minecraft.core.BlockPos pos) { return new net.minecraft.core.BlockPos(pos.getX(), 70, pos.getZ()); }
		@Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
			if ((pos.getX() >> 4) == unloadedChunkX) throw new AssertionError("state read reached an unloaded chunk");
			worldReads++;
			return blocks.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}
		@Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) { return getBlockState(pos).getFluidState(); }
		@Override public int getMinY() { return -64; }
		@Override public int getMaxY() { return 320; }
	}

	private static final class RecoveryFixtureChunks extends net.minecraft.server.level.ServerChunkCache {
		RecoveryFixtureLevel level;
		net.minecraft.world.level.chunk.LevelChunk resident;
		private RecoveryFixtureChunks() { super(null, null, null, null, null, null, 0, 0, false, null, null); }
		@Override public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x, int z) {
			return x == level.unloadedChunkX ? null : resident;
		}
		@Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
				net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create) {
			throw new AssertionError("recovery body check must not wait for or create chunks");
		}
	}

	private static int verifyRemovedDelayedRespawn() {
		AgentRecord removed = AgentRecord.create(AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), 0,
						dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL), 100L);
		var pending = new LinkedHashMap<AgentId, Long>();
		pending.put(removed.agentId(), 10_000L);
		PendingSpawnCancellationLedger cancellations = new PendingSpawnCancellationLedger(120_000L);
		CodexAgentManager.cancelPendingPlayerSpawn(removed, pending, cancellations, 101L);
		pending.remove(removed.agentId()); // Respawn rollback runs after cancellation and clears this map too.
		assertTrue(pending.isEmpty(), "removed respawn loses its pending commit ownership");
		assertEquals(1, cancellations.active(102L).size(), "rollback cannot consume delayed callback cancellation ownership");
		var delayedBodies = new LinkedHashMap<AgentId, AgentProfile>();
		delayedBodies.put(removed.agentId(), removed.profile()); // Carpet's previously queued callback finishes later.
		for (var cancellation : cancellations.active(103L)) {
			delayedBodies.remove(cancellation.agentId(), cancellation.profile());
		}
		assertTrue(delayedBodies.isEmpty(), "reconciliation still owns the removed agent's late body");
		CodexAgentManager.cancelPendingPlayerSpawn(removed, pending, cancellations, 104L);
		assertEquals(1, cancellations.active(105L).size(), "repeated cleanup preserves the existing cancellation");
		return 4;
	}

	private static CodexAgentManager.VanillaRespawnAttempt vanillaRespawnAttempt(AgentRecord deadRecord) {
		try {
			var constructor = CodexAgentManager.VanillaRespawnAttempt.class.getDeclaredConstructor(
					AgentRecord.class, OfflineAgentPlayers.VanillaRespawnTarget.class, long.class, long.class);
			constructor.setAccessible(true);
			return constructor.newInstance(deadRecord, null, 0L, 0L);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing vanilla respawn attempt constructor", exception);
		}
	}

	private static RuntimeException expectRuntimeFailure(Runnable operation) {
		try {
			operation.run();
		} catch (RuntimeException failure) {
			return failure;
		}
		throw new AssertionError("expected cleanup failure");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected same instance");
	}
}
