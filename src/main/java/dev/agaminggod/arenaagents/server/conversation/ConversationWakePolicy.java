package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.Objects;
import java.util.Optional;

public final class ConversationWakePolicy {
	private static final String DIRECT_MESSAGE_GOAL = "Handle the latest PLAYER_MESSAGE in conversation memory as the player's request. Reply directly to that message's sourceId when a response is appropriate, or carry out the requested Minecraft action. Finish when the request is handled.";
	private static final String PROXIMITY_SPEECH_GOAL = "Consider the latest PROXIMITY_SPEECH in conversation memory. Decide whether the nearby player was addressing you. Respond with proximity speech or act on the request when appropriate, then finish.";

	private ConversationWakePolicy() {
	}

	public static Optional<String> goalFor(AgentLifecycleState state, ConversationKind kind) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		if (state != AgentLifecycleState.IDLE && state != AgentLifecycleState.COMPLETED) {
			return Optional.empty();
		}
		return switch (kind) {
			case PLAYER_MESSAGE -> Optional.of(DIRECT_MESSAGE_GOAL);
			case PROXIMITY_SPEECH -> Optional.of(PROXIMITY_SPEECH_GOAL);
			case AGENT_MESSAGE, PLAYER_STEER -> Optional.empty();
		};
	}
}
