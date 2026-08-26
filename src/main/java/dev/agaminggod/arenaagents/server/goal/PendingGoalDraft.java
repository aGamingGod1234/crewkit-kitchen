package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PendingGoalDraft(
		UUID draftId,
		AgentId agentId,
		UUID requestingPlayerId,
		String originalRequest,
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
		proposedPredicate = Objects.requireNonNull(proposedPredicate, "proposedPredicate must not be null");
		Objects.requireNonNull(intent, "intent must not be null");
		if (createdAtTick < 0L) throw new IllegalArgumentException("createdAtTick must be nonnegative");
		if (expectedGoalRevision < 0L) throw new IllegalArgumentException("expectedGoalRevision must be nonnegative");
		expectedGoalId = Objects.requireNonNull(expectedGoalId, "expectedGoalId must not be null");
	}

	public boolean matches(dev.agaminggod.arenaagents.agent.AgentRecord record) {
		Objects.requireNonNull(record, "record must not be null");
		return record.agentId().equals(agentId)
				&& record.goalRevision() == expectedGoalRevision
				&& record.currentGoal().map(dev.agaminggod.arenaagents.agent.AgentGoal::goalId).equals(expectedGoalId);
	}
}
