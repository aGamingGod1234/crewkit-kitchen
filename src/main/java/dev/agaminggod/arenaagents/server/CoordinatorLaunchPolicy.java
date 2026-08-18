package dev.agaminggod.arenaagents.server;

import java.time.Duration;

/** Lets an already-running coordinator reconnect before starting another process. */
public final class CoordinatorLaunchPolicy {
	public static final long STARTUP_GRACE_MS = Duration.ofSeconds(3).toMillis();

	private CoordinatorLaunchPolicy() {
	}

	public static boolean shouldStart(boolean bridgeAuthenticated, boolean ownedProcessAlive, long createdAtEpochMs, long nowEpochMs) {
		return !bridgeAuthenticated && !ownedProcessAlive && nowEpochMs - createdAtEpochMs >= STARTUP_GRACE_MS;
	}
}
