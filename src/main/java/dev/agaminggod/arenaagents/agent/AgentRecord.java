package dev.agaminggod.arenaagents.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AgentRecord(
		int schemaVersion,
		AgentId agentId,
		Optional<UUID> entityUuid,
		Optional<AgentEntityLocation> entityLocation,
		AgentProfile profile,
		AgentLifecycleState state,
		Optional<AgentGoal> currentGoal,
		long goalRevision,
		List<AgentGoal> queuedGoals,
		String lastSummary,
		String inventorySnapshot,
		boolean automaticProgress,
		RespawnPolicy respawnPolicy,
		long createdAtEpochMs,
		long updatedAtEpochMs,
		String lastError
) {
	public AgentRecord {
		if (schemaVersion != AgentConstants.SCHEMA_VERSION) {
			throw new AgentDomainException("UNSUPPORTED_SCHEMA", "Unsupported agent schema version: " + schemaVersion);
		}
		Objects.requireNonNull(agentId, "agentId must not be null");
		entityUuid = Objects.requireNonNull(entityUuid, "entityUuid must not be null");
		entityLocation = Objects.requireNonNull(entityLocation, "entityLocation must not be null");
		if (entityUuid.isEmpty() && entityLocation.isPresent()) {
			throw new AgentDomainException("INVALID_ENTITY_LOCATION", "Entity location requires an entity UUID");
		}
		Objects.requireNonNull(profile, "profile must not be null");
		Objects.requireNonNull(state, "state must not be null");
		currentGoal = Objects.requireNonNull(currentGoal, "currentGoal must not be null");
		if (goalRevision < 0L) {
			throw new AgentDomainException("INVALID_REVISION", "goalRevision must not be negative");
		}
		queuedGoals = List.copyOf(Objects.requireNonNull(queuedGoals, "queuedGoals must not be null"));
		lastSummary = AgentValidators.boundedText(lastSummary, "lastSummary", AgentConstants.MAX_SUMMARY_LENGTH);
		inventorySnapshot = AgentValidators.boundedText(
				inventorySnapshot,
				"inventorySnapshot",
				AgentConstants.MAX_INVENTORY_SNAPSHOT_LENGTH
		);
		Objects.requireNonNull(respawnPolicy, "respawnPolicy must not be null");
		if (createdAtEpochMs <= 0L || updatedAtEpochMs < createdAtEpochMs) {
			throw new AgentDomainException("INVALID_AGENT_TIME", "Agent timestamps are invalid");
		}
		lastError = AgentValidators.boundedText(lastError, "lastError", AgentConstants.MAX_ERROR_LENGTH);
		validateStateGoalInvariant(state, currentGoal);
	}

	public static AgentRecord create(AgentId id, AgentProfile profile, long nowEpochMs) {
		return new AgentRecord(
				AgentConstants.SCHEMA_VERSION,
				id,
				Optional.empty(),
				Optional.empty(),
				profile,
				AgentLifecycleState.IDLE,
				Optional.empty(),
				0L,
				List.of(),
				"",
				"",
				true,
				RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				nowEpochMs,
				nowEpochMs,
				""
		);
	}

	public AgentRecord withEntityUuid(Optional<UUID> revisedEntityUuid, long nowEpochMs) {
		Optional<UUID> checkedEntityUuid = Objects.requireNonNull(revisedEntityUuid, "revisedEntityUuid must not be null");
		Optional<AgentEntityLocation> revisedLocation = checkedEntityUuid.isPresent() ? entityLocation : Optional.empty();
		return copy(state, currentGoal, goalRevision, queuedGoals, checkedEntityUuid, revisedLocation,
				lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, nowEpochMs, lastError);
	}

	public AgentRecord withEntity(
			Optional<UUID> revisedEntityUuid,
			Optional<AgentEntityLocation> revisedEntityLocation,
			long nowEpochMs
	) {
		return copy(state, currentGoal, goalRevision, queuedGoals, revisedEntityUuid, revisedEntityLocation,
				lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, nowEpochMs, lastError);
	}

	public AgentRecord withEntityLocation(AgentEntityLocation revisedEntityLocation, long nowEpochMs) {
		return withEntity(entityUuid, Optional.of(Objects.requireNonNull(
				revisedEntityLocation,
				"revisedEntityLocation must not be null"
		)), nowEpochMs);
	}

	public AgentRecord withLifecycle(
			AgentLifecycleState revisedState,
			Optional<AgentGoal> revisedGoal,
			long revisedRevision,
			List<AgentGoal> revisedQueue,
			long nowEpochMs,
			String revisedError
	) {
		return copy(revisedState, revisedGoal, revisedRevision, revisedQueue, entityUuid, entityLocation,
				lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, nowEpochMs, revisedError);
	}

	public AgentRecord withRecovery(
			String revisedSummary,
			String revisedInventorySnapshot,
			long nowEpochMs
	) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation,
				revisedSummary, revisedInventorySnapshot, automaticProgress, respawnPolicy, nowEpochMs, lastError);
	}

	public AgentRecord withRespawnPolicy(RespawnPolicy revisedPolicy, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation,
				lastSummary, inventorySnapshot, automaticProgress, revisedPolicy, nowEpochMs, lastError);
	}

	public AgentRecord withAutomaticProgress(boolean revisedAutomaticProgress, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation,
				lastSummary, inventorySnapshot, revisedAutomaticProgress, respawnPolicy, nowEpochMs, lastError);
	}

	public boolean acceptsRevision(long proposedRevision) {
		return proposedRevision == goalRevision && state.isActive();
	}

	private AgentRecord copy(
			AgentLifecycleState revisedState,
			Optional<AgentGoal> revisedGoal,
			long revisedRevision,
			List<AgentGoal> revisedQueue,
			Optional<UUID> revisedEntityUuid,
			Optional<AgentEntityLocation> revisedEntityLocation,
			String revisedSummary,
			String revisedInventorySnapshot,
			boolean revisedAutomaticProgress,
			RespawnPolicy revisedRespawnPolicy,
			long nowEpochMs,
			String revisedError
	) {
		return new AgentRecord(
				schemaVersion,
				agentId,
				revisedEntityUuid,
				revisedEntityLocation,
				profile,
				revisedState,
				revisedGoal,
				revisedRevision,
				revisedQueue,
				revisedSummary,
				revisedInventorySnapshot,
				revisedAutomaticProgress,
				revisedRespawnPolicy,
				createdAtEpochMs,
				nowEpochMs,
				revisedError
		);
	}

	private static void validateStateGoalInvariant(
			AgentLifecycleState state,
			Optional<AgentGoal> currentGoal
	) {
		if (state == AgentLifecycleState.IDLE && currentGoal.isPresent()) {
			throw new AgentDomainException("INVALID_AGENT_STATE", "IDLE agents cannot have a current goal");
		}
		if ((state.isActive() || state == AgentLifecycleState.PAUSED || state == AgentLifecycleState.DISCONNECTED)
				&& currentGoal.isEmpty()) {
			throw new AgentDomainException("INVALID_AGENT_STATE", state + " agents require a current goal");
		}
	}
}
