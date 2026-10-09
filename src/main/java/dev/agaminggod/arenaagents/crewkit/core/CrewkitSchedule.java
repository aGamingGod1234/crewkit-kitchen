package dev.agaminggod.arenaagents.crewkit.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Tiny tick scheduler for staged motions (spawn, then animate a few ticks later so the client
 * has the start state to interpolate from). Cleared on reset. Server thread only.
 */
public final class CrewkitSchedule {
	private record Task(int dueTick, Runnable action) {}

	private static final List<Task> TASKS = new ArrayList<>();
	private static int now;

	private CrewkitSchedule() {}

	public static void after(int ticks, Runnable action) {
		TASKS.add(new Task(now + Math.max(1, ticks), action));
	}

	static void tick(MinecraftServer server) {
		now = server.getTickCount();
		if (TASKS.isEmpty()) return;
		List<Task> due = new ArrayList<>();
		for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
			Task task = it.next();
			if (task.dueTick() <= now) {
				due.add(task);
				it.remove();
			}
		}
		for (Task task : due) {
			try {
				task.action().run();
			} catch (RuntimeException e) {
				CrewkitDispatcher.LOGGER.warn("CrewKit scheduled task failed", e);
			}
		}
	}

	static void clear() {
		TASKS.clear();
	}
}
