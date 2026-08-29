package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SimpleVoiceChatSubsystem implements VoiceSubsystem {
	private static final Logger LOGGER = LoggerFactory.getLogger(SimpleVoiceChatSubsystem.class);
	private final MinecraftServer server;
	private final ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredServer;
	private final VoicePlaybackCoordinator playback;

	SimpleVoiceChatSubsystem(
			MinecraftServer server,
			VoiceWorkerClient worker,
			ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredServer
	) {
		this.server = Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(worker, "worker must not be null");
		this.configuredServer = Objects.requireNonNull(configuredServer, "configured server must not be null");
		this.playback = new VoicePlaybackCoordinator(
				worker::synthesize,
				server::execute,
				new SimpleVoiceTransport(),
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

	private Entity findEntity(UUID entityId) {
		if (entityId == null) return null;
		for (var level : server.getAllLevels()) {
			Entity entity = level.getEntity(entityId);
			if (entity != null && entity.isAlive()) return entity;
		}
		return null;
	}

	@Override
	public void stop(AgentId agentId) {
		playback.stop(agentId);
	}

	@Override
	public void cancelHumanSpeech(UUID playerId) {
		configuredServer.cancelHumanSpeech(playerId);
	}

	@Override
	public void close() {
		try {
			playback.close();
		} finally {
			configuredServer.close();
		}
	}

	private final class SimpleVoiceTransport implements VoicePlaybackCoordinator.Transport {
		@Override
		public boolean available() {
			return configuredServer.active();
		}

		@Override
		public VoicePlaybackCoordinator.Playback create(
				AgentId agentId,
				UUID entityId,
				int radius,
				short[] samples,
				Runnable onStopped
		) {
			if (!configuredServer.active()) {
				throw new VoicePlaybackCoordinator.UnavailableException("Voice channel is unavailable");
			}
			VoicechatServerApi api = configuredServer.voicechat();
			Entity entity = findEntity(entityId);
			if (entity == null) throw new VoicePlaybackCoordinator.UnavailableException("Agent entity is unavailable");
			UUID channelId = UUID.nameUUIDFromBytes(
					("arenaagents-voice:" + agentId).getBytes(StandardCharsets.UTF_8)
			);
			EntityAudioChannel channel = api.createEntityAudioChannel(channelId, api.fromEntity(entity));
			if (channel == null) {
				throw new VoicePlaybackCoordinator.UnavailableException("Simple Voice Chat rejected the entity channel");
			}
			channel.setDistance(radius);
			AudioPlayer audioPlayer = api.createAudioPlayer(channel, api.createEncoder(), samples);
			if (audioPlayer == null) {
				throw new VoicePlaybackCoordinator.UnavailableException("Simple Voice Chat rejected the audio player");
			}
			audioPlayer.setOnStopped(onStopped);
			return new VoicePlaybackCoordinator.Playback() {
				@Override public void start() { audioPlayer.startPlaying(); }
				@Override public void stop() { audioPlayer.stopPlaying(); }
			};
		}
	}
}
