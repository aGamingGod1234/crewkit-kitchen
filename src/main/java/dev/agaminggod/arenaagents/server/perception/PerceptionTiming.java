package dev.agaminggod.arenaagents.server.perception;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread cost of perception passes, logged only when the JVM runs with {@code -Darenaagents.perceptionTiming=true}.
 * Measurement runs read the log; production servers pay one boolean check per pass. Counts (candidates, cache hits)
 * are logged the same way so they never reach the model.
 */
final class PerceptionTiming {
	private static final Logger LOGGER = LoggerFactory.getLogger(PerceptionTiming.class);
	static final boolean ENABLED = Boolean.getBoolean("arenaagents.perceptionTiming");
	private static final int WINDOW = 200;
	private static final Map<String, long[]> SAMPLES = new HashMap<>();
	private static final Map<String, Integer> COUNTS = new HashMap<>();
	private static final Map<String, long[]> TICK_TOTALS = new HashMap<>();

	private PerceptionTiming() {
	}

	static long start() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	/** Records one pass; every {@value #WINDOW} passes logs the mean, p95 and maximum of the window. */
	static void record(String pass, long startedAt) {
		if (!ENABLED) return;
		sample(pass, System.nanoTime() - startedAt, 1_000L, "us");
	}

	/**
	 * Records one pass and adds it to the total for its game tick; each finished tick with at least one pass is a sample
	 * of {@code pass + "_tick"}, the cost that tick paid for this kind of pass.
	 */
	static void recordTick(String pass, long gameTime, long startedAt) {
		if (!ENABLED) return;
		long elapsed = System.nanoTime() - startedAt;
		sample(pass, elapsed, 1_000L, "us");
		long finished = -1L;
		synchronized (TICK_TOTALS) {
			long[] total = TICK_TOTALS.computeIfAbsent(pass, ignored -> new long[] {gameTime, 0L});
			if (total[0] != gameTime) {
				finished = total[1];
				total[0] = gameTime;
				total[1] = 0L;
			}
			total[1] += elapsed;
		}
		if (finished >= 0L) sample(pass + "_tick", finished, 1_000L, "us");
	}

	/** Records a count (candidates, cache hits as 0 or 1) with the same window statistics. */
	static void count(String name, long value) {
		if (!ENABLED) return;
		sample(name, value * 1_000L, 1_000L, "");
	}

	private static void sample(String name, long value, long divisor, String unit) {
		synchronized (SAMPLES) {
			long[] window = SAMPLES.computeIfAbsent(name, ignored -> new long[WINDOW]);
			int count = COUNTS.merge(name, 1, Integer::sum);
			window[(count - 1) % WINDOW] = value;
			if (count % WINDOW != 0) return;
			long[] sorted = window.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(sorted).average().orElse(0.0D);
			LOGGER.info("perception timing {}: n={} mean={}{} p95={}{} max={}{}", name, WINDOW,
					Math.round(mean / divisor * 100.0D) / 100.0D, unit, sorted[(int) Math.ceil(WINDOW * 0.95D) - 1] / divisor, unit,
					sorted[WINDOW - 1] / divisor, unit);
		}
	}
}
