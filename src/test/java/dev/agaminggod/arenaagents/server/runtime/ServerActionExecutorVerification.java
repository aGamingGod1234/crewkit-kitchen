package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerController;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public final class ServerActionExecutorVerification {
	private ServerActionExecutorVerification() {
	}

	public static int verify() {
		ServerTransactionAdapter.TerminalGate gate = new ServerTransactionAdapter.TerminalGate();
		ServerTransactionAdapter.TickResult success = ServerTransactionAdapter.TickResult.succeeded(
				"TRANSFER_CONFIRMED", "Transfer confirmed");
		ServerTransactionAdapter.TickResult conflict = ServerTransactionAdapter.TickResult.failed(
				"TRANSACTION_CONFLICT", "Unexpected slot changed");
		assertEquals(success, gate.finish(success), "first terminal result is retained");
		assertEquals(success, gate.finish(conflict), "later terminal result cannot replace the first");

		AtomicInteger cleanupCalls = new AtomicInteger();
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		assertEquals(1, cleanupCalls.get(), "menu, carried stack, use state, and lease cleanup runs once");
		ServerTransactionAdapter.TerminalGate retryableCleanup = new ServerTransactionAdapter.TerminalGate();
		AtomicInteger cleanupAttempts = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> retryableCleanup.cleanupOnce(() -> {
			cleanupAttempts.incrementAndGet();
			throw new IllegalStateException("first cleanup failed");
		}), "failed cleanup remains retryable");
		assertFalse(retryableCleanup.cleanupComplete(), "failed cleanup is not reported as clean");
		retryableCleanup.cleanupOnce(cleanupAttempts::incrementAndGet);
		assertEquals(2, cleanupAttempts.get(), "cleanup retries after a failed attempt");
		assertTrue(retryableCleanup.cleanupComplete(), "successful retry marks cleanup complete");

		AtomicInteger bestEffortSteps = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> ServerTransactionAdapter.runBestEffort(
				() -> { bestEffortSteps.incrementAndGet(); throw new IllegalStateException("menu close failed"); },
				bestEffortSteps::incrementAndGet,
				bestEffortSteps::incrementAndGet
		), "best-effort cleanup reports its first failure");
		assertEquals(3, bestEffortSteps.get(), "controller stop and player stop run after an earlier cleanup failure");

		ServerActionExecutor.CleanupRetry<String> retainedCleanup = new ServerActionExecutor.CleanupRetry<>();
		retainedCleanup.retain("terminal-result");
		assertThrows(IllegalStateException.class, () -> retainedCleanup.complete(() -> {
			throw new IllegalStateException("teardown failed");
		}), "failed executor teardown keeps its terminal result pending");
		assertTrue(retainedCleanup.hasPending(), "failed executor teardown retains the cleanup handle");
		retainedCleanup.retain("later-result");
		assertEquals("terminal-result", retainedCleanup.complete(() -> { }),
				"executor finish publishes only the first retained result after cleanup succeeds");
		assertFalse(retainedCleanup.hasPending(), "successful retry releases the cleanup handle");

		ResourceLeaseManager leases = new ResourceLeaseManager();
		AgentId owner = AgentId.random();
		assertTrue(leases.acquire("container:minecraft:overworld:42", owner, 1_000L, 5_000L),
				"transaction lease is acquired");
		assertTrue(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_001L),
				"active transaction owns its lease");
		leases.releaseAll(owner);
		assertFalse(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_002L),
				"cancellation releases every transaction lease");

		for (String reason : new String[] {"ACTION_CANCELLED", "ACTION_TIMED_OUT", "THREAT_DETECTED"}) {
			ServerTransactionAdapter.TerminalGate interrupted = new ServerTransactionAdapter.TerminalGate();
			AtomicInteger interruptedCleanup = new AtomicInteger();
			interrupted.finish(ServerTransactionAdapter.TickResult.failed(reason, reason));
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			assertEquals(1, interruptedCleanup.get(), reason + " cleanup runs once");
		}
		assertEquals(
				java.util.List.of("transfer_container"),
				ServerObservationCollector.transactionCapabilities("minecraft:chest"),
				"chest observation exposes only the supported transfer adapter"
		);
		assertEquals(
				java.util.List.of("furnace_transaction"),
				ServerObservationCollector.transactionCapabilities("minecraft:blast_furnace"),
				"furnace variants expose only the supported furnace adapter"
		);
		assertEquals(
				java.util.List.of(),
				ServerObservationCollector.transactionCapabilities("modded:machine"),
				"modded menu capabilities fail closed"
		);
		AgentId progressAgent = AgentId.random();
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L
		);
		assertEquals("program-7-1", provenance.programId(), "provenance retains program identity");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "\u00a0", 4L
		), "provenance rejects non-breaking blank source steps");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", -1L
		), "provenance rejects negative event sequences");
		for (ActionType type : ActionType.values()) {
			boolean expectedPrimitive = switch (type) {
				case MOVE_TO, NAVIGATE_TO, LOOK_AT, ATTACK, SELECT_ITEM, USE_ITEM, BREAK_BLOCK, PLACE_BLOCK,
						CHAT, WAIT, SET_DOOR, DROP_ITEM, TRANSFER_CONTAINER, CRAFT_INVENTORY, CRAFT_TABLE,
						FURNACE_TRANSACTION, EQUIP_ITEM, SELECT_TOOL, BLOCK_WITH_SHIELD, USE_RANGED,
						INTERACT_BLOCK, INTERACT_ENTITY, DISMOUNT, START_FALL_FLYING -> true;
				case MENU_TRANSFER, MENU_BUTTON, ANVIL_RENAME -> true;
				case RESPAWN -> true;
				default -> false;
			};
			assertEquals(expectedPrimitive, ServerActionExecutor.isArenaScriptPrimitive(type),
					"server primitive parity for " + type.wireName());
		}
		assertThrows(AgentDomainException.class, () -> ServerActionExecutor.requireArenaScriptPrimitive(ActionType.FIGHT_TARGET),
				"program primitive entry point rejects high-level controller actions");
		ServerActionProgress progress = new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 0.5D, 250L, 1_750_000_000_250L
		);
		assertEquals(progressAgent, progress.agentId(), "progress retains agent identity");
		assertEquals(0.5D, progress.progress(), "progress retains bounded fraction");
		ServerActionRequest cancellationTarget = new ServerActionRequest(
				progressAgent, 7L, "action-7", ActionType.WAIT, new JsonObject(), provenance);
		assertThrows(NullPointerException.class, () -> new ServerActionRequest(
				progressAgent, 7L, "action-7", ActionType.WAIT, new JsonObject(), null
		), "requests reject absent provenance");
		assertTrue(ServerActionExecutor.matchesCancellation(cancellationTarget, 7L, "action-7"),
				"cancellation matches the exact revision and action identity");
		assertFalse(ServerActionExecutor.matchesCancellation(cancellationTarget, 8L, "action-7"),
				"cancellation rejects a newer goal revision");
		assertFalse(ServerActionExecutor.matchesCancellation(cancellationTarget, 7L, "action-8"),
				"cancellation rejects a different action identity");
		assertTrue(ServerActionExecutor.isCurrentCoordinatorGeneration(4L, 4L),
				"respawn completion remains valid only for its coordinator generation");
		assertFalse(ServerActionExecutor.isCurrentCoordinatorGeneration(4L, 5L),
				"respawn completion from a disconnected coordinator generation is ignored");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 1.1D, 250L, 1_750_000_000_250L
		), "progress rejects fractions above one");
		AtomicInteger progressAttempts = new AtomicInteger();
		ServerActionExecutor.publishProgressBestEffort(item -> {
			progressAttempts.incrementAndGet();
			throw new IllegalStateException("bridge backpressure");
		}, progress);
		assertEquals(1, progressAttempts.get(), "progress telemetry failure is isolated from the server tick");
		assertEquals(ServerController.TickResult.failed(
				"CONTROLLER_NO_RESULT", "Navigation controller returned no result", 0.4D),
				ServerActionExecutor.requireControllerResult(null, 0.4D, "Navigation"),
				"missing controller results become bounded failures instead of null dereferences");
		assertEquals(
				new Vec3(10.5D, 65.999D, -3.5D),
				ServerActionExecutor.placementLookTarget(new BlockPos(10, 65, -4), Direction.UP),
				"placement aims at the requested support face instead of its center"
		);
		assertEquals(
				new Vec3(10.999D, 65.5D, -3.5D),
				ServerActionExecutor.placementLookTarget(new BlockPos(10, 65, -4), Direction.EAST),
				"horizontal placement aims at the requested support face"
		);
		assertTrue(ServerActionExecutor.isValidPlacementHit(
				new BlockPos(10, 65, -4), new Vec3(10.999D, 65.5D, -3.5D)
		), "placement accepts a hit location on the support block face");
		assertFalse(ServerActionExecutor.isValidPlacementHit(
				new BlockPos(10, 65, -4), new Vec3(12.0D, 65.5D, -3.5D)
		), "placement rejects a forged hit location outside the support block");
		assertThrows(
				AgentDomainException.class,
				() -> ServerActionExecutor.requirePlacementProtection(
						ServerProtectionPolicy.DENY_ALL, null, null, BlockPos.ZERO
				),
				"placement honors the configured protection policy"
		);
		assertEquals("28c7b487-49f8-4eb1-bf03-848553cce39a",
				EntityTargetSelector.normalize("uuid:28c7b487-49f8-4eb1-bf03-848553cce39a"),
				"observation-style UUID selectors are accepted");
		assertEquals("SolEmer", EntityTargetSelector.normalize("name=SolEmer"),
				"observation-style name selectors are accepted");
		assertEquals("Lucas", EntityTargetSelector.normalize("player:Lucas"),
				"explicit player selectors retain their existing behavior");
		assertEquals("nearest_hostile", EntityTargetSelector.normalize("nearest_hostile"),
				"symbolic proximity selectors remain unchanged");
		assertEquals("TARGET_OCCUPIED",
				ServerActionExecutor.failureReason(new dev.agaminggod.arenaagents.agent.AgentDomainException(
						"TARGET_OCCUPIED", "changed world")),
				"runtime revalidation preserves a precise recoverable domain reason");
		assertEquals("ACTION_EXCEPTION", ServerActionExecutor.failureReason(new IllegalStateException("broken")),
				"unexpected runtime exceptions remain isolated");
		assertEquals(0, ServerActionExecutor.roundRobinStart(0L, 16),
				"round-robin starts with the first active agent");
		assertEquals(1, ServerActionExecutor.roundRobinStart(1L, 16),
				"round-robin advances one active agent per tick");
		assertEquals(0, ServerActionExecutor.roundRobinStart(16L, 16),
				"round-robin wraps after all sixteen active agents");
		boolean[] admitted = new boolean[16];
		for (long cursor = 0L; cursor < admitted.length; cursor++) {
			int start = ServerActionExecutor.roundRobinStart(cursor, admitted.length);
			assertFalse(admitted[start], "round-robin does not admit an agent twice before the full turn");
			admitted[start] = true;
		}
		return 44;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
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
