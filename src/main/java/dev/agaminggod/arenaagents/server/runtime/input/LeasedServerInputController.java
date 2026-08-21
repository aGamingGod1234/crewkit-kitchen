package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LeasedServerInputController implements ServerInputController {
	private static final Comparator<InputLease> PRECEDENCE = Comparator
			.comparingInt(InputLease::priority)
			.thenComparingLong(InputLease::sequence);

	private final InputStateSink sink;
	private final Map<AgentId, LinkedHashMap<InputLease, AgentInputState>> states = new LinkedHashMap<>();
	private long sequence;

	public LeasedServerInputController(InputStateSink sink) {
		this.sink = Objects.requireNonNull(sink, "sink must not be null");
	}

	@Override
	public synchronized InputLease acquire(AgentId agentId, InputOwner owner, int priority) {
		InputLease lease = new InputLease(agentId, owner, priority, ++sequence);
		states.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>()).put(lease, null);
		return lease;
	}

	@Override
	public synchronized void apply(InputLease lease, AgentInputState state) {
		Objects.requireNonNull(lease, "lease must not be null");
		Objects.requireNonNull(state, "state must not be null");
		LinkedHashMap<InputLease, AgentInputState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		agentStates.put(lease, state);
		AgentInputState current = winningState(agentStates).orElse(null);
		if (!Objects.equals(previous, current) && current != null) sink.apply(lease.agentId(), previous, current);
	}

	@Override
	public synchronized void release(InputLease lease) {
		Objects.requireNonNull(lease, "lease must not be null");
		LinkedHashMap<InputLease, AgentInputState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		agentStates.remove(lease);
		AgentInputState current = winningState(agentStates).orElse(null);
		if (agentStates.isEmpty()) states.remove(lease.agentId());
		if (Objects.equals(previous, current)) return;
		if (current == null) sink.clear(lease.agentId(), previous);
		else sink.apply(lease.agentId(), previous, current);
	}

	@Override
	public synchronized void clear(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		LinkedHashMap<InputLease, AgentInputState> removed = states.remove(agentId);
		AgentInputState previous = removed == null ? null : winningState(removed).orElse(null);
		if (removed != null) sink.clear(agentId, previous);
	}

	@Override
	public synchronized Optional<AgentInputState> currentState(AgentId agentId) {
		LinkedHashMap<InputLease, AgentInputState> agentStates = states.get(
				Objects.requireNonNull(agentId, "agentId must not be null")
		);
		return agentStates == null ? Optional.empty() : winningState(agentStates);
	}

	private LinkedHashMap<InputLease, AgentInputState> requireActive(InputLease lease) {
		LinkedHashMap<InputLease, AgentInputState> agentStates = states.get(lease.agentId());
		if (agentStates == null || !agentStates.containsKey(lease)) throw new IllegalStateException("input lease is no longer active");
		return agentStates;
	}

	private static Optional<AgentInputState> winningState(Map<InputLease, AgentInputState> states) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(Map.Entry::getValue);
	}
}
