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
 * once when its mob is no longer present: dead, despawned, or beyond the wider release range (20 blocks),
 * which gives hysteresis against the 16-block detection range.
 */
public final class ThreatSignalLatch {
	public static final long HOLD_TICKS = 100L;

	private final Map<String, Long> lastTrue = new HashMap<>();
	private final Map<String, Long> firstTrue = new HashMap<>();

	public Set<String> update(Set<String> rawKeys, Set<String> presentThreatIds, long tick) {
		Objects.requireNonNull(rawKeys, "rawKeys must not be null");
		Objects.requireNonNull(presentThreatIds, "presentThreatIds must not be null");
		for (String key : rawKeys) {
			lastTrue.put(key, tick);
			firstTrue.putIfAbsent(key, tick);
		}
		lastTrue.entrySet().removeIf(entry -> !presentThreatIds.contains(threatId(entry.getKey()))
				|| tick - entry.getValue() > HOLD_TICKS || entry.getValue() > tick);
		firstTrue.keySet().retainAll(lastTrue.keySet());
		return new TreeSet<>(lastTrue.keySet());
	}

	/** Tick at which a still-latched signal was first raised (its latch age orders the reported threats). */
	public long firstSeen(String key) {
		Long tick = firstTrue.get(key);
		return tick == null ? Long.MAX_VALUE : tick;
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
