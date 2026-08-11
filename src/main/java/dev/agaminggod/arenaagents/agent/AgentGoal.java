package dev.agaminggod.arenaagents.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AgentGoal(
		UUID goalId,
		String prompt,
		List<String> steeringInstructions,
		long createdAtEpochMs,
		long updatedAtEpochMs
) {
	public AgentGoal {
		Objects.requireNonNull(goalId, "goalId must not be null");
		prompt = AgentValidators.normalizePrompt(prompt);
		List<String> suppliedInstructions = List.copyOf(Objects.requireNonNull(
				steeringInstructions,
				"steeringInstructions must not be null"
		));
		if (suppliedInstructions.size() > AgentConstants.MAX_STEERING_INSTRUCTIONS) {
			throw new AgentDomainException("STEERING_LIMIT_REACHED", "Too many steering instructions");
		}
		steeringInstructions = suppliedInstructions.stream().map(AgentValidators::normalizePrompt).toList();
		if (createdAtEpochMs <= 0L || updatedAtEpochMs < createdAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Goal timestamps are invalid");
		}
	}

	public static AgentGoal create(String prompt, long nowEpochMs) {
		return new AgentGoal(UUID.randomUUID(), prompt, List.of(), nowEpochMs, nowEpochMs);
	}

	public AgentGoal steer(String instruction, long nowEpochMs) {
		if (nowEpochMs < updatedAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Steering timestamp precedes the goal update");
		}
		ArrayList<String> revised = new ArrayList<>(steeringInstructions);
		if (revised.size() >= AgentConstants.MAX_STEERING_INSTRUCTIONS) {
			throw new AgentDomainException("STEERING_LIMIT_REACHED", "Steering instruction limit reached");
		}
		revised.add(AgentValidators.normalizePrompt(instruction));
		return new AgentGoal(goalId, prompt, revised, createdAtEpochMs, nowEpochMs);
	}
}
