package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

public final class ArenaAgentsVoiceChatPlugin implements VoicechatPlugin {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ArenaAgentsVoiceChatPlugin.class);
	private static final VoicechatServerBindings<Object, VoicechatServerApi> PRODUCTION_BINDINGS =
			new VoicechatServerBindings<>(configuration ->
					new HumanSpeechCapture(new SpeechWorkerClient(configuration)));
	private static volatile ArenaAgentsVoiceChatPlugin activePlugin;

	private final VoicechatServerBindings<Object, VoicechatServerApi> bindings;
	private final Function<MicrophonePacketEvent, Object> packetServer;

	public ArenaAgentsVoiceChatPlugin() {
		this(PRODUCTION_BINDINGS, ArenaAgentsVoiceChatPlugin::consentingServer);
	}

	ArenaAgentsVoiceChatPlugin(
			ServerSpeechCaptureRegistry.CaptureFactory captureFactory,
			Function<MicrophonePacketEvent, Object> packetServer
	) {
		this(new VoicechatServerBindings<>(captureFactory), packetServer);
	}

	private ArenaAgentsVoiceChatPlugin(
			VoicechatServerBindings<Object, VoicechatServerApi> bindings,
			Function<MicrophonePacketEvent, Object> packetServer
	) {
		this.bindings = Objects.requireNonNull(bindings, "server bindings must not be null");
		this.packetServer = Objects.requireNonNull(packetServer, "packet server resolver must not be null");
	}

	@Override
	public String getPluginId() {
		return "arenaagents_voice";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		activePlugin = this;
		registration.registerEvent(VoicechatServerStartedEvent.class,
				event -> bindings.started(event.getVoicechat()));
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
		registration.registerEvent(VoicechatServerStoppedEvent.class,
				event -> bindings.stopped(event.getVoicechat()));
	}

	private void onMicrophonePacket(MicrophonePacketEvent event) {
		try {
			Object server = packetServer.apply(event);
			if (server != null) bindings.accept(server, event.getVoicechat(), event);
		} catch (RuntimeException exception) {
			LOGGER.error("Arena Agents proximity speech capture could not start", exception);
		}
	}

	ConfiguredServer configureServer(Object server, VoiceSubsystemConfiguration configuration) {
		return new ConfiguredServer(bindings.configure(server, configuration));
	}

	static ConfiguredServer configure(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		ArenaAgentsVoiceChatPlugin plugin = activePlugin;
		if (plugin == null) throw new IllegalStateException("Simple Voice Chat plugin has not registered its events");
		return plugin.configureServer(server, configuration);
	}

	private static Object consentingServer(MicrophonePacketEvent event) {
		if (event.getSenderConnection() == null) return null;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof net.minecraft.server.level.ServerPlayer player)) return null;
		MinecraftServer server = player.level().getServer();
		return dev.agaminggod.arenaagents.server.CodexAgentServerRuntime.hasVoiceConsent(
				server, player.getUUID()
		) ? server : null;
	}

	static final class ConfiguredServer implements AutoCloseable {
		private final VoicechatServerBindings.Binding<VoicechatServerApi> binding;

		private ConfiguredServer(VoicechatServerBindings.Binding<VoicechatServerApi> binding) {
			this.binding = binding;
		}

		VoicechatServerApi voicechat() {
			return binding.owner();
		}

		boolean active() {
			return binding.active();
		}

		void cancelHumanSpeech(java.util.UUID playerId) {
			binding.cancelHumanSpeech(playerId);
		}

		@Override
		public void close() {
			binding.close();
		}
	}
}
