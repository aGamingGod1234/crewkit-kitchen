package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceProvider implements VoiceSubsystemProvider {
	@Override
	public VoiceSubsystem create(MinecraftServer server) {
		return new SimpleVoiceChatSubsystem(server, new VoiceWorkerClient());
	}
}
