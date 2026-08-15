package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;

public final class ProgramActionLedgerVerification {
	private ProgramActionLedgerVerification() {
	}

	public static int verify() {
		ProgramActionLedger ledger = new ProgramActionLedger();
		AgentId agent = AgentId.random();
		ActionProvenance provenance = provenance("program-1", 1L, "step-1", 4L);
		ServerActionRequest first = request(agent, "action-1", provenance);
		ledger.accept(first);
		expectFailure(() -> ledger.accept(first), "ACTION_REPLAY");
		expectFailure(() -> ledger.accept(request(agent, "action-1", provenance("program-2", 1L, "step-1", 4L))), "ACTION_PROVENANCE_MISMATCH");
		ledger.terminal(new ServerActionResult(agent, 7L, "action-1", ActionType.WAIT, ServerActionState.FAILED, "REJECTED", "Rejected", 0L, 1L));
		expectFailure(() -> ledger.accept(first), "ACTION_REPLAY");
		ledger.remove(agent);
		ledger.accept(first);
		return 5;
	}

	private static ServerActionRequest request(AgentId agent, String actionId, ActionProvenance provenance) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 25L);
		return new ServerActionRequest(agent, 7L, actionId, ActionType.WAIT, arguments, provenance);
	}

	private static ActionProvenance provenance(String programId, long version, String step, long sequence) {
		return new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", programId, version, step, sequence);
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("Expected " + code);
		} catch (AgentDomainException exception) {
			if (!code.equals(exception.code())) throw new AssertionError("Expected " + code + " but got " + exception.code());
		}
	}
}
