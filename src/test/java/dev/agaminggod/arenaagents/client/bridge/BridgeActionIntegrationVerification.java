package dev.agaminggod.arenaagents.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.action.ActionContext;
import dev.agaminggod.arenaagents.client.action.ActionEventPublisher;
import dev.agaminggod.arenaagents.client.action.ActionExecutor;
import dev.agaminggod.arenaagents.client.action.ActionFactory;
import dev.agaminggod.arenaagents.client.action.ActionUpdate;
import dev.agaminggod.arenaagents.client.action.RunningAction;
import dev.agaminggod.arenaagents.client.action.SafetyState;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeActionIntegrationVerification {
	private static final String AGENT_ID = "action-integration-agent";
	private static final int SOCKET_TIMEOUT_MS = 2_000;
	private static final long ASYNC_TIMEOUT_MS = 3_000L;

	private BridgeActionIntegrationVerification() {
	}

	public static int verifyLifecycleEventsAndCancellation() throws Exception {
		ProtocolCodec codec = new ProtocolCodec();
		RecordingExecutor callbackExecutor = new RecordingExecutor();
		FakeActionContext context = new FakeActionContext();
		List<RuntimeException> publishFailures = new ArrayList<>();
		AtomicReference<ActionExecutor> executorReference = new AtomicReference<>();
		BridgeEventSink bridgeSink = new BridgeEventSink() {
			@Override
			public void onActionCommand(ActionCommand command) {
				executorReference.get().accept(command);
			}

			@Override
			public void onCancelAction(String commandId) {
				executorReference.get().cancel(commandId, "coordinator_cancelled");
			}
		};

		int port = findAvailablePort();
		AgentConfig config = new AgentConfig(AGENT_ID, port, AgentConfig.DEFAULT_OBSERVATION_RADIUS, true);
		try (BridgeServer server = new BridgeServer(config, codec, callbackExecutor, bridgeSink)) {
			ActionEventPublisher publisher = new ActionEventPublisher(server, publishFailures::add);
			ActionFactory actionFactory = new ActionFactory();
			ActionExecutor actionExecutor = new ActionExecutor(
					context,
					command -> "bridge-timeout-failure".equals(command.commandId())
							? new ThrowingTimeoutAction()
							: actionFactory.create(command),
					publisher
			);
			executorReference.set(actionExecutor);
			server.start();

			try (Socket client = connectAuthenticated(codec, port)) {
				write(codec, client, actionEnvelope("message-wait", waitCommand("bridge-wait", 100L)));
				awaitPending(callbackExecutor, 1);
				assertEquals(0, publishFailures.size(), "action callback has no publish failure before dispatch");
				callbackExecutor.runNext();
				JsonObject progress = read(codec, client);
				assertEquals("action_progress", progress.get("type").getAsString(), "action progress event type");
				assertEquals("bridge-wait", progress.get("commandId").getAsString(), "action progress command id");
				assertEquals("wait", progress.get("actionType").getAsString(), "action progress wire action type");
				assertEquals("RUNNING", progress.get("state").getAsString(), "action progress running state");
				assertEquals(
						Set.of(
								"protocolVersion",
								"agentId",
								"type",
								"messageId",
								"commandId",
								"actionType",
								"state",
								"elapsedMs",
								"message",
								"observedAtEpochMs"
						),
						progress.keySet(),
						"action progress payload is strict"
				);
				assertEquals(true, actionExecutor.currentStatus().present(), "observation sees current action");

				write(codec, client, cancelEnvelope("message-cancel", "bridge-wait"));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject cancelled = read(codec, client);
				assertEquals("action_result", cancelled.get("type").getAsString(), "cancel result event type");
				assertEquals("CANCELLED", cancelled.get("state").getAsString(), "cancel result state");
				assertEquals(
						"COORDINATOR_CANCELLED",
						cancelled.get("reasonCode").getAsString(),
						"cancel result reason"
				);
				assertEquals(
						Set.of(
								"protocolVersion",
								"agentId",
								"type",
								"messageId",
								"commandId",
								"state",
								"reasonCode",
								"message",
								"completedAtEpochMs"
						),
						cancelled.keySet(),
						"action result payload is strict"
				);
				assertEquals(false, actionExecutor.currentStatus().present(), "observation clears terminal action");
				assertEquals(
						ActionState.CANCELLED,
						actionExecutor.lastResult().state(),
						"observation sees last terminal result"
				);

				write(codec, client, actionEnvelope("message-move", moveCommand("bridge-move")));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject moveAccepted = read(codec, client);
				assertEquals("action_progress", moveAccepted.get("type").getAsString(), "move action is enabled");
				assertEquals("RUNNING", moveAccepted.get("state").getAsString(), "move action starts running");
				write(codec, client, cancelEnvelope("message-move-cancel", "bridge-move"));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject moveCancelled = read(codec, client);
				assertEquals("action_result", moveCancelled.get("type").getAsString(), "move cancellation returns result");
				assertEquals("CANCELLED", moveCancelled.get("state").getAsString(), "move cancellation clears executor");

				write(codec, client, actionEnvelope("message-attack", attackCommand("bridge-attack")));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject attackAccepted = read(codec, client);
				assertEquals("action_progress", attackAccepted.get("type").getAsString(), "attack command is accepted");
				assertEquals("RUNNING", attackAccepted.get("state").getAsString(), "attack starts running");
				actionExecutor.tick();
				JsonObject attackFailure = read(codec, client);
				assertEquals("action_result", attackFailure.get("type").getAsString(), "missing target returns result");
				assertEquals("FAILED", attackFailure.get("state").getAsString(), "missing target fails explicitly");
				assertEquals("TARGET_GONE", attackFailure.get("reasonCode").getAsString(), "missing target result code");

				write(codec, client, actionEnvelope("message-after-attack", waitCommand("after-attack", 100L)));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject afterAttack = read(codec, client);
				assertEquals("action_progress", afterAttack.get("type").getAsString(), "combat failure keeps session healthy");
				write(codec, client, cancelEnvelope("message-after-attack-cancel", "after-attack"));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				read(codec, client);

				write(
						codec,
						client,
						actionEnvelope(
								"message-timeout-failure",
								waitCommand("bridge-timeout-failure", 100L)
						)
				);
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject timeoutFailure = read(codec, client);
				assertEquals("action_result", timeoutFailure.get("type").getAsString(), "timeout exception returns result");
				assertEquals("FAILED", timeoutFailure.get("state").getAsString(), "timeout exception fails explicitly");
				assertEquals(
						"ACTION_EXECUTION_FAILED",
						timeoutFailure.get("reasonCode").getAsString(),
						"timeout exception result reason"
				);

				write(codec, client, actionEnvelope("message-after-failure", waitCommand("after-failure", 100L)));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				JsonObject afterFailure = read(codec, client);
				assertEquals("action_progress", afterFailure.get("type").getAsString(), "ordinary failure keeps session healthy");
				assertEquals(0, publishFailures.size(), "healthy session publishes without callback failures");

				write(codec, client, cancelEnvelope("message-cleanup", "after-failure"));
				awaitPending(callbackExecutor, 1);
				callbackExecutor.runNext();
				read(codec, client);
			}
		}
		return 29;
	}

	private static int findAvailablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static Socket connectAuthenticated(ProtocolCodec codec, int port) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		write(
				codec,
				socket,
				"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
						+ "\",\"type\":\"hello\",\"messageId\":\"message-hello\"}"
		);
		JsonObject acknowledgement = read(codec, socket);
		assertEquals("hello_ack", acknowledgement.get("type").getAsString(), "action integration authenticated");
		return socket;
	}

	private static String actionEnvelope(String messageId, String command) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"action_command\",\"messageId\":\"" + messageId
				+ "\",\"command\":" + command + "}";
	}

	private static String cancelEnvelope(String messageId, String commandId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"cancel_action\",\"messageId\":\"" + messageId
				+ "\",\"commandId\":\"" + commandId + "\"}";
	}

	private static String waitCommand(String commandId, long durationMs) {
		return "{\"protocolVersion\":1,\"commandId\":\"" + commandId
				+ "\",\"type\":\"wait\",\"issuedAtEpochMs\":1750000000000,\"durationMs\":"
				+ durationMs + "}";
	}

	private static String moveCommand(String commandId) {
		return "{\"protocolVersion\":1,\"commandId\":\"" + commandId
				+ "\",\"type\":\"move_to\",\"issuedAtEpochMs\":1750000000000,"
				+ "\"x\":0,\"y\":64,\"z\":0,\"tolerance\":0.5,\"sprint\":false}";
	}

	private static String attackCommand(String commandId) {
		return "{\"protocolVersion\":1,\"commandId\":\"" + commandId
				+ "\",\"type\":\"attack\",\"issuedAtEpochMs\":1750000000000,"
				+ "\"targetId\":\"00000000-0000-0000-0000-000000000099\",\"timeoutMs\":5000}";
	}

	private static void write(ProtocolCodec codec, Socket socket, String json) throws IOException {
		codec.writeLine(socket.getOutputStream(), json);
	}

	private static JsonObject read(ProtocolCodec codec, Socket socket) throws IOException {
		String line = codec.readLine(socket.getInputStream());
		if (line == null) {
			throw new AssertionError("bridge closed before sending expected action event");
		}
		return JsonParser.parseString(line).getAsJsonObject();
	}

	private static void awaitPending(RecordingExecutor executor, int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_TIMEOUT_MS);
		while (executor.pendingCount() != expected && System.nanoTime() < deadline) {
			Thread.yield();
		}
		assertEquals(expected, executor.pendingCount(), "action callback queued on provided executor");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static final class RecordingExecutor implements Executor {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void execute(Runnable command) {
			tasks.addLast(command);
		}

		private synchronized int pendingCount() {
			return tasks.size();
		}

		private void runNext() {
			Runnable task;
			synchronized (this) {
				task = tasks.removeFirst();
			}
			task.run();
		}
	}

	private static final class ThrowingTimeoutAction implements RunningAction {
		@Override
		public long timeoutMs() {
			throw new IllegalStateException("timeout resolution failed");
		}

		@Override
		public ActionUpdate tick(ActionContext context, long elapsedMs) {
			return ActionUpdate.running("unreachable");
		}
	}

	private static final class FakeActionContext implements ActionContext {
		@Override
		public boolean isClientThread() {
			return true;
		}

		@Override
		public long monotonicTimeMs() {
			return 0L;
		}

		@Override
		public long epochTimeMs() {
			return 1_750_000_001_000L;
		}

		@Override
		public SafetyState safetyState() {
			return SafetyState.READY;
		}

		@Override
		public NavigationSnapshot navigationSnapshot() {
			return new NavigationSnapshot(0.5D, 64.0D, 0.5D, 0.0F, 0.0F, new GridPosition(0, 64, 0));
		}

		@Override
		public WalkabilityView walkabilityView() {
			return position -> position.y() == 63
					? WalkabilityView.Cell.SAFE_SUPPORT
					: WalkabilityView.Cell.CLEAR;
		}

		@Override
		public void setMovement(MovementInput movement) {
		}

		@Override
		public LookResult lookAt(
				double x,
				double y,
				double z,
				float maxYawDelta,
				float maxPitchDelta,
				float toleranceDegrees
		) {
			return new LookResult(true, 0.0F, 0.0F);
		}

		@Override
		public OperationResult sendChat(String message) {
			return OperationResult.succeeded("CHAT_SENT", "Chat sent");
		}

		@Override
		public OperationResult selectHotbarItem(String itemId) {
			return OperationResult.succeeded("ITEM_SELECTED", "Item selected");
		}

		@Override
		public OperationResult startUsingItem(Hand hand) {
			return OperationResult.succeeded("ITEM_USE_STARTED", "Item use started");
		}

		@Override
		public void stopUsingItem() {
		}

		@Override
		public void releaseAll() {
		}
	}
}
