package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

public record PendingGoalDraft(
		UUID draftId,
		AgentId agentId,
		UUID requestingPlayerId,
		String originalRequest,
		String dimensionId,
		List<String> candidateIds,
		Optional<GoalPredicate> proposedPredicate,
		DraftIntent intent,
		long createdAtTick,
		long expectedGoalRevision,
		Optional<UUID> expectedGoalId
) {
	public PendingGoalDraft {
		Objects.requireNonNull(draftId, "draftId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(requestingPlayerId, "requestingPlayerId must not be null");
		originalRequest = AgentValidators.normalizePrompt(originalRequest);
		dimensionId = Objects.requireNonNull(dimensionId, "dimensionId must not be null").strip();
		if (!dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
		}
		candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "candidateIds must not be null"));
		if (candidateIds.size() > 64 || new HashSet<>(candidateIds).size() != candidateIds.size()) {
			throw new IllegalArgumentException("candidateIds must contain at most 64 unique identifiers");
		}
		for (String candidateId : candidateIds) {
			if (candidateId == null || !candidateId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new IllegalArgumentException("candidateIds must contain namespaced identifiers");
			}
		}
		proposedPredicate = Objects.requireNonNull(proposedPredicate, "proposedPredicate must not be null");
		Objects.requireNonNull(intent, "intent must not be null");
		if (createdAtTick < 0L) throw new IllegalArgumentException("createdAtTick must be nonnegative");
		if (expectedGoalRevision < 0L) throw new IllegalArgumentException("expectedGoalRevision must be nonnegative");
		expectedGoalId = Objects.requireNonNull(expectedGoalId, "expectedGoalId must not be null");
	}

	public PendingGoalDraft(
			UUID draftId, AgentId agentId, UUID requestingPlayerId, String originalRequest,
			List<String> candidateIds, Optional<GoalPredicate> proposedPredicate, DraftIntent intent,
			long createdAtTick, long expectedGoalRevision, Optional<UUID> expectedGoalId
	) {
		this(draftId, agentId, requestingPlayerId, originalRequest, GoalPredicate.DEFAULT_DIMENSION,
				candidateIds, proposedPredicate, intent, createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public PendingGoalDraft(
			UUID draftId, AgentId agentId, UUID requestingPlayerId, String originalRequest,
			Optional<GoalPredicate> proposedPredicate, DraftIntent intent, long createdAtTick,
			long expectedGoalRevision, Optional<UUID> expectedGoalId
	) {
		this(draftId, agentId, requestingPlayerId, originalRequest, GoalPredicate.DEFAULT_DIMENSION,
				List.of(), proposedPredicate, intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public PendingGoalDraft withProposedPredicate(GoalPredicate predicate) {
		return new PendingGoalDraft(draftId, agentId, requestingPlayerId, originalRequest, dimensionId, candidateIds,
				Optional.of(Objects.requireNonNull(predicate, "predicate must not be null")), intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public boolean matches(dev.agaminggod.arenaagents.agent.AgentRecord record) {
		Objects.requireNonNull(record, "record must not be null");
		return record.agentId().equals(agentId)
				&& record.goalRevision() == expectedGoalRevision
				&& record.currentGoal().map(dev.agaminggod.arenaagents.agent.AgentGoal::goalId).equals(expectedGoalId);
	}
}
