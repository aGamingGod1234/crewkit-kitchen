package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LeasedServerInputController implements ServerInputController {
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
		InputLease lease = new InputLease(agentId, owner, priority, ++sequence);
		states.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>())
				.put(lease, new LeaseState(null, deadline()));
		mutationRevision = Math.incrementExact(mutationRevision);
		return lease;
	}

	@Override
	public synchronized void apply(InputLease lease, AgentInputState state) {
		Objects.requireNonNull(lease, "lease must not be null");
		Objects.requireNonNull(state, "state must not be null");
		LinkedHashMap<InputLease, LeaseState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		agentStates.put(lease, new LeaseState(state, deadline()));
		mutationRevision = Math.incrementExact(mutationRevision);
		AgentInputState current = winningState(agentStates).orElse(null);
		if (!Objects.equals(previous, current) && current != null) sink.apply(lease.agentId(), previous, current);
	}

	@Override
	public synchronized void release(InputLease lease) {
		Objects.requireNonNull(lease, "lease must not be null");
		LinkedHashMap<InputLease, LeaseState> agentStates = requireActive(lease);
		AgentInputState previous = winningState(agentStates).orElse(null);
		agentStates.remove(lease);
		mutationRevision = Math.incrementExact(mutationRevision);
		AgentInputState current = winningState(agentStates).orElse(null);
		if (agentStates.isEmpty()) states.remove(lease.agentId());
		if (Objects.equals(previous, current)) return;
		if (current == null) sink.clear(lease.agentId(), previous);
		else sink.apply(lease.agentId(), previous, current);
	}

	@Override
	public synchronized void clear(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		LinkedHashMap<InputLease, LeaseState> removed = states.remove(agentId);
		AgentInputState previous = removed == null ? null : winningState(removed).orElse(null);
		if (removed != null) {
			mutationRevision = Math.incrementExact(mutationRevision);
			sink.clear(agentId, previous);
		}
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
			boolean removed = agentStates.entrySet().removeIf(lease -> lease.getValue().deadlineTick() <= currentTick);
			if (!removed) continue;
			mutationRevision = Math.incrementExact(mutationRevision);
			AgentInputState current = winningState(agentStates).orElse(null);
			if (agentStates.isEmpty()) agents.remove();
			if (Objects.equals(previous, current)) continue;
			if (current == null) sink.clear(agentId, previous);
			else sink.apply(agentId, previous, current);
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
		if (agentStates == null || !agentStates.containsKey(lease)) throw new IllegalStateException("input lease is no longer active");
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
}
