package dev.agaminggod.arenaagents.server.perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-agent debounce for threat signals ("uuid:signal" keys). A signal stays reported for
 * {@link #HOLD_TICKS} after its condition last held, so a creeper that swells and relaxes, or a skeleton
 * that steps behind a tree, raises one attention edge instead of one per flicker. A signal is dropped at
 * once when its mob is no longer present (dead, despawned or out of range).
 */
public final class ThreatSignalLatch {
	public static final long HOLD_TICKS = 100L;

	private final Map<String, Long> lastTrue = new HashMap<>();

	public Set<String> update(Set<String> rawKeys, Set<String> presentThreatIds, long tick) {
		Objects.requireNonNull(rawKeys, "rawKeys must not be null");
		Objects.requireNonNull(presentThreatIds, "presentThreatIds must not be null");
		for (String key : rawKeys) lastTrue.put(key, tick);
		lastTrue.entrySet().removeIf(entry -> !presentThreatIds.contains(threatId(entry.getKey()))
				|| tick - entry.getValue() > HOLD_TICKS || entry.getValue() > tick);
		return new TreeSet<>(lastTrue.keySet());
	}

	public static String key(String threatId, String signal) {
		return threatId + ":" + signal;
	}

	public static String threatId(String key) {
		int separator = key.lastIndexOf(':');
		return separator < 0 ? key : key.substring(0, separator);
	}

	public static String signal(String key) {
		int separator = key.lastIndexOf(':');
		return separator < 0 ? "" : key.substring(separator + 1);
	}
}
