package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import java.util.Objects;

public final class ClientActionRuntime {
	private static final long UNSCOPED_SESSION = 0L;
	private final ActionContext context;
	private final ActionExecutor executor;
	private final SessionEventSink eventSink;
	private long activeSessionId;
	private long callbackSessionId;
	private long resultSessionId;
	private RuntimeException lastLifecycleFailure;

	public ClientActionRuntime(ActionContext context, ActionExecutor.EventSink eventSink) {
		this(context, new SessionEventSink() {
			@Override public void onProgress(long sessionId, ActionProgress progress) { eventSink.onProgress(progress); }
			@Override public void onResult(long sessionId, ActionResult result) { eventSink.onResult(result); }
		});
	}

	public ClientActionRuntime(ActionContext context, SessionEventSink eventSink) {
		this.context = Objects.requireNonNull(context, "context must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
		this.executor = new ActionExecutor(context, new ActionFactory()::create, new ActionExecutor.EventSink() {
			@Override
			public void onProgress(ActionProgress progress) {
				ClientActionRuntime.this.eventSink.onProgress(eventSessionId(), progress);
			}

			@Override
			public void onResult(ActionResult result) {
				long sessionId = eventSessionId();
				resultSessionId = sessionId;
				ClientActionRuntime.this.eventSink.onResult(sessionId, result);
			}
		});
	}

	public ActionExecutor.Acceptance onActionCommand(ActionCommand command) {
		return onActionCommand(UNSCOPED_SESSION, command);
	}

	public ActionExecutor.Acceptance onActionCommand(long sessionId, ActionCommand command) {
		requireSessionId(sessionId);
		if (activeSessionId != UNSCOPED_SESSION && activeSessionId != sessionId) {
			resetExecutor();
			resultSessionId = UNSCOPED_SESSION;
		}
		activeSessionId = sessionId;
		callbackSessionId = sessionId;
		try {
			return executor.accept(command);
		} finally {
			callbackSessionId = UNSCOPED_SESSION;
		}
	}

	public ActionExecutor.Cancellation onCancelAction(String commandId, String reason) {
		return onCancelAction(UNSCOPED_SESSION, commandId, reason);
	}

	public ActionExecutor.Cancellation onCancelAction(long sessionId, String commandId, String reason) {
		requireSessionId(sessionId);
		if (activeSessionId != sessionId) {
			return ActionExecutor.Cancellation.NO_ACTIVE_ACTION;
		}
		callbackSessionId = sessionId;
		try {
			return executor.cancel(commandId, reason);
		} finally {
			callbackSessionId = UNSCOPED_SESSION;
		}
	}

	public void tick() {
		executor.tick();
	}

	public void stop(String reason) {
		ActionExecutor.Cancellation cancellation = executor.cancel(reason);
		if (cancellation != ActionExecutor.Cancellation.NO_ACTIVE_ACTION) {
			return;
		}
		try {
			context.releaseAll();
		} catch (RuntimeException exception) {
			lastLifecycleFailure = exception;
		}
	}

	public void onSessionClosed(long sessionId) {
		requireSessionId(sessionId);
		if (activeSessionId != sessionId && resultSessionId != sessionId) {
			return;
		}
		resetExecutor();
		activeSessionId = UNSCOPED_SESSION;
		resultSessionId = UNSCOPED_SESSION;
	}

	public Observation.ActionStatus currentStatus() {
		return executor.currentStatus();
	}

	public Observation.ActionStatus currentStatus(long sessionId) {
		return activeSessionId == sessionId ? executor.currentStatus() : Observation.ActionStatus.none();
	}

	public ActionResult lastResult() {
		return executor.lastResult();
	}

	public ActionResult lastResult(long sessionId) {
		return resultSessionId == sessionId ? executor.lastResult() : null;
	}

	public RuntimeException lastLifecycleFailure() {
		return lastLifecycleFailure;
	}

	private long eventSessionId() {
		return activeSessionId != UNSCOPED_SESSION ? activeSessionId : callbackSessionId;
	}

	private void resetExecutor() {
		try {
			executor.reset();
		} catch (RuntimeException exception) {
			lastLifecycleFailure = exception;
		}
	}

	private static void requireSessionId(long sessionId) {
		if (sessionId < 0L) throw new IllegalArgumentException("sessionId must not be negative");
	}

	public interface SessionEventSink {
		void onProgress(long sessionId, ActionProgress progress);

		void onResult(long sessionId, ActionResult result);
	}
}
