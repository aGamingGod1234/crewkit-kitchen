package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class SpeechCaptureEngine implements AutoCloseable {
	private static final long STT_RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5L);
	private static final long INITIAL_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
	private static final long MAX_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
	private final Transcriber transcriber;
	private final ScheduledExecutorService scheduler;
	private final long silenceMilliseconds;
	private final int maxSamples;
	private final Consumer<InputLatency> latencyObserver;
	private final LongSupplier monotonicNanos;
	private final Map<UUID, Utterance> utterances = new LinkedHashMap<>();
	private final Map<UUID, Long> sequences = new LinkedHashMap<>();
	private final Map<UUID, TranscriptQueue> transcriptQueues = new LinkedHashMap<>();
	private final Set<CompletableFuture<SpeechWorkerClient.Transcript>> transcriptions = new LinkedHashSet<>();
	private boolean closed;
	private long sttRetryAfterNanos;
	private int decoderFailures;
	private long decoderRetryAfterNanos;

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples
	) {
		this(transcriber, scheduler, silenceMilliseconds, maxSamples, ignored -> { }, System::nanoTime);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples,
			Consumer<InputLatency> latencyObserver
	) {
		this(transcriber, scheduler, silenceMilliseconds, maxSamples, latencyObserver, System::nanoTime);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples,
			Consumer<InputLatency> latencyObserver,
			LongSupplier monotonicNanos
	) {
		this.transcriber = Objects.requireNonNull(transcriber, "transcriber must not be null");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
		if (silenceMilliseconds < 1L) throw new IllegalArgumentException("silenceMilliseconds must be positive");
		if (maxSamples < 1) throw new IllegalArgumentException("maxSamples must be positive");
		this.silenceMilliseconds = silenceMilliseconds;
		this.maxSamples = maxSamples;
		this.latencyObserver = Objects.requireNonNull(latencyObserver, "latencyObserver must not be null");
		this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos must not be null");
	}

	void accept(
			UUID playerId,
			boolean whispering,
			byte[] opus,
			DecoderFactory decoderFactory,
			Executor deliveryExecutor,
			TranscriptDelivery delivery
	) {
		Objects.requireNonNull(playerId, "playerId must not be null");
		Objects.requireNonNull(opus, "opus must not be null");
		Objects.requireNonNull(decoderFactory, "decoderFactory must not be null");
		Objects.requireNonNull(deliveryExecutor, "deliveryExecutor must not be null");
		Objects.requireNonNull(delivery, "delivery must not be null");
		List<CompletedUtterance> completed = new ArrayList<>(2);
		synchronized (this) {
			long now = monotonicNanos.getAsLong();
			if (closed || now < sttRetryAfterNanos || now < decoderRetryAfterNanos) return;
			Utterance utterance = utterances.get(playerId);
			if (utterance != null && utterance.whispering != whispering) {
				completed.add(finishLocked(playerId, utterance));
				utterance = null;
			}
			if (utterance == null) {
				utterance = new Utterance(
						playerId,
						Objects.requireNonNull(decoderFactory.create(), "decoderFactory returned null"),
						whispering,
						sequences.merge(playerId, 1L, Long::sum),
						deliveryExecutor,
						delivery,
						maxSamples
				);
				utterances.put(playerId, utterance);
			}
			short[] decoded;
			try {
				decoded = Objects.requireNonNull(utterance.decoder.decode(opus), "decoder returned null");
			} catch (RuntimeException ignored) {
				completed.add(discardLocked(playerId, utterance));
				recordDecoderFailureLocked(now);
				decoded = null;
			}
			if (decoded != null) {
				decoderFailures = 0;
				decoderRetryAfterNanos = 0L;
				utterance.append(decoded);
				utterance.lastPacketNanos = System.nanoTime();
				if (utterance.timeout != null) utterance.timeout.cancel(false);
				Utterance current = utterance;
				long timeoutEpoch = ++utterance.timeoutEpoch;
				utterance.timeout = scheduler.schedule(
						() -> finishIfCurrent(playerId, current, timeoutEpoch),
						silenceMilliseconds,
						TimeUnit.MILLISECONDS
				);
				if (utterance.length >= maxSamples) completed.add(finishLocked(playerId, utterance));
			}
		}
		for (CompletedUtterance utterance : completed) transcribe(utterance);
	}

	private void recordDecoderFailureLocked(long now) {
		decoderFailures = Math.min(decoderFailures + 1, 31);
		long multiplier = 1L << Math.min(decoderFailures - 1, 5);
		long delay = Math.min(MAX_DECODER_RETRY_NANOS, INITIAL_DECODER_RETRY_NANOS * multiplier);
		decoderRetryAfterNanos = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
	}

	private void finishIfCurrent(UUID playerId, Utterance expected, long timeoutEpoch) {
		CompletedUtterance completed;
		synchronized (this) {
			if (closed || utterances.get(playerId) != expected || expected.timeoutEpoch != timeoutEpoch) return;
			completed = finishLocked(playerId, expected);
		}
		transcribe(completed);
	}

	private CompletedUtterance finishLocked(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.timeoutEpoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		boolean decoderClosed = closeDecoder(utterance.decoder);
		return new CompletedUtterance(
				playerId,
				utterance.sequence,
				utterance.whispering,
				decoderClosed ? Arrays.copyOf(utterance.samples, utterance.length) : new short[0],
				utterance.deliveryExecutor,
				utterance.delivery,
				utterance.lastPacketNanos,
				System.nanoTime()
		);
	}

	private CompletedUtterance discardLocked(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.timeoutEpoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		closeDecoder(utterance.decoder);
		return new CompletedUtterance(
				playerId,
				utterance.sequence,
				utterance.whispering,
				new short[0],
				utterance.deliveryExecutor,
				utterance.delivery,
				utterance.lastPacketNanos,
				System.nanoTime()
		);
	}

	private static boolean closeDecoder(Decoder decoder) {
		try {
			decoder.close();
			return true;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private void transcribe(CompletedUtterance utterance) {
		if (utterance.samples.length == 0) {
			completeTranscription(utterance, null, null);
			return;
		}
		long transcriptionStartedNanos = System.nanoTime();
		CompletableFuture<SpeechWorkerClient.Transcript> transcription;
		try {
			transcription = Objects.requireNonNull(Objects.requireNonNull(transcriber.transcribe(
					utterance.playerId,
					utterance.sequence,
					utterance.whispering,
					utterance.samples
			), "transcriber returned null").toCompletableFuture(), "transcriber returned a null future");
		} catch (RuntimeException failure) {
			reportLatency(utterance, transcriptionStartedNanos, System.nanoTime());
			completeTranscription(utterance, null, failure);
			return;
		}
		synchronized (this) {
			if (closed) {
				transcription.cancel(true);
				return;
			}
			transcriptions.add(transcription);
		}
		transcription.whenComplete((transcript, failure) -> {
			synchronized (this) {
				transcriptions.remove(transcription);
			}
			reportLatency(utterance, transcriptionStartedNanos, System.nanoTime());
			completeTranscription(utterance, transcript, failure);
		});
	}

	private void reportLatency(CompletedUtterance utterance, long transcriptionStartedNanos, long completedNanos) {
		long endpointNanos = Math.max(0L, utterance.endpointCompletedNanos - utterance.lastPacketNanos);
		long transcriptionNanos = Math.max(0L, completedNanos - transcriptionStartedNanos);
		long totalNanos = Math.max(0L, completedNanos - utterance.lastPacketNanos);
		try {
			latencyObserver.accept(new InputLatency(
					utterance.playerId,
					utterance.sequence,
					TimeUnit.NANOSECONDS.toMillis(endpointNanos),
					TimeUnit.NANOSECONDS.toMillis(transcriptionNanos),
					TimeUnit.NANOSECONDS.toMillis(totalNanos)
			));
		} catch (RuntimeException ignored) {
			// Timing diagnostics must never interrupt speech delivery.
		}
	}

	private void completeTranscription(
			CompletedUtterance utterance,
			SpeechWorkerClient.Transcript transcript,
			Throwable failure
	) {
		List<TranscriptOutcome> ready = new ArrayList<>();
		synchronized (this) {
			if (closed) return;
			if (isSttUnavailable(failure)) {
				sttRetryAfterNanos = monotonicNanos.getAsLong() + STT_RETRY_BACKOFF_NANOS;
				recordSkippedLocked(utterance);
				for (Utterance active : utterances.values()) {
					if (active.timeout != null) active.timeout.cancel(false);
					try {
						active.decoder.close();
					} catch (RuntimeException ignored) {
					}
					recordSkippedLocked(new CompletedUtterance(
							active.playerId, active.sequence, active.whispering, new short[0],
							active.deliveryExecutor, active.delivery, active.lastPacketNanos, monotonicNanos.getAsLong()
					));
				}
				utterances.clear();
				return;
			}
			if (failure == null) sttRetryAfterNanos = 0L;
			TranscriptQueue queue = transcriptQueues.computeIfAbsent(utterance.playerId, ignored -> new TranscriptQueue());
			queue.completed.put(utterance.sequence, new TranscriptOutcome(utterance, failure == null ? transcript : null));
			while (true) {
				TranscriptOutcome outcome = queue.completed.remove(queue.nextSequence);
				if (outcome == null) break;
				queue.nextSequence++;
				if (outcome.transcript != null && !outcome.transcript.text().isBlank()) ready.add(outcome);
			}
		}
		if (ready.isEmpty()) return;
		try {
			ready.getFirst().utterance.deliveryExecutor.execute(() -> {
				for (TranscriptOutcome outcome : ready) {
					outcome.utterance.delivery.deliver(
							outcome.utterance.playerId,
							outcome.transcript.text(),
							outcome.utterance.whispering
					);
				}
			});
		} catch (RuntimeException ignored) {
			// The Minecraft server may be stopping while transcription completes.
		}
	}

	private void recordSkippedLocked(CompletedUtterance utterance) {
		TranscriptQueue queue = transcriptQueues.computeIfAbsent(utterance.playerId, ignored -> new TranscriptQueue());
		queue.completed.put(utterance.sequence, new TranscriptOutcome(utterance, null));
		while (queue.completed.remove(queue.nextSequence) != null) queue.nextSequence++;
	}

	private static boolean isSttUnavailable(Throwable failure) {
		Throwable current = failure;
		while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
		return current instanceof VoiceWorkerClient.VoiceWorkerException workerFailure
				&& workerFailure.code().equals("STT_UNAVAILABLE");
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		for (Utterance utterance : utterances.values()) {
			utterance.timeoutEpoch++;
			if (utterance.timeout != null) utterance.timeout.cancel(false);
			closeDecoder(utterance.decoder);
		}
		utterances.clear();
		transcriptQueues.clear();
		for (CompletableFuture<SpeechWorkerClient.Transcript> transcription : List.copyOf(transcriptions)) {
			transcription.cancel(true);
		}
		transcriptions.clear();
		scheduler.shutdownNow();
	}

	@FunctionalInterface
	interface Transcriber {
		CompletionStage<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		);
	}

	@FunctionalInterface
	interface DecoderFactory {
		Decoder create();
	}

	interface Decoder {
		short[] decode(byte[] opus);

		void close();
	}

	@FunctionalInterface
	interface TranscriptDelivery {
		void deliver(UUID playerId, String transcript, boolean whispering);
	}

	private static final class Utterance {
		private final UUID playerId;
		private final Decoder decoder;
		private final boolean whispering;
		private final long sequence;
		private final Executor deliveryExecutor;
		private final TranscriptDelivery delivery;
		private final int maxSamples;
		private short[] samples;
		private int length;
		private long lastPacketNanos;
		private ScheduledFuture<?> timeout;
		private long timeoutEpoch;

		private Utterance(
				UUID playerId,
				Decoder decoder,
				boolean whispering,
				long sequence,
				Executor deliveryExecutor,
				TranscriptDelivery delivery,
				int maxSamples
		) {
			this.playerId = playerId;
			this.decoder = decoder;
			this.whispering = whispering;
			this.sequence = sequence;
			this.deliveryExecutor = deliveryExecutor;
			this.delivery = delivery;
			this.maxSamples = maxSamples;
			this.samples = new short[Math.min(48_000, maxSamples)];
		}

		private void append(short[] decoded) {
			int accepted = Math.min(decoded.length, maxSamples - length);
			if (accepted <= 0) return;
			int required = length + accepted;
			if (required > samples.length) {
				samples = Arrays.copyOf(samples, Math.min(maxSamples, Math.max(required, samples.length * 2)));
			}
			System.arraycopy(decoded, 0, samples, length, accepted);
			length = required;
		}
	}

	private record CompletedUtterance(
			UUID playerId,
			long sequence,
			boolean whispering,
			short[] samples,
			Executor deliveryExecutor,
			TranscriptDelivery delivery,
			long lastPacketNanos,
			long endpointCompletedNanos
	) {
	}

	record InputLatency(
			UUID playerId,
			long utteranceSequence,
			long endpointMilliseconds,
			long transcriptionMilliseconds,
			long totalMilliseconds
	) {
	}

	private static final class TranscriptQueue {
		private long nextSequence = 1L;
		private final Map<Long, TranscriptOutcome> completed = new LinkedHashMap<>();
	}

	private record TranscriptOutcome(
			CompletedUtterance utterance,
			SpeechWorkerClient.Transcript transcript
	) {
	}
}
