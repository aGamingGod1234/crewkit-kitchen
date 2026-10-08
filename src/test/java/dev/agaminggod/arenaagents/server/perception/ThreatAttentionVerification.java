package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies threat signals: latched debounce, one attention edge per new signal, and forced delivery. */
public final class ThreatAttentionVerification {
	private static final String ZOMBIE = "00000000-0000-0000-0000-00000000000a";
	private static final String CREEPER = "00000000-0000-0000-0000-00000000000b";
	private static int assertions;

	private ThreatAttentionVerification() {
	}

	public static int verify() {
		assertions = 0;
		verifyLatch();
		verifyAttentionFacts();
		verifyForcedDelivery();
		verifyHeardThreats();
		verifySignalRules();
		verifyTrend();
		verifyCreeperTimeline();
		return assertions;
	}

	private static void verifyTrend() {
		// Blocks per tick in, blocks per second out: a creeper walking 0.13 b/t straight at a standing agent.
		check(Math.abs(ThreatPerception.closingSpeed(0.0D, 0.0D, 0.0D, 0.0D, 16.0D, 0.0D, -0.13D, 0.0D) - 2.6D) < 1.0E-9D,
				"closing speed is the approach along the line between them, per second");
		check(ThreatPerception.closingSpeed(0.0D, 0.0D, 0.0D, 0.0D, 16.0D, 0.0D, 0.13D, 0.0D) < 0.0D, "receding is negative");
		check(Math.abs(ThreatPerception.closingSpeed(0.0D, 0.0D, 0.0D, 0.0D, 16.0D, 0.0D, 0.0D, 0.13D)) < 1.0E-9D,
				"circling at a fixed distance is not closing");
		check(Math.abs(ThreatPerception.closingSpeed(0.0D, 0.0D, -0.13D, 0.0D, 16.0D, 0.0D, -0.13D, 0.0D)) < 1.0E-9D,
				"a chaser matching a fleeing agent's speed is not closing");
		check(ThreatPerception.closingSpeed(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D) == 0.0D, "a coincident body has no direction");

		ThreatPerception.Trend creeper = ThreatPerception.trend(16.0D, 2.6D, true, false);
		check(creeper.approaching() && creeper.contactRange() == ThreatPerception.CREEPER_CONTACT && creeper.etaSeconds() == 5.0D,
				"a creeper 16 blocks off at 2.6 b/s reaches fuse range (3 blocks) in 5 s");
		check(ThreatPerception.trend(10.0D, 4.0D, false, false).etaSeconds() == 2.0D, "a melee mob reaches 2 blocks of reach");
		check(ThreatPerception.trend(1.5D, 0.0D, false, false).etaSeconds() == 0.0D, "already in reach is an ETA of 0");
		ThreatPerception.Trend pacing = ThreatPerception.trend(8.0D, 0.1D, false, false);
		check(!pacing.approaching() && Double.isNaN(pacing.etaSeconds()), "pacing is not approaching and has no ETA");
		check(Double.isNaN(ThreatPerception.trend(12.0D, 3.0D, false, true).etaSeconds()), "a ranged mob already reaches; no ETA");

		check(!ThreatPerception.signals(false, true, true, false, false, true, 16.0D, false, 5.0D).contains(ThreatPerception.IMMINENT),
				"5 s out is targeting only");
		check(ThreatPerception.signals(false, true, true, false, false, true, 10.0D, false, 2.9D).equals(List.of("targeting", "imminent")),
				"under 3 s a hunting creeper is imminent, one more edge before it is close");
		check(ThreatPerception.signals(false, false, true, false, false, true, 8.0D, false, 2.0D).contains(ThreatPerception.IMMINENT),
				"a creeper that sees the agent and walks in is imminent even before it targets");
		check(!ThreatPerception.signals(false, false, true, false, false, false, 8.0D, false, 2.0D).contains(ThreatPerception.IMMINENT),
				"one behind a wall that is not hunting is not");
		check(!ThreatPerception.signals(false, true, false, false, true, true, 8.0D, false, 1.0D).contains(ThreatPerception.IMMINENT),
				"ranged mobs use ranged_sight, not imminent");
		check(ThreatPerception.signals(true, false, false, false, false, true, 3.0D, false, 1.0D).isEmpty(),
				"a calm neutral mob walking past is not imminent");
		check(!ThreatPerception.signals(false, true, false, false, false, true, 8.0D, false, Double.NaN).contains(ThreatPerception.IMMINENT),
				"no ETA (not approaching) is never imminent");

		JsonObject before = observation();
		before.add("threats", threats(entry(CREEPER, "minecraft:creeper", "targeting")));
		JsonObject after = observation();
		JsonObject both = entry(CREEPER, "minecraft:creeper", "targeting");
		both.getAsJsonArray("signals").add("imminent");
		after.add("threats", threats(both));
		check(AttentionFactDelta.between(before, after, 1L, 1L).changedFacts().equals(List.of("threats." + CREEPER + ".imminent")),
				"becoming imminent is a new urgent edge for an already reported threat");

		ThreatPerception.Snapshot snapshot = new ThreatPerception.Snapshot(List.of(
				new ThreatPerception.Entry(CREEPER, "minecraft:creeper", 16.0D, 0.0D, true, false, true, List.of("targeting"), 4.3D, Map.of(),
						0.0D, creeper, 145.0D),
				new ThreatPerception.Entry(ZOMBIE, "minecraft:zombie", 6.0D, 0.0D, true, false, true, List.of("targeting"), 30.0D, Map.of(),
						3.0D, pacing, Double.NaN)), Set.of(), -1, null);
		JsonArray rows = ThreatPerception.toJson(snapshot).getAsJsonArray("entries");
		JsonObject first = rows.get(0).getAsJsonObject();
		check(first.get("closingSpeed").getAsDouble() == 2.6D && first.get("approaching").getAsBoolean()
				&& first.get("etaSeconds").getAsDouble() == 5.0D && first.get("contactRisk").getAsDouble() == 145.0D,
				"the wire row carries closing speed, approaching, ETA and the risk at contact");
		JsonObject second = rows.get(1).getAsJsonObject();
		check(!second.get("approaching").getAsBoolean() && !second.has("etaSeconds") && !second.has("contactRisk"),
				"a body that is not approaching has no ETA or contact risk on the wire");
	}

	/**
	 * Play-test (2026-10-08): a creeper targeting the agent read risk 4 at 16 blocks and the model treated it as minor;
	 * risk only reached 100-200 at about 3 blocks. The risk at contact range shows what was coming from the first edge.
	 */
	private static void verifyCreeperTimeline() {
		double far = RiskModel.score(creeper(16.0D, 0.0D)).risk();
		double five = RiskModel.score(creeper(5.0D, ThreatDamage.explosionDamage(5.0D, ThreatDamage.CREEPER_RADIUS))).risk();
		double contact = RiskModel.score(creeper(ThreatPerception.CREEPER_CONTACT,
				ThreatDamage.explosionDamage(ThreatPerception.CREEPER_CONTACT, ThreatDamage.CREEPER_RADIUS))).risk();
		check(far >= 3.0D && far <= 6.0D, "a hunting creeper 16 blocks off scores about 4 (" + far + ")");
		check(five < 100.0D, "still under 100 at 5 blocks (" + five + ")");
		check(contact >= 100.0D && contact <= 200.0D, "about 145 once it is at fuse range, " + contact + "; that is its contactRisk");
	}

	private static RiskModel.Input creeper(double distance, double hit) {
		return new RiskModel.Input(0.6D, 1.7D, 20.0D, 0.0D, false, 0.25D, 0.13D, distance, hit, false, false, true, false, 0.0D);
	}

	private static void verifyLatch() {
		ThreatSignalLatch latch = new ThreatSignalLatch();
		String swelling = ThreatSignalLatch.key(CREEPER, "swelling");
		Set<String> present = Set.of(CREEPER);
		check(latch.update(Set.of(swelling), present, 0L).contains(swelling), "a raw signal is reported");
		check(latch.update(Set.of(), present, 50L).contains(swelling), "a flickering signal stays latched (no re-fire)");
		check(!latch.update(Set.of(), present, 50L + ThreatSignalLatch.HOLD_TICKS + 1L).contains(swelling),
				"a signal expires after the hold window");
		latch.update(Set.of(swelling), present, 200L);
		check(latch.update(Set.of(), Set.of(), 201L).isEmpty(), "a dead or departed mob drops its signals at once");
		check(ThreatSignalLatch.threatId(swelling).equals(CREEPER) && ThreatSignalLatch.signal(swelling).equals("swelling"),
				"signal keys split back into mob and signal");
	}

	private static void verifyAttentionFacts() {
		JsonObject before = observation();
		JsonObject targeted = observation();
		targeted.add("threats", threats(entry(ZOMBIE, "minecraft:zombie", "targeting")));
		AttentionFactDelta first = AttentionFactDelta.between(before, targeted, 1L, 1L);
		check(first.attention() && first.changedFacts().contains("threats." + ZOMBIE + ".targeting"),
				"a mob starting to target the agent is an attention edge");
		JsonObject moved = targeted.deepCopy();
		moved.getAsJsonObject("threats").getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("distance", 3.0D);
		check(!AttentionFactDelta.between(targeted, moved, 2L, 2L).attention(),
				"the same threat moving closer does not raise another attention edge");
		JsonObject creeper = moved.deepCopy();
		creeper.getAsJsonObject("threats").getAsJsonArray("entries").add(entry(CREEPER, "minecraft:creeper", "swelling"));
		AttentionFactDelta swell = AttentionFactDelta.between(moved, creeper, 3L, 3L);
		check(swell.changedFacts().equals(java.util.List.of("threats." + CREEPER + ".swelling")),
				"a new creeper swell is reported once, alone");
		JsonObject active = observation();
		active.getAsJsonObject("currentAction").addProperty("active", true);
		JsonObject activeTargeted = active.deepCopy();
		activeTargeted.add("threats", threats(entry(ZOMBIE, "minecraft:zombie", "targeting")));
		check(AttentionFactDelta.between(active, activeTargeted, 4L, 4L).attention(),
				"threat edges interrupt a running action too");
	}

	private static void verifyForcedDelivery() {
		var quiet = raw(Set.of());
		var threatened = raw(Set.of(ThreatSignalLatch.key(ZOMBIE, "targeting")));
		check(threatened.requiresForcedAttention(quiet, false), "a new latched signal is delivered on the urgent queue");
		check(!quiet.requiresForcedAttention(threatened, false), "a signal expiring is only a quiet refresh");
	}

	private static void verifyHeardThreats() {
		check(ServerObservationCollector.hearsThreat(true, false, false), "a hostile targeting the agent from behind is heard");
		check(ServerObservationCollector.hearsThreat(false, true, false), "a hidden mob that just hurt the agent (an arrow in the back) is heard");
		check(!ServerObservationCollector.hearsThreat(false, false, false), "an unaware hidden mob stays unobserved");
		check(!ServerObservationCollector.hearsThreat(true, true, true), "a visible hunter is reported by sight, not twice");
	}

	private static void verifySignalRules() {
		check(ThreatPerception.signals(true, false, false, false, true, true, 8.0D).isEmpty(),
				"a calm neutral mob (piglin tolerating gold) raises nothing, even with a crossbow and sight");
		check(ThreatPerception.signals(true, true, false, false, true, true, 8.0D).equals(java.util.List.of("targeting", "ranged_sight")),
				"an angry neutral mob is a threat like any other");
		check(ThreatPerception.signals(false, false, true, false, false, false, 3.0D).isEmpty(),
				"a close creeper behind a wall that is not hunting the agent is not a threat");
		check(ThreatPerception.signals(false, false, true, false, false, true, 3.0D).contains("creeper_close"),
				"a close creeper in sight is");
		check(ThreatPerception.signals(false, true, false, false, false, true, 18.0D).isEmpty(),
				"nothing new is raised beyond 16 blocks");
		ThreatSignalLatch latch = new ThreatSignalLatch();
		String far = ThreatSignalLatch.key(ZOMBIE, "targeting");
		latch.update(Set.of(far), Set.of(ZOMBIE), 0L);
		check(latch.update(Set.of(), Set.of(ZOMBIE), 20L).contains(far),
				"a latched mob pacing between 16 and 20 blocks (still present) keeps its signal");
		String creeper = ThreatSignalLatch.key(CREEPER, "swelling");
		latch.update(Set.of(creeper), Set.of(ZOMBIE, CREEPER), 30L);
		check(latch.firstSeen(far) == 0L && latch.firstSeen(creeper) == 30L,
				"latch age orders reported threats so a distance reshuffle is never a new edge");
	}

	private static ServerObservationCollector.RawPlayerState raw(Set<String> signals) {
		return new ServerObservationCollector.RawPlayerState(20.0D, 20, 5.0D, false, false, 300, false, true, 0.0D,
				null, 0L, signals);
	}

	private static JsonObject observation() {
		JsonObject value = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("health", 20.0D);
		value.add("player", player);
		JsonObject action = new JsonObject();
		action.addProperty("active", false);
		value.add("currentAction", action);
		return value;
	}

	private static JsonObject threats(JsonObject... entries) {
		JsonObject threats = new JsonObject();
		JsonArray values = new JsonArray();
		for (JsonObject entry : entries) values.add(entry);
		threats.add("entries", values);
		return threats;
	}

	private static JsonObject entry(String uuid, String type, String signal) {
		JsonObject entry = new JsonObject();
		entry.addProperty("uuid", uuid);
		entry.addProperty("type", type);
		entry.addProperty("distance", 6.0D);
		JsonArray signals = new JsonArray();
		signals.add(signal);
		entry.add("signals", signals);
		return entry;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		assertions++;
	}
}
