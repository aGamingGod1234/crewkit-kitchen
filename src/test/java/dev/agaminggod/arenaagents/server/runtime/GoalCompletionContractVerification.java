package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonParser;

/** Focused wire and allowlist checks for revision-bound factual completion contracts. */
public final class GoalCompletionContractVerification {
	private GoalCompletionContractVerification() { }

	public static int verify() {
		GoalCompletionContract contract = GoalCompletionContract.parse(JsonParser.parseString("""
				{"goalRevision":7,"predicates":[
				{"type":"inventory_min","itemId":"minecraft:wooden_pickaxe","count":1},
				{"type":"position_within","x":0.5,"y":64.0,"z":-2.5,"radius":2},
				{"type":"block_matches","x":0,"y":64,"z":-2,"blockId":"minecraft:crafting_table"},
				{"type":"entity_state","entityId":"00000000-0000-4000-8000-000000000001","state":"alive"}
				]}""").getAsJsonObject());
		assertEquals(7L, contract.goalRevision(), "contract revision");
		assertEquals(4, contract.predicates().size(), "all legacy predicate shapes parse");
		assertEquals("minecraft:wooden_pickaxe", contract.predicates().getFirst().itemId(), "false-pickaxe predicate is exact");
		assertEquals(contract.toJson().toString(), GoalCompletionContract.parse(contract.toJson()).toJson().toString(), "contract canonical round trip");
		assertEquals("sha256:b2ba25ecc8d315903a11a7fe14063839480492056211d8617437125e24db7182", contract.fingerprint(), "language-neutral fingerprint matches the JavaScript fixture");
		expectFailure("MALFORMED_CONTRACT", () -> GoalCompletionContract.parse(JsonParser.parseString("{\"goalRevision\":7,\"predicates\":[]}").getAsJsonObject()), "empty contracts fail closed");
		expectFailure("MALFORMED_CONTRACT", () -> GoalCompletionContract.parse(JsonParser.parseString("{\"goalRevision\":6,\"predicates\":[{\"type\":\"inventory_min\",\"itemId\":\"minecraft:wooden_pickaxe\",\"count\":1,\"proof\":true}]}").getAsJsonObject()), "unknown predicate fields fail closed");
		expectFailure("MALFORMED_CONTRACT", () -> GoalCompletionContract.parse(JsonParser.parseString("{\"goalRevision\":7,\"predicates\":[{\"type\":\"action_success_count\",\"actionType\":\"craft_inventory\",\"count\":1}]}").getAsJsonObject()), "action counts are not completion evidence");
		return 10;
	}

	private static void expectFailure(String code, Runnable action, String label) {
		try { action.run(); throw new AssertionError(label + " did not fail"); }
		catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) { assertEquals(code, exception.code(), label + " code"); }
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
