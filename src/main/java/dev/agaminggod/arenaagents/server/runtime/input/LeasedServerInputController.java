package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LeasedServerInputController implements ServerInputController {
	public static final class StaleInputLeaseException extends IllegalStateException {
		private StaleInputLeaseException() {
			super("input lease is no longer active");
		}
	}

	private static final Comparator<InputLease> PRECEDENCE = Comparator
			.comparingInt(InputLease::priority)
			.thenComparingLong(InputLease::sequence);

	private final InputStateSink sink;
	private final Map<AgentId, LinkedHashMap<InputLease, AgentInputState>> states = new LinkedHashMap<>();
	private long sequence;
	private long mutationRevision;

	public LeasedServerInputController(InputStateSink sink) {
		this.sink = Objects.requireNonNull(sink, "sink must not be null");
	}

	public synchronized long mutationRevision() {
		return mutationRevision;
	}

	@Override
	public synchronized InputLease acquire(AgentId agentId, InputOwner owner, int priority) {
		long nextSequence = Math.incrementExact(sequence);
		long nextRevision = Math.incrementExact(mutationRevision);
		InputLease lease = new InputLease(agentId, owner, priority, nextSequence);
		states.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>()).put(lease, null);
		sequence = nextSequence;
		mutationRevision = nextRevision;
		return lease;
	}

	@Override
	public synchronized void apply(InputLease lease, AgentInputState state) {
		Objects.requireNonNull(lease, "lease must not be null");
		Objects.requireNonNull(state, "state must not be null");
		LinkedHashMap<InputLease, AgentInputState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		AgentInputState current = winningStateAfterApply(agentStates, lease, state);
		long nextRevision = Math.incrementExact(mutationRevision);
		if (!Objects.equals(previous, current) && current != null) sink.apply(lease.agentId(), previous, current);
		agentStates.put(lease, state);
		mutationRevision = nextRevision;
	}

	@Override
	public synchronized void release(InputLease lease) {
		Objects.requireNonNull(lease, "lease must not be null");
		LinkedHashMap<InputLease, AgentInputState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		AgentInputState current = winningStateExcluding(agentStates, lease).orElse(null);
		long nextRevision = Math.incrementExact(mutationRevision);
		if (!Objects.equals(previous, current)) {
			if (current == null) sink.clear(lease.agentId(), previous);
			else sink.apply(lease.agentId(), previous, current);
		}
		agentStates.remove(lease);
		if (agentStates.isEmpty()) states.remove(lease.agentId());
		mutationRevision = nextRevision;
	}

	@Override
	public synchronized void clear(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		LinkedHashMap<InputLease, AgentInputState> current = states.get(agentId);
		if (current == null) return;
		AgentInputState previous = winningState(current).orElse(null);
		long nextRevision = Math.incrementExact(mutationRevision);
		sink.clear(agentId, previous);
		states.remove(agentId);
		mutationRevision = nextRevision;
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
		if (agentStates == null || !agentStates.containsKey(lease)) throw new StaleInputLeaseException();
		return agentStates;
	}

	private static Optional<AgentInputState> winningState(Map<InputLease, AgentInputState> states) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(Map.Entry::getValue);
	}

	private static AgentInputState winningStateAfterApply(
			Map<InputLease, AgentInputState> states,
			InputLease appliedLease,
			AgentInputState appliedState
	) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue() != null && PRECEDENCE.compare(entry.getKey(), appliedLease) > 0)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(Map.Entry::getValue)
				.orElse(appliedState);
	}

	private static Optional<AgentInputState> winningStateExcluding(
			Map<InputLease, AgentInputState> states,
			InputLease excludedLease
	) {
		return states.entrySet().stream()
				.filter(entry -> !entry.getKey().equals(excludedLease) && entry.getValue() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(Map.Entry::getValue);
	}
}
