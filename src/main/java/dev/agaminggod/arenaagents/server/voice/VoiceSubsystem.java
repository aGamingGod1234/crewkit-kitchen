package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface VoiceSubsystem extends AutoCloseable {
	boolean available();

	void registerAgent(AgentId agentId, UUID entityId);

	void unregisterAgent(AgentId agentId);

	CompletionStage<VoiceReceipt> speak(VoiceRequest request);

	void stop(AgentId agentId);

	@Override
	void close();
}
