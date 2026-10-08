package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Child process used to halt at journal force boundaries without running JVM shutdown hooks. */
public final class DurableActionJournalCrashWorker {
	static final int BEFORE_FORCE_EXIT = 71;
	static final int AFTER_FORCE_EXIT = 72;
	private static final AgentId AGENT_ID = AgentId.parse("00000000-0000-4000-8000-000000000991");
	private static final UUID GOAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000992");

	private DurableActionJournalCrashWorker() {
	}

	public static void main(String[] arguments) throws Exception {
		String mode = arguments[0];
		Path journalPath = Path.of(arguments[1]);
		Path markerPath = Path.of(arguments[2]);
		DurableActionJournal.ForceHook hook = new DurableActionJournal.ForceHook() {
			@Override
			public void beforeForce() {
				if ("before-force".equals(mode)) Runtime.getRuntime().halt(BEFORE_FORCE_EXIT);
			}

			@Override
			public void afterForce() {
				if ("after-force".equals(mode)) Runtime.getRuntime().halt(AFTER_FORCE_EXIT);
			}
		};
		try (DurableActionJournal journal = DurableActionJournal.open(
				journalPath, DurableActionJournal.MAX_ENTRIES,
				Math.max(128, DurableActionJournal.MAX_ENTRIES * 4), hook)) {
			if ("before-force".equals(mode)) {
				journal.acceptForTick(request(31L, "crash-accept", "crash-accept-step", 1L), GOAL_ID);
				journal.flushTickGroup();
				Files.writeString(markerPath, "executed");
			} else if ("after-force".equals(mode)) {
				ServerActionRequest request = request(32L, "crash-terminal", "crash-terminal-step", 3L);
				journal.terminalIfAcceptedForTick(new ServerActionResult(
						request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
						ServerActionState.SUCCEEDED, "DONE", "Done", 1L, 1L, true, true));
				journal.flushTickGroup();
				Files.writeString(markerPath, "sent");
			} else {
				throw new IllegalArgumentException("unknown crash worker mode " + mode);
			}
		}
	}

	private static ServerActionRequest request(long revision, String actionId, String stepId, long sequence) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 25L);
		return new ServerActionRequest(AGENT_ID, revision, actionId, ActionType.WAIT, arguments,
				new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program", 1L, stepId, sequence));
	}
}
