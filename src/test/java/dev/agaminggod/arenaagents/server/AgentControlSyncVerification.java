package dev.agaminggod.arenaagents.server;

import java.util.concurrent.atomic.AtomicInteger;

public final class AgentControlSyncVerification {
	private AgentControlSyncVerification() {
	}

	public static int verify() {
		AtomicInteger actions = new AtomicInteger();
		assertTrue(!AgentControlSync.executeAuthorizedControlAction(false, actions::incrementAndGet),
				"non-operator control request is rejected");
		assertEquals(0, actions.get(), "non-operator rejection happens before snapshot or action work");
		assertTrue(AgentControlSync.executeAuthorizedControlAction(true, actions::incrementAndGet),
				"operator control request is accepted");
		assertEquals(1, actions.get(), "operator snapshot or action work runs exactly once");
		return 4;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
