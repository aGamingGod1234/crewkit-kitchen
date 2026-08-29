package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceProvider implements VoiceSubsystemProvider {
	@Override
	public VoiceSubsystem create(MinecraftServer server) {
		return create(server, legacyConfiguration());
	}

	@Override
	public VoiceSubsystem create(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredServer =
				ArenaAgentsVoiceChatPlugin.configure(server, configuration);
		try {
			return new SimpleVoiceChatSubsystem(
					server, new VoiceWorkerClient(configuration), configuredServer
			);
		} catch (RuntimeException failure) {
			configuredServer.close();
			throw failure;
		}
	}

	/** Reconstructs the pre-configuration startup contract for older core runtimes. */
	private static VoiceSubsystemConfiguration legacyConfiguration() {
		String endpoint = System.getProperty(
				"arenaagents.voiceUrl", "http://127.0.0.1:8766/v1/tts"
		);
		int requestTimeoutMs = parseRequestTimeout(System.getProperty(
				"arenaagents.voiceRequestTimeoutMs",
				Integer.toString(VoiceSubsystemConfiguration.DEFAULT_REQUEST_TIMEOUT_MS)
		));
		String secretPath = System.getProperty(
				"arenaagents.voiceSecretFile",
				System.getProperty("arenaagents.bridgeSecretFile", "runtime/bridge-secret.txt")
		);
		try {
			String secret = Files.readString(Path.of(secretPath), StandardCharsets.UTF_8).trim();
			return new VoiceSubsystemConfiguration(endpoint, secret, requestTimeoutMs);
		} catch (IOException exception) {
			throw new IllegalStateException("Voice worker secret is unavailable", exception);
		}
	}

	private static int parseRequestTimeout(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("voice request timeout must be an integer", exception);
		}
	}
}
