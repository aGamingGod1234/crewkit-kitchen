package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GoalVerificationRuntimeVerification {
	private GoalVerificationRuntimeVerification() { }

	public static int verify() {
		int assertions = 0;
		assertions += verifyExactInventoryAndIdempotence();
		assertions += verifyStablePositionAndReset();
		assertions += verifyBlockAdvancementAndCompoundPredicates();
		assertions += verifyAgentSpecificKillAttribution();
		assertions += verifySurvivalAndOperatorConfirmation();
		assertions += verifyQueuedPromotionAfterEvidence();
		return assertions;
	}

	private static int verifyExactInventoryAndIdempotence() {
		Fixture fixture = fixture(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1), 100L);
		fixture.facts.items.put("minecraft:stone_pickaxe", 1);
		assertEquals(GoalStatus.ACTIVE, fixture.goalStatus(), "stone pickaxe does not satisfy exact iron inventory");
		assertEquals(0, fixture.runtime.tick().size(), "failed factual check emits no lifecycle transition");
		fixture.advance();
		fixture.facts.items.put("minecraft:iron_pickaxe", 1);
		assertEquals(1, fixture.runtime.tick().size(), "first exact inventory match emits one transition");
		assertEquals(GoalStatus.SATISFIED, fixture.goalStatus(), "iron pickaxe satisfies the stored goal");
		assertEquals(AgentLifecycleState.COMPLETED, fixture.record().state(), "factual satisfaction stops active work");
		assertEquals(1, fixture.transitions.size(), "satisfaction is published exactly once");
		fixture.advance();
		assertEquals(0, fixture.runtime.tick().size(), "later ticks do not repeat terminal satisfaction");
		assertEquals(1, fixture.transitions.size(), "duplicate evidence cannot emit a second transition");
		return 8;
	}

	private static int verifyStablePositionAndReset() {
		Fixture fixture = fixture(new GoalPredicate.PositionWithin(12.0, 64.0, 12.0, 1.0, 2), 200L);
		fixture.facts.position = new GoalCompletionVerifier.Position(12.0, 64.0, 12.0);
		assertEquals(0, fixture.runtime.tick().size(), "first in-radius tick is not yet stable");
		assertEquals(false, fixture.runtime.evaluate(fixture.agentId).verified(), "repeated checks in one tick cannot fake positional stability");
		fixture.advance();
		fixture.facts.position = new GoalCompletionVerifier.Position(15.0, 64.0, 12.0);
		assertEquals(0, fixture.runtime.tick().size(), "leaving the radius resets positional stability");
		fixture.advance();
		fixture.facts.position = new GoalCompletionVerifier.Position(12.0, 64.0, 12.0);
		assertEquals(0, fixture.runtime.tick().size(), "returning starts a new stable interval");
		fixture.advance();
		assertEquals(1, fixture.runtime.tick().size(), "consecutive in-radius ticks satisfy the position goal");
		return 5;
	}

	private static int verifyBlockAdvancementAndCompoundPredicates() {
		GoalPredicate predicate = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.BlockMatches(1, 64, 2, "minecraft:oak_door", Map.of("open", "true")),
				new GoalPredicate.AdvancementGranted("minecraft:story/mine_stone")
		));
		Fixture fixture = fixture(predicate, 300L);
		fixture.facts.blocks.put("1,64,2", new GoalCompletionVerifier.BlockFact("minecraft:oak_door", Map.of("open", "false")));
		fixture.facts.advancements.put("minecraft:story/mine_stone", true);
		GoalCompletionVerifier.VerificationResult failed = fixture.runtime.evaluate(fixture.agentId);
		assertEquals(false, failed.verified(), "wrong block property defeats an all-of goal");
		assertEquals(2, failed.facts().size(), "compound verification returns bounded leaf evidence");
		fixture.facts.blocks.put("1,64,2", new GoalCompletionVerifier.BlockFact("minecraft:oak_door", Map.of("open", "true")));
		assertEquals(true, fixture.runtime.evaluate(fixture.agentId).verified(), "matching block properties and advancement satisfy all-of");
		assertEquals(1, fixture.runtime.tick().size(), "compound satisfaction emits once");
		return 4;
	}

	private static int verifyAgentSpecificKillAttribution() {
		Fixture fixture = fixture(new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true), 400L);
		AgentId other = AgentId.random();
		fixture.runtime.killLedger().record(other, "minecraft:ender_dragon", 401L);
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", 399L);
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", 400L);
		assertEquals(false, fixture.runtime.evaluate(fixture.agentId).verified(), "another player and pre-goal kills cannot satisfy attribution");
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", 401L);
		assertEquals(true, fixture.runtime.evaluate(fixture.agentId).verified(), "responsible agent kill after goal start satisfies attribution");
		assertEquals(1, fixture.runtime.tick().size(), "attributed kill completes once");
		return 3;
	}

	private static int verifySurvivalAndOperatorConfirmation() {
		Fixture survival = fixture(new GoalPredicate.SurviveDuration(2L), 500L);
		assertEquals(0, survival.runtime.tick().size(), "first alive tick starts survival duration");
		survival.advance();
		survival.facts.alive = false;
		assertEquals(0, survival.runtime.tick().size(), "death resets survival duration");
		survival.advance();
		survival.facts.alive = true;
		assertEquals(0, survival.runtime.tick().size(), "survival restarts after becoming alive");
		survival.advance();
		assertEquals(1, survival.runtime.tick().size(), "continuous survival reaches the stored duration");

		Fixture confirmed = fixture(new GoalPredicate.OperatorConfirmed(), 600L);
		assertEquals(false, confirmed.runtime.evaluate(confirmed.agentId).verified(), "operator predicate fails closed before confirmation");
		confirmed.runtime.confirm(confirmed.agentId, confirmed.record().currentGoal().orElseThrow().goalId());
		assertEquals(1, confirmed.runtime.tick().size(), "exact current goal confirmation satisfies once");
		return 6;
	}

	private static int verifyQueuedPromotionAfterEvidence() {
		Fixture fixture = fixture(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1), 700L);
		GoalSpec queued = GoalSpec.create("Get a diamond pickaxe",
				new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1), 701L);
		fixture.registry.queue(fixture.agentId, queued, fixture.now + 1L);
		fixture.facts.items.put("minecraft:iron_pickaxe", 1);
		assertEquals(1, fixture.runtime.tick().size(), "satisfied queued agent first stores accepted evidence");
		assertEquals(true, fixture.record().currentGoal().orElseThrow().evidence().isPresent(), "accepted evidence is persisted before promotion");
		fixture.advance();
		assertEquals(1, fixture.runtime.tick().size(), "next tick promotes queued work in a separate transition");
		assertEquals("Get a diamond pickaxe", fixture.record().currentGoal().orElseThrow().prompt(), "queued head is promoted exactly");
		assertEquals(AgentLifecycleState.STARTING, fixture.record().state(), "promoted goal resumes autonomous work");
		return 5;
	}

	private static Fixture fixture(GoalPredicate predicate, long createdAtTick) {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(16, 8, () -> { }, transitions::add);
		long now = 10_000L + createdAtTick;
		AgentRecord idle = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalSpec spec = GoalSpec.create(goalName(predicate), predicate, createdAtTick);
		registry.start(idle.agentId(), spec, now + 1L);
		transitions.clear();
		FakeFacts facts = new FakeFacts();
		long[] tick = { createdAtTick };
		long[] epoch = { now + 2L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				registry, ignored -> Optional.of(facts), () -> tick[0], () -> epoch[0]);
		return new Fixture(registry, runtime, facts, transitions, idle.agentId(), tick, epoch, now + 2L);
	}

	private static String goalName(GoalPredicate predicate) {
		return "Verify " + predicate.getClass().getSimpleName();
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		System.out.println("PASS: " + label);
	}

	private static final class FakeFacts implements GoalCompletionVerifier.FactSource {
		private final Map<String, Integer> items = new HashMap<>();
		private final Map<String, GoalCompletionVerifier.BlockFact> blocks = new HashMap<>();
		private final Map<String, Boolean> advancements = new HashMap<>();
		private GoalCompletionVerifier.Position position = new GoalCompletionVerifier.Position(0.0, 64.0, 0.0);
		private boolean alive = true;

		@Override public int inventoryCount(String itemId) { return items.getOrDefault(itemId, 0); }
		@Override public GoalCompletionVerifier.Position position() { return position; }
		@Override public GoalCompletionVerifier.BlockFact blockAt(int x, int y, int z) {
			return blocks.getOrDefault(x + "," + y + "," + z, new GoalCompletionVerifier.BlockFact("minecraft:air", Map.of()));
		}
		@Override public boolean advancementGranted(String advancementId) { return advancements.getOrDefault(advancementId, false); }
		@Override public boolean alive() { return alive; }
	}

	private static final class Fixture {
		private final AgentRegistry registry;
		private final GoalVerificationRuntime runtime;
		private final FakeFacts facts;
		private final List<AgentTransition> transitions;
		private final AgentId agentId;
		private final long[] tick;
		private final long[] epoch;
		private long now;

		private Fixture(AgentRegistry registry, GoalVerificationRuntime runtime, FakeFacts facts,
				List<AgentTransition> transitions, AgentId agentId, long[] tick, long[] epoch, long now) {
			this.registry = registry;
			this.runtime = runtime;
			this.facts = facts;
			this.transitions = transitions;
			this.agentId = agentId;
			this.tick = tick;
			this.epoch = epoch;
			this.now = now;
		}

		private AgentRecord record() { return registry.require(agentId); }
		private GoalStatus goalStatus() { return record().currentGoal().orElseThrow().status(); }
		private void advance() { tick[0]++; epoch[0]++; now++; }
	}
}
