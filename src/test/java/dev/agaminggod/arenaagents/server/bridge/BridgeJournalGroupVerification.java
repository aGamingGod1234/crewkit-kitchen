package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Drives the production tick boundary: same-tick result delivery and a persistent journal write failure. */
public final class BridgeJournalGroupVerification {
	public static void main(String[] args) throws Exception {
		System.out.println("BridgeJournalGroupVerification assertions=" + verify());
	}

	public static int verify() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		verifyResultLeavesInTheTickItCompletes();
		verifyShortOutageHoldsOnlyUnrecordedAgents();
		verifyPersistentOutageReturnsUnrecordedActionsToTheModel();
		return 17;
	}

	private static void verifyResultLeavesInTheTickItCompletes() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Agent agent = fixture.agent("SameTick", "same-tick");
			fixture.journal.acceptForTick(agent.request, agent.goalId);
			fixture.install(agent);
			fixture.bridge.startTick();
			require(fixture.executor.lastResult(agent.record.agentId()) != null,
					"an accepted one-tick action runs and finishes in the tick that admitted it");
			require(fixture.bridge.terminalResultsForVerification().pendingCount() == 1,
					"the result of a one-tick action is published in the tick that completed it, not the next one");
			require(fixture.journal.snapshot().stream().anyMatch(entry -> entry.request().actionId().equals("same-tick")
							&& entry.phase() == DurableActionJournal.Phase.TERMINAL),
					"the published result is already durable");
			long forces = fixture.journal.performanceSnapshotForVerification().appendCount();
			require(forces == 3L, "a tick forces once before the action runs and once before its result leaves (seed + 2)");
			fixture.bridge.startTick();
			require(fixture.journal.performanceSnapshotForVerification().appendCount() == forces,
					"an idle tick forces nothing");
		}
	}

	private static void verifyShortOutageHoldsOnlyUnrecordedAgents() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Agent recorded = fixture.agent("Recorded", "short-recorded");
			Agent staged = fixture.agent("Staged", "short-staged");
			fixture.journal.accept(recorded.request, recorded.goalId);
			fixture.journal.acceptForTick(staged.request, staged.goalId);
			fixture.install(recorded);
			fixture.install(staged);
			for (int tick = 0; tick < 3; tick++) {
				fixture.breakJournal();
				fixture.bridge.startTick();
			}
			require(fixture.executor.activeRequest(recorded.record.agentId()) == null,
					"an already recorded action keeps running while the journal cannot write");
			require(fixture.executor.activeRequest(staged.record.agentId()) != null
							&& fixture.executor.lastResult(staged.record.agentId()) == null,
					"an action whose acceptance is not recorded waits");
			require(fixture.bridge.terminalResultsForVerification().pendingCount() == 0, "nothing leaves before it is durable");
			fixture.bridge.startTick();
			require(fixture.executor.lastResult(staged.record.agentId()) != null, "the held action runs once the journal recovers");
			require(fixture.bridge.terminalResultsForVerification().pendingCount() == 2,
					"results staged during and after the outage are published once the journal recovers");
			require(fixture.journal.stagedAcceptances().isEmpty(), "recovery leaves nothing staged");
		}
	}

	private static void verifyPersistentOutageReturnsUnrecordedActionsToTheModel() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Agent recorded = fixture.agent("Survivor", "long-recorded");
			Agent staged = fixture.agent("Rejected", "long-staged");
			fixture.journal.accept(recorded.request, recorded.goalId);
			fixture.journal.acceptForTick(staged.request, staged.goalId);
			fixture.install(recorded);
			fixture.install(staged);
			for (int tick = 0; tick < 19; tick++) {
				fixture.breakJournal();
				fixture.bridge.startTick();
			}
			require(fixture.executor.lastResult(staged.record.agentId()) == null
							&& fixture.journal.stagedAcceptances().size() == 1,
					"an unrecorded action keeps waiting below the failure limit");
			fixture.breakJournal();
			fixture.bridge.startTick();
			require(fixture.journal.stagedAcceptances().isEmpty(), "the failure limit rolls the unrecorded acceptance back");
			require(fixture.executor.activeRequest(staged.record.agentId()) == null,
					"the rejected action leaves the executor");
			ServerActionResult rejection = fixture.executor.lastResult(staged.record.agentId());
			require(rejection != null && rejection.state() == ServerActionState.FAILED
							&& rejection.reasonCode().equals("ACTION_JOURNAL_UNAVAILABLE")
							&& !rejection.executionStarted() && !rejection.physicalAttempted(),
					"the model is told the journal was unavailable and that nothing ran");
			require(fixture.bridge.terminalResultsForVerification().pendingCount() == 1,
					"the model receives one terminal result for the rejected action while the journal is still down");
			fixture.bridge.startTick();
			require(fixture.bridge.terminalResultsForVerification().pendingCount() == 2,
					"the recorded action's result, staged through the outage, is published after recovery");
		}
	}

	/** The fixture has no player, so the real executor ends this action in its first tick. */
	private static final class OneTickAction implements ServerTransactionAdapter.ActiveTransaction {
		@Override
		public ServerTransactionAdapter.TickResult tick(long nowEpochMs) {
			return ServerTransactionAdapter.TickResult.succeeded("DONE", "Done");
		}

		@Override public void cancel(String reason) { }
		@Override public void cleanup() { }
	}

	private record Agent(AgentRecord record, UUID goalId, ServerActionRequest request) { }

	private static final class Fixture implements AutoCloseable {
		final CodexAgentManager manager = MultiplexedServerBridgeVerification.uninitializedManager();
		final Path directory;
		final DurableActionJournal journal;
		final MultiplexedServerBridge bridge;
		final ServerActionExecutor executor;
		private int created;

		Fixture() throws Exception {
			directory = Files.createTempDirectory("arena-bridge-journal-group-");
			Path secretFile = directory.resolve("secret.txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			journal = DurableActionJournal.open(directory.resolve("actions.journal"));
			// A durable seed keeps the journal on its append path, where a write failure can be injected.
			journal.accept(request(AgentId.random(), 1L, "seed", 99L), UUID.randomUUID());
			bridge = new MultiplexedServerBridge(manager, 0, secretFile, ServerSocket::new, System::nanoTime, journal);
			executor = (ServerActionExecutor) read(bridge, "actionExecutor");
		}

		Agent agent(String name, String actionId) {
			AgentRecord made = manager.registry().create("gpt-5.6-sol", "high", Optional.of(name), 5_000L + created++);
			AgentRecord started = manager.registry().start(made.agentId(), "journal group", 5_100L).after();
			return new Agent(started, started.currentGoal().orElseThrow().goalId(),
					request(started.agentId(), started.goalRevision(), actionId, 1L));
		}

		@SuppressWarnings("unchecked")
		void install(Agent agent) throws Exception {
			Class<?> actionClass = Class.forName(ServerActionExecutor.class.getName() + "$ActiveAction");
			var factory = actionClass.getDeclaredMethod("transaction", ServerActionRequest.class,
					net.minecraft.server.level.ServerPlayer.class, ServerTransactionAdapter.ActiveTransaction.class);
			factory.setAccessible(true);
			((Map<AgentId, Object>) read(executor, "active")).put(
					agent.record.agentId(), factory.invoke(null, agent.request, null, new OneTickAction()));
		}

		/** Replaces the journal's channel with a closed one so its next append fails like a locked or full disk. */
		void breakJournal() throws Exception {
			Field channel = DurableActionJournal.class.getDeclaredField("persistentChannel");
			channel.setAccessible(true);
			FileChannel closed = FileChannel.open(directory.resolve("closed.tmp"),
					StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			closed.close();
			channel.set(journal, closed);
		}

		@Override
		public void close() {
			bridge.close();
		}
	}

	private static ServerActionRequest request(AgentId agentId, long revision, String actionId, long sequence) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 25L);
		String traceId = "trace-" + actionId;
		return new ServerActionRequest(agentId, revision, actionId, ActionType.WAIT, arguments,
				new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program-" + actionId, 1L,
						actionId + "-step", sequence, traceId, null),
				traceId);
	}

	private static Object read(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
