package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter.ProximitySpeechAudience;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
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
	public void accept(MicrophonePacketSnapshot packet) {
		if (!(packet.minecraftServer() instanceof MinecraftServer server)) return;
		captureWhileGranted(
				server, packet.playerId(), () -> {
					byte[] opus = packet.opus();
					if (opus.length == 0 || opus.length > 8_192) return;
					VoicechatServerApi api = packet.voicechat();
					boolean whispering = packet.whispering();
					Optional<ProximitySpeechAudience> audience =
							CodexAgentServerRuntime.captureHumanSpeechAudience(server, packet.playerId(), whispering);
					engine.accept(
							packet.playerId(),
							whispering,
							opus,
							() -> decoder(api.createDecoder()),
							server::execute,
							() -> (playerId, transcript, ignoredWhispering) -> audience.ifPresent(
										snapshot -> CodexAgentServerRuntime.deliverHumanSpeech(server, snapshot, transcript)
							)
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
