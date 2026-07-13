package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import java.util.Objects;

public final class ClientActionRuntime {
	private final ActionContext context;
	private final ActionExecutor executor;
	private RuntimeException lastLifecycleFailure;

	public ClientActionRuntime(ActionContext context, ActionExecutor.EventSink eventSink) {
		this.context = Objects.requireNonNull(context, "context must not be null");
		this.executor = new ActionExecutor(context, new ActionFactory()::create, eventSink);
	}

	public ActionExecutor.Acceptance onActionCommand(ActionCommand command) {
		return executor.accept(command);
	}

	public ActionExecutor.Cancellation onCancelAction(String commandId, String reason) {
		return executor.cancel(commandId, reason);
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

	public Observation.ActionStatus currentStatus() {
		return executor.currentStatus();
	}

	public ActionResult lastResult() {
		return executor.lastResult();
	}

	public RuntimeException lastLifecycleFailure() {
		return lastLifecycleFailure;
	}
}
