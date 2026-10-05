package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayList;
import java.util.List;

public final class TerminalReplayFairnessVerification {
	public static void main(String[] args) {
		System.out.println("TerminalReplayFairnessVerification assertions=" + verify());
	}

	public static int verify() {
		var ledger = new TerminalResultLedger();
		var blocked = AgentId.random();
		var other = AgentId.random();
		for (var request : List.of(
				ActionAcknowledgementRetryVerification.request(blocked, "blocked-1"),
				ActionAcknowledgementRetryVerification.request(blocked, "blocked-2"),
				ActionAcknowledgementRetryVerification.request(other, "other"))) {
			ledger.retain(ActionAcknowledgementRetryVerification.result(request));
		}
		var attempts = new ArrayList<String>();
		var session = new Object();
		ledger.replay(session, result -> {
			attempts.add(result.actionId());
			if (result.agentId().equals(blocked)) throw new BridgeProtocolException("AGENT_BACKPRESSURE", "full");
		}, failure -> { });
		require(attempts.equals(List.of("blocked-1", "other")), "blocked agent skipped for the pass; another agent progresses");
		attempts.clear();
		ledger.replay(session, result -> attempts.add(result.actionId()), failure -> { throw new AssertionError(failure); });
		require(attempts.equals(List.of("blocked-1", "blocked-2")), "same-session retry preserves failed FIFO and does not duplicate successful delivery");
		attempts.clear();
		ledger.replay(session, result -> attempts.add(result.actionId()), failure -> { });
		require(attempts.isEmpty(), "successful claims coalesce repeated ticks");
		ledger.sessionClosed(session);
		var replacement = new Object();
		ledger.replay(replacement, result -> {
			attempts.add(result.actionId());
			throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "full");
		}, failure -> { });
		require(attempts.equals(List.of("blocked-1")), "connection-wide pressure stops the pass");
		attempts.clear();
		ledger.replay(replacement, result -> attempts.add(result.actionId()), failure -> { });
		require(attempts.equals(List.of("blocked-1", "blocked-2", "other")), "connection-pressure failure retains all results for retry");
		require(ledger.pendingCount() == 3, "enqueue never retires unacknowledged results");
		return 6;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
