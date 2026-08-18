package dev.agaminggod.arenaagents.server;

import java.util.UUID;

public final class OfflineAgentProfileLookupVerification {
	private OfflineAgentProfileLookupVerification() {
	}

	public static int verify() {
		UUID agent = UUID.fromString("193a9add-1234-5678-9abc-123456789abc");
		UUID ordinary = UUID.fromString("293a9add-1234-5678-9abc-123456789abc");
		OfflineAgentProfileLookup.begin(agent);
		assertTrue(OfflineAgentProfileLookup.shouldBypassRemoteLookup(agent),
				"pending offline agent bypasses Mojang profile lookup");
		assertTrue(!OfflineAgentProfileLookup.shouldBypassRemoteLookup(ordinary),
				"ordinary profiles still use the vanilla resolver");
		OfflineAgentProfileLookup.end(agent);
		assertTrue(!OfflineAgentProfileLookup.shouldBypassRemoteLookup(agent),
				"bypass scope ends immediately after Carpet schedules the fake player");
		return 3;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
