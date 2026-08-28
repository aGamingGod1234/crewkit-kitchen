package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded server-owned attribution ledger for kills made by agent players. */
public final class AgentKillLedger {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_EVENTS = 4_096;
	private final Deque<Kill> kills = new ArrayDeque<>();
	private final Map<KillKey, TimestampSeries> killsByAgentAndType = new HashMap<>();
	private final Runnable mutationListener;
	private int lastLookupProbeCount;

	public AgentKillLedger() {
		this(emptySnapshot(), () -> { });
	}

	public AgentKillLedger(Snapshot snapshot, Runnable mutationListener) {
		this.mutationListener = Objects.requireNonNull(mutationListener, "mutationListener must not be null");
		for (KillEvent event : Objects.requireNonNull(snapshot, "snapshot must not be null").events()) {
			append(event.agentId(), event.entityType(), event.occurredAtEpochMs());
		}
	}

	public synchronized void record(AgentId agentId, String entityType, long occurredAt) {
		append(agentId, entityType, occurredAt);
		mutationListener.run();
	}

	private void append(AgentId agentId, String entityType, long occurredAt) {
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

	public synchronized Snapshot snapshot() {
		return new Snapshot(SCHEMA_VERSION, kills.stream()
				.map(kill -> new KillEvent(kill.key().agentId(), kill.key().entityType(), kill.occurredAt()))
				.toList());
	}

	public static Snapshot emptySnapshot() {
		return new Snapshot(SCHEMA_VERSION, List.of());
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

	public record Snapshot(int schemaVersion, List<KillEvent> events) {
		public Snapshot {
			if (schemaVersion != SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported kill ledger schema: " + schemaVersion);
			}
			events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
			if (events.size() > MAX_EVENTS) {
				throw new IllegalArgumentException("Kill ledger exceeds the bounded event limit");
			}
		}
	}

	public record KillEvent(AgentId agentId, String entityType, long occurredAtEpochMs) {
		public KillEvent {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(entityType, "entityType must not be null");
			if (!entityType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new IllegalArgumentException("entityType must be namespaced");
			}
			if (occurredAtEpochMs < 0L) throw new IllegalArgumentException("occurredAtEpochMs must be nonnegative");
		}
	}

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
