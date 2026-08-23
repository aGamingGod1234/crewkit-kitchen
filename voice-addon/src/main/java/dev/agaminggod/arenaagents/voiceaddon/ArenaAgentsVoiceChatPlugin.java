package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

public final class ArenaAgentsVoiceChatPlugin implements VoicechatPlugin {
	private static volatile VoicechatServerApi serverApi;
	private static volatile HumanSpeechCapture speechCapture;

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
						capture = new HumanSpeechCapture(new SpeechWorkerClient());
						speechCapture = capture;
					} catch (RuntimeException ignored) {
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
}
