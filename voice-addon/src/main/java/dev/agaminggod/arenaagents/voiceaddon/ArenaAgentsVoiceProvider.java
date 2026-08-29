package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArenaAgentsVoiceProvider implements VoiceSubsystemProvider, VoicechatPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger(ArenaAgentsVoiceProvider.class);
	private static final String CONFIGURATION_CLASS =
			"dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration";
	private static final int DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
	private static final int MAX_REQUEST_TIMEOUT_MS = 600_000;
	private static volatile VoicechatServerApi legacyServerApi;
	private static volatile HumanSpeechCapture legacySpeechCapture;

	@Override
	public String getPluginId() {
		return "arenaagents_voice";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		if (modernConfigurationAvailable()) {
			modernVoicechatPlugin().registerEvents(registration);
			return;
		}
		registration.registerEvent(VoicechatServerStartedEvent.class,
				event -> legacyServerApi = event.getVoicechat());
		registration.registerEvent(MicrophonePacketEvent.class, this::onLegacyMicrophonePacket);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			if (legacyServerApi == event.getVoicechat()) legacyServerApi = null;
			HumanSpeechCapture capture = legacySpeechCapture;
			legacySpeechCapture = null;
			if (capture != null) capture.close();
		});
	}

	@Override
	public VoiceSubsystem create(MinecraftServer server) {
		LegacyConfiguration configuration = legacyConfiguration();
		return new LegacyVoiceSubsystem(server, legacyWorker(configuration));
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

	private static boolean modernConfigurationAvailable() {
		try {
			Class.forName(CONFIGURATION_CLASS, false, ArenaAgentsVoiceProvider.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError unavailable) {
			return false;
		}
	}

	private static VoicechatPlugin modernVoicechatPlugin() {
		try {
			return (VoicechatPlugin) Class.forName(
					"dev.agaminggod.arenaagents.voiceaddon.ArenaAgentsVoiceChatPlugin",
					true,
					ArenaAgentsVoiceProvider.class.getClassLoader()
			).getConstructor().newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Modern voice-chat plugin is unavailable", exception);
		}
	}

	private void onLegacyMicrophonePacket(MicrophonePacketEvent event) {
		try {
			if (event.getSenderConnection() == null) return;
			Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
			if (!(rawPlayer instanceof ServerPlayer player)) return;
			MinecraftServer server = player.level().getServer();
			if (!CodexAgentServerRuntime.hasVoiceConsent(server, player.getUUID())) return;
			HumanSpeechCapture capture = legacySpeechCapture;
			if (capture == null) {
				synchronized (ArenaAgentsVoiceProvider.class) {
					capture = legacySpeechCapture;
					if (capture == null) {
						LegacyConfiguration configuration = legacyConfiguration();
						URI speechEndpoint = URI.create(System.getProperty(
								"arenaagents.sttUrl",
								configuration.endpoint().toString().replace("/v1/tts", "/v1/stt")
						));
						capture = new HumanSpeechCapture(new SpeechWorkerClient(
								defaultHttpClient(), speechEndpoint, configuration.secret(),
								configuration.requestTimeout()
						));
						legacySpeechCapture = capture;
					}
				}
			}
			capture.accept(event);
		} catch (RuntimeException exception) {
			LOGGER.error("Arena Agents proximity speech capture could not start", exception);
		}
	}

	private static VoiceWorkerClient legacyWorker(LegacyConfiguration configuration) {
		return new VoiceWorkerClient(
				defaultHttpClient(), configuration.endpoint(), configuration.secret(),
				configuration.requestTimeout()
		);
	}

	private static HttpClient defaultHttpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	private static LegacyConfiguration legacyConfiguration() {
		URI endpoint = validateEndpoint(System.getProperty(
				"arenaagents.voiceUrl", "http://127.0.0.1:8766/v1/tts"
		));
		int requestTimeoutMs = parseRequestTimeout(System.getProperty(
				"arenaagents.voiceRequestTimeoutMs", Integer.toString(DEFAULT_REQUEST_TIMEOUT_MS)
		));
		String secretPath = System.getProperty(
				"arenaagents.voiceSecretFile",
				System.getProperty("arenaagents.bridgeSecretFile", "runtime/bridge-secret.txt")
		);
		try {
			String secret = Files.readString(Path.of(secretPath), StandardCharsets.UTF_8).trim();
			if (secret.length() < 16 || secret.length() > 512) {
				throw new IllegalArgumentException("voice secret is invalid");
			}
			return new LegacyConfiguration(endpoint, secret, Duration.ofMillis(requestTimeoutMs));
		} catch (IOException exception) {
			throw new IllegalStateException("Voice worker secret is unavailable", exception);
		}
	}

	private static URI validateEndpoint(String endpoint) {
		String normalized = Objects.requireNonNull(endpoint, "voice endpoint must not be null").strip();
		if (normalized.isEmpty() || normalized.length() > 2_048) {
			throw new IllegalArgumentException("voice endpoint must be nonblank and bounded");
		}
		try {
			URI uri = new URI(normalized);
			String scheme = uri.getScheme();
			boolean supportedScheme = scheme != null
					&& (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
			if (!supportedScheme
					|| uri.isOpaque()
					|| !"127.0.0.1".equals(uri.getHost())
					|| uri.getPort() < 1
					|| uri.getPort() > 65_535
					|| uri.getRawUserInfo() != null
					|| !"/v1/tts".equals(uri.getRawPath())
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException("voice endpoint must be a loopback worker /v1/tts URI");
			}
			return uri;
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("voice endpoint must be a valid URI", exception);
		}
	}

	private static int parseRequestTimeout(String value) {
		try {
			int timeout = Integer.parseInt(value);
			if (timeout < 1 || timeout > MAX_REQUEST_TIMEOUT_MS) {
				throw new IllegalArgumentException("voice request timeout is invalid");
			}
			return timeout;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("voice request timeout must be an integer", exception);
		}
	}

	private record LegacyConfiguration(URI endpoint, String secret, Duration requestTimeout) {
	}

	private static final class LegacyVoiceSubsystem implements VoiceSubsystem {
		private final MinecraftServer server;
		private final VoicePlaybackCoordinator playback;

		private LegacyVoiceSubsystem(MinecraftServer server, VoiceWorkerClient worker) {
			this.server = Objects.requireNonNull(server, "server must not be null");
			this.playback = new VoicePlaybackCoordinator(
					worker::synthesize,
					server::execute,
					new LegacyVoiceTransport(),
					latency -> LOGGER.info(
							"Voice output latency agent={} sequence={} synthesisMs={} firstPlaybackMs={}",
							latency.agentId(), latency.conversationSequence(), latency.synthesisMilliseconds(),
							latency.firstPlaybackMilliseconds()
					)
			);
		}

		@Override
		public boolean available() {
			return playback.available();
		}

		@Override
		public void registerAgent(AgentId agentId, UUID entityId) {
			playback.registerAgent(agentId, entityId);
		}

		@Override
		public void unregisterAgent(AgentId agentId) {
			playback.unregisterAgent(agentId);
		}

		@Override
		public CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			return playback.speak(request);
		}

		@Override
		public void stop(AgentId agentId) {
			playback.stop(agentId);
		}

		@Override
		public void cancelHumanSpeech(UUID playerId) {
			HumanSpeechCapture capture = legacySpeechCapture;
			if (capture != null) capture.cancel(playerId);
		}

		@Override
		public void close() {
			playback.close();
		}

		private Entity findEntity(UUID entityId) {
			if (entityId == null) return null;
			for (var level : server.getAllLevels()) {
				Entity entity = level.getEntity(entityId);
				if (entity != null && entity.isAlive()) return entity;
			}
			return null;
		}

		private final class LegacyVoiceTransport implements VoicePlaybackCoordinator.Transport {
			@Override
			public boolean available() {
				return legacyServerApi != null;
			}

			@Override
			public VoicePlaybackCoordinator.Playback create(
					AgentId agentId,
					UUID entityId,
					int radius,
					short[] samples,
					Runnable onStopped
			) {
				VoicechatServerApi api = legacyServerApi;
				if (api == null) {
					throw new VoicePlaybackCoordinator.UnavailableException("Voice channel is unavailable");
				}
				Entity entity = findEntity(entityId);
				if (entity == null) {
					throw new VoicePlaybackCoordinator.UnavailableException("Agent entity is unavailable");
				}
				UUID channelId = UUID.nameUUIDFromBytes(
						("arenaagents-voice:" + agentId).getBytes(StandardCharsets.UTF_8)
				);
				EntityAudioChannel channel = api.createEntityAudioChannel(channelId, api.fromEntity(entity));
				if (channel == null) {
					throw new VoicePlaybackCoordinator.UnavailableException(
							"Simple Voice Chat rejected the entity channel"
					);
				}
				channel.setDistance(radius);
				AudioPlayer audioPlayer = api.createAudioPlayer(channel, api.createEncoder(), samples);
				if (audioPlayer == null) {
					throw new VoicePlaybackCoordinator.UnavailableException(
							"Simple Voice Chat rejected the audio player"
					);
				}
				audioPlayer.setOnStopped(onStopped);
				return new VoicePlaybackCoordinator.Playback() {
					@Override public void start() { audioPlayer.startPlaying(); }
					@Override public void stop() { audioPlayer.stopPlaying(); }
				};
			}
		}
	}
}
