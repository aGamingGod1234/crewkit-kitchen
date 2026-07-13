package dev.agaminggod.arenaagents.client.action;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.ArrayList;
import java.util.List;

public final class ActionExecutorVerification {
	private static final long ISSUED_AT_EPOCH_MS = 1_750_000_000_000L;
	private static final long COMPLETED_AT_EPOCH_MS = 1_750_000_001_000L;
	private static final long ACTION_TIMEOUT_MS = 100L;

	private ActionExecutorVerification() {
	}

	public static int verifyLifecycle() {
		int assertions = 0;
		assertions += verifyCancelProducesExactlyOneTerminalResult();
		assertions += verifyBusyAndDuplicateCommandsAreExplicit();
		assertions += verifyTimeoutProducesExactlyOneTerminalResult();
		assertions += verifyProgressUsesBoundedIntervals();
		assertions += verifyClientThreadIsRequired();
		assertions += verifyReleaseFailureStillTerminatesExactlyOnce();
		assertions += verifyTargetedCancellationIsExplicit();
		assertions += verifyEventFailuresAreContained();
		assertions += verifyCommandHistoryIsBoundedAndKeepsTouchedIds();
		return assertions;
	}

	public static int verifyPrimitives() {
		int assertions = 0;
		assertions += verifyWaitUsesElapsedMonotonicTime();
		assertions += verifyLookAtUsesBoundedGradualRotation();
		assertions += verifyChatSendsOnce();
		assertions += verifySelectItemIsDeterministicAndExplicit();
		assertions += verifyUseItemCompletesAndCancelsSafely();
		assertions += verifyFactoryRejectsDeferredActions();
		assertions += verifyUnsafeStateReleasesEveryResource();
		assertions += verifyUnsafeAcceptanceReleasesAndFailsSafely();
		assertions += verifyClientRuntimeOwnsCallbacksAndObservationState();
		return assertions;
	}

	private static int verifyCancelProducesExactlyOneTerminalResult() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);
		ActionCommand command = waitCommand("cancelled-command");

		assertEquals(ActionExecutor.Acceptance.ACCEPTED, executor.accept(command), "accepted command outcome");
		assertEquals(ActionState.RUNNING, executor.state(), "accepted command state");
		executor.cancel("goal_replaced");
		assertEquals(ActionState.CANCELLED, executor.state(), "cancelled command state");
		assertEquals(1, sink.results.size(), "cancel emits one terminal result");
		assertEquals("GOAL_REPLACED", sink.results.getFirst().reasonCode(), "cancel reason code");
		assertEquals(1, context.releaseCount, "cancel releases resources once");

		executor.cancel("goal_replaced");
		executor.tick();
		assertEquals(1, sink.results.size(), "repeated cancel and tick stay terminal-idempotent");
		assertEquals(1, context.releaseCount, "repeated cancel does not release resources twice");
		return 9;
	}

	private static int verifyBusyAndDuplicateCommandsAreExplicit() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);
		ActionCommand first = waitCommand("first-command");
		ActionCommand second = waitCommand("second-command");

		assertEquals(ActionExecutor.Acceptance.ACCEPTED, executor.accept(first), "first command accepted");
		assertEquals(ActionExecutor.Acceptance.DUPLICATE, executor.accept(first), "active duplicate rejected explicitly");
		assertEquals(ActionExecutor.Acceptance.BUSY, executor.accept(second), "second command rejected while busy");
		assertEquals(ActionState.RUNNING, executor.state(), "busy rejection preserves active state");
		assertEquals(1, sink.results.size(), "busy command receives one terminal result");
		assertEquals("EXECUTOR_BUSY", sink.results.getFirst().reasonCode(), "busy reason code");

		executor.cancel("test_complete");
		assertEquals(ActionExecutor.Acceptance.DUPLICATE, executor.accept(first), "terminal duplicate remains rejected");
		assertEquals(2, sink.results.size(), "terminal duplicate does not emit another result");
		return 8;
	}

	private static int verifyTimeoutProducesExactlyOneTerminalResult() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);

		executor.accept(waitCommand("timed-out-command"));
		context.monotonicTimeMs = ACTION_TIMEOUT_MS;
		executor.tick();
		assertEquals(ActionState.TIMED_OUT, executor.state(), "timeout state");
		assertEquals(1, sink.results.size(), "timeout emits one terminal result");
		assertEquals("ACTION_TIMEOUT", sink.results.getFirst().reasonCode(), "timeout reason code");
		assertEquals(COMPLETED_AT_EPOCH_MS, sink.results.getFirst().completedAtEpochMs(), "timeout completion timestamp");
		assertEquals(1, context.releaseCount, "timeout releases resources");

		executor.tick();
		executor.cancel("late_cancel");
		assertEquals(1, sink.results.size(), "post-timeout transitions remain idempotent");
		return 6;
	}

	private static int verifyProgressUsesBoundedIntervals() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = new ActionExecutor(
				context,
				command -> new NeverEndingAction(1_000L),
				sink
		);
		executor.accept(waitCommand("progress-command"));
		assertEquals(1, sink.progress.size(), "accept emits initial progress");

		context.monotonicTimeMs = ActionExecutor.PROGRESS_INTERVAL_MS - 1L;
		executor.tick();
		assertEquals(1, sink.progress.size(), "progress is suppressed before interval");
		context.monotonicTimeMs = ActionExecutor.PROGRESS_INTERVAL_MS;
		executor.tick();
		assertEquals(2, sink.progress.size(), "progress emits at named interval");
		executor.tick();
		assertEquals(2, sink.progress.size(), "progress does not duplicate within interval");
		return 4;
	}

	private static int verifyClientThreadIsRequired() {
		FakeActionContext context = new FakeActionContext();
		context.clientThread = false;
		ActionExecutor executor = executor(context, new RecordingSink());
		expectThrows(
				IllegalStateException.class,
				() -> executor.accept(waitCommand("wrong-thread")),
				"accept rejects non-client thread"
		);
		return 1;
	}

	private static int verifyReleaseFailureStillTerminatesExactlyOnce() {
		FakeActionContext context = new FakeActionContext();
		context.throwOnRelease = true;
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);
		executor.accept(waitCommand("release-failure"));

		executor.cancel("goal_replaced");
		assertEquals(ActionState.FAILED, executor.state(), "release failure changes terminal state to failed");
		assertEquals(1, sink.results.size(), "release failure still emits one terminal result");
		assertEquals("RESOURCE_RELEASE_FAILED", sink.results.getFirst().reasonCode(), "release failure reason code");
		executor.cancel("goal_replaced");
		assertEquals(1, sink.results.size(), "release failure remains terminal-idempotent");
		return 4;
	}

	private static int verifyTargetedCancellationIsExplicit() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);
		executor.accept(waitCommand("targeted-cancel"));

		assertEquals(
				ActionExecutor.Cancellation.COMMAND_MISMATCH,
				executor.cancel("different-command", "coordinator_cancelled"),
				"mismatched cancel is explicit"
		);
		assertEquals(ActionState.RUNNING, executor.state(), "mismatched cancel preserves active action");
		assertEquals(
				ActionExecutor.Cancellation.CANCELLED,
				executor.cancel("targeted-cancel", "coordinator_cancelled"),
				"matching cancel terminates action"
		);
		assertEquals(
				ActionExecutor.Cancellation.ALREADY_TERMINAL,
				executor.cancel("targeted-cancel", "coordinator_cancelled"),
				"terminal cancel is idempotent"
		);
		assertEquals(
				ActionExecutor.Cancellation.NO_ACTIVE_ACTION,
				executor.cancel("unknown-command", "coordinator_cancelled"),
				"unknown cancel without active action is explicit"
		);
		assertEquals(1, sink.results.size(), "targeted cancel emits exactly one result");
		return 6;
	}

	private static int verifyEventFailuresAreContained() {
		FakeActionContext context = new FakeActionContext();
		ThrowingSink sink = new ThrowingSink();
		ActionExecutor executor = executor(context, sink);

		assertDoesNotThrow(
				() -> executor.accept(waitCommand("event-failure")),
				"progress publication failure is contained"
		);
		assertEquals(ActionState.RUNNING, executor.state(), "progress publication failure preserves action");
		assertEquals("progress failed", executor.lastEventFailure().getMessage(), "progress failure is observable");

		context.monotonicTimeMs = ACTION_TIMEOUT_MS;
		assertDoesNotThrow(executor::tick, "result publication failure is contained");
		assertEquals(ActionState.TIMED_OUT, executor.state(), "result publication failure preserves terminal state");
		assertEquals("result failed", executor.lastEventFailure().getMessage(), "result failure is observable");
		return 6;
	}

	private static int verifyCommandHistoryIsBoundedAndKeepsTouchedIds() {
		CommandIdHistory history = new CommandIdHistory(3);
		assertEquals(true, history.remember("command-a"), "history remembers first command");
		assertEquals(true, history.remember("command-b"), "history remembers second command");
		assertEquals(true, history.remember("command-c"), "history remembers third command");
		history.touch("command-a");
		assertEquals(true, history.remember("command-d"), "history accepts command after capacity");
		assertEquals(3, history.size(), "history remains bounded");
		assertEquals(true, history.contains("command-a"), "touched command remains protected from immediate eviction");
		assertEquals(false, history.contains("command-b"), "oldest untouched command is evicted");
		assertEquals(false, history.remember("command-d"), "recent duplicate is rejected");
		return 8;
	}

	private static int verifyWaitUsesElapsedMonotonicTime() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = new ActionExecutor(context, new ActionFactory()::create, sink);
		executor.accept(waitCommand("wait-command"));

		context.monotonicTimeMs = ACTION_TIMEOUT_MS - 1L;
		executor.tick();
		assertEquals(ActionState.RUNNING, executor.state(), "wait remains running before duration");
		context.monotonicTimeMs = ACTION_TIMEOUT_MS;
		executor.tick();
		assertEquals(ActionState.SUCCEEDED, executor.state(), "wait succeeds at duration");
		assertEquals("WAIT_COMPLETE", sink.results.getFirst().reasonCode(), "wait completion reason");
		assertEquals(1, context.releaseCount, "wait completion releases resources");
		return 4;
	}

	private static int verifyLookAtUsesBoundedGradualRotation() {
		FakeActionContext context = new FakeActionContext();
		context.lookResults.addLast(new ActionContext.LookResult(false, 30.0F, 10.0F));
		context.lookResults.addLast(new ActionContext.LookResult(true, 1.0F, 1.0F));
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = new ActionExecutor(context, new ActionFactory()::create, sink);
		executor.accept(lookAtCommand("look-command", 10.0D, 65.0D, -4.0D));

		context.monotonicTimeMs = 1L;
		executor.tick();
		assertEquals(ActionState.RUNNING, executor.state(), "look remains running outside tolerance");
		context.monotonicTimeMs = 2L;
		executor.tick();
		assertEquals(ActionState.SUCCEEDED, executor.state(), "look succeeds inside tolerance");
		assertEquals(2, context.lookCalls, "look rotates incrementally across ticks");
		assertEquals(LookAtAction.MAX_YAW_DEGREES_PER_TICK, context.lastMaxYawDelta, "look yaw step bound");
		assertEquals(LookAtAction.MAX_PITCH_DEGREES_PER_TICK, context.lastMaxPitchDelta, "look pitch step bound");
		assertEquals(LookAtAction.ANGLE_TOLERANCE_DEGREES, context.lastTolerance, "look completion tolerance");
		assertEquals(10.0D, context.lastLookX, "look target x");
		assertEquals(65.0D, context.lastLookY, "look target y");
		assertEquals(-4.0D, context.lastLookZ, "look target z");
		return 9;
	}

	private static int verifyChatSendsOnce() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = new ActionExecutor(context, new ActionFactory()::create, sink);
		executor.accept(chatCommand("chat-command", "Ready."));
		executor.tick();
		executor.tick();

		assertEquals(ActionState.SUCCEEDED, executor.state(), "chat succeeds");
		assertEquals(1, context.chatCalls, "chat sends once");
		assertEquals("Ready.", context.lastChatMessage, "chat sends validated text");
		return 3;
	}

	private static int verifySelectItemIsDeterministicAndExplicit() {
		FakeActionContext successContext = new FakeActionContext();
		successContext.selectResult = ActionContext.OperationResult.succeeded("ITEM_SELECTED", "Selected slot 2");
		RecordingSink successSink = new RecordingSink();
		ActionExecutor successExecutor = new ActionExecutor(
				successContext,
				new ActionFactory()::create,
				successSink
		);
		successExecutor.accept(selectItemCommand("select-success", "minecraft:stone"));
		successExecutor.tick();
		assertEquals(ActionState.SUCCEEDED, successExecutor.state(), "select item succeeds for hotbar match");
		assertEquals(1, successContext.selectCalls, "select item checks hotbar once");
		assertEquals("minecraft:stone", successContext.lastSelectedItemId, "select item uses validated identifier");

		FakeActionContext failureContext = new FakeActionContext();
		failureContext.selectResult = ActionContext.OperationResult.failed(
				"ITEM_NOT_IN_HOTBAR",
				"Item is not present in the hotbar"
		);
		RecordingSink failureSink = new RecordingSink();
		ActionExecutor failureExecutor = new ActionExecutor(
				failureContext,
				new ActionFactory()::create,
				failureSink
		);
		failureExecutor.accept(selectItemCommand("select-failure", "minecraft:diamond_sword"));
		failureExecutor.tick();
		assertEquals(ActionState.FAILED, failureExecutor.state(), "select item fails for missing hotbar item");
		assertEquals("ITEM_NOT_IN_HOTBAR", failureSink.results.getFirst().reasonCode(), "select failure reason");
		return 5;
	}

	private static int verifyUseItemCompletesAndCancelsSafely() {
		FakeActionContext completeContext = new FakeActionContext();
		RecordingSink completeSink = new RecordingSink();
		ActionExecutor completeExecutor = new ActionExecutor(
				completeContext,
				new ActionFactory()::create,
				completeSink
		);
		completeExecutor.accept(useItemCommand("use-complete", ACTION_TIMEOUT_MS));
		completeExecutor.tick();
		completeContext.monotonicTimeMs = ACTION_TIMEOUT_MS - 1L;
		completeExecutor.tick();
		assertEquals(ActionState.RUNNING, completeExecutor.state(), "use item remains active for requested duration");
		completeContext.monotonicTimeMs = ACTION_TIMEOUT_MS;
		completeExecutor.tick();
		assertEquals(ActionState.SUCCEEDED, completeExecutor.state(), "use item succeeds at requested duration");
		assertEquals(1, completeContext.startUseCalls, "use item starts once");
		assertEquals(ActionContext.Hand.MAIN_HAND, completeContext.lastUseHand, "use item uses validated main hand");
		assertEquals(1, completeContext.stopUseCalls, "use item stops on completion");

		FakeActionContext cancelContext = new FakeActionContext();
		RecordingSink cancelSink = new RecordingSink();
		ActionExecutor cancelExecutor = new ActionExecutor(cancelContext, new ActionFactory()::create, cancelSink);
		cancelExecutor.accept(useItemCommand("use-cancel", ACTION_TIMEOUT_MS));
		cancelExecutor.tick();
		cancelExecutor.cancel("goal_replaced");
		cancelExecutor.cancel("goal_replaced");
		assertEquals(ActionState.CANCELLED, cancelExecutor.state(), "use item cancellation state");
		assertEquals(1, cancelContext.stopUseCalls, "use item cancel stops explicit use once");
		assertEquals(1, cancelContext.releaseCount, "use item cancel releases all resources once");
		assertEquals(1, cancelSink.results.size(), "use item cancel emits one result");
		return 9;
	}

	private static int verifyFactoryRejectsDeferredActions() {
		ActionFactory factory = new ActionFactory();
		assertEquals(WaitAction.class, factory.create(waitCommand("factory-wait")).getClass(), "factory creates wait");
		assertEquals(LookAtAction.class, factory.create(lookAtCommand("factory-look", 0.0D, 64.0D, 0.0D)).getClass(), "factory creates look");
		assertEquals(ChatAction.class, factory.create(chatCommand("factory-chat", "hello")).getClass(), "factory creates chat");
		assertEquals(SelectItemAction.class, factory.create(selectItemCommand("factory-select", "minecraft:stone")).getClass(), "factory creates select item");
		assertEquals(UseItemAction.class, factory.create(useItemCommand("factory-use", 1L)).getClass(), "factory creates use item");

		for (ActionType type : List.of(
				ActionType.MOVE_TO,
				ActionType.ATTACK,
				ActionType.BREAK_BLOCK,
				ActionType.PLACE_BLOCK,
				ActionType.COMPLETE_GOAL
		)) {
			ActionCommand command = deferredCommand("deferred-" + type.wireName(), type);
			ActionCreationException exception = expectThrows(
					ActionCreationException.class,
					() -> factory.create(command),
					"factory rejects deferred " + type.wireName()
			);
			assertEquals("ACTION_NOT_IMPLEMENTED", exception.reasonCode(), "deferred action reason code");
		}
		return 15;
	}

	private static int verifyUnsafeStateReleasesEveryResource() {
		FakeActionContext context = new FakeActionContext();
		context.syntheticInputsActive = true;
		context.itemUseActive = true;
		context.blockBreakingActive = true;
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = new ActionExecutor(
				context,
				command -> new NeverEndingAction(1_000L),
				sink
		);
		executor.accept(waitCommand("unsafe-command"));
		context.safetyState = SafetyState.PLAYER_DEAD;
		executor.tick();

		assertEquals(ActionState.FAILED, executor.state(), "unsafe state fails active action");
		assertEquals("PLAYER_DEAD", sink.results.getFirst().reasonCode(), "unsafe state result reason");
		assertEquals(false, context.syntheticInputsActive, "unsafe state releases synthetic inputs");
		assertEquals(false, context.itemUseActive, "unsafe state stops item use");
		assertEquals(false, context.blockBreakingActive, "unsafe state aborts block breaking");
		assertEquals(1, context.releaseCount, "unsafe state invokes central release hook once");
		return 6;
	}

	private static int verifyUnsafeAcceptanceReleasesAndFailsSafely() {
		FakeActionContext context = new FakeActionContext();
		context.safetyState = SafetyState.PLAYER_DEAD;
		context.syntheticInputsActive = true;
		context.itemUseActive = true;
		context.blockBreakingActive = true;
		RecordingSink sink = new RecordingSink();
		ActionExecutor executor = executor(context, sink);

		assertEquals(
				ActionExecutor.Acceptance.REJECTED,
				executor.accept(waitCommand("unsafe-accept")),
				"unsafe acceptance is rejected"
		);
		assertEquals(ActionState.FAILED, executor.state(), "unsafe acceptance records failed state");
		assertEquals("PLAYER_DEAD", sink.results.getFirst().reasonCode(), "unsafe acceptance reason");
		assertEquals(false, context.syntheticInputsActive, "unsafe acceptance releases synthetic inputs");
		assertEquals(false, context.itemUseActive, "unsafe acceptance stops item use");
		assertEquals(false, context.blockBreakingActive, "unsafe acceptance aborts block breaking");

		FakeActionContext failingReleaseContext = new FakeActionContext();
		failingReleaseContext.safetyState = SafetyState.DISCONNECTED;
		failingReleaseContext.throwOnRelease = true;
		RecordingSink failingReleaseSink = new RecordingSink();
		ActionExecutor failingReleaseExecutor = executor(failingReleaseContext, failingReleaseSink);
		assertDoesNotThrow(
				() -> failingReleaseExecutor.accept(waitCommand("unsafe-release-failure")),
				"unsafe acceptance contains cleanup failure"
		);
		assertEquals(
				"RESOURCE_RELEASE_FAILED",
				failingReleaseSink.results.getFirst().reasonCode(),
				"unsafe acceptance cleanup failure reason"
		);
		return 8;
	}

	private static int verifyClientRuntimeOwnsCallbacksAndObservationState() {
		FakeActionContext context = new FakeActionContext();
		RecordingSink sink = new RecordingSink();
		ClientActionRuntime runtime = new ClientActionRuntime(context, sink);
		runtime.onActionCommand(waitCommand("runtime-wait"));
		assertEquals(true, runtime.currentStatus().present(), "client runtime exposes current action");

		context.monotonicTimeMs = ACTION_TIMEOUT_MS;
		runtime.tick();
		assertEquals(false, runtime.currentStatus().present(), "client runtime clears completed action");
		assertEquals(ActionState.SUCCEEDED, runtime.lastResult().state(), "client runtime exposes last result");

		runtime.onActionCommand(waitCommand("runtime-stop"));
		runtime.stop("client_stopping");
		assertEquals(ActionState.CANCELLED, runtime.lastResult().state(), "client runtime stop cancels action");
		assertEquals(false, runtime.currentStatus().present(), "client runtime stop clears current action");
		return 5;
	}

	private static ActionExecutor executor(FakeActionContext context, ActionExecutor.EventSink sink) {
		return new ActionExecutor(
				context,
				command -> new NeverEndingAction(),
				sink
		);
	}

	private static ActionCommand waitCommand(String commandId) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", ACTION_TIMEOUT_MS);
		return new ActionCommand(commandId, ActionType.WAIT, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static ActionCommand lookAtCommand(String commandId, double x, double y, double z) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("x", x);
		arguments.addProperty("y", y);
		arguments.addProperty("z", z);
		return new ActionCommand(commandId, ActionType.LOOK_AT, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static ActionCommand chatCommand(String commandId, String message) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("message", message);
		return new ActionCommand(commandId, ActionType.CHAT, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static ActionCommand selectItemCommand(String commandId, String itemId) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("itemId", itemId);
		return new ActionCommand(commandId, ActionType.SELECT_ITEM, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static ActionCommand useItemCommand(String commandId, long durationMs) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", durationMs);
		return new ActionCommand(commandId, ActionType.USE_ITEM, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static ActionCommand deferredCommand(String commandId, ActionType type) {
		JsonObject arguments = new JsonObject();
		switch (type) {
			case MOVE_TO -> {
				arguments.addProperty("x", 0.0D);
				arguments.addProperty("y", 64.0D);
				arguments.addProperty("z", 0.0D);
				arguments.addProperty("tolerance", 0.5D);
				arguments.addProperty("sprint", false);
			}
			case ATTACK -> {
				arguments.addProperty("targetSelector", "nearest_hostile");
				arguments.addProperty("timeoutMs", 1_000L);
			}
			case BREAK_BLOCK -> {
				arguments.addProperty("x", 0);
				arguments.addProperty("y", 64);
				arguments.addProperty("z", 0);
				arguments.addProperty("timeoutMs", 1_000L);
			}
			case PLACE_BLOCK -> {
				arguments.addProperty("x", 0);
				arguments.addProperty("y", 64);
				arguments.addProperty("z", 0);
				arguments.addProperty("face", "up");
				arguments.addProperty("itemId", "minecraft:stone");
			}
			case COMPLETE_GOAL -> arguments.addProperty("summary", "done");
			default -> throw new IllegalArgumentException("type is not deferred");
		}
		return new ActionCommand(commandId, type, arguments, ISSUED_AT_EPOCH_MS);
	}

	private static <T extends Throwable> T expectThrows(
			Class<T> expectedType,
			ThrowingRunnable action,
			String label
	) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (expectedType.isInstance(throwable)) {
				return expectedType.cast(throwable);
			}
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + expectedType.getSimpleName());
	}

	private static void assertDoesNotThrow(ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
	}

	private static final class NeverEndingAction implements RunningAction {
		private final long timeoutMs;

		private NeverEndingAction() {
			this(ACTION_TIMEOUT_MS);
		}

		private NeverEndingAction(long timeoutMs) {
			this.timeoutMs = timeoutMs;
		}

		@Override
		public long timeoutMs() {
			return timeoutMs;
		}

		@Override
		public ActionUpdate tick(ActionContext context, long elapsedMs) {
			return ActionUpdate.running("waiting");
		}
	}

	private static final class RecordingSink implements ActionExecutor.EventSink {
		private final List<ActionProgress> progress = new ArrayList<>();
		private final List<ActionResult> results = new ArrayList<>();

		@Override
		public void onProgress(ActionProgress update) {
			progress.add(update);
		}

		@Override
		public void onResult(ActionResult result) {
			results.add(result);
		}
	}

	private static final class ThrowingSink implements ActionExecutor.EventSink {
		@Override
		public void onProgress(ActionProgress update) {
			throw new IllegalStateException("progress failed");
		}

		@Override
		public void onResult(ActionResult result) {
			throw new IllegalStateException("result failed");
		}
	}

	private static final class FakeActionContext implements ActionContext {
		private long monotonicTimeMs;
		private int releaseCount;
		private boolean clientThread = true;
		private SafetyState safetyState = SafetyState.READY;
		private final java.util.ArrayDeque<ActionContext.LookResult> lookResults = new java.util.ArrayDeque<>();
		private int lookCalls;
		private double lastLookX;
		private double lastLookY;
		private double lastLookZ;
		private float lastMaxYawDelta;
		private float lastMaxPitchDelta;
		private float lastTolerance;
		private ActionContext.OperationResult chatResult = ActionContext.OperationResult.succeeded(
				"CHAT_SENT",
				"Chat sent"
		);
		private int chatCalls;
		private String lastChatMessage;
		private ActionContext.OperationResult selectResult = ActionContext.OperationResult.succeeded(
				"ITEM_SELECTED",
				"Item selected"
		);
		private int selectCalls;
		private String lastSelectedItemId;
		private ActionContext.OperationResult startUseResult = ActionContext.OperationResult.succeeded(
				"ITEM_USE_STARTED",
				"Item use started"
		);
		private int startUseCalls;
		private int stopUseCalls;
		private ActionContext.Hand lastUseHand;
		private boolean syntheticInputsActive;
		private boolean itemUseActive;
		private boolean blockBreakingActive;
		private boolean throwOnRelease;

		@Override
		public boolean isClientThread() {
			return clientThread;
		}

		@Override
		public long monotonicTimeMs() {
			return monotonicTimeMs;
		}

		@Override
		public long epochTimeMs() {
			return COMPLETED_AT_EPOCH_MS;
		}

		@Override
		public SafetyState safetyState() {
			return safetyState;
		}

		@Override
		public ActionContext.LookResult lookAt(
				double x,
				double y,
				double z,
				float maxYawDelta,
				float maxPitchDelta,
				float toleranceDegrees
		) {
			lookCalls++;
			lastLookX = x;
			lastLookY = y;
			lastLookZ = z;
			lastMaxYawDelta = maxYawDelta;
			lastMaxPitchDelta = maxPitchDelta;
			lastTolerance = toleranceDegrees;
			return lookResults.isEmpty()
					? new ActionContext.LookResult(true, 0.0F, 0.0F)
					: lookResults.removeFirst();
		}

		@Override
		public ActionContext.OperationResult sendChat(String message) {
			chatCalls++;
			lastChatMessage = message;
			return chatResult;
		}

		@Override
		public ActionContext.OperationResult selectHotbarItem(String itemId) {
			selectCalls++;
			lastSelectedItemId = itemId;
			return selectResult;
		}

		@Override
		public ActionContext.OperationResult startUsingItem(ActionContext.Hand hand) {
			startUseCalls++;
			lastUseHand = hand;
			itemUseActive = startUseResult.successful();
			return startUseResult;
		}

		@Override
		public void stopUsingItem() {
			stopUseCalls++;
			itemUseActive = false;
		}

		@Override
		public void releaseAll() {
			releaseCount++;
			syntheticInputsActive = false;
			itemUseActive = false;
			blockBreakingActive = false;
			if (throwOnRelease) {
				throw new IllegalStateException("release failed");
			}
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
