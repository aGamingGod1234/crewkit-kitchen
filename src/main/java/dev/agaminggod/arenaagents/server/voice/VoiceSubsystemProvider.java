package dev.agaminggod.arenaagents.server.voice;

import net.minecraft.server.MinecraftServer;

/** Fabric entrypoint implemented by optional voice transport addons. */
@FunctionalInterface
public interface VoiceSubsystemProvider {
	VoiceSubsystem create(MinecraftServer server);
}
