package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded server-owned attribution ledger for kills made by agent players. */
public final class AgentKillLedger {
	private static final int MAX_EVENTS = 4_096;
	private final Deque<Kill> kills = new ArrayDeque<>();
	private final Map<KillKey, TimestampSeries> killsByAgentAndType = new HashMap<>();
	private int lastLookupProbeCount;

	public synchronized void record(AgentId agentId, String entityType, long occurredAt) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		String type = Objects.requireNonNull(entityType, "entityType must not be null");
		if (!type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("entityType must be namespaced");
		if (occurredAt < 0L) throw new IllegalArgumentException("occurredAt must be nonnegative");
		KillKey key = new KillKey(agentId, type);
		kills.addLast(new Kill(key, occurredAt));
		killsByAgentAndType.computeIfAbsent(key, ignored -> new TimestampSeries()).add(occurredAt);
		while (kills.size() > MAX_EVENTS) {
			Kill removed = kills.removeFirst();
			TimestampSeries series = killsByAgentAndType.get(removed.key());
			series.remove(removed.occurredAt());
			if (series.isEmpty()) killsByAgentAndType.remove(removed.key());
		}
	}

	public synchronized int count(AgentId agentId, String entityType, long afterExclusive) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityType, "entityType must not be null");
		TimestampSeries series = killsByAgentAndType.get(new KillKey(agentId, entityType));
		if (series == null) {
			lastLookupProbeCount = 0;
			return 0;
		}
		TimestampSeries.Lookup lookup = series.countAfter(afterExclusive);
		lastLookupProbeCount = lookup.probes();
		return lookup.count();
	}

	public synchronized int size() {
		return kills.size();
	}

	synchronized int lastLookupProbeCount() {
		return lastLookupProbeCount;
	}

	private record KillKey(AgentId agentId, String entityType) { }
	private record Kill(KillKey key, long occurredAt) { }

	private static final class TimestampSeries {
		private final ArrayList<Long> values = new ArrayList<>();

		private void add(long value) {
			int low = 0;
			int high = values.size();
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (values.get(middle) <= value) low = middle + 1;
				else high = middle;
			}
			values.add(low, value);
		}

		private void remove(long expected) {
			int index = java.util.Collections.binarySearch(values, expected);
			if (index < 0) {
				throw new IllegalStateException("Kill index is inconsistent with its bounded event ledger");
			}
			values.remove(index);
		}

		private Lookup countAfter(long threshold) {
			int low = 0;
			int high = values.size();
			int probes = 0;
			while (low < high) {
				probes++;
				int middle = (low + high) >>> 1;
				if (values.get(middle) <= threshold) low = middle + 1;
				else high = middle;
			}
			return new Lookup(values.size() - low, probes);
		}

		private boolean isEmpty() {
			return values.isEmpty();
		}

		private record Lookup(int count, int probes) { }
	}
}
