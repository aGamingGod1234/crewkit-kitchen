package dev.agaminggod.arenaagents.server.conversation;

import java.util.Optional;

@FunctionalInterface
public interface ConversationEventSink {
	void publish(ConversationEvent event, Optional<String> wakeGoal);
}
