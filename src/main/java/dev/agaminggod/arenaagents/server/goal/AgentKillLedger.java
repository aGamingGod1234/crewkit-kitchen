package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded server-owned attribution ledger for kills made by agent players. */
public final class AgentKillLedger {
	static final int SCHEMA_VERSION = 2;
	static final int MAX_EVENTS = 4_096;
	static final int MAX_PROGRESS_ENTRIES = 16_384;
	private final Deque<Kill> kills = new ArrayDeque<>();
	private final Map<KillKey, TimestampSeries> killsByAgentAndType = new HashMap<>();
	private final Map<ProgressKey, Progress> progress = new HashMap<>();
	private final Map<KillKey, List<ProgressKey>> progressByAgentAndType = new HashMap<>();
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
		for (ProgressEvent event : snapshot.progress()) {
			ProgressKey key = new ProgressKey(event.goalId(), event.agentId(), event.entityType(), event.afterExclusive());
			if (progress.putIfAbsent(key, new Progress(event.requiredCount(), event.evictedCount())) != null) {
				throw new IllegalArgumentException("Kill ledger contains duplicate goal progress");
			}
		}
		rebuildProgressIndex();
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
			for (ProgressKey progressKey : progressByAgentAndType.getOrDefault(removed.key(), List.of())) {
				if (removed.occurredAt() > progressKey.afterExclusive()) {
					progress.computeIfPresent(progressKey, (ignored, value) -> value.creditEvicted());
				}
			}
			TimestampSeries series = killsByAgentAndType.get(removed.key());
			series.remove(removed.occurredAt());
			if (series.isEmpty()) killsByAgentAndType.remove(removed.key());
		}
	}

	public synchronized Snapshot snapshot() {
		List<ProgressEvent> persistedProgress = progress.entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<ProgressKey, Progress> entry) -> entry.getKey().goalId().toString())
						.thenComparing(entry -> entry.getKey().agentId().toString())
						.thenComparing(entry -> entry.getKey().entityType())
						.thenComparingLong(entry -> entry.getKey().afterExclusive()))
				.map(entry -> new ProgressEvent(
						entry.getKey().goalId(), entry.getKey().agentId(), entry.getKey().entityType(),
						entry.getKey().afterExclusive(), entry.getValue().requiredCount(), entry.getValue().evictedCount()))
				.toList();
		return new Snapshot(
				SCHEMA_VERSION,
				kills.stream().map(kill -> new KillEvent(
						kill.key().agentId(), kill.key().entityType(), kill.occurredAt())).toList(),
				persistedProgress
		);
	}

	public static Snapshot emptySnapshot() {
		return new Snapshot(SCHEMA_VERSION, List.of(), List.of());
	}

	public synchronized void synchronizeProgress(List<KillProgressRequirement> requirements) {
		Objects.requireNonNull(requirements, "requirements must not be null");
		Map<ProgressKey, Integer> desired = new HashMap<>();
		for (KillProgressRequirement requirement : requirements) {
			ProgressKey key = new ProgressKey(
					requirement.goalId(), requirement.agentId(), requirement.entityType(), requirement.afterExclusive());
			desired.merge(key, requirement.requiredCount(), Math::max);
		}
		if (desired.size() > MAX_PROGRESS_ENTRIES) {
			throw new IllegalArgumentException("Active kill-goal progress exceeds the bounded limit");
		}
		Map<ProgressKey, Progress> next = new HashMap<>();
		for (Map.Entry<ProgressKey, Integer> entry : desired.entrySet()) {
			Progress previous = progress.get(entry.getKey());
			int credited = previous == null ? 0 : Math.min(previous.evictedCount(), entry.getValue());
			next.put(entry.getKey(), new Progress(entry.getValue(), credited));
		}
		if (!next.equals(progress)) {
			progress.clear();
			progress.putAll(next);
			rebuildProgressIndex();
			mutationListener.run();
		}
	}

	private void rebuildProgressIndex() {
		progressByAgentAndType.clear();
		for (ProgressKey key : progress.keySet()) {
			KillKey killKey = new KillKey(key.agentId(), key.entityType());
			progressByAgentAndType.computeIfAbsent(killKey, ignored -> new ArrayList<>()).add(key);
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

	public synchronized int count(
			UUID goalId, AgentId agentId, String entityType, long afterExclusive
	) {
		Objects.requireNonNull(goalId, "goalId must not be null");
		int recent = count(agentId, entityType, afterExclusive);
		Progress preserved = progress.get(new ProgressKey(goalId, agentId, entityType, afterExclusive));
		return recent + (preserved == null ? 0 : preserved.evictedCount());
	}

	public synchronized int size() {
		return kills.size();
	}

	synchronized int lastLookupProbeCount() {
		return lastLookupProbeCount;
	}

	private record KillKey(AgentId agentId, String entityType) { }
	private record Kill(KillKey key, long occurredAt) { }
	private record ProgressKey(UUID goalId, AgentId agentId, String entityType, long afterExclusive) { }
	private record Progress(int requiredCount, int evictedCount) {
		private Progress creditEvicted() {
			return evictedCount >= requiredCount ? this : new Progress(requiredCount, evictedCount + 1);
		}
	}

	public record Snapshot(int schemaVersion, List<KillEvent> events, List<ProgressEvent> progress) {
		public Snapshot {
			if (schemaVersion != SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported kill ledger schema: " + schemaVersion);
			}
			events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
			progress = List.copyOf(Objects.requireNonNull(progress, "progress must not be null"));
			if (events.size() > MAX_EVENTS) {
				throw new IllegalArgumentException("Kill ledger exceeds the bounded event limit");
			}
			if (progress.size() > MAX_PROGRESS_ENTRIES) {
				throw new IllegalArgumentException("Kill ledger exceeds the bounded progress limit");
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

	public record KillProgressRequirement(
			UUID goalId, AgentId agentId, String entityType, long afterExclusive, int requiredCount
	) {
		public KillProgressRequirement {
			Objects.requireNonNull(goalId, "goalId must not be null");
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(entityType, "entityType must not be null");
			if (!entityType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new IllegalArgumentException("entityType must be namespaced");
			}
			if (requiredCount <= 0 || requiredCount > 16) {
				throw new IllegalArgumentException("requiredCount must be between 1 and 16");
			}
		}
	}

	public record ProgressEvent(
			UUID goalId, AgentId agentId, String entityType, long afterExclusive,
			int requiredCount, int evictedCount
	) {
		public ProgressEvent {
			new KillProgressRequirement(goalId, agentId, entityType, afterExclusive, requiredCount);
			if (evictedCount < 0 || evictedCount > requiredCount) {
				throw new IllegalArgumentException("evictedCount must be between zero and requiredCount");
			}
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
