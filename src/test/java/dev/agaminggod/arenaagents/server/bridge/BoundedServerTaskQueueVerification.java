package dev.agaminggod.arenaagents.server.bridge;

import java.util.ArrayList;
import java.util.List;

public final class BoundedServerTaskQueueVerification {
	private BoundedServerTaskQueueVerification() {
	}

	public static int verify() {
		BoundedServerTaskQueue queue = new BoundedServerTaskQueue(3);
		assertTrue(queue.offer(() -> { }), "first server task is accepted");
		assertTrue(queue.offer(() -> { }), "second server task is accepted");
		assertTrue(queue.offer(() -> { }), "third server task is accepted");
		assertFalse(queue.offer(() -> { }), "server work fails closed once its capacity is full");
		List<Integer> executed = new ArrayList<>();
		queue = new BoundedServerTaskQueue(3);
		queue.offer(() -> executed.add(1));
		queue.offer(() -> executed.add(2));
		queue.offer(() -> executed.add(3));
		assertEquals(2, queue.drain(2, Runnable::run), "tick drain reports its bounded work count");
		assertEquals(List.of(1, 2), executed, "tick drain retains FIFO order");
		assertEquals(1, queue.pendingCount(), "excess work is deferred to a future tick");
		assertEquals(1, queue.drain(2, Runnable::run), "next tick drains the deferred task");
		assertEquals(List.of(1, 2, 3), executed, "deferred work remains ordered");
		return 10;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
