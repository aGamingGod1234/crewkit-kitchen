package dev.agaminggod.arenaagents.server.perception;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateful, bounded scheduler for full server observations. */
public final class ObservationCadencePolicy {
	public static final long IDLE_HEARTBEAT_TICKS = 20L;

	private final ArrayDeque<AgentId> activeRotation = new ArrayDeque<>();
	private final Map<AgentId, Long> lastDueTicks = new HashMap<>();

	public synchronized List<AgentId> due(
			List<AgentRecord> records,
			List<AgentId> urgent,
			long serverTick,
			int fullObservationBudget
	) {
		Objects.requireNonNull(records, "records must not be null");
		Objects.requireNonNull(urgent, "urgent must not be null");
		if (serverTick < 0L) throw new IllegalArgumentException("serverTick must be non-negative");
		if (fullObservationBudget < 1 || fullObservationBudget > AgentConstants.DEFAULT_AGENT_LIMIT) {
			throw new IllegalArgumentException("fullObservationBudget is out of range");
		}
		LinkedHashMap<AgentId, AgentRecord> registered = new LinkedHashMap<>();
		for (AgentRecord record : records) {
			AgentRecord checked = Objects.requireNonNull(record, "record must not be null");
			registered.putIfAbsent(checked.agentId(), checked);
			if (registered.size() == AgentConstants.DEFAULT_AGENT_LIMIT) break;
		}
		Set<AgentId> registeredIds = registered.keySet();
		lastDueTicks.keySet().removeIf(id -> !registeredIds.contains(id));
		reconcileActiveRotation(registered);

		ArrayList<AgentId> selected = new ArrayList<>(fullObservationBudget);
		HashSet<AgentId> selectedSet = new HashSet<>();
		for (AgentId agentId : urgent) {
			if (selected.size() == fullObservationBudget) break;
			if (agentId != null && registeredIds.contains(agentId) && selectedSet.add(agentId)) {
				selected.add(agentId);
				markActiveServed(agentId, registered.get(agentId));
			}
		}
		for (AgentRecord record : registered.values()) {
			if (selected.size() == fullObservationBudget) break;
			if (record.state().isActive() || selectedSet.contains(record.agentId())) continue;
			Long lastDue = lastDueTicks.get(record.agentId());
			if (lastDue == null || serverTick - lastDue >= IDLE_HEARTBEAT_TICKS) {
				selected.add(record.agentId());
				selectedSet.add(record.agentId());
			}
		}
		int examined = activeRotation.size();
		while (selected.size() < fullObservationBudget && examined-- > 0 && !activeRotation.isEmpty()) {
			AgentId agentId = activeRotation.removeFirst();
			activeRotation.addLast(agentId);
			if (selectedSet.add(agentId)) selected.add(agentId);
		}
		for (AgentId agentId : selected) lastDueTicks.put(agentId, serverTick);
		return List.copyOf(selected);
	}

	public synchronized void remove(AgentId agentId) {
		AgentId checked = Objects.requireNonNull(agentId, "agentId must not be null");
		activeRotation.remove(checked);
		lastDueTicks.remove(checked);
	}

	public synchronized void reset() {
		activeRotation.clear();
		lastDueTicks.clear();
	}

	private void reconcileActiveRotation(Map<AgentId, AgentRecord> registered) {
		activeRotation.removeIf(id -> !registered.containsKey(id) || !registered.get(id).state().isActive());
		Set<AgentId> retained = new HashSet<>(activeRotation);
		for (AgentRecord record : registered.values()) {
			if (record.state().isActive() && retained.add(record.agentId())) activeRotation.addLast(record.agentId());
		}
	}

	private void markActiveServed(AgentId agentId, AgentRecord record) {
		if (record == null || !record.state().isActive()) return;
		if (activeRotation.remove(agentId)) activeRotation.addLast(agentId);
	}
}
