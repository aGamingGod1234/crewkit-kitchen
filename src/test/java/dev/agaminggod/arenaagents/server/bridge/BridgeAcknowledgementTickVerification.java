package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.UUID;

/** Calls the production ACK handler and startTick retry boundary with an idle executor. */
public final class BridgeAcknowledgementTickVerification {
	public static void main(String[] args) throws Exception {
		System.out.println("BridgeAcknowledgementTickVerification assertions=" + verify());
	}

	public static int verify() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
		var bridge = (MultiplexedServerBridge) unsafe.allocateInstance(MultiplexedServerBridge.class);
		var manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
		set(manager, "savedData", new AgentSavedData());
		var executor = new ServerActionExecutor(manager, result -> { });
		var ledger = new TerminalResultLedger();
		var queue = new BoundedServerTaskQueue(16, 4, 4);
		set(bridge, "terminalResults", ledger);
		set(bridge, "serverTasks", queue);
		set(bridge, "actionExecutor", executor);
		var agent = AgentId.random();
		var session = new Object();
		try (var journal = DurableActionJournal.open(Files.createTempDirectory("arena-bridge-acks-").resolve("actions.journal"))) {
			set(bridge, "actionJournal", journal);
			for (int i = 0; i < 2; i++) {
				var request = ActionAcknowledgementRetryVerification.request(agent, "tick-" + i);
				var result = ActionAcknowledgementRetryVerification.result(request);
				journal.accept(request, UUID.randomUUID());
				journal.terminal(result);
				ledger.retain(result);
				ledger.claim(result, session);
				queue.offer(MultiplexedServerBridge.inboundLane("action_result_ack"), () -> acknowledge(bridge, agent, request.actionId()));
			}
			int before = journal.persistedEventCountForVerification();
			queue.offer(MultiplexedServerBridge.inboundLane("inspection_request"), () -> {
				throw new AssertionError("startTick must leave inspection work until after physical input and vanilla physics");
			});
			var channel = DurableActionJournal.class.getDeclaredField("persistentChannel");
			channel.setAccessible(true);
			((FileChannel) channel.get(journal)).close();
			bridge.startTick();
			require(queue.pendingCount() == 1, "ACK tasks drain while inspection work remains for endTick");
			require(ledger.pendingCount() == 2, "bridge retains both terminal results after failed group");
			require(journal.persistedEventCountForVerification() == before, "failed tick did not commit ACKs");
			bridge.startTick();
			require(ledger.pendingCount() == 0, "next production tick retires both ACKs without another inbound task");
			require(journal.persistedEventCountForVerification() == before + 1, "bridge persisted both ACKs in one group");
			require(journal.snapshot().stream().allMatch(entry -> entry.phase() == DurableActionJournal.Phase.ACKNOWLEDGED),
					"every retired result is durably acknowledged");
			queue.offer(MultiplexedServerBridge.inboundLane("action_result_ack"), () -> acknowledge(bridge, agent, "tick-0"));
			bridge.startTick();
			require(journal.persistedEventCountForVerification() == before + 1, "duplicate inbound ACK does not add a commit");
		}
		require(MultiplexedServerBridge.inboundLane("inspection_request") == BoundedServerTaskQueue.Lane.INSPECTION,
				"production inspection routing uses the post-input lane");
		return 8;
	}

	private static void acknowledge(MultiplexedServerBridge bridge, AgentId agent, String action) {
		var payload = new JsonObject();
		payload.addProperty("goalRevision", 1L);
		payload.addProperty("actionId", action);
		try {
			var handler = MultiplexedServerBridge.class.getDeclaredMethod("acceptActionResultAck", BridgeEnvelope.class);
			handler.setAccessible(true);
			handler.invoke(bridge, new BridgeEnvelope(2, "test", agent.toString(), "action_result_ack", action, payload));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		var field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
