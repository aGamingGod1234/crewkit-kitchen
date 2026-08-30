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
		return remember(commandId, null);
	}

	boolean remember(String commandId, String protectedCommandId) {
		String requiredId = Objects.requireNonNull(commandId, "commandId must not be null");
		if (commandIds.contains(requiredId)) {
			return false;
		}
		if (commandIds.size() == capacity && !evictOldestExcept(protectedCommandId)) {
			return true;
		}
		commandIds.add(requiredId);
		return true;
	}

	void touch(String commandId) {
		String requiredId = Objects.requireNonNull(commandId, "commandId must not be null");
		commandIds.remove(requiredId);
		if (commandIds.size() == capacity) {
			evictOldestExcept(null);
		}
		commandIds.add(requiredId);
	}

	boolean contains(String commandId) {
		return commandIds.contains(commandId);
	}

	int size() {
		return commandIds.size();
	}

	void clear() {
		commandIds.clear();
	}

	private boolean evictOldestExcept(String protectedCommandId) {
		Iterator<String> oldest = commandIds.iterator();
		while (oldest.hasNext()) {
			String candidate = oldest.next();
			if (!candidate.equals(protectedCommandId)) {
				oldest.remove();
				return true;
			}
		}
		return false;
	}
}
