package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerController;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.InputStateSink;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public final class ServerActionExecutorVerification {
	private ServerActionExecutorVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("Server action executor verification passed: " + verify() + " assertions");
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
		ActionProvenance watcherProvenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, "trace-watcher-1", "watcher-0"
		);
		assertEquals("watcher-0", watcherProvenance.watcherId(), "watcher provenance retains its authorizing identity");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, null, "watcher-0"
		), "watcher provenance requires a trace identity");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, "trace-watcher-1", "x".repeat(129)
		), "watcher provenance remains bounded");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "\u00a0", 4L
		), "provenance rejects non-breaking blank source steps");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", -1L
		), "provenance rejects negative event sequences");
		for (ActionType type : ActionType.values()) {
			boolean expectedPrimitive = switch (type) {
				case CONTROL, MOVE_TO, NAVIGATE_TO, LOOK_AT, ATTACK, SELECT_ITEM, USE_ITEM, BREAK_BLOCK, PLACE_BLOCK, PICK_UP_ITEM,
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
		JsonObject mainHand = new JsonObject();
		mainHand.addProperty("hand", "main");
		assertEquals(net.minecraft.world.InteractionHand.MAIN_HAND, ServerActionExecutor.hand(mainHand),
				"raw control maps the main-hand wire value");
		JsonObject offHand = new JsonObject();
		offHand.addProperty("hand", "off");
		assertEquals(net.minecraft.world.InteractionHand.OFF_HAND, ServerActionExecutor.hand(offHand),
				"raw control maps the offhand wire value");
		JsonObject invalidHand = new JsonObject();
		invalidHand.addProperty("hand", "left");
		assertThrows(AgentDomainException.class, () -> ServerActionExecutor.hand(invalidHand),
				"raw control rejects an unknown hand value");
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
		verifyDisconnectedRespawnFinishesOnce();
		verifyDisconnectedControlNeutralizesLease();
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
		verifySetupFailureDoesNotClaimPhysicalExecution();
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
		return 58;
	}

	private static void verifyDisconnectedControlNeutralizesLease() {
		AgentId agentId = AgentId.random();
		List<AgentId> cleared = new ArrayList<>();
		InputStateSink sink = new InputStateSink() {
			@Override
			public void apply(AgentId ignored, AgentInputState previous, AgentInputState state) {
			}

			@Override
			public void clear(AgentId clearedAgent, AgentInputState previous) {
				cleared.add(clearedAgent);
			}
		};
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(agentId, InputOwner.DIRECT_CONTROL, 250);
		controller.apply(lease, new AgentInputState(
				1.0F, -1.0F, true, true, true, true, true,
				90.0F, 15.0F, 4, InteractionHand.OFF_HAND
		));
		Object action = new Object();
		Map<AgentId, Object> active = new LinkedHashMap<>();
		active.put(agentId, action);
		AtomicInteger lifecycleFinishes = new AtomicInteger();
		assertTrue(ServerActionExecutor.finishDisconnectedControl(
				active, agentId, action, () -> controller.clear(agentId), lifecycleFinishes::incrementAndGet
		), "coordinator disconnect fences the active control action");
		assertTrue(active.isEmpty(), "disconnected control cannot tick again");
		assertTrue(controller.currentState(agentId).isEmpty(), "disconnect removes the active control lease immediately");
		assertEquals(List.of(agentId), cleared, "disconnect neutralizes the physical input sink once");
		assertThrows(IllegalStateException.class, () -> controller.apply(lease, new AgentInputState(
				1.0F, 0.0F, false, false, true, false, false,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		)), "disconnected control lease cannot renew");
		assertEquals(1, lifecycleFinishes.get(), "disconnect finishes the control lifecycle once");
		assertFalse(ServerActionExecutor.finishDisconnectedControl(
				active, agentId, action, () -> controller.clear(agentId), lifecycleFinishes::incrementAndGet
		), "duplicate disconnect cannot neutralize the same control twice");
	}

	private static void verifyDisconnectedRespawnFinishesOnce() {
		AgentId agentId = AgentId.random();
		Object pending = new Object();
		Map<AgentId, Object> pendingRespawns = new LinkedHashMap<>();
		pendingRespawns.put(agentId, pending);
		AtomicInteger rollbacks = new AtomicInteger();
		AtomicInteger terminalReports = new AtomicInteger();
		assertTrue(finishDisconnectedRespawn(
				pendingRespawns, agentId, pending, rollbacks::incrementAndGet, terminalReports::incrementAndGet),
				"the accepted pending respawn is finished at disconnect");
		assertFalse(finishDisconnectedRespawn(
				pendingRespawns, agentId, pending, rollbacks::incrementAndGet, terminalReports::incrementAndGet),
				"a second disconnect cannot finish the same respawn twice");
		assertEquals(1, rollbacks.get(), "pending respawn rollback runs exactly once");
		assertEquals(1, terminalReports.get(), "pending respawn terminal reporting runs exactly once");
	}

	private static boolean finishDisconnectedRespawn(
			Map<AgentId, ?> pendingRespawns,
			AgentId agentId,
			Object pending,
			Runnable rollback,
			Runnable terminalReport
	) {
		try {
			var method = ServerActionExecutor.class.getDeclaredMethod(
					"finishDisconnectedRespawn", Map.class, AgentId.class, Object.class, Runnable.class, Runnable.class);
			method.setAccessible(true);
			return (boolean) method.invoke(null, pendingRespawns, agentId, pending, rollback, terminalReport);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing pending-respawn disconnect boundary", exception);
		}
	}

	private static void verifySetupFailureDoesNotClaimPhysicalExecution() {
		CodexAgentManager manager = uninitializedManager();
		AgentRecord agent = manager.registry().create(
				"codex", "gpt-5.6-sol", "high", Optional.of("SetupFailureTarget"), AgentGameMode.SURVIVAL, 1_000L
		);
		manager.startSubjective(agent.agentId().toString(), "Set up the executor test");
		manager.registry().setAutomaticProgress(agent.agentId(), false, 1_001L);
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-setup", 1L, "step-setup", 1L, "trace-setup"
		);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 1L);
		ServerActionRequest request = new ServerActionRequest(
				agent.agentId(), agent.goalRevision() + 1L, "action-setup", ActionType.WAIT, arguments, provenance, "trace-setup"
		);
		List<ServerActionResult> results = new ArrayList<>();
		new ServerActionExecutor(manager, results::add).submitProgramPrimitive(request);
		assertEquals(1, results.size(), "missing player setup failure emits one terminal result");
		ServerActionResult result = results.getFirst();
		assertFalse(result.executionStarted(), "setup failure does not claim execution started");
		assertFalse(result.physicalAttempted(), "setup failure does not claim a physical attempt");
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
			throw new AssertionError("could not allocate setup-failure manager", exception);
		}
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
