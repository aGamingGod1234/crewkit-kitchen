package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter.ProximitySpeechAudience;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HumanSpeechCapture implements ServerSpeechCaptureRegistry.Capture {
	private static final Logger LOGGER = LoggerFactory.getLogger(HumanSpeechCapture.class);
	private static final int MAX_SAMPLES = 48_000 * 20;
	private static final int ADAPTIVE_ENDPOINT_AFTER_SAMPLES = 48_000 / 2;
	private static final long MIN_SILENCE_MILLISECONDS = 160L;
	private static final long SILENCE_MILLISECONDS = 300L;
	private static final ConsentCapture CONSENT_CAPTURE = resolveConsentCapture();
	private final SpeechCaptureEngine engine;

	HumanSpeechCapture(SpeechWorkerClient worker) {
		this.engine = new SpeechCaptureEngine(
				worker::transcribe,
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
						runnable -> Thread.ofPlatform().daemon().name("arenaagents-stt").unstarted(runnable)
				),
				MIN_SILENCE_MILLISECONDS,
				SILENCE_MILLISECONDS,
				ADAPTIVE_ENDPOINT_AFTER_SAMPLES,
				MAX_SAMPLES,
				latency -> LOGGER.info(
						"Voice input latency player={} sequence={} endpointMs={} transcriptionMs={} totalMs={}",
						latency.playerId(), latency.utteranceSequence(), latency.endpointMilliseconds(),
						latency.transcriptionMilliseconds(), latency.totalMilliseconds()
				)
		);
	}

	@Override
	public void accept(MicrophonePacketEvent event) {
		if (event.getSenderConnection() == null) return;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof ServerPlayer player)) return;
		var server = player.level().getServer();
		captureWhileGranted(
				server, player.getUUID(), () -> {
					byte[] opus = event.getPacket().getOpusEncodedData().clone();
					if (opus.length == 0 || opus.length > 8_192) return;
					VoicechatServerApi api = event.getVoicechat();
					boolean whispering = event.getPacket().isWhispering();
					engine.accept(
							player.getUUID(),
							whispering,
							opus,
							() -> decoder(api.createDecoder()),
							server::execute,
							() -> {
								AtomicReference<Optional<ProximitySpeechAudience>> audience =
										new AtomicReference<>(Optional.empty());
								server.execute(() -> audience.set(CodexAgentServerRuntime.captureHumanSpeechAudience(
										server, player.getUUID(), whispering
								)));
								return (playerId, transcript, ignoredWhispering) -> audience.get().ifPresent(
										snapshot -> CodexAgentServerRuntime.deliverHumanSpeech(server, snapshot, transcript)
								);
							}
					);
				}
		);
	}

	static boolean captureWhileGranted(MinecraftServer server, UUID playerId, Runnable capture) {
		return CONSENT_CAPTURE.capture(server, playerId, capture);
	}

	private static ConsentCapture resolveConsentCapture() {
		try {
			dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.class.getMethod(
					"captureWhileGranted", MinecraftServer.class, UUID.class, Runnable.class
			);
			return ModernConsentCapture.INSTANCE;
		} catch (NoSuchMethodException legacyCore) {
			return (server, playerId, capture) -> {
				if (!dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.granted(server, playerId)) {
					return false;
				}
				capture.run();
				return true;
			};
		}
	}

	@Override
	public void close() {
		engine.close();
	}

	@Override
	public void cancel(UUID playerId) {
		engine.cancel(playerId);
	}

	private static SpeechCaptureEngine.Decoder decoder(OpusDecoder decoder) {
		if (decoder == null) throw new IllegalStateException("Simple Voice Chat did not create an Opus decoder");
		return new SpeechCaptureEngine.Decoder() {
			@Override public short[] decode(byte[] opus) { return decoder.decode(opus); }
			@Override public void close() { decoder.close(); }
		};
	}

	@FunctionalInterface
	private interface ConsentCapture {
		boolean capture(MinecraftServer server, UUID playerId, Runnable capture);
	}

	private static final class ModernConsentCapture implements ConsentCapture {
		private static final ModernConsentCapture INSTANCE = new ModernConsentCapture();

		@Override
		public boolean capture(MinecraftServer server, UUID playerId, Runnable capture) {
			return dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.captureWhileGranted(
					server, playerId, capture
			);
		}
	}
}
