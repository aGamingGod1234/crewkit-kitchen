package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.Objects;

public final class ConversationWakePolicy {
	private ConversationWakePolicy() {
	}

	public static boolean mayInstallNewGoalFromSpeech(AgentLifecycleState state) {
		Objects.requireNonNull(state, "state must not be null");
		return state == AgentLifecycleState.IDLE || state == AgentLifecycleState.COMPLETED;
	}

	public static boolean shouldStartGoal(AgentLifecycleState state, ConversationKind kind) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		if (state != AgentLifecycleState.IDLE
				&& state != AgentLifecycleState.COMPLETED
				&& state != AgentLifecycleState.PAUSED) {
			return false;
		}
		return switch (kind) {
			case PLAYER_MESSAGE, PROXIMITY_SPEECH -> true;
			case AGENT_MESSAGE, PLAYER_STEER -> false;
		};
	}

	public static boolean isPlayerGoalChannel(ConversationKind kind, ConversationAudience audience) {
		return kind == ConversationKind.PLAYER_MESSAGE && audience == ConversationAudience.DIRECT
				|| kind == ConversationKind.PROXIMITY_SPEECH && audience == ConversationAudience.PROXIMITY;
	}

	/** An operator's typed or spoken instruction can change the task without a goal-panel visit. */
	public static boolean mayReplaceGoalFromSpeech(AgentLifecycleState state, ConversationKind kind,
			ConversationAudience audience, boolean operator) {
		return operator && isPlayerGoalChannel(kind, audience)
				&& (state.isActive() || state == AgentLifecycleState.PAUSED);
	}

	public static boolean isCompletionConfirmation(String text) {
		return switch (text.strip().toLowerCase(java.util.Locale.ROOT).replaceFirst("[.!]+$", "").strip()) {
			case "yes", "yep", "yeah", "confirmed", "i confirm", "yes, confirmed", "that's right", "thats right" -> true;
			default -> false;
		};
	}
}
