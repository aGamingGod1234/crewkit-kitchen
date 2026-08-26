package dev.agaminggod.arenaagents.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;

public final class AgentRegistry {
	private final int maxAgents;
	private final int queueLimit;
	private final Map<AgentId, AgentRecord> records;
	private final Runnable onChange;
	private final Consumer<AgentTransition> transitionSink;

	public AgentRegistry(int maxAgents, int queueLimit, Runnable onChange, Consumer<AgentTransition> transitionSink) {
		this(maxAgents, queueLimit, Map.of(), onChange, transitionSink);
	}

	private AgentRegistry(
			int maxAgents,
			int queueLimit,
			Map<AgentId, AgentRecord> initialRecords,
			Runnable onChange,
			Consumer<AgentTransition> transitionSink
	) {
		this.maxAgents = requireBoundedPositive(
				maxAgents,
				"maxAgents",
				AgentConstants.MAX_CONFIGURED_AGENTS
		);
		this.queueLimit = requireBoundedPositive(
				queueLimit,
				"queueLimit",
				AgentConstants.MAX_CONFIGURED_QUEUE_LIMIT
		);
		this.records = new LinkedHashMap<>(Objects.requireNonNull(initialRecords, "initialRecords must not be null"));
		if (records.size() > maxAgents) {
			throw new AgentDomainException("AGENT_LIMIT_EXCEEDED", "Snapshot contains more agents than its configured limit");
		}
		this.onChange = Objects.requireNonNull(onChange, "onChange must not be null");
		this.transitionSink = Objects.requireNonNull(transitionSink, "transitionSink must not be null");
		validateUniqueNames(records.values());
	}

	public static AgentRegistry createDefault(Runnable onChange, Consumer<AgentTransition> transitionSink) {
		return new AgentRegistry(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				onChange,
				transitionSink
		);
	}

	public static AgentRegistry restore(
			Snapshot snapshot,
			Runnable onChange,
			Consumer<AgentTransition> transitionSink,
			long nowEpochMs
	) {
		return restore(snapshot, onChange, transitionSink, nowEpochMs, Set.of());
	}

	public static AgentRegistry restore(
			Snapshot snapshot,
			Runnable onChange,
			Consumer<AgentTransition> transitionSink,
			long nowEpochMs,
			Set<AgentId> pendingConversationWakeAgents
	) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Set<AgentId> wakeAgents = Set.copyOf(Objects.requireNonNull(
				pendingConversationWakeAgents, "pendingConversationWakeAgents must not be null"
		));
		LinkedHashMap<AgentId, AgentRecord> recovered = new LinkedHashMap<>();
		for (AgentRecord record : snapshot.records()) {
			AgentRecord revised = wakeAgents.contains(record.agentId())
					? recoverConversationWake(record, nowEpochMs)
					: AgentLifecycleReducer.recoverAfterReload(record, nowEpochMs);
			if (recovered.put(revised.agentId(), revised) != null) {
				throw new AgentDomainException("DUPLICATE_AGENT_ID", "Duplicate agent ID in snapshot: " + revised.agentId());
			}
		}
		return new AgentRegistry(snapshot.maxAgents(), snapshot.queueLimit(), recovered, onChange, transitionSink);
	}

	private static AgentRecord recoverConversationWake(AgentRecord record, long nowEpochMs) {
		if (!record.state().isReloadUncertain() || record.currentGoal().isEmpty()) {
			throw new AgentDomainException("INVALID_CONVERSATION_WAKE", "Pending conversation wake does not own recoverable active work");
		}
		return record.withLifecycle(
				AgentLifecycleState.STARTING, record.currentGoal(), record.goalRevision(), record.queuedGoals(), nowEpochMs, ""
		);
	}

	public synchronized int availableCapacity() {
		return maxAgents - records.size();
	}

	public synchronized void requireCapacity(int requested) {
		if (requested < 0) throw new IllegalArgumentException("requested capacity must not be negative");
		int available = maxAgents - records.size();
		if (requested > available) {
			throw new AgentDomainException(
					"AGENT_LIMIT_REACHED",
					"Agent limit reached: " + maxAgents + " total, " + available + " available"
			);
		}
	}

	public synchronized AgentRecord create(
			String model,
			String reasoning,
			Optional<String> userName,
			long nowEpochMs
	) {
		return create("codex", model, reasoning, userName, nowEpochMs);
	}

	public synchronized AgentRecord create(
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			long nowEpochMs
	) {
		return create(provider, model, reasoning, userName, AgentGameMode.SURVIVAL, nowEpochMs);
	}

	public synchronized AgentRecord create(
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			AgentGameMode gameMode,
			long nowEpochMs
	) {
		return create(provider, model, reasoning, "priority", userName, gameMode, nowEpochMs);
	}

	public synchronized AgentRecord create(
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			Optional<String> userName,
			AgentGameMode gameMode,
			long nowEpochMs
	) {
		if (records.size() >= maxAgents) {
			throw new AgentDomainException("AGENT_LIMIT_REACHED", "Agent limit reached: " + maxAgents);
		}
		Optional<String> checkedName = Objects.requireNonNull(userName, "userName must not be null")
				.map(AgentValidators::requireUserName);
		checkedName.ifPresent(this::requireUniqueName);
		AgentId id;
		do {
			id = AgentId.random();
		} while (records.containsKey(id));
		int skinVariant = Math.floorMod(id.value().hashCode(), AgentConstants.DEFAULT_SKIN_VARIANT_COUNT);
		AgentProfile profile = new AgentProfile(provider, model, reasoning, serviceTier, checkedName, skinVariant, gameMode);
		AgentRecord created = AgentRecord.create(id, profile, nowEpochMs);
		records.put(id, created);
		onChange.run();
		return created;
	}

	public synchronized AgentRecord attachEntity(AgentId id, UUID entityUuid, long nowEpochMs) {
		AgentRecord current = require(id);
		AgentRecord revised = current.withEntityUuid(Optional.of(
				Objects.requireNonNull(entityUuid, "entityUuid must not be null")
		), nowEpochMs);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentRecord attachEntity(
			AgentId id,
			UUID entityUuid,
			AgentEntityLocation entityLocation,
			long nowEpochMs
	) {
		AgentRecord current = require(id);
		AgentRecord revised = current.withEntity(
				Optional.of(Objects.requireNonNull(entityUuid, "entityUuid must not be null")),
				Optional.of(Objects.requireNonNull(entityLocation, "entityLocation must not be null")),
				nowEpochMs
		);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentRecord detachEntity(AgentId id, long nowEpochMs) {
		AgentRecord current = require(id);
		if (current.entityUuid().isEmpty()) return current;
		AgentRecord revised = current.withEntityUuid(Optional.empty(), nowEpochMs);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentRecord updateEntityLocation(
			AgentId id,
			AgentEntityLocation entityLocation,
			long nowEpochMs
	) {
		AgentRecord current = require(id);
		AgentEntityLocation checkedLocation = Objects.requireNonNull(entityLocation, "entityLocation must not be null");
		if (current.entityLocation().filter(checkedLocation::equals).isPresent()) {
			return current;
		}
		AgentRecord revised = current.withEntityLocation(checkedLocation, nowEpochMs);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentRecord setAutomaticProgress(AgentId id, boolean enabled, long nowEpochMs) {
		AgentRecord current = require(id);
		if (current.automaticProgress() == enabled) {
			return current;
		}
		AgentRecord revised = current.withAutomaticProgress(enabled, nowEpochMs);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentTransition start(AgentId id, String prompt, long nowEpochMs) {
		return apply(AgentLifecycleReducer.start(require(id), prompt, nowEpochMs));
	}

	public synchronized AgentTransition start(AgentId id, GoalSpec spec, long nowEpochMs) {
		return apply(AgentLifecycleReducer.start(require(id), spec, nowEpochMs));
	}

	/** Commits a prepared start only after its publication barrier succeeds. */
	public synchronized AgentTransition startAtomically(
			AgentId id,
			String prompt,
			long nowEpochMs,
			BiConsumer<AgentTransition, Runnable> barrier
	) {
		Objects.requireNonNull(barrier, "barrier must not be null");
		AgentTransition transition = AgentLifecycleReducer.start(require(id), prompt, nowEpochMs);
		return applyAtomically(transition, barrier, "start");
	}

	public synchronized AgentTransition startAtomically(
			AgentId id,
			GoalSpec spec,
			long nowEpochMs,
			BiConsumer<AgentTransition, Runnable> barrier
	) {
		Objects.requireNonNull(barrier, "barrier must not be null");
		AgentTransition transition = AgentLifecycleReducer.start(require(id), spec, nowEpochMs);
		return applyAtomically(transition, barrier, "start");
	}

	public synchronized AgentTransition queue(AgentId id, String prompt, long nowEpochMs) {
		return apply(AgentLifecycleReducer.queue(require(id), prompt, queueLimit, nowEpochMs));
	}

	public synchronized AgentTransition queue(AgentId id, GoalSpec spec, long nowEpochMs) {
		return apply(AgentLifecycleReducer.queue(require(id), spec, queueLimit, nowEpochMs));
	}

	public synchronized AgentTransition stop(AgentId id, long nowEpochMs) {
		return apply(AgentLifecycleReducer.stop(require(id), nowEpochMs));
	}

	public synchronized AgentTransition resume(AgentId id, long nowEpochMs) {
		return apply(AgentLifecycleReducer.resume(require(id), nowEpochMs));
	}

	public synchronized AgentTransition steer(AgentId id, String prompt, long nowEpochMs) {
		return apply(AgentLifecycleReducer.steer(require(id), prompt, nowEpochMs));
	}

	public synchronized AgentTransition beginPlanning(AgentId id, long nowEpochMs) {
		return apply(AgentLifecycleReducer.beginPlanning(require(id), nowEpochMs));
	}

	public synchronized AgentTransition beginAction(AgentId id, long revision, long nowEpochMs) {
		return apply(AgentLifecycleReducer.beginAction(require(id), revision, nowEpochMs));
	}

	public synchronized AgentTransition actionFinished(AgentId id, long revision, long nowEpochMs) {
		return apply(AgentLifecycleReducer.actionFinished(require(id), revision, nowEpochMs));
	}

	public synchronized AgentTransition completeGoal(AgentId id, long revision, long nowEpochMs) {
		return apply(AgentLifecycleReducer.completeGoal(require(id), revision, nowEpochMs));
	}

	public synchronized AgentTransition satisfyGoal(AgentId id, long revision, GoalEvidence evidence, long nowEpochMs) {
		return apply(AgentLifecycleReducer.satisfyGoal(require(id), revision, evidence, nowEpochMs));
	}

	public synchronized AgentTransition promoteSatisfied(AgentId id, long nowEpochMs) {
		return apply(AgentLifecycleReducer.promoteSatisfied(require(id), nowEpochMs));
	}

	/** Applies a coordinator-owned terminal state, promoting queued work when present. */
	public synchronized AgentRecord coordinatorCompleted(AgentId id, long revision, long nowEpochMs) {
		AgentRecord current = require(id);
		if (current.goalRevision() != revision) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator completion revision is stale");
		}
		if (current.state() == AgentLifecycleState.COMPLETED) return current;
		if (!current.state().isActive()) {
			throw new AgentDomainException("INVALID_AGENT_STATE", "Coordinator completion requires an active agent");
		}
		if (!current.queuedGoals().isEmpty()) {
			return apply(AgentLifecycleReducer.completeGoal(current, revision, nowEpochMs)).after();
		}
		AgentRecord completed = current.withLifecycle(
				AgentLifecycleState.COMPLETED,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return apply(new AgentTransition(current, completed, false, false)).after();
	}

	public synchronized AgentTransition fail(AgentId id, String message, long nowEpochMs) {
		return apply(AgentLifecycleReducer.fail(require(id), message, nowEpochMs));
	}

	public synchronized AgentTransition disconnect(AgentId id, long nowEpochMs) {
		return apply(AgentLifecycleReducer.disconnect(require(id), nowEpochMs));
	}

	/** Re-arms a durable wake after transport loss without manufacturing a new goal revision. */
	public synchronized AgentTransition rearmConversationWake(
			AgentId id,
			long goalRevision,
			UUID goalId,
			long nowEpochMs
	) {
		AgentRecord current = require(id);
		if (current.goalRevision() != goalRevision
				|| current.currentGoal().map(goal -> !goal.goalId().equals(goalId)).orElse(true)) {
			throw new AgentDomainException("STALE_REVISION", "Pending conversation wake no longer owns the current goal");
		}
		if (current.state() == AgentLifecycleState.STARTING) {
			return new AgentTransition(current, current, false, false);
		}
		if (!current.state().isActive() && current.state() != AgentLifecycleState.PAUSED
				&& current.state() != AgentLifecycleState.DISCONNECTED) {
			throw new AgentDomainException("INVALID_AGENT_STATE", "Pending conversation wake cannot re-arm " + current.state());
		}
		AgentRecord rearmed = current.withLifecycle(
				AgentLifecycleState.STARTING, current.currentGoal(), current.goalRevision(), current.queuedGoals(), nowEpochMs, ""
		);
		records.put(id, rearmed);
		onChange.run();
		return new AgentTransition(current, rearmed, current.state() != AgentLifecycleState.STARTING, true);
	}

	public synchronized AgentTransition die(AgentId id, AgentDeathSnapshot deathSnapshot, long nowEpochMs) {
		return apply(AgentLifecycleReducer.die(require(id), deathSnapshot, nowEpochMs));
	}

	public synchronized AgentTransition respawn(AgentId id, UUID entityUuid, long nowEpochMs) {
		return apply(AgentLifecycleReducer.respawn(require(id), entityUuid, nowEpochMs));
	}

	/** Commits a prepared respawn only after its physical/protocol barrier succeeds. */
	public synchronized AgentTransition respawnAtomically(
			AgentId id,
			UUID entityUuid,
			long nowEpochMs,
			BiConsumer<AgentTransition, Runnable> barrier
	) {
		Objects.requireNonNull(barrier, "barrier must not be null");
		AgentTransition transition = AgentLifecycleReducer.respawn(require(id), entityUuid, nowEpochMs);
		return applyAtomically(transition, barrier, "respawn");
	}

	public synchronized AgentTransition respawnAtomically(
			AgentId id,
			UUID entityUuid,
			AgentEntityLocation entityLocation,
			long nowEpochMs,
			BiConsumer<AgentTransition, Runnable> barrier
	) {
		Objects.requireNonNull(entityLocation, "entityLocation must not be null");
		Objects.requireNonNull(barrier, "barrier must not be null");
		AgentTransition lifecycle = AgentLifecycleReducer.respawn(require(id), entityUuid, nowEpochMs);
		AgentTransition located = new AgentTransition(
				lifecycle.before(), lifecycle.after().withEntityLocation(entityLocation, nowEpochMs),
				lifecycle.cancelAction(), lifecycle.interruptPlanner()
		);
		return applyAtomically(located, barrier, "respawn");
	}

	private AgentTransition applyAtomically(
			AgentTransition transition,
			BiConsumer<AgentTransition, Runnable> barrier,
			String operation
	) {
		AgentId id = transition.after().agentId();
		boolean[] committed = { false };
		Runnable commit = () -> {
			if (committed[0]) throw new IllegalStateException(operation + " transition was already committed");
			records.put(id, transition.after());
			try {
				onChange.run();
				committed[0] = true;
			} catch (RuntimeException exception) {
				records.put(id, transition.before());
				throw exception;
			}
		};
		try {
			barrier.accept(transition, commit);
			if (!committed[0]) throw new IllegalStateException(operation + " barrier did not commit the transition");
			return transition;
		} catch (RuntimeException exception) {
			if (committed[0]) {
				records.put(id, transition.before());
				try {
					onChange.run();
				} catch (RuntimeException rollbackFailure) {
					exception.addSuppressed(rollbackFailure);
				}
				committed[0] = false;
			}
			throw exception;
		}
	}

	public synchronized AgentRecord updateRecovery(
			AgentId id,
			String summary,
			String inventorySnapshot,
			long nowEpochMs
	) {
		AgentRecord revised = require(id).withRecovery(summary, inventorySnapshot, nowEpochMs);
		records.put(id, revised);
		onChange.run();
		return revised;
	}

	public synchronized AgentRecord remove(AgentId id) {
		AgentRecord removed = records.remove(Objects.requireNonNull(id, "id must not be null"));
		if (removed == null) {
			throw new AgentDomainException("AGENT_NOT_FOUND", "Unknown agent: " + id);
		}
		onChange.run();
		return removed;
	}

	public synchronized boolean isCurrentActiveRevision(AgentId id, long goalRevision) {
		AgentRecord record = records.get(Objects.requireNonNull(id, "id must not be null"));
		return record != null && record.state().isActive() && record.acceptsRevision(goalRevision);
	}

	public synchronized AgentRecord require(AgentId id) {
		AgentRecord record = records.get(Objects.requireNonNull(id, "id must not be null"));
		if (record == null) {
			throw new AgentDomainException("AGENT_NOT_FOUND", "Unknown agent: " + id);
		}
		return record;
	}

	public synchronized AgentRecord resolve(String selector) {
		String checked = AgentValidators.requireNonBlank(selector, "agent", 64).trim();
		try {
			AgentRecord exact = records.get(AgentId.parse(checked));
			if (exact != null) {
				return exact;
			}
		} catch (AgentDomainException ignored) {
			// A command selector may be a short ID or user name instead of a full UUID.
		}

		String folded = checked.toLowerCase(Locale.ROOT);
		List<AgentRecord> matches = records.values().stream()
				.filter(record -> record.agentId().startsWith(checked)
						|| record.profile().userName().map(name -> name.toLowerCase(Locale.ROOT).equals(folded)).orElse(false))
				.toList();
		if (matches.isEmpty()) {
			throw new AgentDomainException("AGENT_NOT_FOUND", "Unknown agent: " + checked);
		}
		if (matches.size() > 1) {
			throw new AgentDomainException("AMBIGUOUS_AGENT", "Agent selector is ambiguous: " + checked);
		}
		return matches.getFirst();
	}

	public synchronized List<AgentRecord> records() {
		return records.values().stream()
				.sorted(Comparator.comparingLong(AgentRecord::createdAtEpochMs).thenComparing(AgentRecord::agentId))
				.toList();
	}

	public synchronized List<String> selectors() {
		ArrayList<String> selectors = new ArrayList<>();
		for (AgentRecord record : records()) {
			selectors.add(record.agentId().shortValue());
			record.profile().userName().ifPresent(selectors::add);
		}
		return List.copyOf(selectors);
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(AgentConstants.SCHEMA_VERSION, maxAgents, queueLimit, records());
	}

	public int maxAgents() {
		return maxAgents;
	}

	public int queueLimit() {
		return queueLimit;
	}

	private AgentTransition apply(AgentTransition transition) {
		if (transition.before() == transition.after()) {
			return transition;
		}
		records.put(transition.after().agentId(), transition.after());
		onChange.run();
		transitionSink.accept(transition);
		return transition;
	}

	private void requireUniqueName(String proposedName) {
		String folded = proposedName.toLowerCase(Locale.ROOT);
		boolean duplicate = records.values().stream()
				.flatMap(record -> record.profile().userName().stream())
				.anyMatch(existing -> existing.toLowerCase(Locale.ROOT).equals(folded));
		if (duplicate) {
			throw new AgentDomainException("DUPLICATE_AGENT_NAME", "Agent name is already in use: " + proposedName);
		}
	}

	private static void validateUniqueNames(Collection<AgentRecord> records) {
		ArrayList<String> names = new ArrayList<>();
		for (AgentRecord record : records) {
			record.profile().userName().ifPresent(name -> {
				String folded = name.toLowerCase(Locale.ROOT);
				if (names.contains(folded)) {
					throw new AgentDomainException("DUPLICATE_AGENT_NAME", "Duplicate agent name in snapshot: " + name);
				}
				names.add(folded);
			});
		}
	}

	private static int requireBoundedPositive(int value, String name, int maximum) {
		if (value <= 0 || value > maximum) {
			throw new AgentDomainException(
					"INVALID_LIMIT",
					name + " must be between 1 and " + maximum
			);
		}
		return value;
	}

	public record Snapshot(int schemaVersion, int maxAgents, int queueLimit, List<AgentRecord> records) {
		public Snapshot {
			if (schemaVersion != AgentConstants.SCHEMA_VERSION) {
				throw new AgentDomainException("UNSUPPORTED_SCHEMA", "Unsupported registry schema: " + schemaVersion);
			}
			records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
		}
	}
}
