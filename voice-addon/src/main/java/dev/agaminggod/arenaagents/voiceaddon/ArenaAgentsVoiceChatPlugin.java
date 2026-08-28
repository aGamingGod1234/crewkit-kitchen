package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceChatPlugin implements VoicechatPlugin {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ArenaAgentsVoiceChatPlugin.class);
	private static volatile VoicechatServerApi serverApi;
	private static final ServerSpeechCaptureRegistry<MinecraftServer, VoicechatServerApi> SPEECH_CAPTURES =
			new ServerSpeechCaptureRegistry<>(configuration ->
					new HumanSpeechCapture(new SpeechWorkerClient(configuration)));

	@Override
	public String getPluginId() {
		return "arenaagents_voice";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> serverApi = event.getVoicechat());
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			VoicechatServerApi stoppedApi = event.getVoicechat();
			if (serverApi == stoppedApi) serverApi = null;
			SPEECH_CAPTURES.clearOwner(stoppedApi);
		});
	}

	private void onMicrophonePacket(MicrophonePacketEvent event) {
		if (event.getSenderConnection() == null) return;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof net.minecraft.server.level.ServerPlayer player)) return;
		if (!dev.agaminggod.arenaagents.server.CodexAgentServerRuntime.hasVoiceConsent(
				player.level().getServer(), player.getUUID())) return;
		try {
			SPEECH_CAPTURES.accept(player.level().getServer(), event.getVoicechat(), event);
		} catch (RuntimeException exception) {
			LOGGER.error("Arena Agents proximity speech capture could not start", exception);
		}
	}

	static VoicechatServerApi serverApi() {
		return serverApi;
	}

	static void configure(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		SPEECH_CAPTURES.configure(server, serverApi, configuration);
	}

	static void clearConfiguration(MinecraftServer server) {
		SPEECH_CAPTURES.clear(server);
	}
}
