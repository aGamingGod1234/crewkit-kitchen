package dev.agaminggod.arenaagents.server;

import java.time.Duration;

/** Lets an already-running coordinator reconnect before starting another process. */
public final class CoordinatorLaunchPolicy {
	public static final long STARTUP_GRACE_MS = Duration.ofSeconds(3).toMillis();
	private static final long[] RESTART_DELAYS_MS = {1_000L, 2_000L, 5_000L, 15_000L, 30_000L};

	private CoordinatorLaunchPolicy() {
	}

	public static boolean shouldStart(boolean bridgeAuthenticated, boolean ownedProcessAlive, long createdAtEpochMs, long nowEpochMs) {
		return !bridgeAuthenticated && !ownedProcessAlive && nowEpochMs - createdAtEpochMs >= STARTUP_GRACE_MS;
	}

	/** Capped restart state for one server startup episode. */
	public static final class RestartBudget {
		private int restartCount;

		public boolean recordUnexpectedExit() {
			if (restartCount < Integer.MAX_VALUE) restartCount++;
			return true;
		}

		public long nextDelayMs() {
			if (restartCount < 1) {
				throw new IllegalStateException("No coordinator restart is pending");
			}
			return RESTART_DELAYS_MS[Math.min(restartCount, RESTART_DELAYS_MS.length) - 1];
		}

		public int restartCount() {
			return restartCount;
		}

		public void resetAfterStability() {
			restartCount = 0;
		}

		/** Kept for source compatibility; supervisors call this only after authenticated stability. */
		public void resetAfterAuthentication() {
			resetAfterStability();
		}
	}
}
