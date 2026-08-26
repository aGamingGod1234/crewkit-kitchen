package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentRegistrySnapshotCodec;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWakeCodec;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraftCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentSavedData extends SavedData {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentSavedData.class);
	private static final String PAYLOAD_FIELD = "payload";
	private static final String PAYLOAD_CHUNKS_FIELD = "payload_chunks";
	private static final String CONVERSATION_WAKES_FIELD = "conversation_wakes";
	private static final String GOAL_DRAFTS_FIELD = "goal_drafts";
	private static final AgentRegistrySnapshotCodec SNAPSHOT_CODEC = new AgentRegistrySnapshotCodec();
	private static final PendingConversationWakeCodec WAKE_CODEC = new PendingConversationWakeCodec();
	private static final PendingGoalDraftCodec DRAFT_CODEC = new PendingGoalDraftCodec();
	private static final Codec<AgentSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf(PAYLOAD_FIELD, "").forGetter(data -> ""),
			Codec.STRING.listOf().optionalFieldOf(PAYLOAD_CHUNKS_FIELD, List.of()).forGetter(data -> ChunkedSavedPayload.split(data.encodePayload())),
			Codec.STRING.listOf().optionalFieldOf(CONVERSATION_WAKES_FIELD, List.of()).forGetter(AgentSavedData::encodeConversationWakes),
			Codec.STRING.listOf().optionalFieldOf(GOAL_DRAFTS_FIELD, List.of()).forGetter(AgentSavedData::encodeGoalDrafts)
	).apply(instance, AgentSavedData::decodePayload));
	public static final SavedDataType<AgentSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "codex_agents"),
			AgentSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private final AgentRegistry registry;
	private final Map<AgentId, PendingConversationWake> conversationWakes = new LinkedHashMap<>();
	private final Map<UUID, PendingGoalDraft> goalDrafts = new LinkedHashMap<>();
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	public AgentSavedData() {
		this(new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of()
		));
	}

	private AgentSavedData(AgentRegistry.Snapshot snapshot) {
		this(snapshot, List.of(), List.of());
	}

	private AgentSavedData(
			AgentRegistry.Snapshot snapshot,
			List<PendingConversationWake> persistedWakes,
			List<PendingGoalDraft> persistedDrafts
	) {
		if (persistedWakes.size() > snapshot.maxAgents()) {
			throw new AgentDomainException("INVALID_PERSISTED_CONVERSATION_WAKE", "Persisted conversation wake count exceeds the agent limit");
		}
		Map<AgentId, AgentRecord> records = new LinkedHashMap<>();
		for (AgentRecord record : snapshot.records()) records.put(record.agentId(), record);
		if (persistedDrafts.size() > snapshot.maxAgents() * 2) {
			throw new AgentDomainException("INVALID_PERSISTED_GOAL_DRAFT", "Persisted goal draft count exceeds the bounded limit");
		}
		boolean discardedDraft = false;
		for (PendingGoalDraft draft : persistedDrafts) {
			AgentRecord record = records.get(draft.agentId());
			if (record == null || !draft.matches(record) || goalDrafts.containsKey(draft.draftId())) {
				discardedDraft = true;
				continue;
			}
			goalDrafts.put(draft.draftId(), draft);
		}
		boolean discardedWake = false;
		for (PendingConversationWake wake : persistedWakes) {
			AgentRecord record = records.get(wake.event().agentId());
			if (record == null || !wake.matches(record) || !record.state().isReloadUncertain()
					|| conversationWakes.containsKey(record.agentId())
					|| conversationWakes.values().stream().anyMatch(existing -> existing.transactionId().equals(wake.transactionId()))) {
				discardedWake = true;
				continue;
			}
			conversationWakes.put(record.agentId(), wake);
		}
		boolean recoveredUncertainState = snapshot.records().stream()
				.anyMatch(record -> record.state().isReloadUncertain());
		this.registry = AgentRegistry.restore(
				snapshot,
				this::setDirty,
				this::dispatchTransition,
				System.currentTimeMillis(),
				conversationWakes.keySet()
		);
		if (recoveredUncertainState || discardedWake || discardedDraft) {
			setDirty();
		}
	}

	public static AgentSavedData get(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public AgentRegistry registry() {
		return registry;
	}

	public void setRuntimeHooks(AgentRuntimeHooks runtimeHooks) {
		this.runtimeHooks = Objects.requireNonNull(runtimeHooks, "runtimeHooks must not be null");
	}

	private String encodePayload() {
		return SNAPSHOT_CODEC.encode(registry.snapshot());
	}

	private synchronized List<String> encodeConversationWakes() {
		return conversationWakes.values().stream().map(WAKE_CODEC::encode).toList();
	}

	private synchronized List<String> encodeGoalDrafts() {
		return goalDrafts.values().stream().map(DRAFT_CODEC::encode).toList();
	}

	private static AgentSavedData decodePayload(
			String legacyPayload,
			List<String> chunks,
			List<String> encodedWakes,
			List<String> encodedDrafts
	) {
		return new AgentSavedData(
				SNAPSHOT_CODEC.decode(ChunkedSavedPayload.join(legacyPayload, chunks)),
				encodedWakes.stream().map(WAKE_CODEC::decode).toList(),
				encodedDrafts.stream().map(DRAFT_CODEC::decode).toList()
		);
	}

	public synchronized void stageGoalDraft(PendingGoalDraft draft) {
		Objects.requireNonNull(draft, "draft must not be null");
		AgentRecord record = registry.records().stream()
				.filter(candidate -> candidate.agentId().equals(draft.agentId()))
				.findFirst()
				.orElse(null);
		if (record == null) {
			throw new AgentDomainException("UNKNOWN_AGENT", "Goal draft target does not exist");
		}
		if (!draft.matches(record)) {
			throw new AgentDomainException("STALE_GOAL_DRAFT", "Goal draft no longer matches the target goal revision");
		}
		if (goalDrafts.containsKey(draft.draftId())) {
			throw new AgentDomainException("DUPLICATE_GOAL_DRAFT", "Goal draft identity already exists");
		}
		goalDrafts.entrySet().removeIf(entry -> entry.getValue().agentId().equals(draft.agentId())
				&& entry.getValue().requestingPlayerId().equals(draft.requestingPlayerId()));
		if (goalDrafts.size() >= registry.maxAgents() * 2) {
			throw new AgentDomainException("GOAL_DRAFT_LIMIT_REACHED", "Too many pending goal clarifications");
		}
		goalDrafts.put(draft.draftId(), draft);
		setDirty();
	}

	public synchronized List<PendingGoalDraft> goalDrafts() {
		return List.copyOf(goalDrafts.values());
	}

	public synchronized Optional<PendingGoalDraft> goalDraft(UUID draftId) {
		return Optional.ofNullable(goalDrafts.get(Objects.requireNonNull(draftId, "draftId must not be null")));
	}

	public synchronized boolean removeGoalDraft(UUID draftId) {
		boolean removed = goalDrafts.remove(Objects.requireNonNull(draftId, "draftId must not be null")) != null;
		if (removed) setDirty();
		return removed;
	}

	public synchronized void clearGoalDrafts(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (goalDrafts.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId))) setDirty();
	}

	public synchronized void stageConversationWake(PendingConversationWake wake) {
		Objects.requireNonNull(wake, "wake must not be null");
		PendingConversationWake existing = conversationWakes.get(wake.event().agentId());
		if (existing != null) {
			throw new AgentDomainException("CONVERSATION_WAKE_PENDING", "Agent already has a pending conversation wake");
		}
		if (conversationWakes.values().stream().anyMatch(value -> value.transactionId().equals(wake.transactionId()))) {
			throw new AgentDomainException("DUPLICATE_CONVERSATION_WAKE", "Conversation wake transaction already exists");
		}
		conversationWakes.put(wake.event().agentId(), wake);
		setDirty();
	}

	public synchronized void rollbackConversationWake(UUID transactionId) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		AgentId target = conversationWakes.entrySet().stream()
				.filter(entry -> entry.getValue().transactionId().equals(transactionId))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
		if (target != null) {
			conversationWakes.remove(target);
			setDirty();
		}
	}

	public synchronized boolean acknowledgeConversationWake(UUID transactionId, AgentId agentId, long goalRevision) {
		PendingConversationWake wake = conversationWakes.get(Objects.requireNonNull(agentId, "agentId must not be null"));
		if (wake == null) return false;
		if (!wake.transactionId().equals(Objects.requireNonNull(transactionId, "transactionId must not be null"))
				|| wake.goalRevision() != goalRevision) {
			throw new AgentDomainException("CONVERSATION_WAKE_MISMATCH", "Conversation wake acknowledgement does not match the durable transaction");
		}
		if (!wake.acknowledged()) {
			conversationWakes.put(agentId, wake.acknowledge());
			setDirty();
		}
		return true;
	}

	public synchronized Optional<PendingConversationWake> conversationWake(AgentId agentId) {
		return Optional.ofNullable(conversationWakes.get(Objects.requireNonNull(agentId, "agentId must not be null")));
	}

	public synchronized List<PendingConversationWake> conversationWakes() {
		return List.copyOf(conversationWakes.values());
	}

	public synchronized void clearConversationWake(AgentId agentId) {
		if (conversationWakes.remove(Objects.requireNonNull(agentId, "agentId must not be null")) != null) setDirty();
	}

	private void dispatchTransition(AgentTransition transition) {
		clearSupersededConversationWake(transition.after());
		try {
			runtimeHooks.onTransition(transition);
		} catch (RuntimeException exception) {
			LOGGER.error("Codex agent runtime transition hook failed for {}", transition.after().agentId(), exception);
		}
	}

	private synchronized void clearSupersededConversationWake(AgentRecord record) {
		PendingConversationWake wake = conversationWakes.get(record.agentId());
		if (wake != null && (!wake.matches(record) || !record.state().isActive())) {
			conversationWakes.remove(record.agentId());
			setDirty();
		}
	}
}
