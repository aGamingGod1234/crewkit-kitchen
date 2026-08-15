package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, bridge-lifetime binding between a model program step and its action ID. */
final class ProgramActionLedger {
	private static final int MAX_TRACKED_ACTIONS_PER_AGENT = 4_096;
	private final Map<AgentId, LinkedHashMap<String, ActionProvenance>> accepted = new HashMap<>();
	private final Map<AgentId, LinkedHashMap<String, ActionProvenance>> terminal = new HashMap<>();
	private final Map<AgentId, LinkedHashMap<ActionProvenance, String>> actionIdsByProgramStep = new HashMap<>();

	synchronized void accept(ServerActionRequest request) {
		ActionProvenance prior = lookup(accepted, request.agentId(), request.actionId());
		if (prior == null) prior = lookup(terminal, request.agentId(), request.actionId());
		if (prior != null) {
			if (!prior.equals(request.provenance())) {
				throw new AgentDomainException("ACTION_PROVENANCE_MISMATCH", "Action ID is already bound to a different program step");
			}
			throw new AgentDomainException("ACTION_REPLAY", "Action ID has already been accepted");
		}
		LinkedHashMap<ActionProvenance, String> actionIds = actionIdsByProgramStep.computeIfAbsent(request.agentId(), ignored -> new LinkedHashMap<>());
		String priorActionId = actionIds.get(request.provenance());
		if (priorActionId != null) throw new AgentDomainException("ACTION_REPLAY", "Program step is already bound to action ID " + priorActionId);
		actionIds.put(request.provenance(), request.actionId());
		trim(actionIds);
		LinkedHashMap<String, ActionProvenance> entries = accepted.computeIfAbsent(request.agentId(), ignored -> new LinkedHashMap<>());
		entries.put(request.actionId(), request.provenance());
		trim(entries);
	}

	synchronized void terminal(ServerActionResult result) {
		LinkedHashMap<String, ActionProvenance> entries = accepted.get(result.agentId());
		if (entries == null) return;
		ActionProvenance provenance = entries.remove(result.actionId());
		if (provenance == null) return;
		LinkedHashMap<String, ActionProvenance> completed = terminal.computeIfAbsent(result.agentId(), ignored -> new LinkedHashMap<>());
		completed.put(result.actionId(), provenance);
		trim(completed);
	}

	synchronized void remove(AgentId agentId) {
		accepted.remove(agentId);
		terminal.remove(agentId);
		actionIdsByProgramStep.remove(agentId);
	}

	private static ActionProvenance lookup(Map<AgentId, LinkedHashMap<String, ActionProvenance>> entries, AgentId agentId, String actionId) {
		LinkedHashMap<String, ActionProvenance> perAgent = entries.get(agentId);
		return perAgent == null ? null : perAgent.get(actionId);
	}

	private static void trim(LinkedHashMap<?, ?> entries) {
		while (entries.size() > MAX_TRACKED_ACTIONS_PER_AGENT) entries.remove(entries.keySet().iterator().next());
	}
}
