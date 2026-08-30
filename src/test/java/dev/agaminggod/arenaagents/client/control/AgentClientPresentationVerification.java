package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import java.util.List;

public final class AgentClientPresentationVerification {
	private AgentClientPresentationVerification() {
	}

	public static int verify() {
		SnapshotRefreshPolicy.Tick disconnected = SnapshotRefreshPolicy.advance(0, false, 20);
		assertFalse(disconnected.requestSnapshot(), "disconnected clients do not send snapshot requests");
		assertEquals(0, disconnected.nextCountdown(), "disconnect resets the background refresh timer");

		SnapshotRefreshPolicy.Tick due = SnapshotRefreshPolicy.advance(0, true, 20);
		assertTrue(due.requestSnapshot(), "connected clients refresh snapshots even with the console closed");
		assertEquals(19, due.nextCountdown(), "a refresh starts an exact twenty-tick interval");
		SnapshotRefreshPolicy.Tick waiting = SnapshotRefreshPolicy.advance(7, true, 20);
		assertFalse(waiting.requestSnapshot(), "background polling waits until the interval expires");
		assertEquals(6, waiting.nextCountdown(), "background polling advances once per client tick");
		SnapshotRefreshPolicy.Tick hidden = SnapshotRefreshPolicy.advance(0, true, false, 20, 100);
		assertTrue(hidden.requestSnapshot(), "hidden controls retain a slow roster heartbeat");
		assertEquals(99, hidden.nextCountdown(), "hidden controls avoid polling a full snapshot every second");
		SnapshotRefreshPolicy.Tick shown = SnapshotRefreshPolicy.advance(99, true, true, 20, 100);
		assertFalse(shown.requestSnapshot(), "showing controls advances the next request without a duplicate tick send");
		assertEquals(0, shown.nextCountdown(), "showing controls makes the next tick immediately due");

		AgentControlAgent agent = new AgentControlAgent(
				"12345678-1234-1234-1234-123456789abc", "12345678", "Builder", "codex",
				"gpt-5.6-sol", "high", "SolCyan_12345678", 0, "IDLE", "", 0, "", "", true, true
		);
		AgentControlSnapshot snapshot = new AgentControlSnapshot(true, 1L, List.of(agent));
		assertEquals(agent, AgentPlayerIdentity.find(snapshot, "SolCyan_12345678").orElseThrow(),
				"exact player profile names resolve the custom agent identity");
		assertEquals(agent, AgentPlayerIdentity.find(snapshot, "solcyan_12345678").orElseThrow(),
				"profile-name casing differences do not break custom skins or label suppression");
		assertTrue(AgentPlayerIdentity.find(snapshot, "DifferentPlayer").isEmpty(),
				"ordinary players never inherit agent presentation");
		assertTrue(AgentPlayerIdentity.find(snapshot, "  ").isEmpty(),
				"blank profile names never match an agent");
		return 14;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
