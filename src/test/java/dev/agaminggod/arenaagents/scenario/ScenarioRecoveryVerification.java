package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRecoveryDecision;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRecoveryGate;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRunSnapshot;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeClock;
import dev.agaminggod.arenaagents.server.AgentRemovalCoordinator;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public final class ScenarioRecoveryVerification {
	private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final UUID OPERATOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

	private ScenarioRecoveryVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		ScenarioSession session = runningSession();
		ScenarioRuntimeClock clock = new ScenarioRuntimeClock(session);
		clock.tick();
		clock.tick();
		session.pauseForRecovery(2L);
		ScenarioRuntimeClock.Snapshot clockSnapshot = clock.snapshot();
		assertEquals(2L, clock.tick().elapsedTick(), "recovery pause reports the frozen elapsed tick");
		assertEquals(clockSnapshot, clock.snapshot(), "recovery pause freezes all clock progress");
		assertTrue(session.resumeRecovery(2L), "recovery resumes once");
		assertFalse(session.resumeRecovery(2L), "duplicate recovery completion is idempotent");
		assertEquals(2L, clock.tick().elapsedTick(), "first resumed tick continues at exact saved position");

		ScenarioRunSnapshot snapshot = new ScenarioRunSnapshot(
				ScenarioRunSnapshot.CURRENT_VERSION,
				SESSION_ID,
				"last-valley",
				ScenarioPresets.require("last-valley").mapVersion(),
				51L,
				52L,
				ScenarioPresets.require("last-valley").defaultDurationTicks(),
				true,
				List.of(
						new ScenarioRunSnapshot.Participant("agent-a", "Codex Sol High", Optional.of("amber")),
						new ScenarioRunSnapshot.Participant("agent-b", "Gemini Pro High", Optional.of("blue"))
				),
				1_750_000_000_000L,
				"minecraft:overworld",
				OPERATOR_ID,
				List.of("agent-b", "agent-a"),
				new ScenarioRunSnapshot.Origin(10, 64, -10),
				ScenarioSessionState.PAUSED_RECOVERY,
				Optional.empty(),
				2L,
				Map.of("agent-b", 1.0D, "agent-a", 2.0D),
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioResetReceipt.verified(
						"blueprint-sha", "blueprint-sha", 2, 2, 2
				),
				clockSnapshot,
				List.of(new ScenarioPublicEvent(
						1L, "agent-a", "Codex Sol High", "action_completed", "harvest", 0.0D,
						"acting"
				))
		);
		String encoded = snapshot.toJson();
		assertEquals(snapshot, ScenarioRunSnapshot.fromJson(encoded), "strict snapshot JSON round trip");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.replace("\"version\":1", "\"version\":99")),
				"UNSUPPORTED_SCENARIO_SNAPSHOT");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.substring(0, encoded.length() - 1) + ",\"extra\":true}"),
				"INVALID_SCENARIO_SNAPSHOT");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.replace(
				"\"state\":\"acting\"", "\"state\":\"acting\",\"message\":\"raw provider prose\""
		)), "INVALID_SCENARIO_SNAPSHOT");

		var pausedStatus = new ScenarioRecoveryGate.AgentStatus("agent-a", AgentLifecycleState.PAUSED, true);
		var disconnectedGate = ScenarioRecoveryGate.evaluate(false, List.of(pausedStatus));
		assertFalse(disconnectedGate.ready(), "recovery stays frozen without an authenticated coordinator");
		assertEquals(List.of(), disconnectedGate.resumeAgentIds(), "disconnected recovery does not resume agents");
		var pausedGate = ScenarioRecoveryGate.evaluate(true, List.of(pausedStatus));
		assertFalse(pausedGate.ready(), "paused agents must resume before scenario time advances");
		assertEquals(List.of("agent-a"), pausedGate.resumeAgentIds(), "authenticated recovery identifies paused agent to resume");
		assertTrue(ScenarioRecoveryGate.evaluate(true, List.of(
				new ScenarioRecoveryGate.AgentStatus("agent-a", AgentLifecycleState.STARTING, true)
		)).ready(), "recovery becomes ready only after the agent is active");
		long statusNow = 50_000L;
		List<String> recoveredAgentIds = snapshot.boundAgentIds();
		assertFalse(ScenarioRecoveryGate.coordinatorReady(Optional.empty(), recoveredAgentIds, statusNow, 2_500L),
				"recovery rejects a missing coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a", "agent-b"), statusNow - 2_501L)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects a stale coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(false, List.of("agent-a", "agent-b"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects an unreconciled coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects a reconciled status missing a bound agent");
		assertTrue(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a", "agent-b"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery accepts only a fresh reconciled status containing every bound agent");

		ScenarioRecoveryDecision missing = ScenarioRecoveryDecision.evaluate(snapshot, Set.of("minecraft:the_nether"));
		assertFalse(missing.recoverable(), "missing dimension fails closed");
		assertEquals(List.of("agent-a", "agent-b"), missing.boundAgentsToRemove(), "failed recovery removes every bound agent");
		assertTrue(ScenarioRecoveryDecision.evaluate(snapshot, Set.of("minecraft:overworld")).recoverable(),
				"exact saved dimension is recoverable");

		List<ScenarioArenaBlueprint.Placement> duplicates = List.of(
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 64, 1), Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(1, 64, 1), Blocks.DIRT.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 64, 1), Blocks.GOLD_BLOCK.defaultBlockState())
		);
		List<ScenarioArenaBlueprint.Placement> canonical = ScenarioArenaResetJob.canonicalize(duplicates);
		assertEquals(2, canonical.size(), "duplicate positions canonicalize to one placement");
		assertEquals(Blocks.GOLD_BLOCK, canonical.get(1).state().getBlock(), "duplicate placements are last-write-wins");
		assertEquals(ScenarioArenaResetJob.hash(canonical), ScenarioArenaResetJob.hash(List.of(
				canonical.get(1), canonical.get(0)
		)), "reset hash is independent of input ordering");
		assertEquals(2, ScenarioArenaResetJob.batchEnd(0, 2, 2_048), "reset batch stays within available work");
		assertEquals(2_048, ScenarioArenaResetJob.batchEnd(0, 5_000, 2_048), "reset batch is capped at 2048 entries");
		var verifiedReset = ScenarioArenaResetJob.verifyCanonical(canonical, List.of(canonical.get(1), canonical.get(0)));
		assertTrue(verifiedReset.verified(), "exhaustive reset verification accepts identical managed state");
		var mismatchedReset = ScenarioArenaResetJob.verifyCanonical(canonical, List.of(
				canonical.get(0),
				new ScenarioArenaBlueprint.Placement(canonical.get(1).position(), Blocks.IRON_BLOCK.defaultBlockState())
		));
		assertFalse(mismatchedReset.verified(), "exhaustive reset verification rejects one mismatched state");
		assertEquals(2, mismatchedReset.verifiedPlacements(), "verification inspects the full managed volume after a mismatch");

		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord removable = registry.create("codex", "gpt-5.6-sol", "high", Optional.of("Removal Test"), 1L);
		AtomicInteger removalFailures = new AtomicInteger();
		AgentRemovalCoordinator.removeRegistryFirst(
				registry,
				removable.agentId(),
				() -> { throw new IllegalStateException("cleanup failure"); },
				() -> { throw new IllegalStateException("hook failure"); },
				failure -> removalFailures.incrementAndGet()
		);
		assertEquals(List.of(), registry.records(), "registry deletion survives cleanup and hook failures");
		assertEquals(2, removalFailures.get(), "post-delete failures are reported without restoring the agent");
		return 36;
	}

	private static CoordinatorStatusSnapshot recoveryStatus(
			boolean reconciled,
			List<String> agentIds,
			long receivedAtEpochMs
	) {
		List<CoordinatorStatusSnapshot.SupportedProfile> profiles = agentIds.stream()
				.map(agentId -> new CoordinatorStatusSnapshot.SupportedProfile(
						agentId, "codex", "gpt-5.6-sol", "high"
				))
				.toList();
		return new CoordinatorStatusSnapshot(
				reconciled,
				profiles,
				profiles.size(),
				profiles.size(),
				profiles.size(),
				new CoordinatorStatusSnapshot.SchedulerStatus(0, 0, 4, 12, false),
				List.of(new CoordinatorStatusSnapshot.CircuitHealth(
						"codex", "gpt-5.6-sol", "decide", 0, 0, 0, 0.0D, "closed"
				)),
				receivedAtEpochMs
		);
	}

	private static ScenarioSession runningSession() {
		ScenarioPreset preset = ScenarioPresets.require("last-valley");
		ScenarioSession session = new ScenarioSession(new ScenarioSessionConfig(
				SESSION_ID,
				preset,
				51L,
				52L,
				preset.defaultDurationTicks(),
				true,
				List.of(
						new ScenarioParticipant("agent-a", "Codex Sol High", Optional.of("amber")),
						new ScenarioParticipant("agent-b", "Gemini Pro High", Optional.of("blue"))
				),
				1_750_000_000_000L
		));
		session.markReady(0L);
		session.beginCountdown(0L);
		session.start(0L);
		return session;
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("expected failure " + code);
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains(code)) {
				throw new AssertionError("expected failure " + code + " but was " + expected.getMessage());
			}
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
