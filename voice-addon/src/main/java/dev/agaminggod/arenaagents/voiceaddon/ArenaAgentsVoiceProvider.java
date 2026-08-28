package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceProvider implements VoiceSubsystemProvider {
	@Override
	public VoiceSubsystem create(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		ArenaAgentsVoiceChatPlugin.configure(server, configuration);
		try {
			return new SimpleVoiceChatSubsystem(
					server, new VoiceWorkerClient(configuration),
					() -> ArenaAgentsVoiceChatPlugin.clearConfiguration(server)
			);
		} catch (RuntimeException failure) {
			ArenaAgentsVoiceChatPlugin.clearConfiguration(server);
			throw failure;
		}
	}
}
