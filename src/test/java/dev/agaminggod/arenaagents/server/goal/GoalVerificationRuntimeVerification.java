package dev.agaminggod.arenaagents.server.goal;

import com.mojang.serialization.Codec;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentRegistrySnapshotCodec;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

public final class GoalVerificationRuntimeVerification {
	private GoalVerificationRuntimeVerification() { }

	public static int verify() {
		int assertions = 0;
		assertions += verifyExactInventoryAndIdempotence();
		assertions += verifyStablePositionAndReset();
		assertions += verifyDimensionBinding();
		assertions += verifyBlockAdvancementAndCompoundPredicates();
		assertions += verifyAgentSpecificKillAttribution();
		assertions += verifyAnyOfEvidenceSelection();
		assertions += verifyDistinctRepeatedKillAttribution();
		assertions += verifyKillGoalAfterServerTickReset();
		assertions += verifyPersistedKillProgressAcrossRestart();
		assertions += verifyActiveKillProgressSurvivesLedgerEviction();
		assertions += verifyPersistedKillActivationFencing();
		assertions += verifyKillLedgerPersistenceCompatibility();
		assertions += verifyIndexedKillLookup();
		assertions += verifyVerifierFailureIsolationAndRetry();
		assertions += verifySurvivalAndOperatorConfirmation();
		assertions += verifyOperatorConfirmationAcrossRestart();
		assertions += verifySurvivalProgressAcrossRestart();
		assertions += verifySurvivalDeathResetAcrossRestart();
		assertions += verifySurvivalProgressBoundsAndPruning();
		assertions += verifySurvivalProgressPersistenceCompatibility();
		assertions += verifyQueuedPromotionAfterEvidence();
		assertions += verifyQueuedKillActivationBoundary();
		assertions += verifyRequestedCompletionLifecycle();
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

	private static int verifyDimensionBinding() {
		Fixture fixture = fixture(new GoalPredicate.PositionWithin("minecraft:the_nether", 0.0, 64.0, 0.0, 1.0, 1), 250L);
		fixture.facts.dimension = GoalPredicate.DEFAULT_DIMENSION;
		GoalCompletionVerifier.VerificationResult mismatch = fixture.runtime.evaluate(fixture.agentId);
		assertEquals(false, mismatch.verified(), "coordinate goal fails closed in the wrong dimension");
		assertEquals("position_dimension", mismatch.facts().getFirst().type(), "dimension mismatch is explicit evidence");
		return 2;
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
		long goalCreated = fixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		fixture.runtime.killLedger().record(other, "minecraft:ender_dragon", goalCreated + 1L);
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", goalCreated - 1L);
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", goalCreated);
		assertEquals(false, fixture.runtime.evaluate(fixture.agentId).verified(), "another player and pre-goal kills cannot satisfy attribution");
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:ender_dragon", goalCreated + 1L);
		assertEquals(true, fixture.runtime.evaluate(fixture.agentId).verified(), "responsible agent kill after goal start satisfies attribution");
		assertEquals(1, fixture.runtime.tick().size(), "attributed kill completes once");
		return 3;
	}

	private static int verifyAnyOfEvidenceSelection() {
		GoalPredicate alternatives = new GoalPredicate.AnyOf(List.of(
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
		));
		Fixture fixture = fixture(alternatives, 425L);
		long goalCreated = fixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:zombie", goalCreated + 1L);

		GoalCompletionVerifier.VerificationResult result = fixture.runtime.evaluate(fixture.agentId);
		assertEquals(true, result.verified(), "one successful alternative verifies the goal");
		assertEquals(1, result.facts().size(), "verified alternatives retain only selected branch evidence");
		assertEquals("minecraft:zombie x1", result.facts().getFirst().expectedValue(),
				"selected alternative evidence identifies the successful branch");
		assertEquals(true, result.facts().getFirst().satisfied(),
				"accepted alternative evidence contains no failed requirement");

		Fixture failedFixture = fixture(alternatives, 426L);
		GoalCompletionVerifier.VerificationResult failed = failedFixture.runtime.evaluate(failedFixture.agentId);
		assertEquals(false, failed.verified(), "unsatisfied alternatives fail verification");
		assertEquals(List.of("minecraft:zombie x1", "minecraft:skeleton x1"),
				failed.facts().stream().map(fact -> fact.expectedValue()).toList(),
				"failed alternatives retain bounded evidence from every branch");
		return 6;
	}

	private static int verifyDistinctRepeatedKillAttribution() {
		GoalPredicate repeatedKills = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
		));
		Fixture fixture = fixture(repeatedKills, 450L);
		long goalCreated = fixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:zombie", goalCreated + 1L);
		assertEquals(false, fixture.runtime.evaluate(fixture.agentId).verified(),
				"one kill cannot satisfy two repeated kill requirements");
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:zombie", goalCreated + 2L);
		assertEquals(true, fixture.runtime.evaluate(fixture.agentId).verified(),
				"two distinct kills satisfy two repeated kill requirements");

		GoalPredicate alternatives = new GoalPredicate.AnyOf(List.of(
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
		));
		Fixture alternativeFixture = fixture(alternatives, 451L);
		long alternativeStart = alternativeFixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		alternativeFixture.runtime.killLedger().record(
				alternativeFixture.agentId, "minecraft:zombie", alternativeStart + 1L);
		assertEquals(true, alternativeFixture.runtime.evaluate(alternativeFixture.agentId).verified(),
				"alternative kill branches share the same single-event requirement");

		GoalPredicate alternativeThenRepeated = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
						new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
				)),
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
		));
		Fixture backtrackingFixture = fixture(alternativeThenRepeated, 452L);
		long backtrackingStart = backtrackingFixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		backtrackingFixture.runtime.killLedger().record(
				backtrackingFixture.agentId, "minecraft:zombie", backtrackingStart + 1L);
		backtrackingFixture.runtime.killLedger().record(
				backtrackingFixture.agentId, "minecraft:skeleton", backtrackingStart + 2L);
		assertEquals(true, backtrackingFixture.runtime.evaluate(backtrackingFixture.agentId).verified(),
				"an enclosing conjunction backtracks to the satisfiable kill alternative");

		Fixture insufficientFixture = fixture(alternativeThenRepeated, 453L);
		long insufficientStart = insufficientFixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		insufficientFixture.runtime.killLedger().record(
				insufficientFixture.agentId, "minecraft:zombie", insufficientStart + 1L);
		assertEquals(false, insufficientFixture.runtime.evaluate(insufficientFixture.agentId).verified(),
				"alternative backtracking cannot reuse one kill across a repeated requirement");

		GoalPredicate nestedAlternative = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.AllOf(List.of(
								new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
								new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
						)),
						new GoalPredicate.EntityKilledByAgent("minecraft:creeper", true)
				)),
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
		));
		Fixture nestedFixture = fixture(nestedAlternative, 454L);
		long nestedStart = nestedFixture.record().currentGoal().orElseThrow().createdAtEpochMs();
		nestedFixture.runtime.killLedger().record(nestedFixture.agentId, "minecraft:zombie", nestedStart + 1L);
		nestedFixture.runtime.killLedger().record(nestedFixture.agentId, "minecraft:skeleton", nestedStart + 2L);
		nestedFixture.runtime.killLedger().record(nestedFixture.agentId, "minecraft:creeper", nestedStart + 3L);
		GoalCompletionVerifier.VerificationResult nestedResult = nestedFixture.runtime.evaluate(nestedFixture.agentId);
		assertEquals(true, nestedResult.verified(),
				"nested alternatives backtrack when their first satisfied compound exhausts a later requirement");
		assertEquals(2, nestedResult.facts().size(),
				"nested backtracking retains only the allocation branch that satisfies the conjunction");
		assertEquals("minecraft:creeper x1", nestedResult.facts().get(0).expectedValue(),
				"nested backtracking evidence identifies the selected alternative");
		assertEquals("minecraft:zombie x1", nestedResult.facts().get(1).expectedValue(),
				"nested backtracking evidence retains the enclosing requirement");
		return 9;
	}

	private static int verifyKillGoalAfterServerTickReset() {
		Fixture original = fixture(new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true), 20_000L);
		String persisted = new AgentRegistrySnapshotCodec().encode(original.registry.snapshot());
		AgentRegistry.Snapshot decoded = new AgentRegistrySnapshotCodec().decode(persisted);
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		long restartEpoch = original.record().currentGoal().orElseThrow().createdAtEpochMs() + 1_000L;
		AgentRegistry restored = AgentRegistry.restore(decoded, () -> { }, transitions::add, restartEpoch);
		long[] resetTick = { 1L };
		long[] epoch = { restartEpoch + 1L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				restored, ignored -> Optional.of(original.facts), () -> resetTick[0], () -> epoch[0]);
		runtime.recordKill(original.agentId, "minecraft:ender_dragon");
		assertEquals(true, runtime.evaluate(original.agentId).verified(),
				"a persisted kill goal remains satisfiable after the server tick resets");
		assertEquals(1, runtime.tick().size(),
				"post-restart kill evidence completes the restored goal once");
		return 2;
	}

	private static int verifyPersistedKillProgressAcrossRestart() {
		AgentSavedData data = new AgentSavedData();
		AgentRegistry registry = data.registry();
		long now = 40_000L;
		AgentRecord idle = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalPredicate compound = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.InventoryContains("minecraft:iron_ingot", 1)
		));
		registry.start(idle.agentId(), GoalSpec.create("Kill a zombie and get iron", compound, 900L), now + 1L);
		FakeFacts facts = new FakeFacts();
		long[] tick = { 900L };
		long[] epoch = { now + 2L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				registry, ignored -> Optional.of(facts), () -> tick[0], () -> epoch[0], data.killLedger());
		runtime.recordKill(idle.agentId(), "minecraft:zombie");
		assertEquals(true, data.isDirty(), "kill recording dirties saved data before a verification tick");

		AgentSavedData restored = roundTripSavedData(data, false);
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> 1L, () -> epoch[0] + 1L, restored.killLedger());
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"a restored compound goal still waits for its unfinished non-kill predicate");
		facts.items.put("minecraft:iron_ingot", 1);
		assertEquals(true, restoredRuntime.evaluate(idle.agentId()).verified(),
				"persisted partial kill progress completes the compound goal after restart");

		AgentSavedData singleData = new AgentSavedData();
		AgentRecord singleIdle = singleData.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now + 10L);
		singleData.registry().start(singleIdle.agentId(), GoalSpec.create(
				"Kill a skeleton", new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true), 901L), now + 11L);
		GoalVerificationRuntime singleRuntime = new GoalVerificationRuntime(
				singleData.registry(), ignored -> Optional.of(new FakeFacts()), () -> 901L, () -> now + 12L,
				singleData.killLedger());
		singleRuntime.recordKill(singleIdle.agentId(), "minecraft:skeleton");
		AgentSavedData restoredSingle = roundTripSavedData(singleData, false);
		GoalVerificationRuntime restoredSingleRuntime = new GoalVerificationRuntime(
				restoredSingle.registry(), ignored -> Optional.of(new FakeFacts()), () -> 1L, () -> now + 13L,
				restoredSingle.killLedger());
		assertEquals(true, restoredSingleRuntime.evaluate(singleIdle.agentId()).verified(),
				"a kill saved before the next verification tick survives a crash and restart");
		return 4;
	}

	private static int verifyPersistedKillActivationFencing() {
		AgentSavedData data = new AgentSavedData();
		long now = 50_000L;
		AgentRecord idle = data.registry().create("codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		data.registry().start(idle.agentId(), GoalSpec.create("Get iron",
				new GoalPredicate.InventoryContains("minecraft:iron_ingot", 1), 950L), now + 1L);
		data.registry().queue(idle.agentId(), GoalSpec.create("Kill a zombie",
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true), 951L), now + 2L);
		FakeFacts facts = new FakeFacts();
		long[] tick = { 950L };
		long[] epoch = { now + 3L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(facts), () -> tick[0], () -> epoch[0], data.killLedger());
		runtime.recordKill(idle.agentId(), "minecraft:zombie");
		facts.items.put("minecraft:iron_ingot", 1);
		runtime.tick();
		tick[0]++;
		epoch[0]++;
		runtime.tick();
		long activation = data.registry().require(idle.agentId()).currentGoal().orElseThrow().createdAtEpochMs();
		data.killLedger().record(idle.agentId(), "minecraft:zombie", activation);

		AgentSavedData restored = roundTripSavedData(data, false);
		long[] restartEpoch = { activation + 1L };
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> 1L, () -> restartEpoch[0], restored.killLedger());
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"persisted kills before or exactly at queued-goal activation remain fenced after restart");
		restoredRuntime.recordKill(idle.agentId(), "minecraft:zombie");
		assertEquals(true, restoredRuntime.evaluate(idle.agentId()).verified(),
				"the first persisted kill strictly after activation satisfies the restored queued goal");
		return 2;
	}

	private static int verifyActiveKillProgressSurvivesLedgerEviction() {
		AgentSavedData data = new AgentSavedData();
		long now = 45_000L;
		AgentRecord idle = data.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalPredicate compound = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
				new GoalPredicate.InventoryContains("minecraft:iron_ingot", 1)
		));
		data.registry().start(idle.agentId(), GoalSpec.create(
				"Kill two zombies and get iron", compound, 925L), now + 1L);
		FakeFacts facts = new FakeFacts();
		long[] tick = { 925L };
		long[] epoch = { now + 2L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(facts), () -> tick[0], () -> epoch[0], data.killLedger());
		runtime.recordKill(idle.agentId(), "minecraft:zombie");
		UUID goalId = data.registry().require(idle.agentId()).currentGoal().orElseThrow().goalId();
		long activation = data.registry().require(idle.agentId()).currentGoal().orElseThrow().createdAtEpochMs();
		AgentId noisyAgent = AgentId.random();
		for (int index = 0; index < AgentKillLedger.MAX_EVENTS; index++) {
			data.killLedger().record(noisyAgent, "minecraft:skeleton", now + 3L + index);
		}
		assertEquals(AgentKillLedger.MAX_EVENTS, data.killLedger().size(),
				"global kill history stays bounded while an active goal is tracked");
		assertEquals(0, data.killLedger().count(idle.agentId(), "minecraft:zombie", activation),
				"the qualifying kill is no longer present in the bounded event suffix");
		assertEquals(1, data.killLedger().count(goalId, idle.agentId(), "minecraft:zombie", activation),
				"eviction compacts the qualifying kill into bounded active-goal progress");

		AgentSavedData restored = roundTripSavedData(data, false);
		long[] restartEpoch = { now + AgentKillLedger.MAX_EVENTS + 10L };
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> 1L, () -> restartEpoch[0], restored.killLedger());
		facts.items.put("minecraft:iron_ingot", 1);
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"one compacted kill cannot satisfy two repeated kill requirements after restart");
		restoredRuntime.recordKill(idle.agentId(), "minecraft:zombie");
		assertEquals(true, restoredRuntime.evaluate(idle.agentId()).verified(),
				"a new kill combines with persisted compacted progress to finish the repeated goal");
		assertEquals(true, restored.isDirty(),
				"persisted progress and its later update dirty Minecraft saved data");
		assertEquals(1, restoredRuntime.tick().size(),
				"compacted kill evidence completes the active goal exactly once");
		assertEquals(0, restored.killLedger().snapshot().progress().size(),
				"completed goals release their compacted kill progress");
		return 8;
	}

	private static int verifyKillLedgerPersistenceCompatibility() {
		AgentSavedData data = new AgentSavedData();
		AgentId agentId = AgentId.random();
		data.killLedger().record(agentId, "minecraft:zombie", 1L);
		AgentSavedData legacy = roundTripSavedData(data, true);
		assertEquals(0, legacy.killLedger().size(), "saved data without the new optional field loads as an empty legacy ledger");

		AgentKillLedgerCodec codec = new AgentKillLedgerCodec();
		String encoded = codec.encode(data.killLedger().snapshot());
		String legacyEncoding = encoded.replace("\"schema_version\":2", "\"schema_version\":1")
				.replace(",\"progress\":[]", "");
		assertEquals(1, codec.decode(legacyEncoding).events().size(),
				"schema-one kill ledgers upgrade with empty compacted progress");
		String unsupported = encoded.replace("\"schema_version\":2", "\"schema_version\":3");
		try {
			codec.decode(unsupported);
			throw new AssertionError("unsupported kill-ledger schema must fail closed");
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
			assertEquals("INVALID_PERSISTED_KILL_LEDGER", expected.code(),
					"unsupported persisted kill-ledger versions are rejected explicitly");
		}
		return 3;
	}

	private static int verifyIndexedKillLookup() {
		AgentKillLedger ledger = new AgentKillLedger();
		AgentId agentId = AgentId.random();
		for (int index = 0; index < 4_096; index++) {
			ledger.record(agentId, "minecraft:zombie", index);
		}
		assertEquals(16, ledger.count(agentId, "minecraft:zombie", 4_079L),
				"indexed kill lookup returns the exact suffix count");
		assertEquals(true, ledger.lastLookupProbeCount() <= 13,
				"a full kill ledger lookup uses logarithmic probes instead of rescanning all events");
		ledger.record(agentId, "minecraft:zombie", 4_096L);
		assertEquals(AgentKillLedger.MAX_EVENTS, ledger.size(), "kill history remains bounded after overflow");
		assertEquals(AgentKillLedger.MAX_EVENTS, ledger.count(agentId, "minecraft:zombie", 0L),
				"overflow evicts the oldest kill without discarding the bounded recent suffix");
		return 4;
	}

	private static int verifyVerifierFailureIsolationAndRetry() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(16, 8, () -> { }, transitions::add);
		long now = 30_000L;
		AgentRecord broken = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.of("Broken"),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		AgentRecord healthy = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.of("Healthy"),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now + 1L);
		registry.start(broken.agentId(), GoalSpec.create("Broken verifier",
				new GoalPredicate.InventoryContains("minecraft:stone", 1), 100L), now + 2L);
		registry.start(healthy.agentId(), GoalSpec.create("Healthy verifier",
				new GoalPredicate.InventoryContains("minecraft:stone", 1), 100L), now + 3L);
		transitions.clear();
		int[] brokenCalls = { 0 };
		FakeFacts healthyFacts = new FakeFacts();
		healthyFacts.items.put("minecraft:stone", 1);
		GoalCompletionVerifier.FactSource brokenFacts = new FakeFacts() {
			@Override public int inventoryCount(String itemId) {
				brokenCalls[0]++;
				throw new IllegalStateException("fixture verifier failure");
			}
		};
		long[] tick = { 100L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				registry,
				agentId -> Optional.of(agentId.equals(broken.agentId()) ? brokenFacts : healthyFacts),
				() -> tick[0], () -> now + tick[0]);
		assertEquals(1, runtime.tick().size(), "one verifier exception does not prevent a later agent from completing");
		assertEquals(GoalStatus.ACTIVE, registry.require(broken.agentId()).currentGoal().orElseThrow().status(),
				"a verifier exception leaves the affected goal active");
		GoalVerificationRuntime.VerificationFault fault = runtime.faults().getFirst();
		assertEquals(broken.agentId(), fault.agentId(), "the bounded diagnostic identifies the affected agent");
		assertEquals(1, fault.consecutiveFailures(), "the first verifier failure is diagnosed once");

		tick[0]++;
		runtime.tick();
		assertEquals(2, brokenCalls[0], "the verifier retries after the first bounded delay");
		tick[0]++;
		runtime.tick();
		assertEquals(2, brokenCalls[0], "exponential retry backoff avoids retrying every server tick");
		assertEquals(true, runtime.faults().getFirst().retryAtTick() - tick[0]
				<= GoalVerificationRuntime.MAX_RETRY_DELAY_TICKS,
				"verifier retry remains bounded");
		return 7;
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

	private static int verifyOperatorConfirmationAcrossRestart() {
		AgentSavedData data = new AgentSavedData();
		long now = 65_000L;
		AgentRecord idle = data.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalPredicate compound = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.OperatorConfirmed(),
				new GoalPredicate.InventoryContains("minecraft:iron_ingot", 1)
		));
		data.registry().start(idle.agentId(), GoalSpec.create("Confirm and get iron", compound, 675L), now + 1L);
		FakeFacts facts = new FakeFacts();
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(facts), () -> 675L, () -> now + 2L,
				data.killLedger(), data.survivalProgress(), data.operatorConfirmations());
		UUID goalId = data.registry().require(idle.agentId()).currentGoal().orElseThrow().goalId();
		runtime.confirm(idle.agentId(), goalId);
		assertEquals(false, runtime.evaluate(idle.agentId()).verified(),
				"operator confirmation remains partial while a factual sibling is unfinished");

		AgentSavedData restored = roundTripSavedData(data, false);
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> 1L, () -> now + 3L,
				restored.killLedger(), restored.survivalProgress(), restored.operatorConfirmations());
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"persisted confirmation remains true while the restored factual sibling is unfinished");
		facts.items.put("minecraft:iron_ingot", 1);
		assertEquals(true, restoredRuntime.evaluate(idle.agentId()).verified(),
				"persisted confirmation completes its compound goal after the remaining fact becomes true");
		assertEquals(1, restoredRuntime.tick().size(),
				"restored operator confirmation emits one terminal transition");
		assertEquals(0, restored.operatorConfirmations().snapshot().goalIds().size(),
				"resolved goals release their durable operator confirmation");

		AgentSavedData legacy = roundTripSavedData(data, false, false, true);
		assertEquals(0, legacy.operatorConfirmations().snapshot().goalIds().size(),
				"saved data without confirmations loads as an empty legacy ledger");
		OperatorConfirmationLedgerCodec codec = new OperatorConfirmationLedgerCodec();
		String encoded = codec.encode(data.operatorConfirmations().snapshot());
		assertEquals(1, codec.decode(encoded).goalIds().size(),
				"operator confirmation codec preserves a bounded goal identity");
		return 7;
	}

	private static int verifySurvivalProgressAcrossRestart() {
		AgentSavedData data = new AgentSavedData();
		long now = 60_000L;
		AgentRecord idle = data.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalPredicate compound = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.SurviveDuration(3L),
				new GoalPredicate.InventoryContains("minecraft:iron_ingot", 1)
		));
		data.registry().start(idle.agentId(), GoalSpec.create("Survive and get iron", compound, 650L), now + 1L);
		FakeFacts facts = new FakeFacts();
		long[] tick = { 650L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(facts), () -> tick[0], () -> now + tick[0],
				data.killLedger(), data.survivalProgress());
		assertEquals(false, runtime.evaluate(idle.agentId()).verified(),
				"the first alive tick starts compound survival progress");

		AgentSavedData restored = roundTripSavedData(data, false);
		long[] restartTick = { 0L };
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> restartTick[0], () -> now + 2_000L,
				restored.killLedger(), restored.survivalProgress());
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"partial survival progress resumes after saved-data restart");
		restartTick[0]++;
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"satisfied survival progress remains while its compound sibling waits");

		AgentSavedData restoredAgain = roundTripSavedData(restored, false);
		facts.items.put("minecraft:iron_ingot", 1);
		GoalVerificationRuntime completed = new GoalVerificationRuntime(
				restoredAgain.registry(), ignored -> Optional.of(facts), () -> 0L, () -> now + 3_000L,
				restoredAgain.killLedger(), restoredAgain.survivalProgress());
		assertEquals(true, completed.evaluate(idle.agentId()).verified(),
				"an already-satisfied survival leaf completes its compound goal after another restart");
		return 4;
	}

	private static int verifySurvivalDeathResetAcrossRestart() {
		AgentSavedData data = new AgentSavedData();
		long now = 70_000L;
		AgentRecord idle = data.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		data.registry().start(idle.agentId(), GoalSpec.create(
				"Survive after death", new GoalPredicate.SurviveDuration(3L), 700L), now + 1L);
		FakeFacts facts = new FakeFacts();
		long[] tick = { 700L };
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(facts), () -> tick[0], () -> now + tick[0],
				data.killLedger(), data.survivalProgress());
		runtime.evaluate(idle.agentId());
		tick[0]++;
		runtime.evaluate(idle.agentId());
		tick[0]++;
		facts.alive = false;
		assertEquals(false, runtime.evaluate(idle.agentId()).verified(),
				"an authoritative death clears accumulated survival progress");

		AgentSavedData restored = roundTripSavedData(data, false);
		facts.alive = true;
		long[] restartTick = { 0L };
		GoalVerificationRuntime restoredRuntime = new GoalVerificationRuntime(
				restored.registry(), ignored -> Optional.of(facts), () -> restartTick[0], () -> now + 2_000L,
				restored.killLedger(), restored.survivalProgress());
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"death reset remains authoritative after restart");
		restartTick[0]++;
		assertEquals(false, restoredRuntime.evaluate(idle.agentId()).verified(),
				"post-death survival must rebuild the full duration");
		restartTick[0]++;
		assertEquals(true, restoredRuntime.evaluate(idle.agentId()).verified(),
				"the full post-death duration eventually satisfies");
		return 4;
	}

	private static int verifySurvivalProgressBoundsAndPruning() {
		AgentId agentId = AgentId.random();
		SurvivalProgressLedger ledger = new SurvivalProgressLedger();
		SurvivalProgressLedger.Requirement retained = new SurvivalProgressLedger.Requirement(
				new UUID(0L, 1L), agentId, "root.0", 5L);
		SurvivalProgressLedger.Requirement stale = new SurvivalProgressLedger.Requirement(
				new UUID(0L, 2L), agentId, "root.1", 5L);
		ledger.synchronizeProgress(List.of(retained, stale));
		ledger.observe(retained, true, 1L);
		ledger.observe(stale, true, 1L);
		ledger.synchronizeProgress(List.of(retained));
		assertEquals(1, ledger.snapshot().entries().size(),
				"inactive goal leaves are pruned from durable survival progress");
		assertEquals(retained.goalId(), ledger.snapshot().entries().getFirst().goalId(),
				"pruning retains only the current survival requirement");

		ArrayList<SurvivalProgressLedger.Requirement> maximum = new ArrayList<>();
		for (int index = 0; index < SurvivalProgressLedger.MAX_ENTRIES; index++) {
			maximum.add(new SurvivalProgressLedger.Requirement(
					new UUID(1L, index), agentId, "root", 1L));
		}
		ledger.synchronizeProgress(maximum);
		assertEquals(SurvivalProgressLedger.MAX_ENTRIES, ledger.snapshot().entries().size(),
				"durable survival progress accepts its exact configured bound");
		maximum.add(new SurvivalProgressLedger.Requirement(
				new UUID(2L, 0L), agentId, "root", 1L));
		try {
			ledger.synchronizeProgress(maximum);
			throw new AssertionError("survival progress above the configured bound must fail");
		} catch (IllegalArgumentException expected) {
			assertEquals("Active survival progress exceeds the bounded limit", expected.getMessage(),
					"survival progress rejects entries above the configured bound");
		}
		return 4;
	}

	private static int verifySurvivalProgressPersistenceCompatibility() {
		AgentSavedData data = new AgentSavedData();
		long now = 80_000L;
		AgentRecord idle = data.registry().create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		data.registry().start(idle.agentId(), GoalSpec.create(
				"Survive", new GoalPredicate.SurviveDuration(2L), 800L), now + 1L);
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				data.registry(), ignored -> Optional.of(new FakeFacts()), () -> 800L, () -> now + 2L,
				data.killLedger(), data.survivalProgress());
		runtime.evaluate(idle.agentId());
		AgentSavedData legacy = roundTripSavedData(data, false, true);
		assertEquals(0, legacy.survivalProgress().snapshot().entries().size(),
				"saved data without survival progress loads as an empty legacy ledger");

		SurvivalProgressLedgerCodec codec = new SurvivalProgressLedgerCodec();
		String encoded = codec.encode(data.survivalProgress().snapshot());
		assertEquals(1, codec.decode(encoded).entries().size(),
				"survival progress codec preserves a bounded entry");
		try {
			codec.decode(encoded.replace("\"schema_version\":1", "\"schema_version\":2"));
			throw new AssertionError("unsupported survival-progress schema must fail closed");
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
			assertEquals("INVALID_PERSISTED_SURVIVAL_PROGRESS", expected.code(),
					"unsupported survival-progress versions are rejected explicitly");
		}
		return 3;
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

	private static int verifyQueuedKillActivationBoundary() {
		Fixture fixture = fixture(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1), 750L);
		GoalSpec queued = GoalSpec.create("Kill a zombie",
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true), 751L);
		fixture.registry.queue(fixture.agentId, queued, fixture.now + 1L);
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:zombie", fixture.epoch[0]);
		fixture.facts.items.put("minecraft:iron_pickaxe", 1);
		assertEquals(1, fixture.runtime.tick().size(), "first goal satisfies before queued kill promotion");
		fixture.advance();
		assertEquals(1, fixture.runtime.tick().size(), "queued kill goal promotes after prior goal evidence");
		assertEquals(false, fixture.runtime.evaluate(fixture.agentId).verified(), "queued goal ignores kills recorded before activation");
		fixture.runtime.killLedger().record(fixture.agentId, "minecraft:zombie", fixture.epoch[0] + 1L);
		assertEquals(true, fixture.runtime.evaluate(fixture.agentId).verified(), "queued goal accepts a kill recorded after activation");
		return 4;
	}

	private static int verifyRequestedCompletionLifecycle() {
		Fixture fixture = fixture(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1), 800L);
		long requestedRevision = fixture.record().goalRevision();
		String fingerprint = fixture.record().currentGoal().orElseThrow().spec().fingerprint();
		GoalCompletionVerifier.VerificationResult failed = fixture.runtime.evaluateRequest(
				fixture.agentId, requestedRevision, fingerprint);
		assertEquals(false, failed.verified(), "server rejects a finish request while the immutable predicate is false");
		assertEquals(GoalStatus.ACTIVE, fixture.goalStatus(), "failed finish verification leaves the goal active");
		assertEquals(1, failed.facts().size(), "failed finish verification returns exact observed evidence");

		fixture.facts.items.put("minecraft:iron_pickaxe", 1);
		GoalCompletionVerifier.VerificationResult verified = fixture.runtime.evaluateRequest(
				fixture.agentId, requestedRevision, fingerprint);
		assertEquals(true, verified.verified(), "server accepts a finish request only after live facts satisfy the goal");
		assertEquals(true, fixture.runtime.acceptVerified(
				fixture.agentId, requestedRevision, fingerprint, verified).isPresent(),
				"accepted server evidence performs one lifecycle transition");
		assertEquals(GoalStatus.SATISFIED, fixture.goalStatus(), "accepted finish evidence persists satisfied status");
		assertEquals(false, fixture.runtime.acceptVerified(
				fixture.agentId, requestedRevision, fingerprint, verified).isPresent(),
				"replayed verified finish is idempotent");
		assertEquals(true, fixture.runtime.evaluateRequest(
				fixture.agentId, requestedRevision, fingerprint).verified(),
				"a delayed completion-result replay returns stored accepted evidence");

		try {
			fixture.runtime.evaluateRequest(fixture.agentId, requestedRevision, "stale-fingerprint");
			throw new AssertionError("stale finish fingerprint must fail closed");
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
			assertEquals("STALE_GOAL_FINGERPRINT", expected.code(), "stale finish fingerprint is rejected explicitly");
		}
		return 9;
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

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTripSavedData(AgentSavedData data, boolean removeKillLedger) {
		return roundTripSavedData(data, removeKillLedger, false);
	}

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTripSavedData(
			AgentSavedData data, boolean removeKillLedger, boolean removeSurvivalProgress
	) {
		return roundTripSavedData(data, removeKillLedger, removeSurvivalProgress, false);
	}

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTripSavedData(
			AgentSavedData data,
			boolean removeKillLedger,
			boolean removeSurvivalProgress,
			boolean removeOperatorConfirmations
	) {
		try {
			Field field = AgentSavedData.class.getDeclaredField("CODEC");
			field.setAccessible(true);
			Codec<AgentSavedData> codec = (Codec<AgentSavedData>) field.get(null);
			Tag encoded = codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
			if (removeKillLedger) ((CompoundTag) encoded).remove("kill_ledger_chunks");
			if (removeSurvivalProgress) ((CompoundTag) encoded).remove("survival_progress_chunks");
			if (removeOperatorConfirmations) ((CompoundTag) encoded).remove("operator_confirmation_chunks");
			return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not round-trip ledgers through Minecraft SavedData", exception);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		System.out.println("PASS: " + label);
	}

	private static class FakeFacts implements GoalCompletionVerifier.FactSource {
		private final Map<String, Integer> items = new HashMap<>();
		private final Map<String, GoalCompletionVerifier.BlockFact> blocks = new HashMap<>();
		private final Map<String, Boolean> advancements = new HashMap<>();
		private GoalCompletionVerifier.Position position = new GoalCompletionVerifier.Position(0.0, 64.0, 0.0);
		private boolean alive = true;
		private String dimension = GoalPredicate.DEFAULT_DIMENSION;

		@Override public int inventoryCount(String itemId) { return items.getOrDefault(itemId, 0); }
		@Override public GoalCompletionVerifier.Position position() { return position; }
		@Override public GoalCompletionVerifier.BlockFact blockAt(int x, int y, int z) {
			return blocks.getOrDefault(x + "," + y + "," + z, new GoalCompletionVerifier.BlockFact("minecraft:air", Map.of()));
		}
		@Override public boolean advancementGranted(String advancementId) { return advancements.getOrDefault(advancementId, false); }
		@Override public boolean alive() { return alive; }
		@Override public String dimensionId() { return dimension; }
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
