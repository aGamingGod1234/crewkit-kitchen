package dev.agaminggod.arenaagents.server;

import java.time.Duration;

/** Lets an already-running coordinator reconnect before starting another process. */
public final class CoordinatorLaunchPolicy {
	public static final long STARTUP_GRACE_MS = Duration.ofSeconds(3).toMillis();
	public static final int MAX_RESTARTS = 3;
	private static final long[] RESTART_DELAYS_MS = {2_000L, 5_000L, 15_000L};

	private CoordinatorLaunchPolicy() {
	}

	public static boolean shouldStart(boolean bridgeAuthenticated, boolean ownedProcessAlive, long createdAtEpochMs, long nowEpochMs) {
		return !bridgeAuthenticated && !ownedProcessAlive && nowEpochMs - createdAtEpochMs >= STARTUP_GRACE_MS;
	}

	/** Bounded restart state for one server startup episode. */
	public static final class RestartBudget {
		private int restartCount;

		public boolean recordUnexpectedExit() {
			if (restartCount >= MAX_RESTARTS) return false;
			restartCount++;
			return true;
		}

		public long nextDelayMs() {
			if (restartCount < 1 || restartCount > MAX_RESTARTS) {
				throw new IllegalStateException("No coordinator restart is pending");
			}
			return RESTART_DELAYS_MS[restartCount - 1];
		}

		public void resetAfterAuthentication() {
			restartCount = 0;
		}
	}
}
