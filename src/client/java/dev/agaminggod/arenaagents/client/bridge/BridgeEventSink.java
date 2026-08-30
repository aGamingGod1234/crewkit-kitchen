package dev.agaminggod.arenaagents.client.bridge;

import dev.agaminggod.arenaagents.protocol.ActionCommand;

@FunctionalInterface
public interface BridgeEventSink {
	void onActionCommand(ActionCommand command);

	default void onActionCommand(long sessionId, ActionCommand command) {
		onActionCommand(command);
	}

	default void onCancelAction(String commandId) {
	}

	default void onCancelAction(long sessionId, String commandId) {
		onCancelAction(commandId);
	}

	default void onObservationRequested() {
	}

	default void onObservationRequested(long sessionId) {
		onObservationRequested();
	}

	default void onSessionClosed(long sessionId) {
	}
}
