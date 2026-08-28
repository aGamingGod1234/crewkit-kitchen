package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;

import java.util.List;

/** Deterministic checks for terminal result fencing, coalescing, replay, and acknowledgement. */
public final class TerminalResultLedgerVerification {
	private TerminalResultLedgerVerification() { }

	public static int verify() {
		TerminalResultLedger ledger = new TerminalResultLedger();
		AgentId agent = AgentId.random();
		ServerActionResult result = result(agent, 7L, "action-1");
		Object firstSession = new Object();
		Object replacementSession = new Object();

		ledger.beginGoal(agent, 7L);
		assertTrue(ledger.retain(result), "current terminal result is retained");
		assertEquals(List.of(result), ledger.pending(), "retained result is visible for replay");
		assertTrue(ledger.claim(result, firstSession), "first session claims the result once");
		assertFalse(ledger.claim(result, firstSession), "same session cannot enqueue a duplicate result");
		ledger.sessionClosed(firstSession);
		assertTrue(ledger.claim(result, replacementSession), "reconnect can claim a result fenced by the old session");
		assertFalse(ledger.acknowledge(agent, 7L, "unknown"), "unknown acknowledgement does not remove retained work");
		assertTrue(ledger.acknowledge(agent, 7L, "action-1"), "matching acknowledgement removes retained work");
		assertFalse(ledger.acknowledge(agent, 7L, "action-1"), "duplicate acknowledgement is idempotent");

		ServerActionResult stale = result(agent, 8L, "old-action");
		ledger.beginGoal(agent, 7L);
		assertFalse(ledger.retain(stale), "stale goal result cannot re-enter the current replay fence");
		assertEquals(0, ledger.pendingCount(), "acknowledged and stale results leave no replay work");
		ledger.retain(result(agent, 7L, "action-2"));
		ledger.beginGoal(agent, 8L);
		assertEquals(0, ledger.pendingCount(), "new goal fences old terminal results before a later tick");
		ledger.remove(agent);
		assertEquals(0, ledger.pendingCount(), "agent removal clears retained terminal results");
		return 13;
	}

	private static ServerActionResult result(AgentId agent, long goalRevision, String actionId) {
		return new ServerActionResult(
				agent, goalRevision, actionId, ActionType.WAIT, "trace-" + actionId,
				ServerActionState.SUCCEEDED, "DONE", "done", 1L, 1_750_000_000_001L, true, true
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
