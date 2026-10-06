package dev.agaminggod.arenaagents.client.pov;

import java.util.function.Consumer;

/**
 * Debug-only takeover latency probe, enabled with {@code -Darenaagents.povLatencyLog=true}. It times each input
 * frame from the moment it is sent until a pose arrives that the server produced after applying it (the pose
 * echoes the last applied frame sequence), which covers client send, server queueing, the server tick with its
 * physics, and the trip back. Every {@link #WINDOW} samples it reports the average and worst round trip.
 */
public final class PovLatencyProbe {
	public static final boolean ENABLED = Boolean.getBoolean("arenaagents.povLatencyLog");
	static final int WINDOW = 40;
	private static final int RING = 128;

	private final int[] sequences = new int[RING];
	private final long[] sentNanos = new long[RING];
	private final Consumer<String> report;
	private int lastAcknowledged;
	private int samples;
	private long totalNanos;
	private long worstNanos;

	public PovLatencyProbe(Consumer<String> report) {
		this.report = report;
	}

	public void sent(int sequence, long nowNanos) {
		int slot = Math.floorMod(sequence, RING);
		sequences[slot] = sequence;
		sentNanos[slot] = nowNanos;
	}

	/** Returns the measured round trip in nanoseconds, or -1 when the pose acknowledges nothing new. */
	public long acknowledged(int sequence, long nowNanos) {
		if (sequence <= 0 || sequence == lastAcknowledged) return -1L;
		lastAcknowledged = sequence;
		int slot = Math.floorMod(sequence, RING);
		if (sequences[slot] != sequence) return -1L;
		long elapsed = Math.max(0L, nowNanos - sentNanos[slot]);
		samples++;
		totalNanos += elapsed;
		worstNanos = Math.max(worstNanos, elapsed);
		if (samples >= WINDOW) {
			report.accept(String.format(java.util.Locale.ROOT,
					"Takeover input round trip over %d frames: average %.1f ms, worst %.1f ms",
					samples, totalNanos / (samples * 1.0E6D), worstNanos / 1.0E6D));
			samples = 0;
			totalNanos = 0L;
			worstNanos = 0L;
		}
		return elapsed;
	}

	public void reset() {
		java.util.Arrays.fill(sequences, 0);
		lastAcknowledged = 0;
		samples = 0;
		totalNanos = 0L;
		worstNanos = 0L;
	}
}
