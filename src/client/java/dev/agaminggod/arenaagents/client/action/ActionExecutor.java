package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public final class ActionExecutor {
	public static final long PROGRESS_INTERVAL_MS = 250L;
	public static final int MAX_TRACKED_COMMAND_IDS = 4_096;

	private static final String ACCEPTED_MESSAGE = "Action accepted";
	private static final String BUSY_REASON = "EXECUTOR_BUSY";
	private static final String BUSY_MESSAGE = "Another action is already running";
	private static final String TIMEOUT_REASON = "ACTION_TIMEOUT";
	private static final String TIMEOUT_MESSAGE = "Action exceeded its execution timeout";
	private static final String EXECUTION_FAILED_REASON = "ACTION_EXECUTION_FAILED";
	private static final String INVALID_ACTION_REASON = "INVALID_RUNNING_ACTION";
	private static final String INVALID_ACTION_MESSAGE = "Action factory returned an invalid running action";
	private static final String RESOURCE_RELEASE_FAILED_REASON = "RESOURCE_RELEASE_FAILED";
	private static final String DEFAULT_CANCEL_REASON = "ACTION_CANCELLED";
	private static final String DEFAULT_CANCEL_MESSAGE = "Action was cancelled";

	private final ActionContext context;
	private final Function<ActionCommand, RunningAction> actionCreator;
	private final EventSink eventSink;
	private final CommandIdHistory knownCommandIds = new CommandIdHistory(MAX_TRACKED_COMMAND_IDS);

	private ActionCommand activeCommand;
	private RunningAction activeAction;
	private ActionState state;
	private ActionResult lastResult;
	private long startedAtMonotonicMs;
	private long lastProgressAtMonotonicMs;
	private long activeTimeoutMs;
	private RuntimeException lastEventFailure;

	public ActionExecutor(
			ActionContext context,
			Function<ActionCommand, RunningAction> actionCreator,
			EventSink eventSink
	) {
		this.context = Objects.requireNonNull(context, "context must not be null");
		this.actionCreator = Objects.requireNonNull(actionCreator, "actionCreator must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
	}

	public Acceptance accept(ActionCommand command) {
		requireClientThread();
		Objects.requireNonNull(command, "command must not be null");
		if (activeCommand != null && activeCommand.commandId().equals(command.commandId())) {
			return Acceptance.DUPLICATE;
		}
		String activeCommandId = activeCommand == null ? null : activeCommand.commandId();
		if (!knownCommandIds.remember(command.commandId(), activeCommandId)) {
			return Acceptance.DUPLICATE;
		}
		if (activeCommand != null) {
			emitRejected(command, BUSY_REASON, BUSY_MESSAGE);
			return Acceptance.BUSY;
		}

		SafetyState safetyState = context.safetyState();
		if (safetyState != SafetyState.READY) {
			emitUnsafeRejection(command, safetyState);
			return Acceptance.REJECTED;
		}

		RunningAction created;
		long resolvedTimeoutMs;
		long resolvedStartTimeMs;
		try {
			created = actionCreator.apply(command);
			if (created == null) {
				emitRejectedAfterCleanup(command, INVALID_ACTION_REASON, INVALID_ACTION_MESSAGE);
				return Acceptance.REJECTED;
			}
			resolvedTimeoutMs = created.timeoutMs();
			if (resolvedTimeoutMs <= 0L) {
				emitRejectedAfterCleanup(command, INVALID_ACTION_REASON, INVALID_ACTION_MESSAGE);
				return Acceptance.REJECTED;
			}
			resolvedStartTimeMs = context.monotonicTimeMs();
		} catch (ActionCreationException exception) {
			emitRejectedAfterCleanup(command, exception.reasonCode(), safeExceptionMessage(exception));
			return Acceptance.REJECTED;
		} catch (RuntimeException exception) {
			emitRejectedAfterCleanup(command, EXECUTION_FAILED_REASON, safeExceptionMessage(exception));
			return Acceptance.REJECTED;
		}

		activeCommand = command;
		activeAction = created;
		state = ActionState.RUNNING;
		startedAtMonotonicMs = resolvedStartTimeMs;
		lastProgressAtMonotonicMs = startedAtMonotonicMs;
		activeTimeoutMs = resolvedTimeoutMs;
		emitProgress(0L, ACCEPTED_MESSAGE);
		return Acceptance.ACCEPTED;
	}

	public void tick() {
		requireClientThread();
		if (activeCommand == null) {
			return;
		}

		SafetyState safetyState = context.safetyState();
		if (safetyState != SafetyState.READY) {
			finish(ActionState.FAILED, safetyState.reasonCode(), safetyState.message());
			return;
		}

		long now = context.monotonicTimeMs();
		long elapsedMs = nonNegativeElapsed(startedAtMonotonicMs, now);
		if (elapsedMs >= activeTimeoutMs) {
			finish(ActionState.TIMED_OUT, TIMEOUT_REASON, TIMEOUT_MESSAGE);
			return;
		}

		ActionUpdate update;
		try {
			update = activeAction.tick(context, elapsedMs);
		} catch (RuntimeException exception) {
			finish(ActionState.FAILED, EXECUTION_FAILED_REASON, safeExceptionMessage(exception));
			return;
		}
		if (update == null) {
			finish(ActionState.FAILED, INVALID_ACTION_REASON, INVALID_ACTION_MESSAGE);
			return;
		}
		if (update.state().isTerminal()) {
			finish(update.state(), update.reasonCode(), update.message());
			return;
		}
		if (now - lastProgressAtMonotonicMs >= PROGRESS_INTERVAL_MS) {
			lastProgressAtMonotonicMs = now;
			emitProgress(elapsedMs, update.message());
		}
	}

	public Cancellation cancel(String reason) {
		requireClientThread();
		if (activeCommand == null) {
			return Cancellation.NO_ACTIVE_ACTION;
		}
		try {
			activeAction.cancel(context);
		} catch (RuntimeException exception) {
			finish(ActionState.FAILED, EXECUTION_FAILED_REASON, safeExceptionMessage(exception));
			return Cancellation.CANCELLED;
		}
		String reasonCode = normalizedReasonCode(reason);
		String message = reason == null || reason.isBlank() ? DEFAULT_CANCEL_MESSAGE : reason;
		finish(ActionState.CANCELLED, reasonCode, boundedMessage(message));
		return Cancellation.CANCELLED;
	}

	public Cancellation cancel(String commandId, String reason) {
		requireClientThread();
		if (commandId == null || commandId.isBlank()) {
			throw new IllegalArgumentException("commandId must not be blank");
		}
		if (activeCommand == null) {
			return knownCommandIds.contains(commandId)
					? Cancellation.ALREADY_TERMINAL
					: Cancellation.NO_ACTIVE_ACTION;
		}
		if (!activeCommand.commandId().equals(commandId)) {
			return Cancellation.COMMAND_MISMATCH;
		}
		return cancel(reason);
	}

	/** Releases client controls and forgets all status owned by a closed bridge session. */
	public void reset() {
		requireClientThread();
		activeCommand = null;
		activeAction = null;
		state = null;
		lastResult = null;
		startedAtMonotonicMs = 0L;
		lastProgressAtMonotonicMs = 0L;
		activeTimeoutMs = 0L;
		lastEventFailure = null;
		knownCommandIds.clear();
		context.releaseAll();
	}

	public ActionState state() {
		return state;
	}

	public ActionResult lastResult() {
		return lastResult;
	}

	public RuntimeException lastEventFailure() {
		return lastEventFailure;
	}

	public Observation.ActionStatus currentStatus() {
		if (activeCommand == null) {
			return Observation.ActionStatus.none();
		}
		return new Observation.ActionStatus(
				true,
				activeCommand.commandId(),
				activeCommand.type().wireName(),
				ActionState.RUNNING.name()
		);
	}

	public String activeCommandId() {
		return activeCommand == null ? null : activeCommand.commandId();
	}

	private void emitRejected(ActionCommand command, String reasonCode, String message) {
		ActionResult result = new ActionResult(
				command.commandId(),
				ActionState.FAILED,
				reasonCode,
				boundedMessage(message),
				context.epochTimeMs()
		);
		lastResult = result;
		if (activeCommand == null) {
			state = ActionState.FAILED;
		}
		emitResult(result);
	}

	private void emitUnsafeRejection(ActionCommand command, SafetyState safetyState) {
		emitRejectedAfterCleanup(command, safetyState.reasonCode(), safetyState.message());
	}

	private void emitRejectedAfterCleanup(ActionCommand command, String reasonCode, String message) {
		String completedReason = reasonCode;
		String completedMessage = message;
		try {
			context.releaseAll();
		} catch (RuntimeException exception) {
			completedReason = RESOURCE_RELEASE_FAILED_REASON;
			completedMessage = safeExceptionMessage(exception);
		}
		emitRejected(command, completedReason, completedMessage);
	}

	private void finish(ActionState terminalState, String reasonCode, String message) {
		ActionCommand completedCommand = activeCommand;
		activeCommand = null;
		activeAction = null;
		activeTimeoutMs = 0L;
		knownCommandIds.touch(completedCommand.commandId());
		ActionState completedState = terminalState;
		String completedReason = reasonCode;
		String completedMessage = message;
		try {
			context.releaseAll();
		} catch (RuntimeException exception) {
			completedState = ActionState.FAILED;
			completedReason = RESOURCE_RELEASE_FAILED_REASON;
			completedMessage = safeExceptionMessage(exception);
		}
		state = completedState;
		ActionResult result = new ActionResult(
				completedCommand.commandId(),
				completedState,
				completedReason,
				boundedMessage(completedMessage),
				context.epochTimeMs()
		);
		lastResult = result;
		emitResult(result);
	}

	private void emitProgress(long elapsedMs, String message) {
		ActionProgress progress = new ActionProgress(
				activeCommand.commandId(),
				activeCommand.type(),
				ActionState.RUNNING,
				elapsedMs,
				boundedMessage(message),
				context.epochTimeMs()
		);
		try {
			eventSink.onProgress(progress);
		} catch (RuntimeException exception) {
			lastEventFailure = exception;
		}
	}

	private void emitResult(ActionResult result) {
		try {
			eventSink.onResult(result);
		} catch (RuntimeException exception) {
			lastEventFailure = exception;
		}
	}

	private void requireClientThread() {
		if (!context.isClientThread()) {
			throw new IllegalStateException("ActionExecutor must run on the Minecraft client thread");
		}
	}

	private static String normalizedReasonCode(String reason) {
		if (reason == null || reason.isBlank()) {
			return DEFAULT_CANCEL_REASON;
		}
		String normalized = reason.strip()
				.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9]+", "_")
				.replaceAll("^_+|_+$", "");
		if (normalized.isBlank()) {
			return DEFAULT_CANCEL_REASON;
		}
		return normalized.length() <= ProtocolConstants.MAX_REASON_CODE_LENGTH
				? normalized
				: normalized.substring(0, ProtocolConstants.MAX_REASON_CODE_LENGTH);
	}

	private static String safeExceptionMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return boundedMessage(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
	}

	private static String boundedMessage(String message) {
		String nonNull = message == null ? "" : message;
		return nonNull.length() <= ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH
				? nonNull
				: nonNull.substring(0, ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH);
	}

	private static long nonNegativeElapsed(long startedAtMs, long nowMs) {
		if (nowMs <= startedAtMs) {
			return 0L;
		}
		try {
			return Math.subtractExact(nowMs, startedAtMs);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	public enum Acceptance {
		ACCEPTED,
		DUPLICATE,
		BUSY,
		REJECTED
	}

	public enum Cancellation {
		CANCELLED,
		ALREADY_TERMINAL,
		COMMAND_MISMATCH,
		NO_ACTIVE_ACTION
	}

	public interface EventSink {
		void onProgress(ActionProgress progress);

		void onResult(ActionResult result);
	}
}
