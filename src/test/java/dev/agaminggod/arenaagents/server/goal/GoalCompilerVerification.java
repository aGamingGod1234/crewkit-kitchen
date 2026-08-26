package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Optional;
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
		assertions += verifyDraftRoundTrip();
		assertions += verifyDraftRevisionBinding();
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
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Hi, can you hear me?"), "casual speech remains conversation");
		assertEquals(true, GoalCompiler.looksLikeGoalRequest("Can you get an iron pickaxe?"), "actionable speech is a goal request");
		return 5;
	}

	private static int verifyExactPositionEntityAndAdvancement() {
		GoalCompiler compiler = new GoalCompiler();
		GoalPredicate.PositionWithin position = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(12.0D, 64.0D, -8.0D, 1.0D, 20), position, "exact coordinates");
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Kill the ender dragon", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"exact entity kill"
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
		return 4;
	}

	private static int verifyDraftRoundTrip() {
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000000101"),
				new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
				UUID.fromString("00000000-0000-0000-0000-000000000103"),
				"Get a good pickaxe",
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
		return 1;
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
		return 2;
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
}
