package dev.agaminggod.arenaagents.server.perception;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;

public final class ObservationDispatchQueue<T> {
	private final int capacity;
	private final int maximumPerDrain;
	private final LinkedHashSet<T> pending = new LinkedHashSet<>();

	public ObservationDispatchQueue(int capacity, int maximumPerDrain) {
		if (capacity <= 0 || maximumPerDrain <= 0 || maximumPerDrain > capacity) {
			throw new IllegalArgumentException("invalid observation dispatch bounds");
		}
		this.capacity = capacity;
		this.maximumPerDrain = maximumPerDrain;
	}

	public synchronized boolean offer(T identity) {
		Objects.requireNonNull(identity, "identity must not be null");
		if (pending.contains(identity)) return false;
		if (pending.size() >= capacity) throw new IllegalStateException("observation dispatch capacity is full");
		pending.add(identity);
		return true;
	}

	public void drain(Consumer<T> consumer) {
		Objects.requireNonNull(consumer, "consumer must not be null");
		for (int emitted = 0; emitted < maximumPerDrain; emitted++) {
			T identity;
			synchronized (this) {
				Iterator<T> iterator = pending.iterator();
				if (!iterator.hasNext()) return;
				identity = iterator.next();
				iterator.remove();
			}
			consumer.accept(identity);
		}
	}

	public synchronized int pendingCount() {
		return pending.size();
	}
}
