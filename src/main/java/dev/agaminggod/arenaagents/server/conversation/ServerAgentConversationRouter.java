package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ServerAgentConversationRouter implements AgentConversationRouter {
	public static final double DEFAULT_PROXIMITY_RANGE = 48.0D;

	private final CodexAgentManager manager;
	private final ConversationEventSink eventSink;
	private final Map<AgentId, Long> sequences = new LinkedHashMap<>();

	public ServerAgentConversationRouter(CodexAgentManager manager, ConversationEventSink eventSink) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
	}

	public DeliveryReceipt deliverAgentMessage(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, true, true);
	}

	public DeliveryReceipt deliverAgentMessageToAgents(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, false, true);
	}

	public DeliveryReceipt deliverAgentMessageToPlayers(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, true, false);
	}

	private DeliveryReceipt deliverAgentMessage(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text,
			boolean deliverToPlayers,
			boolean deliverToAgents
	) {
		Objects.requireNonNull(sourceAgentId, "sourceAgentId must not be null");
		ServerPlayer source = manager.findAgentPlayer(sourceAgentId).orElseThrow(
				() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
		);
		String canonicalRecipient = audience == ConversationAudience.DIRECT
				? resolveRecipient(recipientId).id()
				: "";
		AgentRecord sourceRecord = manager.registry().require(sourceAgentId);
		return deliverFrom(
				source,
				manager.displayName(sourceRecord),
				new ConversationEvent(
						sourceAgentId,
						sourceAgentId.toString(),
						canonicalRecipient,
						audience,
						ConversationKind.AGENT_MESSAGE,
						text,
						sourceRecord.goalRevision(),
						System.currentTimeMillis(),
						0L,
						dimensionId(source)
				),
				deliverToPlayers,
				deliverToAgents
		);
	}

	public DeliveryReceipt deliverPlayerMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		return deliverPlayerMessage(source, recipientAgentId, text, false);
	}

	public DeliveryReceipt deliverPlayerMessageFromNativeWhisper(
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		return deliverPlayerMessage(source, recipientAgentId, text, true);
	}

	private DeliveryReceipt deliverPlayerMessage(
			ServerPlayer source,
			AgentId recipientAgentId,
			String text,
			boolean vanillaAlreadyEchoed
	) {
		Objects.requireNonNull(source, "source must not be null");
		AgentRecord recipientRecord = manager.registry().require(
				Objects.requireNonNull(recipientAgentId, "recipientAgentId must not be null")
		);
		OnlineParticipant recipient = resolveRecipient(recipientAgentId.toString());
		ConversationEvent event = new ConversationEvent(
				recipientAgentId,
				source.getUUID().toString(),
				recipient.id(),
				ConversationAudience.DIRECT,
				ConversationKind.PLAYER_MESSAGE,
				text,
				recipientRecord.goalRevision(),
				System.currentTimeMillis(),
				0L,
				dimensionId(source)
		);
		DeliveryReceipt receipt = deliverFrom(
				source,
				source.getScoreboardName(),
				event,
				true,
				true,
				vanillaAlreadyEchoed ? Set.of(source.getUUID().toString()) : Set.of()
		);
		if (!vanillaAlreadyEchoed && !receipt.deliveredIds().contains(source.getUUID().toString())) {
			source.sendSystemMessage(directComponent(source.getScoreboardName(), recipient.displayName(), event.text(), false));
		}
		return receipt;
	}

	public DeliveryReceipt deliverPlayerProximitySpeech(ServerPlayer source, String text, double range) {
		Objects.requireNonNull(source, "source must not be null");
		if (!Double.isFinite(range) || range <= 0.0D || range > 128.0D) {
			throw new IllegalArgumentException("Speech range must be between 0 and 128 blocks");
		}
		ArrayList<String> delivered = new ArrayList<>();
		double rangeSquared = range * range;
		for (OnlineParticipant participant : onlineParticipants(source)) {
			AgentRecord target = participant.agentRecord();
			if (target == null || participant.player() == source) continue;
			if (participant.player().level() != source.level()
					|| source.distanceToSqr(participant.player()) > rangeSquared) continue;
			publishToAgent(target, new ConversationEvent(
					target.agentId(),
					source.getUUID().toString(),
					target.agentId().toString(),
					ConversationAudience.PROXIMITY,
					ConversationKind.PROXIMITY_SPEECH,
					text,
					target.goalRevision(),
					System.currentTimeMillis(),
					0L,
					dimensionId(source)
			));
			delivered.add(target.agentId().toString());
		}
		return new DeliveryReceipt(List.copyOf(delivered), List.of());
	}

	@Override
	public DeliveryReceipt deliver(ConversationEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		AgentId sourceAgentId = AgentId.parse(event.sourceId());
		ServerPlayer source = manager.findAgentPlayer(sourceAgentId).orElseThrow(
				() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
		);
		return deliverFrom(source, manager.displayName(manager.registry().require(sourceAgentId)), event, true, true);
	}

	private DeliveryReceipt deliverFrom(
			ServerPlayer source,
			String sourceName,
			ConversationEvent event,
			boolean deliverToPlayers,
			boolean deliverToAgents
	) {
		return deliverFrom(source, sourceName, event, deliverToPlayers, deliverToAgents, Set.of());
	}

	private DeliveryReceipt deliverFrom(
			ServerPlayer source,
			String sourceName,
			ConversationEvent event,
			boolean deliverToPlayers,
			boolean deliverToAgents,
			Set<String> suppressedPlayerIds
	) {
		List<OnlineParticipant> online = onlineParticipants(source);
		DeliveryReceipt receipt = ConversationDeliveryPolicy.plan(
				event,
				online.stream().map(OnlineParticipant::policy).toList(),
				DEFAULT_PROXIMITY_RANGE
		);
		Map<String, OnlineParticipant> byId = new LinkedHashMap<>();
		for (OnlineParticipant participant : online) byId.putIfAbsent(participant.id(), participant);
		String recipientName = Optional.ofNullable(byId.get(event.recipientId()))
				.map(OnlineParticipant::displayName)
				.orElse(event.recipientId());
		for (String deliveredId : receipt.deliveredIds()) {
			OnlineParticipant participant = byId.get(deliveredId);
			if (participant == null) continue;
			boolean mirror = receipt.mirroredOperatorIds().contains(deliveredId);
			if (deliverToPlayers && !suppressedPlayerIds.contains(participant.player().getUUID().toString())) {
				if (event.audience() == ConversationAudience.DIRECT
						&& !mirror
						&& participant.agentRecord() == null) {
					MinecraftWhisperDelivery.send(source, sourceName, participant.player(), event.text());
				} else {
					participant.player().sendSystemMessage(component(event, sourceName, recipientName, mirror));
				}
			}
			if (deliverToAgents && participant.agentRecord() != null
					&& !event.sourceId().equals(participant.agentRecord().agentId().toString())) {
				publishToAgent(participant.agentRecord(), event);
			}
		}
		return receipt;
	}

	private void publishToAgent(AgentRecord target, ConversationEvent source) {
		long sequence = sequences.merge(target.agentId(), 1L, Long::sum);
		ConversationEvent delivered = new ConversationEvent(
				target.agentId(),
				source.sourceId(),
				target.agentId().toString(),
				source.audience(),
				source.kind(),
				source.text(),
				target.goalRevision(),
				System.currentTimeMillis(),
				sequence,
				source.dimensionId()
		);
		eventSink.publish(delivered, ConversationWakePolicy.goalFor(target.state(), delivered.kind()));
	}

	private OnlineParticipant resolveRecipient(String recipientId) {
		if (recipientId == null || recipientId.isBlank()) {
			throw new AgentDomainException("MISSING_RECIPIENT", "Direct conversations require a recipient");
		}
		for (OnlineParticipant participant : onlineParticipants(null)) {
			if (participant.id().equals(recipientId)
					|| participant.player().getUUID().toString().equals(recipientId)) return participant;
		}
		throw new AgentDomainException("RECIPIENT_OFFLINE", "Direct-message recipient is not online");
	}

	private List<OnlineParticipant> onlineParticipants(ServerPlayer source) {
		Map<UUID, AgentRecord> agentsByEntity = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) record.entityUuid().ifPresent(uuid -> agentsByEntity.put(uuid, record));
		ArrayList<OnlineParticipant> result = new ArrayList<>();
		for (ServerPlayer player : manager.server().getPlayerList().getPlayers()) {
			AgentRecord agent = agentsByEntity.get(player.getUUID());
			String id = agent == null ? player.getUUID().toString() : agent.agentId().toString();
			boolean operator = GoalControl.mayControl(player.createCommandSourceStack());
			double distanceSquared = source == null || source.level() != player.level()
					? Double.MAX_VALUE
					: source.distanceToSqr(player);
			String displayName = agent == null ? player.getScoreboardName() : manager.displayName(agent);
			result.add(new OnlineParticipant(
					new ConversationParticipant(id, operator, true, dimensionId(player), distanceSquared),
					player,
					agent,
					displayName
			));
		}
		return List.copyOf(result);
	}

	private static Component component(ConversationEvent event, String sourceName, String recipientName, boolean mirror) {
		return switch (event.audience()) {
			case PUBLIC -> Component.literal("<" + sourceName + "> " + event.text());
			case DIRECT -> directComponent(sourceName, recipientName, event.text(), mirror);
			case PROXIMITY -> Component.literal("[Nearby] <" + sourceName + "> " + event.text());
		};
	}

	private static Component directComponent(String sourceName, String recipientName, String text, boolean mirror) {
		return Component.literal((mirror ? "[DM spy] " : "[DM] ") + sourceName + " -> " + recipientName + ": " + text);
	}

	private static String dimensionId(ServerPlayer player) {
		return player.level().dimension().identifier().toString();
	}

	private record OnlineParticipant(
			ConversationParticipant policy,
			ServerPlayer player,
			AgentRecord agentRecord,
			String displayName
	) {
		String id() {
			return policy.id();
		}
	}
}
