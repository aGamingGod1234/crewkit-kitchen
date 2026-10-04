package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.control.AgentControlActions;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentRecoverySpawnPolicyVerification {
	private AgentRecoverySpawnPolicyVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " recovery policy assertions");
	}

	public static int verify() {
		int assertions = 0;
		AgentRecoverySpawnPolicy.Column unsafeWater = new AgentRecoverySpawnPolicy.Column(
				64, false, false, false, true, false, true, false);
		AgentRecoverySpawnPolicy.Column safeLand = new AgentRecoverySpawnPolicy.Column(
				67, true, true, true, true, true, true, true);
		AgentRecoverySpawnPolicy.Column unsupportedSand = new AgentRecoverySpawnPolicy.Column(
				63, true, false, true, true, true, true, true);
		AtomicInteger sampled = new AtomicInteger();
		Optional<AgentRecoverySpawnPolicy.Position> selected = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15,
				(x, z) -> {
					sampled.incrementAndGet();
					return x == 9 && z == 8 ? safeLand : unsafeWater;
				}
		);
		assertEquals(Optional.of(new AgentRecoverySpawnPolicy.Position(9, 67, 8)), selected,
				"submerged chunk center is rejected for the nearest dry supported column");
		assertions++;
		assertEquals(5, sampled.get(), "nearest-first recovery stops sampling after the first safe distance tier");
		assertions++;

		Optional<AgentRecoverySpawnPolicy.Position> shiftingFloor = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15, (x, z) -> x == 8 && z == 8 ? unsupportedSand : unsafeWater);
		assertEquals(Optional.empty(), shiftingFloor,
				"a gravity-affected floor without stable support is rejected before it can fall into water");
		assertions++;

		Optional<AgentRecoverySpawnPolicy.Position> none = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15, (x, z) -> unsafeWater);
		assertEquals(Optional.empty(), none, "a chunk without dry supported body space has no recovery position");
		assertions++;

		assertEquals(
				new AgentRecoverySpawnPolicy.ChunkPosition(-1, 1),
				AgentRecoverySpawnPolicy.chunkContaining(-0.1D, 16.0D),
				"legacy entity coordinates use floor-based chunk selection before the safety search"
		);
		assertions++;

		expectIllegalArgument(
				() -> AgentRecoverySpawnPolicy.selectNearestDryPosition(
						0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0, (x, z) -> safeLand),
				"corrupted recovery bounds are rejected before an overflowing scan"
		);
		assertions++;

		assertEquals(OptionalInt.of(61), AgentRecoverySpawnPolicy.selectNearestSafeY(
				64, -64, 319, y -> y == 61 || y == 128),
				"recovery selects playable body space near the persisted height instead of the Nether roof");
		assertions++;
		assertEquals(OptionalInt.empty(), AgentRecoverySpawnPolicy.selectNearestSafeY(
				64, -64, 319, y -> y == 128),
				"recovery rejects safe-looking terrain outside the bounded persisted-height search");
		assertions++;

		CodexAgentManager.RecoveryAttemptGate attemptGate = new CodexAgentManager.RecoveryAttemptGate();
		assertEquals(true, attemptGate.tryClaim(), "the first missing agent can claim this tick's recovery attempt");
		assertions++;
		assertEquals(false, attemptGate.tryClaim(),
				"a failed first recovery still consumes the tick budget and prevents recovery fan-out");
		assertions++;
		return assertions + verifyDeferredAndFailedRecoveryLocations();
	}

	private static int verifyDeferredAndFailedRecoveryLocations() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, ignored -> { });
		AgentRecord first = registry.create("codex", "gpt-5.6-sol", "high", "priority",
				Optional.empty(), AgentGameMode.SURVIVAL, 100L);
		AgentRecord second = registry.create("codex", "gpt-5.6-sol", "high", "priority",
				Optional.empty(), AgentGameMode.SURVIVAL, 101L);
		AgentEntityLocation nether = AgentEntityLocation.exact("minecraft:the_nether", 7, -3, 115, 42, -40, 90, 5);
		AgentEntityLocation end = AgentEntityLocation.exact("minecraft:the_end", 12, 8, 200, 70, 130, 180, 0);
		registry.attachEntity(first.agentId(), UUID.randomUUID(), nether, 102L);
		registry.attachEntity(second.agentId(), UUID.randomUUID(), end, 102L);
		registry.start(first.agentId(), "continue mining", 103L);
		registry.start(second.agentId(), "continue exploring", 103L);
		Set<AgentId> pending = new LinkedHashSet<>();
		CodexAgentManager.RecoveryAttemptGate firstTick = new CodexAgentManager.RecoveryAttemptGate();
		int admitted = 0;
		for (AgentId id : List.of(first.agentId(), second.agentId())) {
			CodexAgentManager.prepareMissingPlayer(registry, registry.require(id), true, pending, 104L);
			if (firstTick.tryClaim()) admitted++; // The first physical attempt fails; no attachment is committed.
		}
		assertEquals(1, admitted, "failed recovery consumes the only spawn admission");
		assertEquals(Optional.of(nether), registry.require(first.agentId()).entityLocation(), "failed attempt retains exact Nether position");
		assertEquals(Optional.of(end), registry.require(second.agentId()).entityLocation(), "deferred agent retains exact End position");
		assertEquals(AgentLifecycleState.DISCONNECTED, registry.require(first.agentId()).state(), "missing active player remains fenced from action execution");
		assertEquals(Set.of(first.agentId(), second.agentId()), pending, "both missing active agents retain resume intent");
		AtomicInteger trackedTickets = new AtomicInteger();
		AtomicInteger releasedTickets = new AtomicInteger();
		for (AgentId id : List.of(first.agentId(), second.agentId())) {
			assertEquals(true, registry.require(id).entityUuid().isPresent(), "missing body retains its saved attachment");
			var control = AgentControlSync.controlSnapshot(true, true, "Automation ready", 104L,
					List.of(registry.require(id)), List.of(), AgentControlCatalog.currentOptions(), ignored -> Optional.empty())
					.agents().getFirst();
			assertEquals(false, control.entityPresent(), "failed and deferred recovery do not project the retained UUID as a body");
			assertEquals(false, AgentControlActions.supports(control, "start"), "missing recovered agent cannot Start");
			assertEquals(false, AgentControlActions.supports(control, "resume"), "missing recovered agent cannot Resume");
			CodexAgentManager.maintainPresentBodyTicket(Optional.empty(), ignored -> trackedTickets.incrementAndGet(),
					releasedTickets::incrementAndGet);
		}
		assertEquals(0, trackedTickets.get(), "saved locations cannot reacquire chunk tickets for absent or deferred bodies");
		assertEquals(2, releasedTickets.get(), "ticket maintenance releases both missing-body tickets");
		CodexAgentManager.maintainPresentBodyTicket(Optional.of("observed living body"),
				ignored -> trackedTickets.incrementAndGet(), releasedTickets::incrementAndGet);
		assertEquals(1, trackedTickets.get(), "an observed living replacement keeps its chunk ticket");
		assertEquals(2, releasedTickets.get(), "a live-body ticket is not released during maintenance");
		// A later retry uses the persisted record, not a transient copy from the failed tick.
		AgentRecord retry = CodexAgentManager.prepareMissingPlayer(registry, registry.require(first.agentId()), true, pending, 105L);
		assertEquals(Optional.of(nether), retry.entityLocation(), "retry reads the durable original location");
		var retriedControl = AgentControlSync.controlSnapshot(true, true, "Automation ready", 105L,
				List.of(retry), List.of(), AgentControlCatalog.currentOptions(), ignored -> Optional.empty()).agents().getFirst();
		assertEquals(false, retriedControl.entityPresent(), "retry still requires an observed living body");
		CodexAgentManager.RecoveryAttemptGate nextTick = new CodexAgentManager.RecoveryAttemptGate();
		assertEquals(true, nextTick.tryClaim(), "the deferred agent can claim the next tick while the first backs off");
		AgentEntityLocation replacement = AgentEntityLocation.exact("minecraft:the_end", 12, 8, 201, 71, 131, 180, 0);
		registry.attachEntity(second.agentId(), registry.require(second.agentId()).entityUuid().orElseThrow(), replacement, 106L);
		assertEquals(Optional.of(replacement), registry.require(second.agentId()).entityLocation(), "a verified replacement supersedes the saved recovery location");
		return 21;
	}

	private static void expectIllegalArgument(Runnable action, String message) {
		try {
			action.run();
			throw new AssertionError(message + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
	}
}
