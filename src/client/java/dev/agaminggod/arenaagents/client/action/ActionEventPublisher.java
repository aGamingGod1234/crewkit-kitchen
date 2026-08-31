package dev.agaminggod.arenaagents.client.action;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import java.util.Objects;
import java.util.function.Consumer;

public final class ActionEventPublisher implements ActionExecutor.EventSink, ClientActionRuntime.SessionEventSink {
	public static final String EVENT_ACTION_PROGRESS = "action_progress";
	public static final String EVENT_ACTION_RESULT = "action_result";

	private final BridgeServer bridgeServer;
	private final Consumer<RuntimeException> failureHandler;
	private volatile RuntimeException lastFailure;

	public ActionEventPublisher(
			BridgeServer bridgeServer,
			Consumer<RuntimeException> failureHandler
	) {
		this.bridgeServer = Objects.requireNonNull(bridgeServer, "bridgeServer must not be null");
		this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler must not be null");
	}

	@Override
	public void onProgress(ActionProgress progress) {
		onProgress(0L, progress);
	}

	@Override
	public void onProgress(long sessionId, ActionProgress progress) {
		Objects.requireNonNull(progress, "progress must not be null");
		JsonObject payload = new JsonObject();
		payload.addProperty("commandId", progress.commandId());
		payload.addProperty("actionType", progress.actionType().wireName());
		payload.addProperty("state", progress.state().name());
		payload.addProperty("elapsedMs", progress.elapsedMs());
		payload.addProperty("message", progress.message());
		payload.addProperty("observedAtEpochMs", progress.observedAtEpochMs());
		send(sessionId, EVENT_ACTION_PROGRESS, payload);
	}

	@Override
	public void onResult(ActionResult result) {
		onResult(0L, result);
	}

	@Override
	public void onResult(long sessionId, ActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		JsonObject payload = new JsonObject();
		payload.addProperty("commandId", result.commandId());
		payload.addProperty("state", result.state().name());
		payload.addProperty("reasonCode", result.reasonCode());
		payload.addProperty("message", result.message());
		payload.addProperty("completedAtEpochMs", result.completedAtEpochMs());
		send(sessionId, EVENT_ACTION_RESULT, payload);
	}

	public RuntimeException lastFailure() {
		return lastFailure;
	}

	private void send(long sessionId, String type, JsonObject payload) {
		try {
			if (sessionId == 0L) bridgeServer.sendEvent(type, payload);
			else bridgeServer.sendEvent(sessionId, type, payload);
		} catch (RuntimeException exception) {
			lastFailure = exception;
			try {
				failureHandler.accept(exception);
			} catch (RuntimeException handlerFailure) {
				exception.addSuppressed(handlerFailure);
			}
		}
	}
}
