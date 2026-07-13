package dev.agaminggod.arenaagents.client.action;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class CommandIdHistory {
	private final int capacity;
	private final Set<String> commandIds = new LinkedHashSet<>();

	CommandIdHistory(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		this.capacity = capacity;
	}

	boolean remember(String commandId) {
		String requiredId = Objects.requireNonNull(commandId, "commandId must not be null");
		if (commandIds.contains(requiredId)) {
			return false;
		}
		if (commandIds.size() == capacity) {
			Iterator<String> oldest = commandIds.iterator();
			oldest.next();
			oldest.remove();
		}
		commandIds.add(requiredId);
		return true;
	}

	void touch(String commandId) {
		if (commandIds.remove(commandId)) {
			commandIds.add(commandId);
		}
	}

	boolean contains(String commandId) {
		return commandIds.contains(commandId);
	}

	int size() {
		return commandIds.size();
	}
}
