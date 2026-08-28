package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceChatPlugin implements VoicechatPlugin {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ArenaAgentsVoiceChatPlugin.class);
	private static volatile VoicechatServerApi serverApi;
	private static volatile HumanSpeechCapture speechCapture;
	private static final Map<MinecraftServer, VoiceSubsystemConfiguration> CONFIGURATIONS = new ConcurrentHashMap<>();

	@Override
	public String getPluginId() {
		return "arenaagents_voice";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> serverApi = event.getVoicechat());
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			serverApi = null;
			HumanSpeechCapture capture = speechCapture;
			speechCapture = null;
			if (capture != null) capture.close();
		});
	}

	private void onMicrophonePacket(MicrophonePacketEvent event) {
		if (event.getSenderConnection() == null) return;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof net.minecraft.server.level.ServerPlayer player)) return;
		if (!dev.agaminggod.arenaagents.server.CodexAgentServerRuntime.hasVoiceConsent(
				player.level().getServer(), player.getUUID())) return;
		HumanSpeechCapture capture = speechCapture;
		if (capture == null) {
			synchronized (ArenaAgentsVoiceChatPlugin.class) {
				capture = speechCapture;
				if (capture == null) {
					try {
						VoiceSubsystemConfiguration configuration = CONFIGURATIONS.get(player.level().getServer());
						if (configuration == null) return;
						capture = new HumanSpeechCapture(new SpeechWorkerClient(configuration));
						speechCapture = capture;
					} catch (RuntimeException exception) {
						LOGGER.error("Arena Agents proximity speech capture could not start", exception);
						return;
					}
				}
			}
		}
		capture.accept(event);
	}

	static VoicechatServerApi serverApi() {
		return serverApi;
	}

	static void configure(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		CONFIGURATIONS.put(server, configuration);
	}

	static void clearConfiguration(MinecraftServer server) {
		CONFIGURATIONS.remove(server);
	}
}
