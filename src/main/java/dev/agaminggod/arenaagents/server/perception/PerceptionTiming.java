package dev.agaminggod.arenaagents.server.perception;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread cost of perception passes, logged only when the JVM runs with {@code -Darenaagents.perceptionTiming=true}.
 * Measurement runs read the log; production servers pay one boolean check per pass.
 */
final class PerceptionTiming {
	private static final Logger LOGGER = LoggerFactory.getLogger(PerceptionTiming.class);
	static final boolean ENABLED = Boolean.getBoolean("arenaagents.perceptionTiming");
	private static final int WINDOW = 200;
	private static final Map<String, long[]> SAMPLES = new HashMap<>();
	private static final Map<String, Integer> COUNTS = new HashMap<>();

	private PerceptionTiming() {
	}

	static long start() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	/** Records one pass; every {@value #WINDOW} passes logs the mean, p95 and maximum of the window. */
	static void record(String pass, long startedAt) {
		if (!ENABLED) return;
		long elapsed = System.nanoTime() - startedAt;
		synchronized (SAMPLES) {
			long[] window = SAMPLES.computeIfAbsent(pass, ignored -> new long[WINDOW]);
			int count = COUNTS.merge(pass, 1, Integer::sum);
			window[(count - 1) % WINDOW] = elapsed;
			if (count % WINDOW != 0) return;
			long[] sorted = window.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(sorted).average().orElse(0.0D);
			LOGGER.info("perception timing {}: n={} mean={}us p95={}us max={}us", pass, WINDOW, Math.round(mean / 1_000.0D),
					sorted[(int) Math.ceil(WINDOW * 0.95D) - 1] / 1_000L, sorted[WINDOW - 1] / 1_000L);
		}
	}
}
