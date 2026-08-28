package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread service that proactively completes goals only from server-observed facts. */
public final class GoalVerificationRuntime {
	public static final long MAX_RETRY_DELAY_TICKS = 20L;
	private final AgentRegistry registry;
	private final Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts;
	private final LongSupplier serverTick;
	private final LongSupplier epochMillis;
	private final GoalCompletionVerifier verifier;
	private final AgentKillLedger killLedger;
	private final Set<UUID> operatorConfirmed = new HashSet<>();
	private final Map<AgentId, VerificationFault> faults = new LinkedHashMap<>();

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis
	) {
		this(registry, facts, serverTick, epochMillis, new GoalCompletionVerifier(), new AgentKillLedger());
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			AgentKillLedger killLedger
	) {
		this(registry, facts, serverTick, epochMillis, new GoalCompletionVerifier(), killLedger);
	}

	GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			GoalCompletionVerifier verifier,
			AgentKillLedger killLedger
	) {
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
		this.facts = Objects.requireNonNull(facts, "facts must not be null");
		this.serverTick = Objects.requireNonNull(serverTick, "serverTick must not be null");
		this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis must not be null");
		this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
		this.killLedger = Objects.requireNonNull(killLedger, "killLedger must not be null");
		synchronizeKillProgress();
	}

	public List<AgentTransition> tick() {
		synchronizeKillProgress();
		long tick = serverTick.getAsLong();
		long now = epochMillis.getAsLong();
		if (tick < 0L) throw new IllegalStateException("Server tick must be nonnegative");
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		for (AgentRecord snapshot : registry.records()) {
			AgentRecord record = registry.require(snapshot.agentId());
			if (readyToPromote(record)) {
				transitions.add(registry.promoteSatisfied(record.agentId(), now));
				continue;
			}
			if (!record.state().isActive() || record.currentGoal().isEmpty()) continue;
			GoalStatus status = record.currentGoal().orElseThrow().status();
			if (status != GoalStatus.ACTIVE && status != GoalStatus.RECOVERING) continue;
			GoalCompletionVerifier.VerificationResult result = verifySafely(record, tick);
			if (!result.verified()) continue;
			transitions.add(registry.satisfyGoal(record.agentId(), record.goalRevision(), result.evidence(tick), now));
		}
		synchronizeKillProgress();
		Set<UUID> currentGoals = registry.records().stream().flatMap(record -> record.currentGoal().stream())
				.map(AgentGoal::goalId).collect(java.util.stream.Collectors.toUnmodifiableSet());
		verifier.retainGoals(currentGoals);
		operatorConfirmed.retainAll(currentGoals);
		faults.entrySet().removeIf(entry -> registry.records().stream().noneMatch(record ->
				record.agentId().equals(entry.getKey())
						&& record.currentGoal().isPresent()
						&& record.goalRevision() == entry.getValue().goalRevision()));
		return List.copyOf(transitions);
	}

	public GoalCompletionVerifier.VerificationResult evaluate(AgentId agentId) {
		synchronizeKillProgress();
		AgentRecord record = registry.require(agentId);
		long tick = serverTick.getAsLong();
		return verifySafely(record, tick);
	}

	public GoalCompletionVerifier.VerificationResult evaluateRequest(
			AgentId agentId, long requestedRevision, String goalFingerprint
	) {
		AgentRecord record = registry.require(agentId);
		AgentGoal goal = record.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to verify"));
		if (!goal.spec().fingerprint().equals(Objects.requireNonNull(goalFingerprint, "goalFingerprint must not be null"))) {
			throw new AgentDomainException("STALE_GOAL_FINGERPRINT", "Completion request does not match the immutable current goal");
		}
		if (record.goalRevision() == requestedRevision) return evaluate(agentId);
		if (record.goalRevision() > 0L && record.goalRevision() - 1L == requestedRevision && goal.status() == GoalStatus.SATISFIED) {
			var evidence = goal.evidence().orElseThrow();
			return new GoalCompletionVerifier.VerificationResult(
					true, requestedRevision, evidence.reasonCode(), evidence.facts());
		}
		throw new AgentDomainException("STALE_REVISION", "Completion request revision is stale");
	}

	public Optional<AgentTransition> acceptVerified(
			AgentId agentId,
			long requestedRevision,
			String goalFingerprint,
			GoalCompletionVerifier.VerificationResult result
	) {
		Objects.requireNonNull(result, "result must not be null");
		if (!result.verified()) throw new IllegalArgumentException("Only verified evidence may be accepted");
		AgentRecord record = registry.require(agentId);
		AgentGoal goal = record.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to satisfy"));
		if (!goal.spec().fingerprint().equals(goalFingerprint)) {
			throw new AgentDomainException("STALE_GOAL_FINGERPRINT", "Verified evidence targets a stale goal");
		}
		if (record.goalRevision() > 0L && record.goalRevision() - 1L == requestedRevision && goal.status() == GoalStatus.SATISFIED) {
			return Optional.empty();
		}
		if (record.goalRevision() != requestedRevision) {
			throw new AgentDomainException("STALE_REVISION", "Verified evidence targets a stale goal revision");
		}
		return Optional.of(registry.satisfyGoal(
				agentId, requestedRevision, result.evidence(serverTick.getAsLong()), epochMillis.getAsLong()));
	}

	public void recordKill(AgentId agentId, String entityType) {
		synchronizeKillProgress();
		killLedger.record(agentId, entityType, epochMillis.getAsLong());
	}

	public void confirm(AgentId agentId, UUID goalId) {
		AgentRecord record = registry.require(agentId);
		UUID currentGoalId = record.currentGoal().map(AgentGoal::goalId)
				.orElseThrow(() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to confirm"));
		if (!currentGoalId.equals(Objects.requireNonNull(goalId, "goalId must not be null"))) {
			throw new AgentDomainException("STALE_GOAL_CONFIRMATION", "Operator confirmation targets a stale goal");
		}
		operatorConfirmed.add(goalId);
	}

	public AgentKillLedger killLedger() {
		return killLedger;
	}

	public List<VerificationFault> faults() {
		return List.copyOf(faults.values());
	}

	private void synchronizeKillProgress() {
		ArrayList<AgentKillLedger.KillProgressRequirement> requirements = new ArrayList<>();
		for (AgentRecord record : registry.records()) {
			AgentGoal goal = record.currentGoal().orElse(null);
			if (goal == null || goal.status() != GoalStatus.ACTIVE && goal.status() != GoalStatus.RECOVERING) continue;
			Map<KillRequirementKey, Integer> requiredByEntity = new HashMap<>();
			collectKillRequirements(goal.spec().completion(), goal.createdAtEpochMs(), requiredByEntity);
			for (Map.Entry<KillRequirementKey, Integer> entry : requiredByEntity.entrySet()) {
				requirements.add(new AgentKillLedger.KillProgressRequirement(
						goal.goalId(), record.agentId(), entry.getKey().entityType(),
						entry.getKey().afterExclusive(), entry.getValue()));
			}
		}
		killLedger.synchronizeProgress(requirements);
	}

	private static void collectKillRequirements(
			GoalPredicate predicate, long goalStartedAt, Map<KillRequirementKey, Integer> requiredByEntity
	) {
		if (predicate instanceof GoalPredicate.EntityKilledByAgent killed) {
			long afterExclusive = killed.afterGoalStart() ? goalStartedAt : Long.MIN_VALUE;
			requiredByEntity.merge(new KillRequirementKey(killed.entityType(), afterExclusive), 1, Math::addExact);
			return;
		}
		if (predicate instanceof GoalPredicate.AllOf all) {
			for (GoalPredicate child : all.predicates()) {
				collectKillRequirements(child, goalStartedAt, requiredByEntity);
			}
			return;
		}
		if (predicate instanceof GoalPredicate.AnyOf any) {
			for (GoalPredicate child : any.predicates()) {
				collectKillRequirements(child, goalStartedAt, requiredByEntity);
			}
		}
	}

	private record KillRequirementKey(String entityType, long afterExclusive) { }

	private GoalCompletionVerifier.VerificationResult verifySafely(AgentRecord record, long tick) {
		VerificationFault existing = faults.get(record.agentId());
		if (existing != null && existing.goalRevision() == record.goalRevision() && tick < existing.retryAtTick()) {
			return failed(record.goalRevision(), "VERIFIER_RETRY_PENDING");
		}
		try {
			GoalCompletionVerifier.VerificationResult result = verifier.verify(
					record,
					facts.apply(record.agentId()).orElse(null),
					killLedger,
					tick,
					record.currentGoal().map(AgentGoal::goalId).filter(operatorConfirmed::contains).isPresent()
			);
			faults.remove(record.agentId());
			return result;
		} catch (RuntimeException exception) {
			int failures = existing != null && existing.goalRevision() == record.goalRevision()
					? existing.consecutiveFailures() + 1 : 1;
			long delay = Math.min(MAX_RETRY_DELAY_TICKS, 1L << Math.min(failures - 1, 30));
			String exceptionType = exception.getClass().getSimpleName();
			if (exceptionType.isBlank()) exceptionType = exception.getClass().getName();
			faults.put(record.agentId(), new VerificationFault(
					record.agentId(), record.goalRevision(), exceptionType, failures, tick + delay));
			return failed(record.goalRevision(), "VERIFIER_ERROR");
		}
	}

	private static GoalCompletionVerifier.VerificationResult failed(long revision, String reasonCode) {
		return new GoalCompletionVerifier.VerificationResult(false, revision, reasonCode, List.of());
	}

	public record VerificationFault(
			AgentId agentId,
			long goalRevision,
			String exceptionType,
			int consecutiveFailures,
			long retryAtTick
	) {
		public VerificationFault {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(exceptionType, "exceptionType must not be null");
			if (goalRevision < 0L || consecutiveFailures <= 0 || retryAtTick < 0L) {
				throw new IllegalArgumentException("Verification fault fields are invalid");
			}
		}
	}

	private static boolean readyToPromote(AgentRecord record) {
		return record.state() == AgentLifecycleState.COMPLETED
				&& record.currentGoal().map(goal -> goal.status() == GoalStatus.SATISFIED).orElse(false)
				&& !record.queuedGoals().isEmpty();
	}
}
