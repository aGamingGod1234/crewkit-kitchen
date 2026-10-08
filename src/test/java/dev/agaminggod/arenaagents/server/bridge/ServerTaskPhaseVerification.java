package dev.agaminggod.arenaagents.server.bridge;

import java.util.ArrayList;
import java.util.List;

public final class ServerTaskPhaseVerification {
	public static void main(String[] args) {
		System.out.println("ServerTaskPhaseVerification assertions=" + verify());
	}

	public static int verify() {
		var queue = new BoundedServerTaskQueue(16, 4, 4);
		var events = new ArrayList<String>();
		Thread worldThread = Thread.currentThread();
		for (int i = 0; i < 6; i++) queue.offer(BoundedServerTaskQueue.Lane.INSPECTION, () -> {
			require(Thread.currentThread() == worldThread, "inspection retains world-thread ownership");
			events.add("inspect");
		});
		queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> events.add("accept"));
		queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> events.add("cancel-exact"));
		queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> events.add("control"));
		queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> events.add("lifecycle"));
		queue.drainBeforePhysics(2, 1, 1, Runnable::run, () -> events.add("input"));
		require(!events.contains("inspect"), "inspection cannot delay native input or vanilla physics after the start hook");
		events.add("physical");
		queue.drainInspections(2, Runnable::run);
		require(events.equals(List.of("accept", "cancel-exact", "control", "lifecycle", "input", "physical", "inspect", "inspect")),
				"command/cancel and lifecycle fences precede physical tick; expensive inspection follows it");
		require(queue.pendingCount() == 4, "inspection batch has an independent bounded budget");
		events.clear();
		// Continuous urgent traffic must not consume the inspection/control/bulk allotments.
		Runnable[] flood = new Runnable[1];
		flood[0] = () -> { events.add("urgent"); queue.offer(BoundedServerTaskQueue.Lane.URGENT, flood[0]); };
		queue.offer(BoundedServerTaskQueue.Lane.URGENT, flood[0]);
		queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> events.add("control"));
		queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> events.add("bulk"));
		queue.drainBeforePhysics(2, 1, 1, Runnable::run, () -> events.add("input"));
		events.add("physical");
		queue.drainInspections(2, Runnable::run);
		require(events.equals(List.of("urgent", "urgent", "control", "bulk", "input", "physical", "inspect", "inspect")),
				"all lanes progress despite urgent flood and input runs once");
		require(queue.pendingCount() == 3, "urgent and inspection excess are deferred");
		var capacity = new BoundedServerTaskQueue(8, 2, 2);
		for (int i = 0; i < 6; i++) require(capacity.offer(BoundedServerTaskQueue.Lane.INSPECTION, () -> { }), "inspection admitted within control capacity");
		require(!capacity.offer(BoundedServerTaskQueue.Lane.INSPECTION, () -> { }), "inspection cannot consume urgent reserve");
		require(capacity.offer(BoundedServerTaskQueue.Lane.URGENT, () -> { }), "command admission survives inspection pressure");
		require(capacity.offer(BoundedServerTaskQueue.Lane.URGENT, () -> { }), "exact cancellation admission survives inspection pressure");
		var gated = new BoundedServerTaskQueue(4, 1, 1);
		var executions = new int[1];
		gated.drainBeforePhysics(2, 1, 1, Runnable::run, () -> false, () -> executions[0]++);
		require(executions[0] == 0, "action execution stays behind a failed durability barrier");
		gated.drainBeforePhysics(2, 1, 1, Runnable::run, () -> true, () -> executions[0]++);
		require(executions[0] == 1, "action execution resumes after the durability barrier succeeds");
		return 20;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
