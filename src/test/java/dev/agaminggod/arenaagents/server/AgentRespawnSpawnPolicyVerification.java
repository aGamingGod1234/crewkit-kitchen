package dev.agaminggod.arenaagents.server;

public final class AgentRespawnSpawnPolicyVerification {
	private AgentRespawnSpawnPolicyVerification() {
	}

	public static int verify() {
		long deadline = 2_000L;
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_REMOVAL,
				AgentRespawnSpawnPolicy.decide(false, true, 1_999L, deadline),
				"old fake player must leave before a replacement is requested"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, deadline, deadline),
				"stuck fake-player removal times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.REQUEST_SPAWN,
				AgentRespawnSpawnPolicy.decide(false, false, 1_000L, deadline),
				"replacement is requested only after absence is confirmed"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_SPAWN,
				AgentRespawnSpawnPolicy.decide(true, false, 1_999L, deadline),
				"accepted Carpet spawn remains pending until the player appears"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.VERIFY_PLAYER,
				AgentRespawnSpawnPolicy.decide(true, true, 1_500L, deadline),
				"physical player presence advances to verification"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(true, false, deadline, deadline),
				"accepted spawn without a physical player times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.WAIT_FOR_NATURAL_REMOVAL,
				AgentRespawnSpawnPolicy.existingPlayerAction(false),
				"a dead Carpet player must finish its own disconnect without a second kill"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.REMOVE_STALE_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true),
				"a stale live player must be removed before replacement"
		);
		return 8;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
