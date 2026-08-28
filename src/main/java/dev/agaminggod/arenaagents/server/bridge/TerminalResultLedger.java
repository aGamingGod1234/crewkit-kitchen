package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Retains terminal action results until the authenticated coordinator acknowledges them.
 *
 * <p>The ledger is deliberately independent of action execution. A result is retained after
 * the physical action has been cleaned up, so reconnect replay can never execute the action a
 * second time. Session ownership is only a delivery hint; closing a session fences that hint
 * and makes the result eligible for the next session.</p>
 */
final class TerminalResultLedger {
	private static final int MAX_RESULTS_PER_AGENT = 4_096;
	private final Map<AgentId, GoalFence> goalFences = new LinkedHashMap<>();
	private final LinkedHashMap<Key, Entry> pending = new LinkedHashMap<>();

	/** Advances a lifecycle revision while retaining terminal results for the same logical goal. */
	synchronized void beginGoal(AgentId agentId, long goalRevision, UUID logicalGoalId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (goalRevision < 0L) throw new IllegalArgumentException("goalRevision must be nonnegative");
		GoalFence previous = goalFences.put(agentId, new GoalFence(goalRevision, logicalGoalId));
		if (previous != null && logicalGoalId != null && logicalGoalId.equals(previous.logicalGoalId())) return;
		removeOtherGoals(agentId, logicalGoalId);
	}

	/** Retains a result only when it belongs to the current goal fence. */
	synchronized boolean retain(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		GoalFence current = goalFences.get(result.agentId());
		if (current != null && current.goalRevision() != result.goalRevision()) return false;
		UUID logicalGoalId = current == null ? null : current.logicalGoalId();
		if (current == null) goalFences.put(result.agentId(), new GoalFence(result.goalRevision(), null));
		Key key = new Key(result.agentId(), result.goalRevision(), result.actionId());
		if (pending.containsKey(key)) return true;
		pending.put(key, new Entry(result, logicalGoalId));
		trimAgent(result.agentId());
		return true;
	}

	synchronized List<ServerActionResult> pending() {
		return pending.values().stream().map(Entry::result).toList();
	}

	/** Claims one enqueue attempt for a session, coalescing repeated server ticks. */
	synchronized boolean claim(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry == null) return false;
		if (entry.queuedSession() == session) return false;
		entry.queuedSession = Objects.requireNonNull(session, "session must not be null");
		return true;
	}

	synchronized void release(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry != null && entry.queuedSession() == session) entry.queuedSession = null;
	}

	/** Removes a result only when the caller owns its failed enqueue attempt. */
	synchronized void discard(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry != null && entry.queuedSession() == session) pending.remove(key(result));
	}

	/** Fences all enqueue claims from a closed session without dropping the result. */
	synchronized void sessionClosed(Object session) {
		Objects.requireNonNull(session, "session must not be null");
		for (Entry entry : pending.values()) {
			if (entry.queuedSession() == session) entry.queuedSession = null;
		}
	}

	synchronized boolean acknowledge(AgentId agentId, long goalRevision, String actionId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(actionId, "actionId must not be null");
		return pending.remove(new Key(agentId, goalRevision, actionId)) != null;
	}

	synchronized void remove(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		goalFences.remove(agentId);
		for (Iterator<Key> iterator = pending.keySet().iterator(); iterator.hasNext();) {
			if (iterator.next().agentId().equals(agentId)) iterator.remove();
		}
	}

	synchronized int pendingCount() {
		return pending.size();
	}

	private void removeOtherGoals(AgentId agentId, UUID logicalGoalId) {
		for (Iterator<Key> iterator = pending.keySet().iterator(); iterator.hasNext();) {
			Key key = iterator.next();
			if (key.agentId().equals(agentId)
					&& (logicalGoalId == null || !logicalGoalId.equals(pending.get(key).logicalGoalId()))) iterator.remove();
		}
	}

	private void trimAgent(AgentId agentId) {
		int count = 0;
		for (Key key : pending.keySet()) if (key.agentId().equals(agentId)) count++;
		if (count <= MAX_RESULTS_PER_AGENT) return;
		for (Iterator<Key> iterator = pending.keySet().iterator(); iterator.hasNext() && count > MAX_RESULTS_PER_AGENT;) {
			if (iterator.next().agentId().equals(agentId)) {
				iterator.remove();
				count--;
			}
		}
	}

	private static Key key(ServerActionResult result) {
		return new Key(result.agentId(), result.goalRevision(), result.actionId());
	}

	private record Key(AgentId agentId, long goalRevision, String actionId) { }

	private record GoalFence(long goalRevision, UUID logicalGoalId) { }

	private static final class Entry {
		private final ServerActionResult result;
		private final UUID logicalGoalId;
		private Object queuedSession;

		private Entry(ServerActionResult result, UUID logicalGoalId) {
			this.result = result;
			this.logicalGoalId = logicalGoalId;
		}

		private ServerActionResult result() { return result; }
		private UUID logicalGoalId() { return logicalGoalId; }
		private Object queuedSession() { return queuedSession; }
	}
}
