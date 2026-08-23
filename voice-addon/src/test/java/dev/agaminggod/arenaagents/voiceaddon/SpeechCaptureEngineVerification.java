package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class SpeechCaptureEngineVerification {
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");

	private SpeechCaptureEngineVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifySilenceFlushesOneOrderedUtterance();
		assertions += verifyWhisperChangeSplitsAndSequencesUtterances();
		assertions += verifyMaximumDurationBoundsDecodedSamples();
		assertions += verifyMalformedPacketDoesNotWedgeLaterSpeech();
		assertions += verifyTranscriptsDeliverInUtteranceOrder();
		assertions += verifyUnavailableSttDisablesFurtherCapture();
		assertions += verifyCloseDiscardsPartialSpeechAndClosesDecoder();
		return assertions;
	}

	private static int verifySilenceFlushesOneOrderedUtterance() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ScheduledExecutorService scheduler = scheduler();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler, 20L, 32);
		List<Delivered> delivered = new ArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) -> {
			delivered.add(new Delivered(playerId, text, whispering));
			latch.countDown();
		};
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(PLAYER, false, new byte[] { 1, 2 }, () -> decoder(decoders), Runnable::run, delivery);
		engine.accept(PLAYER, false, new byte[] { 3 }, () -> decoder(decoders), Runnable::run, delivery);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "silence flush completed");
		Captured captured = transcriber.captured.getFirst();
		assertEquals(1L, captured.sequence, "first utterance sequence");
		assertEquals(false, captured.whispering, "normal speech state");
		assertEquals(true, Arrays.equals(new short[] { 1, 2, 3 }, captured.samples), "ordered decoded samples");
		assertEquals(List.of(new Delivered(PLAYER, "heard 3", false)), delivered, "transcript delivered once");
		assertEquals(true, decoders.remove().closed, "decoder closes after silence");
		engine.close();
		return 6;
	}

	private static int verifyWhisperChangeSplitsAndSequencesUtterances() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 20L, 32);
		CountDownLatch latch = new CountDownLatch(2);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) -> {
			delivered.add(new Delivered(playerId, text, whispering));
			latch.countDown();
		};
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(PLAYER, false, new byte[] { 4 }, () -> decoder(decoders), Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 5, 6 }, () -> decoder(decoders), Runnable::run, delivery);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "whisper split delivered both utterances");
		assertEquals(2, transcriber.captured.size(), "whisper split transcription count");
		assertEquals(1L, transcriber.captured.get(0).sequence, "normal utterance sequence");
		assertEquals(2L, transcriber.captured.get(1).sequence, "whisper utterance sequence");
		assertEquals(false, transcriber.captured.get(0).whispering, "first utterance normal");
		assertEquals(true, transcriber.captured.get(1).whispering, "second utterance whispering");
		assertEquals(true, decoders.remove().closed, "normal decoder closes on whisper change");
		assertEquals(true, decoders.remove().closed, "whisper decoder closes on silence");
		engine.close();
		return 8;
	}

	private static int verifyMaximumDurationBoundsDecodedSamples() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 3);
		CountDownLatch latch = new CountDownLatch(1);
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(
				PLAYER,
				false,
				new byte[] { 7, 8, 9, 10 },
				() -> decoder(decoders),
				Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "sample cap flush completed");
		assertEquals(true, Arrays.equals(new short[] { 7, 8, 9 }, transcriber.captured.getFirst().samples),
				"sample cap truncates decoded frame");
		assertEquals(true, decoders.remove().closed, "decoder closes at sample cap");
		engine.close();
		return 3;
	}

	private static int verifyMalformedPacketDoesNotWedgeLaterSpeech() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 20L, 32);
		RecordingDecoder broken = new RecordingDecoder();
		broken.decodeFailure = new IllegalArgumentException("bad Opus frame");
		RecordingDecoder recovered = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(broken, recovered));
		engine.accept(
				PLAYER, false, new byte[] { 1 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { }
		);
		CountDownLatch latch = new CountDownLatch(1);
		engine.accept(
				PLAYER, false, new byte[] { 12 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, broken.closed, "malformed packet closes broken decoder");
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "speech recovers after malformed packet");
		assertEquals(true, Arrays.equals(new short[] { 12 }, transcriber.captured.getFirst().samples),
				"recovered utterance excludes malformed packet");
		engine.close();
		return 3;
	}

	private static int verifyTranscriptsDeliverInUtteranceOrder() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, delivery);

		transcriber.complete(2L, "second");
		assertEquals(List.of(), delivered, "later transcript waits for the prior utterance");
		transcriber.complete(1L, "first");
		assertEquals(
				List.of(new Delivered(PLAYER, "first", false), new Delivered(PLAYER, "second", true)),
				delivered,
				"transcripts deliver in captured utterance order"
		);
		engine.close();
		return 2;
	}

	private static int verifyUnavailableSttDisablesFurtherCapture() {
		int[] transcriptions = { 0 };
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			transcriptions[0]++;
			return CompletableFuture.failedFuture(new VoiceWorkerClient.VoiceWorkerException(
					"STT_UNAVAILABLE", "Speech recognition is not configured"
			));
		}, scheduler(), 5_000L, 1);
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("disabled capture must not create another decoder");
		}, Runnable::run, (playerId, text, whispering) -> { });
		assertEquals(1, transcriptions[0], "STT_UNAVAILABLE permanently disables this capture engine");
		engine.close();
		return 1;
	}

	private static int verifyCloseDiscardsPartialSpeechAndClosesDecoder() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 32);
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(
				PLAYER,
				false,
				new byte[] { 11 },
				() -> decoder(decoders),
				Runnable::run,
				(playerId, text, whispering) -> { throw new AssertionError("closed partial speech must not deliver"); }
		);
		RecordingDecoder decoder = decoders.remove();
		engine.close();
		assertEquals(true, decoder.closed, "close closes active decoder");
		assertEquals(0, transcriber.captured.size(), "close discards partial utterance");
		return 2;
	}

	private static ScheduledExecutorService scheduler() {
		return Executors.newSingleThreadScheduledExecutor(
				runnable -> Thread.ofPlatform().daemon().name("voice-capture-verification").unstarted(runnable)
		);
	}

	private static RecordingDecoder decoder(Queue<RecordingDecoder> decoders) {
		RecordingDecoder decoder = new RecordingDecoder();
		decoders.add(decoder);
		return decoder;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static final class RecordingTranscriber implements SpeechCaptureEngine.Transcriber {
		private final List<Captured> captured = new ArrayList<>();

		@Override
		public CompletableFuture<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		) {
			captured.add(new Captured(playerId, utteranceSequence, whispering, samples.clone()));
			return CompletableFuture.completedFuture(new SpeechWorkerClient.Transcript("heard " + samples.length, 0.9));
		}
	}

	private static final class ControlledTranscriber implements SpeechCaptureEngine.Transcriber {
		private final java.util.Map<Long, CompletableFuture<SpeechWorkerClient.Transcript>> pending =
				new java.util.LinkedHashMap<>();

		@Override
		public CompletableFuture<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		) {
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(utteranceSequence, future);
			return future;
		}

		private void complete(long sequence, String text) {
			pending.get(sequence).complete(new SpeechWorkerClient.Transcript(text, 0.9));
		}
	}

	private static final class RecordingDecoder implements SpeechCaptureEngine.Decoder {
		private boolean closed;
		private RuntimeException decodeFailure;

		@Override
		public short[] decode(byte[] opus) {
			if (decodeFailure != null) throw decodeFailure;
			short[] decoded = new short[opus.length];
			for (int index = 0; index < opus.length; index++) decoded[index] = opus[index];
			return decoded;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private record Captured(UUID playerId, long sequence, boolean whispering, short[] samples) {
	}

	private record Delivered(UUID playerId, String text, boolean whispering) {
	}
}
