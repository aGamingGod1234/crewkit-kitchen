package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server rules for a model's takeTask call: the model decided a player's message asks it to do
 * something, and Minecraft decides whether that request may become the agent's goal. Mirrors the
 * existing speech rules: any player may start work on an idle or completed agent and resume a
 * paused one; only an operator may put a new task over a paused one; active work is never replaced
 * here (operators keep the panel and speech paths); a taken-over agent accepts no new task.
 */
public final class ModelTaskAdoption {
	public enum Operation { START, RESUME }

	private ModelTaskAdoption() {
	}

	public static Operation operation(AgentLifecycleState state, boolean resumeRequested, boolean operator, boolean reserved) {
		Objects.requireNonNull(state, "state must not be null");
		if (reserved) {
			throw new AgentDomainException("AGENT_TAKEN_OVER", "A player is controlling this agent right now.");
		}
		if (resumeRequested) {
			if (state != AgentLifecycleState.PAUSED) {
				throw new AgentDomainException("NOTHING_TO_RESUME", "There is no paused task to resume.");
			}
			return Operation.RESUME;
		}
		if (state == AgentLifecycleState.IDLE || state == AgentLifecycleState.COMPLETED) return Operation.START;
		if (state == AgentLifecycleState.PAUSED) {
			if (operator) return Operation.START;
			throw new AgentDomainException("PAUSED_TASK",
					"I have a paused task. I can resume it, but only an operator can give me a different one.");
		}
		if (state.isActive()) {
			throw new AgentDomainException("AGENT_BUSY", "I already have a task.");
		}
		throw new AgentDomainException("AGENT_UNAVAILABLE",
				"I cannot take a task while " + state.name().toLowerCase(java.util.Locale.ROOT) + ".");
	}

	/**
	 * The requester must be the sender of a player message Minecraft actually delivered to this
	 * agent during its current goal revision, so a model cannot credit (or be talked into crediting)
	 * anyone else.
	 */
	public static void requireSpokenBy(Optional<SpokenRequest> spoken, UUID requesterId, long goalRevision) {
		Objects.requireNonNull(requesterId, "requesterId must not be null");
		SpokenRequest request = spoken.orElseThrow(() -> new AgentDomainException("UNKNOWN_REQUEST",
				"I cannot find the message that asked for this."));
		if (!request.sourceId().equals(requesterId)) {
			throw new AgentDomainException("REQUESTER_MISMATCH", "That message was sent by someone else.");
		}
		if (request.goalRevision() != goalRevision) {
			throw new AgentDomainException("STALE_REQUEST", "That message was for an earlier task.");
		}
	}

	/** One decoded takeTask request: the model's words plus the delivered message it answers. */
	public record TaskRequest(String requestId, long goalRevision, UUID requesterId, long conversationSequence,
			String request, boolean resume) {
		public TaskRequest {
			Objects.requireNonNull(requestId, "requestId must not be null");
			Objects.requireNonNull(requesterId, "requesterId must not be null");
			Objects.requireNonNull(request, "request must not be null");
		}
	}

	/** accepted: the goal started or resumed; pending: it starts once its translation validates; rejected: why not. */
	public record Outcome(String status, String reasonCode, String message, long goalRevision) {
		public static Outcome accepted(String reasonCode, String message, long goalRevision) {
			return new Outcome("accepted", reasonCode, message, goalRevision);
		}

		public static Outcome pending(String reasonCode, String message, long goalRevision) {
			return new Outcome("pending", reasonCode, message, goalRevision);
		}

		public static Outcome rejected(String reasonCode, String message, long goalRevision) {
			return new Outcome("rejected", reasonCode, message, goalRevision);
		}
	}

	public record SpokenRequest(long sequence, UUID sourceId, long goalRevision) {
		public SpokenRequest {
			Objects.requireNonNull(sourceId, "sourceId must not be null");
		}
	}

	/** Player messages delivered to each agent, bounded and single-use for task adoption. */
	public static final class SpokenRequests {
		static final int PER_AGENT = 32;
		private final Map<AgentId, ArrayDeque<SpokenRequest>> byAgent = new HashMap<>();

		public synchronized void record(ConversationEvent event) {
			Objects.requireNonNull(event, "event must not be null");
			if (event.kind() != ConversationKind.PLAYER_MESSAGE && event.kind() != ConversationKind.PROXIMITY_SPEECH) return;
			UUID sourceId;
			try {
				sourceId = UUID.fromString(event.sourceId());
			} catch (IllegalArgumentException exception) {
				return;
			}
			ArrayDeque<SpokenRequest> recent = byAgent.computeIfAbsent(event.agentId(), ignored -> new ArrayDeque<>());
			recent.addLast(new SpokenRequest(event.sequence(), sourceId, event.goalRevision()));
			while (recent.size() > PER_AGENT) recent.removeFirst();
		}

		public synchronized Optional<SpokenRequest> find(AgentId agentId, long sequence) {
			ArrayDeque<SpokenRequest> recent = byAgent.get(agentId);
			if (recent == null) return Optional.empty();
			return recent.stream().filter(entry -> entry.sequence() == sequence).findFirst();
		}

		/** One message adopts at most one task. */
		public synchronized void consume(AgentId agentId, long sequence) {
			ArrayDeque<SpokenRequest> recent = byAgent.get(agentId);
			if (recent == null) return;
			for (Iterator<SpokenRequest> iterator = recent.iterator(); iterator.hasNext();) {
				if (iterator.next().sequence() == sequence) iterator.remove();
			}
		}

		public synchronized void forget(AgentId agentId) {
			byAgent.remove(agentId);
		}
	}

	/**
	 * Remembers who asked for a model-adopted task so that player may confirm it is done, which
	 * open tasks need. Drafts awaiting translation are bound to their goal once it starts.
	 */
	public static final class Requesters {
		private record Entry(UUID requesterId, Optional<UUID> goalId, Optional<UUID> draftId) { }

		private final Map<AgentId, Entry> byAgent = new HashMap<>();

		public synchronized void startedGoal(AgentId agentId, UUID goalId, UUID requesterId) {
			byAgent.put(agentId, new Entry(requesterId, Optional.of(goalId), Optional.empty()));
		}

		public synchronized void pendingDraft(AgentId agentId, UUID draftId, UUID requesterId) {
			byAgent.put(agentId, new Entry(requesterId, Optional.empty(), Optional.of(draftId)));
		}

		public synchronized void draftActivated(AgentId agentId, UUID draftId, UUID goalId) {
			Entry entry = byAgent.get(agentId);
			if (entry != null && entry.draftId().equals(Optional.of(draftId))) {
				byAgent.put(agentId, new Entry(entry.requesterId(), Optional.of(goalId), Optional.empty()));
			}
		}

		public synchronized boolean mayConfirm(AgentId agentId, Optional<UUID> currentGoalId, UUID playerId) {
			Entry entry = byAgent.get(agentId);
			return entry != null && playerId != null && entry.requesterId().equals(playerId)
					&& entry.goalId().isPresent() && entry.goalId().equals(currentGoalId);
		}

		public synchronized void forget(AgentId agentId) {
			byAgent.remove(agentId);
		}
	}
}
