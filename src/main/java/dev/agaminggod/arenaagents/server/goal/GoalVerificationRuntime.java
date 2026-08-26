package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread service that proactively completes goals only from server-observed facts. */
public final class GoalVerificationRuntime {
	private final AgentRegistry registry;
	private final Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts;
	private final LongSupplier serverTick;
	private final LongSupplier epochMillis;
	private final GoalCompletionVerifier verifier;
	private final AgentKillLedger killLedger;
	private final Set<UUID> operatorConfirmed = new HashSet<>();

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis
	) {
		this(registry, facts, serverTick, epochMillis, new GoalCompletionVerifier(), new AgentKillLedger());
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
	}

	public List<AgentTransition> tick() {
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
			GoalCompletionVerifier.VerificationResult result = verifier.verify(
					record,
					facts.apply(record.agentId()).orElse(null),
					killLedger,
					tick,
					operatorConfirmed.contains(record.currentGoal().orElseThrow().goalId())
			);
			if (!result.verified()) continue;
			transitions.add(registry.satisfyGoal(record.agentId(), record.goalRevision(), result.evidence(tick), now));
		}
		Set<UUID> currentGoals = registry.records().stream().flatMap(record -> record.currentGoal().stream())
				.map(AgentGoal::goalId).collect(java.util.stream.Collectors.toUnmodifiableSet());
		verifier.retainGoals(currentGoals);
		operatorConfirmed.retainAll(currentGoals);
		return List.copyOf(transitions);
	}

	public GoalCompletionVerifier.VerificationResult evaluate(AgentId agentId) {
		AgentRecord record = registry.require(agentId);
		long tick = serverTick.getAsLong();
		return verifier.verify(record, facts.apply(agentId).orElse(null), killLedger, tick,
				record.currentGoal().map(AgentGoal::goalId).filter(operatorConfirmed::contains).isPresent());
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
		killLedger.record(agentId, entityType, serverTick.getAsLong());
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

	private static boolean readyToPromote(AgentRecord record) {
		return record.state() == AgentLifecycleState.COMPLETED
				&& record.currentGoal().map(goal -> goal.status() == GoalStatus.SATISFIED).orElse(false)
				&& !record.queuedGoals().isEmpty();
	}
}
