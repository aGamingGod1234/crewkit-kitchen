package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.server.goal.GoalCompilation;
import dev.agaminggod.arenaagents.server.goal.GoalCompiler;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
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
		assertions += verifyProximitySpeechAudienceIsImmutable();
		assertions += verifyPublicDelivery();
		assertions += verifyUnicodeSafeTextBound();
		assertions += verifyPlayerConversationWakePolicy();
		assertions += verifySpeechGoalCompilationRouting();
		assertions += verifySpokenTaskParity();
		assertions += verifyNativeWhisperPayload();
		assertions += verifyNativeWhisperContentSelection();
		return assertions;
	}

	private static int verifyProximitySpeechAudienceIsImmutable() {
		java.util.ArrayList<AgentId> recipients = new java.util.ArrayList<>(List.of(AGENT_ID));
		var audience = new ServerAgentConversationRouter.ProximitySpeechAudience(
				UUID.fromString("10000000-0000-4000-8000-000000000001"),
				net.minecraft.world.level.Level.OVERWORLD,
				recipients
		);
		recipients.clear();
		assertEquals(List.of(AGENT_ID), audience.recipientAgentIds(),
				"speech-time recipients remain fixed while listeners move during transcription");
		return 1;
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

	private static int verifySpeechGoalCompilationRouting() {
		assertEquals(true, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.PLANNING,
				ConversationKind.PLAYER_MESSAGE, ConversationAudience.DIRECT, true), "direct operator task changes replace an active goal");
		assertEquals(true, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.PLANNING,
				ConversationKind.PROXIMITY_SPEECH, ConversationAudience.PROXIMITY, true), "an operator's spoken task replaces active work just like a DM");
		assertEquals(false, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.PLANNING,
				ConversationKind.PLAYER_MESSAGE, ConversationAudience.DIRECT, false), "another player's chat cannot replace operator-controlled work");
		assertEquals(true, ConversationWakePolicy.isCompletionConfirmation("yep!"), "the reported short confirmation is recognized");
		assertEquals(false, ConversationWakePolicy.isCompletionConfirmation("yes, get iron tools"), "a new task is not treated as completion evidence");
		var replacement = ServerAgentConversationRouter.routeCompiledSpeechGoal(AgentLifecycleState.PLANNING,
				ConversationKind.PLAYER_MESSAGE, new GoalCompiler().compile("Get iron pickaxe now", RegistryAccess.EMPTY, 1_200L), true,
				() -> { throw new AssertionError("exact replacement must not translate"); }, message -> { throw new AssertionError(message); });
		assertEquals(true, replacement.publish(), "a task switch publishes a durable conversation wake while active");
		assertEquals(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1), replacement.wakeSpec().orElseThrow().completion(),
				"a replacement freezes the newly requested factual result");
		GoalCompilation dragon = new GoalCompiler().compile("Beat the game", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION,
				GoalCompiler.withAdvisoryPlan(dragon).kind(),
				"dragon goal requests Luna's advisory subgoals before activation");
		assertEquals(GoalCompilation.Kind.ACCEPTED,
				GoalCompiler.withAdvisoryPlan(
						new GoalCompiler().compile("Get dirt", RegistryAccess.EMPTY, 1_200L)).kind(),
				"easy factual goals keep the direct fast path");
		String rejectionMessage = "That advancement ID does not exist on this server.";
		GoalCompilation rejected = new GoalCompiler().compile(
				"Earn advancement mod:removed", RegistryAccess.EMPTY, 1_200L, ignored -> false
		);
		assertEquals(GoalCompilation.Kind.REJECTED, rejected.kind(),
				"exact missing advancement IDs reach the rejected speech route");
		for (AgentLifecycleState state : List.of(AgentLifecycleState.IDLE, AgentLifecycleState.COMPLETED)) {
			AtomicInteger coordinatorDrafts = new AtomicInteger();
			java.util.ArrayList<String> playerMessages = new java.util.ArrayList<>();
			var route = ServerAgentConversationRouter.routeCompiledSpeechGoal(
					state, state == AgentLifecycleState.IDLE
							? ConversationKind.PLAYER_MESSAGE : ConversationKind.PROXIMITY_SPEECH,
					rejected,
					coordinatorDrafts::incrementAndGet, playerMessages::add
			);
			assertEquals(false, route.publish(), state + " rejected speech is consumed without waking a goal");
			assertEquals(true, route.wakeSpec().isEmpty(), state + " rejected speech cannot install a fallback goal");
			assertEquals(List.of(rejectionMessage), playerMessages,
					state + " rejected speech reports the compiler player message verbatim");
			assertEquals(0, coordinatorDrafts.get(),
					state + " rejected speech cannot publish a translation draft or operator-confirmed bypass");
		}

		AtomicInteger translationDrafts = new AtomicInteger();
		var translation = ServerAgentConversationRouter.routeCompiledSpeechGoal(
				AgentLifecycleState.IDLE,
				ConversationKind.PLAYER_MESSAGE,
				GoalCompilation.needsTranslation("Choose an exact result."),
				translationDrafts::incrementAndGet,
				message -> { throw new AssertionError("translation must not report a rejection"); }
		);
		assertEquals(false, translation.publish(), "translation speech is consumed while its draft is staged");
		assertEquals(1, translationDrafts.get(), "only NEEDS_TRANSLATION stages one coordinator draft");
		return 20;
	}

	private static int verifySpokenTaskParity() {
		int assertions = 0;
		for (String phrase : List.of("I want you to get a stone pickaxe.", "Your goal is to get a stone pickaxe.",
				"I'd like you to get a stone pickaxe.", "Your task is to get a stone pickaxe.")) {
			assertEquals(true, GoalCompiler.looksLikeGoalRequest(phrase), "natural spoken assignment is recognized: " + phrase);
			assertEquals(new GoalPredicate.InventoryContains("minecraft:stone_pickaxe", 1),
					new GoalCompiler().compile(phrase, RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
					"natural spoken assignment preserves the requested factual result: " + phrase);
			assertions += 2;
		}
		GoalCompilation requested = new GoalCompiler().compile("Can you please get a stone pickaxe?", RegistryAccess.EMPTY, 1_200L);
		for (AgentLifecycleState state : List.of(AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING,
				AgentLifecycleState.ACTING, AgentLifecycleState.PAUSED)) {
			boolean replace = ConversationWakePolicy.mayReplaceGoalFromSpeech(state,
					ConversationKind.PROXIMITY_SPEECH, ConversationAudience.PROXIMITY, true);
			assertEquals(true, replace, state + " accepts an operator's spoken task switch");
			assertEquals(true, GoalCompiler.consumePlayerSpeechAsGoal(true, "Can you please get a stone pickaxe?", replace),
					state + " spoken task reaches goal compilation instead of conversation-only tools");
			var route = ServerAgentConversationRouter.routeCompiledSpeechGoal(state, ConversationKind.PROXIMITY_SPEECH,
					requested, replace, () -> { throw new AssertionError("exact spoken task must not translate"); },
					message -> { throw new AssertionError(message); });
			assertEquals(true, route.publish(), state + " publishes the spoken goal wake");
			assertEquals(new GoalPredicate.InventoryContains("minecraft:stone_pickaxe", 1),
					route.wakeSpec().orElseThrow().completion(), state + " verifies the stone pickaxe instead of a chat reply");
			assertions += 4;
		}
		assertEquals(false, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.ACTING,
				ConversationKind.PROXIMITY_SPEECH, ConversationAudience.PROXIMITY, false),
				"a nearby non-operator cannot replace active work");
		assertEquals(false, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.ACTING,
				ConversationKind.AGENT_MESSAGE, ConversationAudience.PROXIMITY, true),
				"nearby agent chatter cannot replace active work");
		assertEquals(false, ConversationWakePolicy.mayReplaceGoalFromSpeech(AgentLifecycleState.ACTING,
				ConversationKind.PLAYER_MESSAGE, ConversationAudience.PUBLIC, true),
				"public text chat does not become a task switch");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "Thanks, how are you?", true),
				"ordinary spoken conversation does not create a goal");
		assertEquals(true, ConversationWakePolicy.isPlayerGoalChannel(ConversationKind.PROXIMITY_SPEECH, ConversationAudience.PROXIMITY),
				"spoken completion confirmation uses the same guarded confirmation path as a DM");
		assertEquals(false, ConversationWakePolicy.isPlayerGoalChannel(ConversationKind.AGENT_MESSAGE, ConversationAudience.PROXIMITY),
				"agent speech cannot confirm a human's goal");
		AtomicInteger translated = new AtomicInteger();
		String originalSpeech = "hey, can you go and get stone tools and pickaxe and and axe";
		assertEquals(true, GoalCompiler.looksLikeGoalRequest(originalSpeech), "the user's exact noisy transcript is a task request");
		GoalCompilation originalCompilation = new GoalCompiler().compile(originalSpeech, RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, originalCompilation.kind(), "the exact spoken tool request has factual completion immediately");
		var spoken = ServerAgentConversationRouter.routeCompiledSpeechGoal(AgentLifecycleState.ACTING, ConversationKind.PROXIMITY_SPEECH,
				originalCompilation, true, () -> { throw new AssertionError("named spoken tools need no translation"); },
				message -> { throw new AssertionError(message); });
		assertEquals(true, spoken.publish(), "the exact spoken task replaces active work without a DM");
		assertEquals(new GoalPredicate.AllOf(List.of(new GoalPredicate.InventoryContains("minecraft:stone_pickaxe", 1),
				new GoalPredicate.InventoryContains("minecraft:stone_axe", 1))), spoken.wakeSpec().orElseThrow().completion(),
				"the spoken wake carries both stone tool requirements");
		GoalCompilation translatedCompilation = new GoalCompiler().compile("Get a pickaxe and an axe", RegistryAccess.EMPTY, 1_200L);
		var draft = ServerAgentConversationRouter.routeCompiledSpeechGoal(AgentLifecycleState.ACTING, ConversationKind.PROXIMITY_SPEECH,
				translatedCompilation, true, translated::incrementAndGet,
				message -> { throw new AssertionError(message); });
		assertEquals(1, translated.get(), "complex spoken task stages translation while another task is active");
		assertEquals(false, draft.publish(), "complex spoken task awaits validated translation instead of a chat-only reply");
		return assertions + 12;
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
