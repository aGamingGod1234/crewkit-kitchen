package dev.agaminggod.arenaagents.client.bridge;

import dev.agaminggod.arenaagents.protocol.ActionCommand;

@FunctionalInterface
public interface BridgeEventSink {
	void onActionCommand(ActionCommand command);

	default void onCancelAction(String commandId) {
	}

	default void onObservationRequested() {
	}
}
