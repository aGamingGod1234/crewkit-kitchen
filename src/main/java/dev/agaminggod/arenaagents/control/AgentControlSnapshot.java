package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record AgentControlSnapshot(
		int schemaVersion,
		boolean canControl,
		long generatedAtEpochMs,
		List<AgentControlAgent> agents
) {
	public static final int SCHEMA_VERSION = 2;
	public static final int MAX_AGENTS = AgentConstants.DEFAULT_AGENT_LIMIT;

	public AgentControlSnapshot(boolean canControl, long generatedAtEpochMs, List<AgentControlAgent> agents) {
		this(SCHEMA_VERSION, canControl, generatedAtEpochMs, agents);
	}

	public AgentControlSnapshot {
		if (schemaVersion != SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported control snapshot schema: " + schemaVersion);
		}
		if (generatedAtEpochMs <= 0L) {
			throw new IllegalArgumentException("generatedAtEpochMs must be positive");
		}
		agents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (agents.size() > MAX_AGENTS) {
			throw new IllegalArgumentException("Control snapshot exceeds the supported agent limit");
		}
	}

	public static AgentControlSnapshot fromRecords(
			boolean canControl,
			long generatedAtEpochMs,
			List<AgentRecord> records
	) {
		Objects.requireNonNull(records, "records must not be null");
		List<AgentControlAgent> agents = records.stream()
				.sorted(Comparator.comparingLong(AgentRecord::createdAtEpochMs).thenComparing(record -> record.agentId().toString()))
				.map(AgentControlSnapshot::fromRecord)
				.toList();
		return new AgentControlSnapshot(canControl, generatedAtEpochMs, agents);
	}

	private static AgentControlAgent fromRecord(AgentRecord record) {
		Objects.requireNonNull(record, "records must not contain null");
		String displayName = record.profile().userName().orElse(record.profile().nameTag());
		String currentGoal = record.currentGoal().map(goal -> goal.prompt()).orElse("");
		return new AgentControlAgent(
				record.agentId().toString(),
				record.agentId().shortValue(),
				AgentControlAgent.truncate(displayName, AgentControlAgent.MAX_DISPLAY_NAME_LENGTH),
				record.profile().provider(),
				record.profile().model(),
				record.profile().reasoning(),
				record.state().name(),
				AgentControlAgent.truncate(currentGoal, AgentControlAgent.MAX_CURRENT_GOAL_LENGTH),
				record.queuedGoals().size(),
				AgentControlAgent.truncate(record.lastSummary(), AgentControlAgent.MAX_LAST_SUMMARY_LENGTH),
				AgentControlAgent.truncate(record.lastError(), AgentControlAgent.MAX_LAST_ERROR_LENGTH),
				record.automaticProgress(),
				record.entityUuid().isPresent()
		);
	}
}
