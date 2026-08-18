package dev.agaminggod.arenaagents.server.bridge;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

final class BoundedServerTaskQueue {
	private final ArrayBlockingQueue<Runnable> tasks;

	BoundedServerTaskQueue(int capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
		this.tasks = new ArrayBlockingQueue<>(capacity);
	}

	boolean offer(Runnable task) {
		return tasks.offer(Objects.requireNonNull(task, "task must not be null"));
	}

	int drain(int maximum, Consumer<Runnable> consumer) {
		if (maximum < 0) throw new IllegalArgumentException("maximum must not be negative");
		Objects.requireNonNull(consumer, "consumer must not be null");
		int drained = 0;
		Runnable task;
		while (drained < maximum && (task = tasks.poll()) != null) {
			consumer.accept(task);
			drained++;
		}
		return drained;
	}

	int pendingCount() {
		return tasks.size();
	}
}
