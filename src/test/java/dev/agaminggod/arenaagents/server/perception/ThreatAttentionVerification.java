package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
		return assertions;
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
