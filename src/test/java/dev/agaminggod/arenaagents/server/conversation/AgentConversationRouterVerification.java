package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;

public final class AgentConversationRouterVerification {
	private static final AgentId AGENT_ID = new AgentId(
			UUID.fromString("00000000-0000-0000-0000-000000000007")
	);

	private AgentConversationRouterVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyDirectDeliveryAndOperatorMirror();
		assertions += verifyOfflineRecipientFailure();
		assertions += verifyProximityFiltering();
		assertions += verifyPublicDelivery();
		assertions += verifyUnicodeSafeTextBound();
		assertions += verifyPlayerConversationWakePolicy();
		assertions += verifyNativeWhisperPayload();
		assertions += verifyNativeWhisperContentSelection();
		return assertions;
	}

	private static int verifyNativeWhisperPayload() {
		OutgoingChatMessage message = MinecraftWhisperDelivery.outgoingMessage("Ready when you are.");
		assertEquals(OutgoingChatMessage.Disguised.class, message.getClass(),
				"agent whispers use Minecraft's chat packet path");
		assertEquals("Ready when you are.", message.content().getString(), "native whisper content");
		return 2;
	}

	private static int verifyNativeWhisperContentSelection() {
		PlayerChatMessage message = PlayerChatMessage.system("signed content")
				.withUnsignedContent(Component.literal("decorated content"));
		assertEquals("decorated content", MinecraftWhisperDelivery.nativeText(message, false),
				"native whisper preserves vanilla decorated content");
		return 1;
	}

	private static int verifyPlayerConversationWakePolicy() {
		assertEquals(true, ConversationWakePolicy.mayInstallNewGoalFromSpeech(AgentLifecycleState.IDLE),
				"idle agent can receive a goal from speech");
		assertEquals(true, ConversationWakePolicy.mayInstallNewGoalFromSpeech(AgentLifecycleState.COMPLETED),
				"completed goal is inactive even while its evidence remains attached");
		assertEquals(false, ConversationWakePolicy.mayInstallNewGoalFromSpeech(AgentLifecycleState.PAUSED),
				"paused goal cannot be silently replaced by speech");
		assertEquals(false, ConversationWakePolicy.mayInstallNewGoalFromSpeech(AgentLifecycleState.ACTING),
				"active work cannot be silently replaced by speech");
		assertEquals(true, ConversationWakePolicy.shouldStartGoal(
				AgentLifecycleState.IDLE, ConversationKind.PLAYER_MESSAGE
		), "idle agent wakes for direct player message");
		assertEquals(true, ConversationWakePolicy.shouldStartGoal(
				AgentLifecycleState.COMPLETED, ConversationKind.PROXIMITY_SPEECH
		), "completed agent wakes for nearby player speech");
		assertEquals(true, ConversationWakePolicy.shouldStartGoal(
				AgentLifecycleState.PAUSED, ConversationKind.PLAYER_MESSAGE
		), "paused agent wakes for direct player message");
		assertEquals(true, ConversationWakePolicy.shouldStartGoal(
				AgentLifecycleState.PAUSED, ConversationKind.PROXIMITY_SPEECH
		), "paused agent wakes for nearby player speech");
		assertEquals(false, ConversationWakePolicy.shouldStartGoal(
				AgentLifecycleState.IDLE, ConversationKind.AGENT_MESSAGE
		), "agent chatter does not wake idle agent");
		for (AgentLifecycleState state : List.of(
				AgentLifecycleState.STARTING,
				AgentLifecycleState.PLANNING,
				AgentLifecycleState.ACTING,
				AgentLifecycleState.ERROR,
				AgentLifecycleState.DEAD,
				AgentLifecycleState.DISCONNECTED
		)) {
			assertEquals(false, ConversationWakePolicy.shouldStartGoal(
					state, ConversationKind.PLAYER_MESSAGE
			), state + " agent is not auto-started by conversation");
		}
		return 15;
	}

	private static int verifyDirectDeliveryAndOperatorMirror() {
		ConversationEvent event = event(ConversationAudience.DIRECT, "target", "Meet behind the tower.");
		DeliveryReceipt receipt = ConversationDeliveryPolicy.plan(event, List.of(
				participant("target", false, true, "overworld", 4.0D),
				participant("operator-a", true, true, "nether", 10_000.0D),
				participant("operator-b", true, true, "overworld", 2.0D),
				participant("offline-operator", true, false, "overworld", 1.0D)
		), 48.0D);
		assertEquals(List.of("target", "operator-a", "operator-b"), receipt.deliveredIds(), "direct recipients");
		assertEquals(List.of("operator-a", "operator-b"), receipt.mirroredOperatorIds(), "operator mirrors");

		DeliveryReceipt operatorRecipient = ConversationDeliveryPolicy.plan(
				event(ConversationAudience.DIRECT, "operator-a", "Private update."),
				List.of(
						participant("operator-a", true, true, "overworld", 1.0D),
						participant("operator-b", true, true, "overworld", 1.0D)
				),
				48.0D
		);
		assertEquals(List.of("operator-a", "operator-b"), operatorRecipient.deliveredIds(), "operator recipient deduplication");
		assertEquals(List.of("operator-b"), operatorRecipient.mirroredOperatorIds(),
				"the intended recipient receives a real whisper even when they are an operator");
		return 4;
	}

	private static int verifyOfflineRecipientFailure() {
		expectFailure(
				() -> ConversationDeliveryPolicy.plan(
						event(ConversationAudience.DIRECT, "target", "Are you there?"),
						List.of(participant("target", false, false, "overworld", 1.0D)),
						48.0D
				),
				"RECIPIENT_OFFLINE"
		);
		return 1;
	}

	private static int verifyProximityFiltering() {
		DeliveryReceipt receipt = ConversationDeliveryPolicy.plan(
				event(ConversationAudience.PROXIMITY, "", "Can anyone hear me?"),
				List.of(
						participant("near", false, true, "overworld", 47.9D * 47.9D),
						participant("boundary", false, true, "overworld", 48.0D * 48.0D),
						participant("far", false, true, "overworld", 48.1D * 48.1D),
						participant("other-dimension", false, true, "nether", 1.0D),
						participant("offline", false, false, "overworld", 1.0D)
				),
				48.0D
		);
		assertEquals(List.of("near", "boundary"), receipt.deliveredIds(), "proximity recipients");
		assertEquals(List.of(), receipt.mirroredOperatorIds(), "proximity has no DM mirror");
		return 2;
	}

	private static int verifyPublicDelivery() {
		DeliveryReceipt receipt = ConversationDeliveryPolicy.plan(
				event(ConversationAudience.PUBLIC, "", "Hello everyone."),
				List.of(
						participant("first", false, true, "overworld", 100.0D),
						participant("second", true, true, "nether", 100.0D),
						participant("offline", false, false, "overworld", 1.0D)
				),
				48.0D
		);
		assertEquals(List.of("first", "second"), receipt.deliveredIds(), "public recipients");
		assertEquals(List.of(), receipt.mirroredOperatorIds(), "public delivery is not a DM mirror");
		return 2;
	}

	private static int verifyUnicodeSafeTextBound() {
		ConversationEvent event = event(ConversationAudience.PUBLIC, "", "\ud83d\ude80".repeat(513));
		assertEquals(512, event.text().codePointCount(0, event.text().length()), "conversation code-point limit");
		assertEquals("\ud83d\ude80", event.text().substring(event.text().length() - 2), "surrogate pair retained");
		return 2;
	}

	private static ConversationEvent event(ConversationAudience audience, String recipientId, String text) {
		return new ConversationEvent(
				AGENT_ID,
				"source",
				recipientId,
				audience,
				ConversationKind.AGENT_MESSAGE,
				text,
				4L,
				1_787_184_000_000L,
				18L,
				"overworld"
		);
	}

	private static ConversationParticipant participant(
			String id,
			boolean operator,
			boolean online,
			String dimensionId,
			double distanceSquared
	) {
		return new ConversationParticipant(id, operator, online, dimensionId, distanceSquared);
	}

	private static void expectFailure(Runnable action, String expectedCode) {
		try {
			action.run();
		} catch (AgentDomainException exception) {
			assertEquals(expectedCode, exception.code(), "domain error code");
			return;
		}
		throw new AssertionError("expected failure " + expectedCode);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
