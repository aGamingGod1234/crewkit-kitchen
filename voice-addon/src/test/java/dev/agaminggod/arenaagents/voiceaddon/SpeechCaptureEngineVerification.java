package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class SpeechCaptureEngineVerification {
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");

	private SpeechCaptureEngineVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyProductionSpeechEndpointFlushesWithinBudget();
		assertions += verifyInputLatencyReportsOneCompletedUtterance();
		assertions += verifySilenceFlushesOneOrderedUtterance();
		assertions += verifyCanceledRunningSilenceTimerCannotFinishNewerAudio();
		assertions += verifyWhisperChangeSplitsAndSequencesUtterances();
		assertions += verifyMaximumDurationBoundsDecodedSamples();
		assertions += verifyMalformedPacketDoesNotWedgeLaterSpeech();
		assertions += verifyDecoderCloseFailureDoesNotWedgeLaterSpeech();
		assertions += verifyTranscriptsDeliverInUtteranceOrder();
		assertions += verifyUnavailableSttRecoversAfterBackoff();
		assertions += verifyCloseCancelsPendingTranscription();
		assertions += verifyCloseDiscardsPartialSpeechAndClosesDecoder();
		assertions += verifyCloseContinuesAfterDecoderCloseFailure();
		return assertions;
	}

	private static int verifyCanceledRunningSilenceTimerCannotFinishNewerAudio() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler, 20L, 32);
		RecordingDecoder first = new RecordingDecoder();
		RecordingDecoder afterClose = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(first, afterClose));

		engine.accept(PLAYER, false, new byte[] { 1 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 2 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(true, scheduler.tasks.get(0).isCancelled(), "new packet cancels the prior silence timer");
		scheduler.runEvenIfCancelled(0);
		assertEquals(0, transcriber.captured.size(), "canceled running timer cannot finish newer audio");
		assertEquals(false, first.closed, "canceled running timer cannot close the current decoder");

		scheduler.runEvenIfCancelled(1);
		assertEquals(1, transcriber.captured.size(), "current silence timer finishes exactly once");
		assertEquals(true, Arrays.equals(new short[] { 1, 2 }, transcriber.captured.getFirst().samples),
				"current timer preserves every packet in its epoch");

		engine.accept(PLAYER, false, new byte[] { 3 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.close();
		scheduler.runEvenIfCancelled(2);
		assertEquals(1, transcriber.captured.size(), "closed generation fences a captured timer");
		return 6;
	}

	private static int verifyInputLatencyReportsOneCompletedUtterance() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		List<SpeechCaptureEngine.InputLatency> latencies = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 5_000L, 1, latencies::add
		);
		engine.accept(
				PLAYER, true, new byte[] { 7 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { }
		);
		assertEquals(1, latencies.size(), "one input latency sample");
		SpeechCaptureEngine.InputLatency latency = latencies.getFirst();
		assertEquals(PLAYER, latency.playerId(), "input latency player identity");
		assertEquals(1L, latency.utteranceSequence(), "input latency utterance sequence");
		assertEquals(true, latency.endpointMilliseconds() >= 0L, "input endpoint latency is non-negative");
		assertEquals(true, latency.transcriptionMilliseconds() >= 0L,
				"input transcription latency is non-negative");
		assertEquals(true, latency.totalMilliseconds() >= latency.transcriptionMilliseconds(),
				"input total latency includes transcription");
		engine.close();
		return 6;
	}

	private static int verifyProductionSpeechEndpointFlushesWithinBudget() throws Exception {
		var field = HumanSpeechCapture.class.getDeclaredField("SILENCE_MILLISECONDS");
		field.setAccessible(true);
		long productionSilenceMilliseconds = field.getLong(null);
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), productionSilenceMilliseconds, 32
		);
		CountDownLatch latch = new CountDownLatch(1);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, latch.await(550, TimeUnit.MILLISECONDS),
				"production speech endpoint flushes within the conversational latency budget");
		engine.close();
		return 1;
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
		long[] now = { 0L };
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 20L, 32, ignored -> { }, () -> now[0]
		);
		RecordingDecoder broken = new RecordingDecoder();
		broken.decodeFailure = new IllegalArgumentException("bad Opus frame");
		RecordingDecoder recovered = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(broken, recovered));
		int[] decoderCreations = { 0 };
		SpeechCaptureEngine.DecoderFactory decoderFactory = () -> {
			decoderCreations[0]++;
			return decoders.remove();
		};
		engine.accept(
				PLAYER, false, new byte[] { 1 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { }
		);
		CountDownLatch latch = new CountDownLatch(1);
		engine.accept(
				PLAYER, false, new byte[] { 2 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(1, decoderCreations[0], "decoder failure enters a packet-safe cooldown");
		now[0] = TimeUnit.SECONDS.toNanos(60L);
		engine.accept(
				PLAYER, false, new byte[] { 12 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, broken.closed, "malformed packet closes broken decoder");
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "speech recovers after malformed packet");
		assertEquals(2, decoderCreations[0], "decoder is reconstructed once after the retry deadline");
		assertEquals(true, Arrays.equals(new short[] { 12 }, transcriber.captured.getFirst().samples),
				"recovered utterance excludes malformed packet");
		engine.close();
		return 5;
	}

	private static int verifyDecoderCloseFailureDoesNotWedgeLaterSpeech() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 20L, 32);
		RecordingDecoder broken = new RecordingDecoder();
		broken.closeFailure = new IllegalStateException("decoder close failed");
		broken.closeAttempted = new CountDownLatch(1);
		RecordingDecoder recovered = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(broken, recovered));
		List<Delivered> delivered = new ArrayList<>();
		CountDownLatch deliveredLatch = new CountDownLatch(1);

		engine.accept(
				PLAYER, false, new byte[] { 1 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		assertEquals(true, broken.closeAttempted.await(2, TimeUnit.SECONDS),
				"silence flush attempts the failing decoder close");
		engine.accept(
				PLAYER, true, new byte[] { 2 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> {
					delivered.add(new Delivered(playerId, text, whispering));
					deliveredLatch.countDown();
				}
		);

		assertEquals(true, broken.closed, "failed decoder close is attempted once");
		assertEquals(true, deliveredLatch.await(2, TimeUnit.SECONDS),
				"later transcript is not wedged behind the failed silence flush");
		assertEquals(1, transcriber.captured.size(), "failed utterance is skipped before transcription");
		assertEquals(2L, transcriber.captured.getFirst().sequence,
				"later utterance keeps its monotonic sequence");
		assertEquals(List.of(new Delivered(PLAYER, "heard 1", true)), delivered,
				"later transcript delivers after the skipped close failure");
		engine.close();
		return 6;
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

	private static int verifyUnavailableSttRecoversAfterBackoff() {
		int[] transcriptions = { 0 };
		int[] decoders = { 0 };
		long[] now = { 0L };
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			transcriptions[0]++;
			return transcriptions[0] == 1
					? CompletableFuture.failedFuture(new VoiceWorkerClient.VoiceWorkerException(
							"STT_UNAVAILABLE", "Speech recognition is not configured"))
					: CompletableFuture.completedFuture(new SpeechWorkerClient.Transcript("recovered", 0.9));
		}, scheduler(), 5_000L, 1, ignored -> { }, () -> now[0]);
		SpeechCaptureEngine.DecoderFactory decoderFactory = () -> {
			decoders[0]++;
			return new RecordingDecoder();
		};
		engine.accept(PLAYER, false, new byte[] { 1 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		engine.accept(PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("capture must respect the bounded STT backoff");
		}, Runnable::run, (playerId, text, whispering) -> { });
		now[0] = TimeUnit.SECONDS.toNanos(5L);
		engine.accept(PLAYER, false, new byte[] { 3 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		assertEquals(2, transcriptions[0], "STT capture probes again after its bounded backoff");
		assertEquals(2, decoders[0], "backoff drops packets without decoding and recovery creates one decoder");
		assertEquals(List.of(new Delivered(PLAYER, "recovered", false)), delivered,
				"the recovered transcript remains sequenced after the unavailable utterance");
		engine.close();
		return 3;
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

	private static int verifyCloseContinuesAfterDecoderCloseFailure() {
		ScheduledExecutorService scheduler = scheduler();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				new RecordingTranscriber(), scheduler, 5_000L, 32
		);
		RecordingDecoder broken = new RecordingDecoder();
		broken.closeFailure = new IllegalStateException("first decoder close failed");
		RecordingDecoder later = new RecordingDecoder();
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> broken, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(UUID.randomUUID(), false, new byte[] { 2 }, () -> later, Runnable::run,
				(playerId, text, whispering) -> { });

		long startedNanos = System.nanoTime();
		engine.close();
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

		assertEquals(true, broken.closed, "close attempts the failing decoder");
		assertEquals(true, later.closed, "close continues to later decoders");
		assertEquals(true, scheduler.isShutdown(), "close still shuts down the scheduler");
		assertEquals(true, elapsedMillis < 1_000L, "close remains bounded after cleanup failure");
		return 4;
	}

	private static int verifyCloseCancelsPendingTranscription() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		engine.accept(
				PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		CompletableFuture<SpeechWorkerClient.Transcript> firstPending = transcriber.pending.get(1L);
		CompletableFuture<SpeechWorkerClient.Transcript> secondPending = transcriber.pending.get(2L);
		engine.close();
		assertEquals(true, firstPending.isCancelled(), "close cancels the first pending STT request");
		assertEquals(true, secondPending.isCancelled(), "close cancels the replacement STT request");
		assertEquals(List.of(), delivered, "a late cancelled transcript cannot deliver after close");
		return 3;
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
		private RuntimeException closeFailure;
		private CountDownLatch closeAttempted;

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
			if (closeAttempted != null) closeAttempted.countDown();
			if (closeFailure != null) throw closeFailure;
		}
	}

	private static final class ManualScheduledExecutor extends AbstractExecutorService
			implements ScheduledExecutorService {
		private final List<ManualFuture> tasks = new ArrayList<>();
		private boolean shutdown;

		private void runEvenIfCancelled(int index) {
			tasks.get(index).runEvenIfCancelled();
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			ManualFuture future = new ManualFuture(command);
			tasks.add(future);
			return future;
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(
				Runnable command, long initialDelay, long period, TimeUnit unit
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(
				Runnable command, long initialDelay, long delay, TimeUnit unit
		) {
			throw new UnsupportedOperationException();
		}

		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
		@Override public void execute(Runnable command) { command.run(); }
	}

	private static final class ManualFuture implements ScheduledFuture<Object> {
		private final Runnable command;
		private boolean cancelled;
		private boolean done;

		private ManualFuture(Runnable command) {
			this.command = command;
		}

		private void runEvenIfCancelled() {
			command.run();
			done = true;
		}

		@Override public long getDelay(TimeUnit unit) { return 0L; }
		@Override public int compareTo(Delayed other) { return 0; }
		@Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
		@Override public boolean isCancelled() { return cancelled; }
		@Override public boolean isDone() { return done; }
		@Override public Object get() { return null; }
		@Override public Object get(long timeout, TimeUnit unit) { return null; }
	}

	private record Captured(UUID playerId, long sequence, boolean whispering, short[] samples) {
	}

	private record Delivered(UUID playerId, String text, boolean whispering) {
	}
}
