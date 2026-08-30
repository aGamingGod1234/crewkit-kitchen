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

	public static final long LEASE_TIMEOUT_TICKS = 40L;
	private static final Comparator<InputLease> PRECEDENCE = Comparator
			.comparingInt(InputLease::priority)
			.thenComparingLong(InputLease::sequence);

	private final InputStateSink sink;
	private final Map<AgentId, LinkedHashMap<InputLease, LeaseState>> states = new LinkedHashMap<>();
	private long sequence;
	private long currentTick;
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
		long nextDeadline = deadline();
		InputLease lease = new InputLease(agentId, owner, priority, nextSequence);
		states.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>())
				.put(lease, new LeaseState(null, nextDeadline));
		sequence = nextSequence;
		mutationRevision = nextRevision;
		return lease;
	}

	@Override
	public synchronized void apply(InputLease lease, AgentInputState state) {
		Objects.requireNonNull(lease, "lease must not be null");
		Objects.requireNonNull(state, "state must not be null");
		LinkedHashMap<InputLease, LeaseState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		AgentInputState current = winningStateAfterApply(agentStates, lease, state);
		long nextRevision = Math.incrementExact(mutationRevision);
		long nextDeadline = deadline();
		if (!Objects.equals(previous, current) && current != null) sink.apply(lease.agentId(), previous, current);
		agentStates.put(lease, new LeaseState(state, nextDeadline));
		mutationRevision = nextRevision;
	}

	@Override
	public synchronized void release(InputLease lease) {
		Objects.requireNonNull(lease, "lease must not be null");
		LinkedHashMap<InputLease, LeaseState> agentStates = requireActive(lease);
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
		LinkedHashMap<InputLease, LeaseState> current = states.get(agentId);
		if (current == null) return;
		AgentInputState previous = winningState(current).orElse(null);
		long nextRevision = Math.incrementExact(mutationRevision);
		sink.clear(agentId, previous);
		states.remove(agentId);
		mutationRevision = nextRevision;
	}

	@Override
	public synchronized Optional<AgentInputState> currentState(AgentId agentId) {
		LinkedHashMap<InputLease, LeaseState> agentStates = states.get(
				Objects.requireNonNull(agentId, "agentId must not be null")
		);
		return agentStates == null ? Optional.empty() : winningState(agentStates);
	}

	/** Advances the server-tick deadman and neutralizes leases that stopped renewing. */
	public synchronized void tick() {
		currentTick = Math.incrementExact(currentTick);
		var agents = states.entrySet().iterator();
		while (agents.hasNext()) {
			Map.Entry<AgentId, LinkedHashMap<InputLease, LeaseState>> entry = agents.next();
			AgentId agentId = entry.getKey();
			LinkedHashMap<InputLease, LeaseState> agentStates = entry.getValue();
			AgentInputState previous = winningState(agentStates).orElse(null);
			boolean expired = agentStates.values().stream().anyMatch(state -> state.deadlineTick() <= currentTick);
			if (!expired) continue;
			AgentInputState current = winningStateAfterExpiration(agentStates, currentTick).orElse(null);
			long nextRevision = Math.incrementExact(mutationRevision);
			if (!Objects.equals(previous, current)) {
				if (current == null) sink.clear(agentId, previous);
				else sink.apply(agentId, previous, current);
			}
			agentStates.entrySet().removeIf(lease -> lease.getValue().deadlineTick() <= currentTick);
			if (agentStates.isEmpty()) agents.remove();
			mutationRevision = nextRevision;
		}
		for (Map.Entry<AgentId, LinkedHashMap<InputLease, LeaseState>> entry : states.entrySet()) {
			winningState(entry.getValue()).ifPresent(state -> sink.tick(entry.getKey(), state));
		}
	}

	private long deadline() {
		return Math.addExact(currentTick, LEASE_TIMEOUT_TICKS);
	}

	private LinkedHashMap<InputLease, LeaseState> requireActive(InputLease lease) {
		LinkedHashMap<InputLease, LeaseState> agentStates = states.get(lease.agentId());
		if (agentStates == null || !agentStates.containsKey(lease)) throw new StaleInputLeaseException();
		return agentStates;
	}

	private static Optional<AgentInputState> winningState(Map<InputLease, LeaseState> states) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue().state() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(entry -> entry.getValue().state());
	}

	private record LeaseState(AgentInputState state, long deadlineTick) {
	}

	private static AgentInputState winningStateAfterApply(
			Map<InputLease, LeaseState> states,
			InputLease appliedLease,
			AgentInputState appliedState
	) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue().state() != null && PRECEDENCE.compare(entry.getKey(), appliedLease) > 0)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(entry -> entry.getValue().state())
				.orElse(appliedState);
	}

	private static Optional<AgentInputState> winningStateExcluding(
			Map<InputLease, LeaseState> states,
			InputLease excludedLease
	) {
		return states.entrySet().stream()
				.filter(entry -> !entry.getKey().equals(excludedLease) && entry.getValue().state() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(entry -> entry.getValue().state());
	}

	private static Optional<AgentInputState> winningStateAfterExpiration(
			Map<InputLease, LeaseState> states,
			long currentTick
	) {
		return states.entrySet().stream()
				.filter(entry -> entry.getValue().deadlineTick() > currentTick && entry.getValue().state() != null)
				.max(Map.Entry.comparingByKey(PRECEDENCE))
				.map(entry -> entry.getValue().state());
	}
}
