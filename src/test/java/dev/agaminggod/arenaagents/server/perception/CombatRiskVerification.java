package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatPlanning;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatPlanning.PolicyCandidate;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatPlanning.TargetPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.Difficulty;

/**
 * Verifies the risk facts and model-chosen combat options: the open-ended risk score and its factors, expected hit
 * damage rules, the potential/active split for players, risk ordering, target-policy hysteresis and the debounced
 * healing signals. None of these choose an action; they inform the model or apply a policy the model picked.
 */
public final class CombatRiskVerification {
	private static int assertions;

	private CombatRiskVerification() {
	}

	public static int verify() {
		assertions = 0;
		verifyRiskScale();
		verifyRiskFactors();
		verifyOpenEnded();
		verifyExpectedHitRules();
		verifyAggressionLedger();
		verifyAttackedSignal();
		verifyRiskOrder();
		verifyTargetPolicies();
		verifyHealingSignals();
		verifyHealingAttention();
		return assertions;
	}

	private static RiskModel.Input zombie(double distance, boolean hunting) {
		return new RiskModel.Input(0.6D, 1.95D, 20.0D, 2.0D * 0.0D, false, 0.23D, 0.0D, distance, 3.0D, false, false, hunting, false, 0.0D);
	}

	private static void verifyRiskScale() {
		double calm = RiskModel.score(zombie(3.0D, false)).risk();
		double hunting = RiskModel.score(zombie(3.0D, true)).risk();
		check(calm >= 40.0D && calm <= 50.0D, "an ordinary zombie 3 blocks away scores about 45 (" + calm + ")");
		check(hunting >= 50.0D && hunting <= 60.0D, "the same zombie hunting the agent scores about 56 (" + hunting + ")");
		RiskModel.Result result = RiskModel.score(zombie(3.0D, false));
		Map<String, Double> factors = result.factors();
		check(factors.keySet().equals(Set.of("proximity", "health", "speed", "size", "damage", "behaviour"))
				&& factors.get("proximity") == 0.5D && factors.get("health") == 1.0D,
				"risk factors are reported so the model can see why");
	}

	private static void verifyRiskFactors() {
		double base = RiskModel.score(zombie(3.0D, false)).risk();
		check(RiskModel.score(zombie(1.0D, false)).risk() > base && RiskModel.score(zombie(10.0D, false)).risk() < base,
				"closer is riskier");
		check(RiskModel.score(new RiskModel.Input(0.4D, 0.3D, 20.0D, 0.0D, false, 0.23D, 0.0D, 3.0D, 3.0D, false, false, false, false, 0.0D)).risk() > base,
				"a smaller hitbox is riskier");
		check(RiskModel.score(new RiskModel.Input(0.6D, 1.95D, 80.0D, 0.0D, false, 0.23D, 0.0D, 3.0D, 3.0D, false, false, false, false, 0.0D)).risk() > base,
				"more health is riskier");
		check(RiskModel.score(new RiskModel.Input(0.6D, 1.95D, 20.0D, 0.0D, false, 0.4D, 0.0D, 3.0D, 3.0D, false, false, false, false, 0.0D)).risk() > base,
				"a faster mob is riskier");
		check(RiskModel.score(new RiskModel.Input(0.6D, 1.95D, 20.0D, 0.0D, false, 0.23D, 0.6D, 3.0D, 3.0D, false, false, false, false, 0.0D)).risk() > base,
				"observed speed (a charge) raises risk above the attribute");
		check(RiskModel.score(new RiskModel.Input(0.6D, 1.95D, 20.0D, 0.0D, false, 0.23D, 0.0D, 3.0D, 9.0D, false, false, false, false, 0.0D)).risk() > base,
				"a harder expected hit is riskier");
		double farSkeleton = RiskModel.score(new RiskModel.Input(0.6D, 1.99D, 20.0D, 0.0D, false, 0.25D, 0.0D, 14.0D, 3.0D, true, true, false, false, 0.0D)).proximity();
		check(farSkeleton == RiskModel.RANGED_PROXIMITY_FLOOR, "a ranged mob with a clear shot keeps a proximity floor");
		double swelling = RiskModel.score(new RiskModel.Input(0.6D, 1.7D, 20.0D, 0.0D, false, 0.25D, 0.0D, 3.0D, 16.0D, false, false, true, true, 0.9D)).risk();
		double idle = RiskModel.score(new RiskModel.Input(0.6D, 1.7D, 20.0D, 0.0D, false, 0.25D, 0.0D, 3.0D, 16.0D, false, false, false, false, 0.0D)).risk();
		check(swelling > idle * 3.0D, "a swelling creeper near its fuse dominates");
		double unarmored = RiskModel.score(new RiskModel.Input(0.6D, 1.8D, 20.0D, 0.0D, true, 0.1D, 0.0D, 3.0D, 1.0D, false, false, false, false, 0.0D)).risk();
		double netherite = RiskModel.score(new RiskModel.Input(0.6D, 1.8D, 20.0D, 20.0D, true, 0.1D, 0.0D, 3.0D, 7.0D, false, false, false, false, 0.0D)).risk();
		check(netherite > unarmored * 2.0D, "an armoured, armed player is a much larger potential risk than a bare-handed one");
	}

	private static void verifyOpenEnded() {
		double boss = RiskModel.score(new RiskModel.Input(0.6D, 1.95D, 2000.0D, 30.0D, false, 0.6D, 0.0D, 1.0D, 60.0D, false, false, true, false, 0.0D)).risk();
		check(boss > 1000.0D, "risk is open-ended for boosted mobs (" + boss + ")");
		RiskModel.Result broken = RiskModel.score(new RiskModel.Input(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false,
				Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false, false, false, Double.NaN));
		check(Double.isFinite(broken.risk()), "non-finite inputs are neutralised");
		RiskModel.Result infinite = RiskModel.score(new RiskModel.Input(0.6D, 1.95D, Double.MAX_VALUE, Double.MAX_VALUE, false,
				0.23D, 0.0D, 0.0D, Double.MAX_VALUE, false, false, true, false, 0.0D));
		check(infinite.risk() == RiskModel.NON_FINITE_RISK, "an overflowing score is reported as a large finite value");
	}

	private static void verifyExpectedHitRules() {
		check(ThreatDamage.explosionDamage(0.0D, 3.0D) == 43.0D, "a creeper blast at point blank deals 43 before armour");
		check(ThreatDamage.explosionDamage(7.0D, 3.0D) == 0.0D, "a creeper blast cannot reach beyond 6 blocks");
		check(ThreatDamage.explosionDamage(4.0D, 6.0D) > ThreatDamage.explosionDamage(4.0D, 3.0D) * 2.0D,
				"a charged creeper (double radius) is far more dangerous at the same distance");
		check(ThreatDamage.difficultyScaled(3.0D, Difficulty.EASY) == 2.5D && ThreatDamage.difficultyScaled(3.0D, Difficulty.HARD) == 4.5D
				&& ThreatDamage.difficultyScaled(3.0D, Difficulty.PEACEFUL) == 0.0D, "mob damage scales with difficulty");
		check(ThreatDamage.resistanceScaled(10.0D, 0) == 8.0D && ThreatDamage.resistanceScaled(10.0D, 4) == 0.0D,
				"Resistance reduces 20% per level");
		check(ThreatDamage.bowArrowDamage(0, ThreatDamage.PLAYER_BOW_SPEED, 0) == 6.0D, "a fully drawn bow arrow deals 6");
		check(ThreatDamage.bowArrowDamage(5, ThreatDamage.PLAYER_BOW_SPEED, 0) == 15.0D, "Power V raises a full draw to 15");
		check(ThreatDamage.crossbowDamage(false, 1, ThreatDamage.CROSSBOW_SPEED) > ThreatDamage.crossbowDamage(false, 0, ThreatDamage.CROSSBOW_SPEED)
				&& ThreatDamage.crossbowDamage(true, 0, ThreatDamage.CROSSBOW_SPEED) == 7.0D,
				"Multishot raises the expected volley and a firework rocket blast is counted");
		check(ThreatDamage.kineticBonus(10.0D, 1.2D) == 12.0D && ThreatDamage.kineticBonus(-3.0D, 1.2D) == 0.0D,
				"a spear lunge adds closing speed times the multiplier, never when retreating");
		check(ThreatDamage.criticalFall(1.0D, false, false, false, false) && !ThreatDamage.criticalFall(1.0D, true, false, false, false),
				"a falling player lands a critical hit; one on the ground does not");
	}

	private static void verifyAggressionLedger() {
		AggressionLedger ledger = new AggressionLedger();
		UUID agent = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
		UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
		check(!ledger.attackedRecently(agent, player, 100L), "a player near the agent is never active without an attack");
		ledger.record(agent, player, 100L);
		check(ledger.attackedRecently(agent, player, 100L + AggressionLedger.ACTIVE_TICKS),
				"a player who hit the agent stays an active threat for 30 s");
		check(!ledger.attackedRecently(agent, player, 101L + AggressionLedger.ACTIVE_TICKS),
				"after 30 s of calm it decays back to potential");
		check(!ledger.attackedRecently(agent, player, 50L), "a world-time rewind never makes a future hit active");
		ledger.record(agent, player, 2_000L);
		check(ledger.attackedRecently(agent, player, 2_300L), "another hit renews the active window");
		check(ledger.hasAttackers(agent, 2_300L) && !ledger.hasAttackers(player, 2_300L), "hasAttackers short-circuits lookups");
		check(!ledger.attackedRecently(agent, player, 2_000L + AggressionLedger.ACTIVE_TICKS + 1L) && ledger.victims() == 0,
				"expired entries are pruned on read");
		ledger.record(agent, player, 3_000L);
		ledger.forgetEverywhere(player);
		check(!ledger.attackedRecently(agent, player, 3_001L) && ledger.victims() == 0, "a departed attacker is forgotten");
		ledger.record(agent, player, 3_000L);
		ledger.clear();
		check(ledger.victims() == 0, "server stop clears the ledger");
	}

	private static void verifyAttackedSignal() {
		check(ThreatPerception.signals(true, false, false, false, false, true, 5.0D, true).equals(List.of("attacked")),
				"a player who hurt the agent raises one attacked signal");
		check(ThreatPerception.signals(true, false, false, false, false, true, 5.0D, false).isEmpty(),
				"an armed player who never attacked raises nothing");
		check(ThreatPerception.signals(true, false, false, false, false, true, 18.0D, true).isEmpty(),
				"an attacker beyond 16 blocks raises nothing new");
		check(ThreatPerception.signals(true, true, false, false, false, true, 5.0D, true).equals(List.of("targeting")),
				"attacked never duplicates another signal for the same creature");
		JsonObject before = new JsonObject();
		JsonObject after = new JsonObject();
		JsonObject threats = new JsonObject();
		JsonArray entries = new JsonArray();
		JsonObject entry = new JsonObject();
		entry.addProperty("uuid", "00000000-0000-0000-0000-0000000000b2");
		JsonArray signals = new JsonArray();
		signals.add("attacked");
		entry.add("signals", signals);
		entries.add(entry);
		threats.add("entries", entries);
		after.add("threats", threats);
		Set<String> facts = new java.util.TreeSet<>();
		AttentionSignalPolicy.addNewThreatSignals(facts, AttentionSignalPolicy.threatSignals(before), AttentionSignalPolicy.threatSignals(after));
		check(facts.equals(Set.of("threats.00000000-0000-0000-0000-0000000000b2.attacked")),
				"a player turning hostile is one urgent threat edge");
	}

	private static void verifyRiskOrder() {
		List<ThreatPerception.Entry> entries = new ArrayList<>(List.of(
				new ThreatPerception.Entry("a", "minecraft:zombie", 2.0D, 0.0D, true, false, true, List.of("targeting"), 60.0D, Map.of(), 3.0D),
				new ThreatPerception.Entry("b", "minecraft:creeper", 4.0D, 0.0D, true, true, true, List.of("swelling"), 180.0D, Map.of(), 20.0D),
				new ThreatPerception.Entry("c", "minecraft:skeleton", 9.0D, 0.0D, true, false, true, List.of("ranged_sight"), 60.0D, Map.of(), 4.0D)));
		entries.sort(ThreatPerception.RISK_ORDER);
		check(entries.get(0).uuid().equals("b") && entries.get(1).uuid().equals("a") && entries.get(2).uuid().equals("c"),
				"threats are ordered by risk, ties by distance");
	}

	private static void verifyTargetPolicies() {
		check(TargetPolicy.parse(null) == TargetPolicy.NAMED && TargetPolicy.parse("highest_risk") == TargetPolicy.HIGHEST_RISK,
				"the policy defaults to named and parses the wire names");
		boolean rejected = false;
		try {
			TargetPolicy.parse("auto");
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		check(rejected, "unknown policies are rejected");
		PolicyCandidate current = new PolicyCandidate(3.0D, 50.0D, true, false);
		List<PolicyCandidate> slightlyRiskier = List.of(new PolicyCandidate(4.0D, 58.0D, true, false));
		List<PolicyCandidate> muchRiskier = List.of(new PolicyCandidate(4.0D, 120.0D, true, false));
		check(CombatPlanning.policyRetarget(TargetPolicy.NAMED, current, muchRiskier, 100) == -1,
				"a named fight never switches by itself");
		check(CombatPlanning.policyRetarget(TargetPolicy.HIGHEST_RISK, current, muchRiskier, 5) == -1,
				"hysteresis: no switch before the minimum dwell");
		check(CombatPlanning.policyRetarget(TargetPolicy.HIGHEST_RISK, current, slightlyRiskier, 100) == -1,
				"hysteresis: a slightly riskier attacker does not cause flip-flopping");
		check(CombatPlanning.policyRetarget(TargetPolicy.HIGHEST_RISK, current, muchRiskier, 100) == 0,
				"highest_risk switches to a clearly riskier attacker");
		check(CombatPlanning.policyRetarget(TargetPolicy.HIGHEST_RISK, current,
				List.of(new PolicyCandidate(2.0D, 500.0D, true, true), new PolicyCandidate(2.0D, 500.0D, false, false)), 100) == -1,
				"policies never pick a creeper or something not attacking the agent");
		check(CombatPlanning.policyRetarget(TargetPolicy.NEAREST_ATTACKER, new PolicyCandidate(6.0D, 50.0D, true, false),
				List.of(new PolicyCandidate(5.0D, 10.0D, true, false)), 100) == -1,
				"nearest_attacker ignores an attacker only 1 block nearer");
		check(CombatPlanning.policyRetarget(TargetPolicy.NEAREST_ATTACKER, new PolicyCandidate(6.0D, 50.0D, true, false),
				List.of(new PolicyCandidate(5.0D, 10.0D, true, false), new PolicyCandidate(2.0D, 10.0D, true, false)), 100) == 1,
				"nearest_attacker switches to an attacker clearly nearer");
		check(!CombatPlanning.switchableKind(true, false) && CombatPlanning.switchableKind(true, true)
				&& CombatPlanning.switchableKind(false, false),
				"follow-through and policies never switch to a player unless the model passed includePlayers");
		check(CombatPlanning.policyRetarget(TargetPolicy.HIGHEST_RISK, null,
				List.of(new PolicyCandidate(5.0D, 30.0D, true, false), new PolicyCandidate(8.0D, 70.0D, true, false)), 0) == 1,
				"after a kill, highest_risk follows through to the riskiest attacker at once");
	}

	private static final SurvivalPerception.Food BREAD = new SurvivalPerception.Food(3, "minecraft:bread", 5, 6.0F, false);
	private static final SurvivalPerception.Food GOLDEN_APPLE = new SurvivalPerception.Food(4, "minecraft:golden_apple", 4, 9.6F, true);

	private static void verifyHealingSignals() {
		check(SurvivalPerception.rawSignals(14.0D, 20.0D, 18, BREAD, false, true).equals(List.of(SurvivalPerception.HEAL_OPPORTUNITY)),
				"at 70% health, safe and with food: a heal opportunity (long before 4-5 hearts)");
		check(SurvivalPerception.rawSignals(15.0D, 20.0D, 18, BREAD, false, true).isEmpty(),
				"a small scratch is not worth a wake-up");
		check(SurvivalPerception.rawSignals(14.0D, 20.0D, 18, BREAD, true, false).isEmpty(),
				"no heal opportunity while a threat is close or hunting");
		check(SurvivalPerception.rawSignals(14.0D, 20.0D, 20, BREAD, false, true).isEmpty(),
				"ordinary food cannot be eaten at full hunger");
		check(SurvivalPerception.rawSignals(14.0D, 20.0D, 20, GOLDEN_APPLE, false, true).equals(List.of(SurvivalPerception.HEAL_OPPORTUNITY)),
				"a golden apple can always be eaten");
		check(SurvivalPerception.rawSignals(10.0D, 20.0D, 18, null, true, false).equals(List.of(SurvivalPerception.LOW_HEALTH_NO_FOOD)),
				"half health, threatened, nothing to eat: urgent low-health signal");
		check(SurvivalPerception.rawSignals(10.0D, 20.0D, 18, null, false, true).isEmpty(),
				"no food while safe is not an emergency");
		check(SurvivalPerception.rawSignals(10.0D, 20.0D, 20, BREAD, true, false).isEmpty(),
				"at full hunger with food carried there is no low_health_no_food (it is not a reason to flee)");
		SurvivalPerception.Snapshot full = SurvivalPerception.evaluate(10.0D, 20.0D, 20, BREAD, false, true, new SurvivalPerception.Latch(), 0L);
		check(full.bestFood() == BREAD && !full.canHealNow(), "bestFood is reported regardless of hunger; canHealNow needs it edible now");
		SurvivalPerception.Latch latch = new SurvivalPerception.Latch();
		List<String> heal = List.of(SurvivalPerception.HEAL_OPPORTUNITY);
		check(latch.update(heal, 0L).contains(SurvivalPerception.HEAL_OPPORTUNITY), "the signal is reported");
		check(latch.update(List.of(), 20L).contains(SurvivalPerception.HEAL_OPPORTUNITY), "a flicker stays latched");
		check(latch.update(List.of(), 20L + SurvivalPerception.HOLD_TICKS + 1L).isEmpty(), "it clears after the hold");
		check(latch.update(heal, 200L).isEmpty(), "debounce: it cannot be raised again during the cooldown");
		check(latch.update(heal, 61L + SurvivalPerception.HOLD_TICKS + SurvivalPerception.COOLDOWN_TICKS).contains(SurvivalPerception.HEAL_OPPORTUNITY),
				"after the cooldown it may be raised again");
		SurvivalPerception.Snapshot snapshot = SurvivalPerception.evaluate(12.0D, 20.0D, 15, BREAD, false, true,
				new SurvivalPerception.Latch(), 0L);
		check(snapshot.canHealNow() && snapshot.bestFood() == BREAD, "canHealNow and bestFood are facts");
		JsonObject json = SurvivalPerception.toJson(snapshot);
		check(json.get("canHealNow").getAsBoolean() && json.getAsJsonObject("bestFood").get("itemId").getAsString().equals("minecraft:bread")
				&& json.getAsJsonArray("signals").get(0).getAsString().equals(SurvivalPerception.HEAL_OPPORTUNITY),
				"the survival section carries canHealNow, bestFood and signals");
		check(SurvivalPerception.toJson(SurvivalPerception.evaluate(20.0D, 20.0D, 20, BREAD, false, true, new SurvivalPerception.Latch(), 0L)) == null,
				"a healthy agent has no survival section");
	}

	private static void verifyHealingAttention() {
		JsonObject before = new JsonObject();
		JsonObject after = new JsonObject();
		JsonObject survival = new JsonObject();
		JsonArray signals = new JsonArray();
		signals.add(SurvivalPerception.HEAL_OPPORTUNITY);
		survival.add("signals", signals);
		after.add("survival", survival);
		Set<String> facts = new java.util.TreeSet<>();
		AttentionSignalPolicy.addNewSurvivalSignals(facts, AttentionSignalPolicy.survivalSignals(before), AttentionSignalPolicy.survivalSignals(after));
		check(facts.equals(Set.of("survival.heal_opportunity")), "a new healing signal is one attention fact");
		facts.clear();
		AttentionSignalPolicy.addNewSurvivalSignals(facts, AttentionSignalPolicy.survivalSignals(after), AttentionSignalPolicy.survivalSignals(after));
		check(facts.isEmpty(), "a held healing signal does not re-raise attention");
		var quiet = new ServerObservationCollector.RawPlayerState(12.0D, 15, 2.0D, false, false, 300, false, true, 0.0D, null, 0L, Set.of());
		var raised = new ServerObservationCollector.RawPlayerState(12.0D, 15, 2.0D, false, false, 300, false, true, 0.0D, null, 0L,
				Set.of("survival:" + SurvivalPerception.HEAL_OPPORTUNITY));
		check(raised.requiresForcedAttention(quiet, false), "a new survival signal is delivered at once");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		assertions++;
	}
}
