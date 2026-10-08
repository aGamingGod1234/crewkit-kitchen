package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Verifies that urgent hazards stay factual attention, not body-authored tactics. */
public final class AttentionHazardVerification {
	private AttentionHazardVerification() {
	}

	public static int verify() {
		JsonObject before = observation("mine");
		JsonObject after = before.deepCopy();
		JsonObject player = after.getAsJsonObject("player");
		player.addProperty("health", 18.0D);
		player.addProperty("onFire", true);
		player.addProperty("air", 40);
		player.addProperty("suffocating", true);
		player.addProperty("fallDistance", 8.0D);
		JsonObject attacker = new JsonObject();
		attacker.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
		attacker.addProperty("type", "minecraft:zombie");
		player.add("lastAttacker", attacker);

		AttentionFactDelta active = AttentionFactDelta.between(before, after, 7L, 123L);
		assertTrue(active.attention(), "hazard changes gain urgent attention during a brain-authored action");
		for (String fact : new String[] {
				"player.health", "player.onFire", "player.air", "player.suffocating",
				"player.fallDistance", "player.lastAttacker"
		}) {
			assertTrue(active.changedFacts().contains(fact), fact + " remains a factual attention delta");
		}
		assertFalse(active.changedFacts().stream().anyMatch(AttentionHazardVerification::isTacticalLabel),
				"hazard attention does not invent a tactical label");

		JsonObject lavaBefore = observation("idle");
		JsonObject lavaAfter = lavaBefore.deepCopy();
		lavaAfter.getAsJsonArray("blocks").add(block("minecraft:lava"));
		AttentionFactDelta lava = AttentionFactDelta.between(lavaBefore, lavaAfter, 8L, 124L);
		assertTrue(lava.attention(), "observed lava remains a factual attention delta");
		assertTrue(lava.changedFacts().contains("blocks.0,64,0"), "lava is delivered as an observed block fact");
		assertFalse(lava.changedFacts().stream().anyMatch(AttentionHazardVerification::isTacticalLabel),
				"lava attention does not choose flee, jump, or another movement");
		return 11 + verifyRawAirSampling() + verifyHalfAirWarning();
	}

	private static int verifyRawAirSampling() {
		var before = rawState(299, 20, true, 0, 0);
		var after = rawState(298, 20, true, 0, 0);
		assertFalse(before.equals(after), "safe air still changes the raw sample");
		assertFalse(after.requiresForcedAttention(before, false), "safe air alone does not force attention");
		assertFalse(before.requiresForcedAttention(after, false), "safe air recovery does not force attention");
		assertTrue(after.requiresForcedAttention(before, true), "inventory/menu/component freshness still forces delivery");
		assertTrue(rawState(60, 20, true, 0, 0).requiresForcedAttention(rawState(61, 20, true, 0, 0), false),
				"crossing the air hazard threshold remains forced");
		assertTrue(rawState(59, 20, true, 0, 0).requiresForcedAttention(rawState(60, 20, true, 0, 0), false),
				"continuing critical air remains forced");
		assertTrue(rawState(298, 19, true, 0, 0).requiresForcedAttention(before, false), "damage plus safe air remains forced");
		assertTrue(rawState(298, 20, true, 6, 0).requiresForcedAttention(before, false), "hazardous falling remains forced");
		assertTrue(rawState(298, 20, true, 0, 1).requiresForcedAttention(before, false), "perception events remain forced");
		return 9 + verifyRoutineStateWaitsForHeartbeat();
	}

	/**
	 * Only edges AttentionSignalPolicy raises as a fact force an observation; everything else rides the idle heartbeat.
	 * Each case also checks the policy side, so the two cannot drift apart.
	 */
	private static int verifyRoutineStateWaitsForHeartbeat() {
		var rest = raw(20, 20, 5, false, false, 300, false, true, 0.0D, null, 0L);
		assertFalse(raw(20, 20, 5, false, false, 300, false, false, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"leaving the ground is not a fact");
		assertFalse(rest.requiresForcedAttention(raw(20, 20, 5, false, false, 300, false, false, 0.0D, null, 0L), false),
				"landing is not a fact");
		assertFalse(raw(20, 20, 5, false, false, 300, false, true, 3.5D, null, 0L).requiresForcedAttention(rest, false),
				"falling below the damaging distance is not a fact");
		assertFalse(raw(20, 20, 5, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(
				raw(20, 20, 5, false, false, 300, false, true, 9.0D, null, 0L), false), "a fall ending is not a fact");
		assertTrue(raw(20, 20, 5, false, false, 300, false, false, 6.0D, null, 0L).requiresForcedAttention(rest, false),
				"reaching the damaging fall distance is a fact");
		assertFalse(raw(20, 20, 5, false, false, 300, false, false, 8.0D, null, 0L).requiresForcedAttention(
				raw(20, 20, 5, false, false, 300, false, false, 7.0D, null, 0L), false), "falling further is not a new fact");
		assertFalse(raw(20, 20, 3, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"saturation drifting is not a fact");
		assertFalse(raw(20, 19, 5, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"hunger above the warning level is not a fact");
		assertTrue(raw(20, 6, 5, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(
				raw(20, 7, 5, false, false, 300, false, true, 0.0D, null, 0L), false), "food reaching the warning level is a fact");
		assertFalse(raw(20, 5, 5, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(
				raw(20, 6, 5, false, false, 300, false, true, 0.0D, null, 0L), false), "food falling further is not a new fact");
		assertFalse(raw(20, 20, 5, false, true, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"entering water is not a fact");
		var wounded = raw(18, 20, 5, false, false, 300, false, true, 0.0D, null, 0L);
		assertFalse(raw(20, 20, 5, false, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(wounded, false),
				"regeneration is not a fact");
		assertTrue(wounded.requiresForcedAttention(rest, false), "a health drop is a fact");
		assertTrue(raw(20, 20, 5, true, false, 300, false, true, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"catching fire is a fact");
		assertFalse(rest.requiresForcedAttention(raw(20, 20, 5, true, false, 300, false, true, 0.0D, null, 0L), false),
				"burning out is not a fact");
		assertTrue(raw(20, 20, 5, false, false, 300, true, true, 0.0D, null, 0L).requiresForcedAttention(rest, false),
				"suffocating is a fact");
		assertTrue(raw(20, 20, 5, false, false, 300, false, true, 0.0D, java.util.UUID.randomUUID(), 0L)
				.requiresForcedAttention(rest, false), "a new attacker is a fact");

		JsonObject before = observation("idle");
		JsonObject after = before.deepCopy();
		after.getAsJsonObject("player").addProperty("onGround", false);
		after.getAsJsonObject("player").addProperty("saturation", 1.0D);
		after.getAsJsonObject("player").addProperty("fallDistance", 3.5D);
		assertFalse(AttentionSignalPolicy.changedFacts(before, after).stream().anyMatch(fact -> fact.startsWith("player.")),
				"the policy raises none of the routine changes the heartbeat carries");
		after.getAsJsonObject("player").addProperty("fallDistance", 6.0D);
		assertTrue(AttentionSignalPolicy.changedFacts(before, after).contains("player.fallDistance"), "and raises the fall that matters");
		return 19;
	}

	private static ServerObservationCollector.RawPlayerState raw(double health, int food, double saturation, boolean onFire,
			boolean inWater, int air, boolean suffocating, boolean onGround, double fallDistance, java.util.UUID attacker,
			long perceptionSequence) {
		return new ServerObservationCollector.RawPlayerState(health, food, saturation, onFire, inWater, air, suffocating,
				onGround, fallDistance, attacker, perceptionSequence);
	}

	/** Drowning attention must leave time to decide: it fires at half air (7.5 s), once per dive. */
	private static int verifyHalfAirWarning() {
		JsonObject before = observation("idle");
		before.getAsJsonObject("player").addProperty("air", 151);
		JsonObject after = before.deepCopy();
		after.getAsJsonObject("player").addProperty("air", 150);
		assertTrue(AttentionFactDelta.between(before, after, 9L, 125L).changedFacts().contains("player.air"),
				"crossing half air raises the drowning attention");
		JsonObject deeper = after.deepCopy();
		deeper.getAsJsonObject("player").addProperty("air", 120);
		assertFalse(AttentionFactDelta.between(after, deeper, 10L, 126L).changedFacts().contains("player.air"),
				"air draining further inside the same band does not re-raise it (debounced)");
		assertTrue(rawState(150, 20, true, 0, 0).requiresForcedAttention(rawState(151, 20, true, 0, 0), false),
				"the half-air crossing is delivered at once, not with the next routine sample");
		assertFalse(rawState(140, 20, true, 0, 0).requiresForcedAttention(rawState(141, 20, true, 0, 0), false),
				"air draining inside the warning band alone is not forced");
		return 4;
	}

	private static ServerObservationCollector.RawPlayerState rawState(int air, double health, boolean onGround,
			double fallDistance, long perceptionSequence) {
		return new ServerObservationCollector.RawPlayerState(health, 20, 5, false, true, air, false,
				onGround, fallDistance, null, perceptionSequence);
	}

	private static JsonObject observation(String actionType) {
		JsonObject value = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("health", 20.0D);
		player.addProperty("onFire", false);
		player.addProperty("air", 300);
		player.addProperty("maxAir", 300);
		player.addProperty("suffocating", false);
		player.addProperty("fallDistance", 0.0D);
		value.add("player", player);
		value.add("blocks", new JsonArray());
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", !"idle".equals(actionType));
		currentAction.addProperty("actionType", actionType);
		value.add("currentAction", currentAction);
		return value;
	}

	private static JsonObject block(String blockId) {
		JsonObject value = new JsonObject();
		value.addProperty("x", 0);
		value.addProperty("y", 64);
		value.addProperty("z", 0);
		value.addProperty("blockId", blockId);
		return value;
	}

	private static boolean isTacticalLabel(String value) {
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		return switch (lower) {
			case "danger", "flee", "escape", "jump", "sprint", "attack", "move", "tactic" -> true;
			default -> false;
		};
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
