package dev.agaminggod.arenaagents.server.bridge;

import java.util.List;
import java.util.Objects;

public record CoordinatorStatusSnapshot(
		boolean reconciled,
		List<SupportedProfile> profiles,
		int supportedProfileCount,
		int rosterReadyCount,
		int rosterCount,
		SchedulerStatus scheduler,
		List<CircuitHealth> circuits,
		List<LatencyHealth> latencies,
		long receivedAtEpochMs
) {
	public static final int MAX_PROFILES = 16;
	public static final int MAX_CIRCUITS = 32;
	public static final int MAX_LATENCIES = 16;

	public CoordinatorStatusSnapshot(
			boolean reconciled,
			List<SupportedProfile> profiles,
			int supportedProfileCount,
			int rosterReadyCount,
			int rosterCount,
			SchedulerStatus scheduler,
			List<CircuitHealth> circuits,
			long receivedAtEpochMs
	) {
		this(reconciled, profiles, supportedProfileCount, rosterReadyCount, rosterCount, scheduler, circuits, List.of(), receivedAtEpochMs);
	}

	public CoordinatorStatusSnapshot {
		profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles must not be null"));
		circuits = List.copyOf(Objects.requireNonNull(circuits, "circuits must not be null"));
		latencies = List.copyOf(Objects.requireNonNull(latencies, "latencies must not be null"));
		scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
		if (profiles.size() > MAX_PROFILES || circuits.size() > MAX_CIRCUITS || latencies.size() > MAX_LATENCIES) throw new IllegalArgumentException("coordinator status exceeds bounded entries");
		if (supportedProfileCount != profiles.size()) throw new IllegalArgumentException("supported profile count does not match profiles");
		if (profiles.stream().map(SupportedProfile::agentId).distinct().count() != profiles.size()) throw new IllegalArgumentException("supported profile identities must be unique");
		if (circuits.stream().map(health -> health.provider() + "\u0000" + health.model() + "\u0000" + health.operation()).distinct().count() != circuits.size()) {
			throw new IllegalArgumentException("circuit identities must be unique");
		}
		if (latencies.stream().map(LatencyHealth::operation).distinct().count() != latencies.size()) {
			throw new IllegalArgumentException("latency operations must be unique");
		}
		if (rosterReadyCount < 0 || rosterCount < 0 || rosterReadyCount > rosterCount || rosterCount > MAX_PROFILES) throw new IllegalArgumentException("invalid roster counts");
		if (receivedAtEpochMs < 0L) throw new IllegalArgumentException("receivedAtEpochMs must be non-negative");
	}

	public boolean fresh(long nowEpochMs, long maximumAgeMs) {
		return nowEpochMs >= receivedAtEpochMs && maximumAgeMs >= 0L && nowEpochMs - receivedAtEpochMs <= maximumAgeMs;
	}

	public boolean supports(String agentId, String provider, String model, String reasoningEffort) {
		return profiles.stream().anyMatch(profile -> profile.agentId().equals(agentId)
				&& profile.provider().equals(provider)
				&& profile.model().equals(model)
				&& profile.reasoningEffort().equals(reasoningEffort));
	}

	public record SupportedProfile(String agentId, String provider, String model, String reasoningEffort) {
		public SupportedProfile {
			agentId = nonblank(agentId, "agentId");
			provider = nonblank(provider, "provider");
			model = nonblank(model, "model");
			reasoningEffort = nonblank(reasoningEffort, "reasoningEffort");
		}
	}

	public record SchedulerStatus(int active, int pending, int maxConcurrent, int maxPending, boolean warning) {
		public SchedulerStatus {
			if (active < 0 || pending < 0 || maxConcurrent < 1 || maxPending < 0 || active > maxConcurrent || pending > maxPending
					|| (long) maxConcurrent + maxPending > MAX_PROFILES) {
				throw new IllegalArgumentException("invalid scheduler status");
			}
		}

		public long availableCapacity() {
			return (long) maxConcurrent + maxPending - active - pending;
		}
	}

	public record CircuitHealth(String provider, String model, String operation, int count, int p50Ms, int p95Ms, double failureRate, String circuit) {
		public CircuitHealth {
			provider = nonblank(provider, "provider");
			model = nonblank(model, "model");
			operation = nonblank(operation, "operation");
			circuit = nonblank(circuit, "circuit");
			if (!List.of("closed", "open", "half_open").contains(circuit)) throw new IllegalArgumentException("invalid circuit state");
			if (count < 0 || p50Ms < 0 || p95Ms < 0 || !Double.isFinite(failureRate) || failureRate < 0.0 || failureRate > 1.0) {
				throw new IllegalArgumentException("invalid circuit health");
			}
		}
	}

	public record LatencyHealth(String operation, int count, double p50Ms, double p95Ms) {
		public LatencyHealth {
			operation = nonblank(operation, "operation");
			if (count < 0 || !Double.isFinite(p50Ms) || !Double.isFinite(p95Ms) || p50Ms < 0 || p95Ms < 0) throw new IllegalArgumentException("invalid latency health");
		}
	}

	private static String nonblank(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException(field + " must be 1-256 characters");
		return value;
	}
}
