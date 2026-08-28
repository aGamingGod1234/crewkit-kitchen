package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HumanSpeechCapture implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(HumanSpeechCapture.class);
	private static final int MAX_SAMPLES = 48_000 * 20;
	private static final long SILENCE_MILLISECONDS = 300L;
	private final SpeechCaptureEngine engine;

	HumanSpeechCapture(SpeechWorkerClient worker) {
		this.engine = new SpeechCaptureEngine(
				worker::transcribe,
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
						runnable -> Thread.ofPlatform().daemon().name("arenaagents-stt").unstarted(runnable)
				),
				SILENCE_MILLISECONDS,
				MAX_SAMPLES,
				latency -> LOGGER.info(
						"Voice input latency player={} sequence={} endpointMs={} transcriptionMs={} totalMs={}",
						latency.playerId(), latency.utteranceSequence(), latency.endpointMilliseconds(),
						latency.transcriptionMilliseconds(), latency.totalMilliseconds()
				)
		);
	}

	void accept(MicrophonePacketEvent event) {
		if (event.getSenderConnection() == null) return;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof ServerPlayer player)) return;
		byte[] opus = event.getPacket().getOpusEncodedData().clone();
		if (opus.length == 0 || opus.length > 8_192) return;
		VoicechatServerApi api = event.getVoicechat();
		var server = player.level().getServer();
		engine.accept(
				player.getUUID(),
				event.getPacket().isWhispering(),
				opus,
				() -> decoder(api.createDecoder()),
				server::execute,
				(playerId, transcript, whispering) -> CodexAgentServerRuntime.deliverHumanSpeech(
						server, playerId, transcript, whispering
				)
		);
	}

	@Override
	public void close() {
		engine.close();
	}

	private static SpeechCaptureEngine.Decoder decoder(OpusDecoder decoder) {
		if (decoder == null) throw new IllegalStateException("Simple Voice Chat did not create an Opus decoder");
		return new SpeechCaptureEngine.Decoder() {
			@Override public short[] decode(byte[] opus) { return decoder.decode(opus); }
			@Override public void close() { decoder.close(); }
		};
	}
}
