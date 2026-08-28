package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;

public final class GoalCompilerVerification {
	private GoalCompilerVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int assertions = 0;
		assertions += verifyExactItemAndAmbiguity();
		assertions += verifyExactPositionEntityAndAdvancement();
		assertions += verifyCompoundItemsAndKills();
		assertions += verifyDraftRoundTrip();
		assertions += verifyDraftRevisionBinding();
		assertions += verifyDraftAuthorizationAndChoices();
		assertions += verifySpecAwareLifecycleStart();
		return assertions;
	}

	private static int verifyExactItemAndAmbiguity() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation exact = compiler.compile("Hey, get an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, exact.kind(), "exact item request is accepted");
		assertEquals(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				exact.acceptedSpec().orElseThrow().completion(),
				"exact item request freezes the iron pickaxe predicate"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get a good pickaxe", RegistryAccess.EMPTY, 1_200L).kind(),
				"subjective item request requires translation"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe"
				),
				compiler.candidateIdsFor("Get a good pickaxe", RegistryAccess.EMPTY),
				"subjective item request keeps only bounded registered pickaxe candidates"
		);
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Hi, can you hear me?"), "casual speech remains conversation");
		assertEquals(true, GoalCompiler.looksLikeGoalRequest("Can you get an iron pickaxe?"), "actionable speech is a goal request");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("come here"), "come here is live steering, not a new goal");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Follow me"), "follow me is live steering, not a new goal");
		assertEquals(true, GoalCompiler.isLiveSteeringRequest("Can you come here?"), "polite come-here stays steering");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "come here", false), "busy agents still hear come here");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "get an iron pickaxe", false), "busy agents keep working unless replace/queue was chosen");
		assertEquals(true, GoalCompiler.consumePlayerSpeechAsGoal(false, "get an iron pickaxe", false), "idle agents still compile exact goal speech");
		assertEquals(
				List.of("minecraft:iron_axe", "minecraft:iron_hoe", "minecraft:iron_pickaxe", "minecraft:iron_shovel", "minecraft:iron_sword"),
				compiler.candidateIdsFor("Get iron tools", RegistryAccess.EMPTY),
				"tool-set clarification keeps the registered material tool family"
		);
		GoalCompilation craft = compiler.compile("Craft an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, craft.kind(),
				"craft wording cannot be reduced to already-held inventory");
		assertEquals(Optional.empty(), craft.acceptedSpec(),
				"craft wording has no false possession completion predicate");
		GoalCompilation make = compiler.compile("Make an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, make.kind(),
				"make wording cannot be reduced to already-held inventory");
		return 17;
	}

	private static int verifyExactPositionEntityAndAdvancement() {
		GoalCompiler compiler = new GoalCompiler();
		GoalPredicate.PositionWithin position = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(12.0D, 64.0D, -8.0D, 1.0D, 20), position, "exact coordinates");
		GoalPredicate.PositionWithin labelledPosition = (GoalPredicate.PositionWithin) compiler.compile(
				"Move to coordinates x=-22, y=99, z=12 and stop there", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(-22.0D, 99.0D, 12.0D, 1.0D, 20), labelledPosition,
				"labelled coordinates from the task UI compile to a positional verifier");
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Kill the ender dragon", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"exact entity kill"
		);
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Beat the game", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"beating the game freezes an agent-attributed dragon kill"
		);
		assertEquals(
				new GoalPredicate.AdvancementGranted("minecraft:story/mine_stone"),
				compiler.compile("Complete advancement minecraft:story/mine_stone", RegistryAccess.EMPTY, 1_200L,
						id -> id.equals("minecraft:story/mine_stone"))
						.acceptedSpec().orElseThrow().completion(),
				"exact advancement ID"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Complete advancement minecraft:story/not_real", RegistryAccess.EMPTY, 1_200L, id -> false).kind(),
				"nonexistent advancement ID requires clarification"
		);
		return 6;
	}

	private static int verifyCompoundItemsAndKills() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation mixed = compiler.compile("Get 2 apples and kill a zombie", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, mixed.kind(), "mixed factual item and kill request is accepted");
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:apple", 2),
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
				)),
				mixed.acceptedSpec().orElseThrow().completion(),
				"mixed request freezes one bounded predicate per factual result"
		);
		GoalCompilation twoKills = compiler.compile("Kill a zombie and a skeleton", RegistryAccess.EMPTY, 1_200L);
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
						new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
				)),
				twoKills.acceptedSpec().orElseThrow().completion(),
				"a carried kill verb compiles each entity into its own verifier"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe", "minecraft:zombie"
				),
				compiler.candidateIdsFor("Get a good pickaxe and kill a zombie", RegistryAccess.EMPTY),
				"compound translation candidates include registered items and entities"
		);
		GoalCompilation overBound = compiler.compile("Get apple" + " and apple".repeat(16), RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.REJECTED, overBound.kind(), "compound predicates reject more than sixteen factual leaves");
		GoalCompilation carriedCraft = compiler.compile("Craft an iron pickaxe and a shield", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, carriedCraft.kind(),
				"compound craft wording cannot be reduced to possession predicates");
		assertEquals(Optional.empty(), carriedCraft.acceptedSpec(),
				"compound craft wording has no false possession completion predicate");
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get an iron pickaxe and make a shield", RegistryAccess.EMPTY, 1_200L).kind(),
				"an explicit make clause keeps the entire compound goal verifiable"
		);
		return 8;
	}

	private static int verifyDraftRoundTrip() {
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000000101"),
				new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
				UUID.fromString("00000000-0000-0000-0000-000000000103"),
				"Get a good pickaxe",
				List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
				Optional.of(new GoalPredicate.AnyOf(java.util.List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)
				))),
				DraftIntent.CONFIRM_TRANSLATION,
				1_200L,
				4L,
				Optional.empty()
		);
		PendingGoalDraftCodec codec = new PendingGoalDraftCodec();
		assertEquals(draft, codec.decode(codec.encode(draft)), "pending goal draft round-trip");
		assertEquals(
				draft.withProposedPredicate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				codec.decode(codec.encode(draft.withProposedPredicate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)))),
				"atomic proposal replacement retains draft identity and candidate IDs"
		);
		return 2;
	}

	private static int verifySpecAwareLifecycleStart() {
		GoalSpec spec = new GoalCompiler().compile(
				"Get an iron pickaxe", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow();
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(spec, started.currentGoal().orElseThrow().spec(), "lifecycle starts the frozen compiled specification");
		assertEquals(dev.agaminggod.arenaagents.agent.goal.GoalStatus.ACTIVE,
				started.currentGoal().orElseThrow().status(), "compiled goal starts active");

		GoalSpec queuedSpec = new GoalCompiler().compile(
				"Get a diamond pickaxe", RegistryAccess.EMPTY, 1_201L
		).acceptedSpec().orElseThrow();
		AgentRecord queued = AgentLifecycleReducer.queue(started, queuedSpec, 8, 10_002L).after();
		UUID replacedGoalId = queued.currentGoal().orElseThrow().goalId();
		long replacedRevision = queued.goalRevision();
		GoalSpec replacement = new GoalCompiler().compile(
				"Get an iron axe", RegistryAccess.EMPTY, 1_202L
		).acceptedSpec().orElseThrow();
		var replaced = AgentLifecycleReducer.replace(queued, replacement, 10_003L);
		assertEquals(replacement, replaced.after().currentGoal().orElseThrow().spec(),
				"replace installs the confirmed frozen specification");
		assertEquals(false, replacedGoalId.equals(replaced.after().currentGoal().orElseThrow().goalId()),
				"replace creates a distinct goal identity");
		assertEquals(replacedRevision + 1L, replaced.after().goalRevision(),
				"replace advances the lifecycle revision exactly once");
		assertEquals(queued.queuedGoals(), replaced.after().queuedGoals(),
				"replace preserves already queued work");
		assertEquals(true, replaced.cancelAction(), "replace cancels physical work owned by the prior goal");
		return 7;
	}

	private static int verifyDraftAuthorizationAndChoices() {
		UUID requester = UUID.randomUUID();
		PendingGoalDraft idleDraft = new PendingGoalDraft(
				UUID.randomUUID(), AgentId.random(), requester, "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.CONFIRM_TRANSLATION, 100L, 0L, Optional.empty()
		);
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, requester, false, GoalDraftChoice.CONFIRM),
				"requester can confirm a validated idle draft");
		expectCode("GOAL_DRAFT_FORBIDDEN",
				() -> GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), false, GoalDraftChoice.CONFIRM),
				"another player cannot resolve the draft");
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), true, GoalDraftChoice.CONFIRM),
				"operator can confirm another player's draft");

		PendingGoalDraft activeDraft = new PendingGoalDraft(
				UUID.randomUUID(), idleDraft.agentId(), requester, "Get a diamond pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)),
				DraftIntent.REPLACE_OR_QUEUE, 101L, 4L, Optional.of(UUID.randomUUID())
		);
		assertEquals(GoalDraftResolution.Operation.REPLACE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.REPLACE),
				"active draft explicitly replaces only after the player's choice");
		assertEquals(GoalDraftResolution.Operation.QUEUE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.QUEUE),
				"active draft can queue without changing current work");
		assertEquals(GoalDraftResolution.Operation.CANCEL,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CANCEL),
				"cancel removes only the draft");
		expectCode("GOAL_DRAFT_CHOICE_REQUIRED",
				() -> GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CONFIRM),
				"active draft requires an explicit replace or queue choice");
		return 7;
	}

	private static int verifyDraftRevisionBinding() {
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.randomUUID(), idle.agentId(), UUID.randomUUID(), "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.START, 100L, idle.goalRevision(), Optional.empty()
		);
		assertEquals(true, draft.matches(idle), "draft matches the exact idle revision it was created against");
		GoalSpec spec = new GoalCompiler().compile("Get an iron pickaxe", RegistryAccess.EMPTY, 100L)
				.acceptedSpec().orElseThrow();
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(false, draft.matches(started), "draft becomes stale when the goal revision changes");
		return 2;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		System.out.println("PASS: " + label);
	}

	private static void expectCode(String code, Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), label);
		}
	}
}
