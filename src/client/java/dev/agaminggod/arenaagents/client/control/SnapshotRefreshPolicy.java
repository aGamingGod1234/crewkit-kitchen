package dev.agaminggod.arenaagents.client.control;

/** Pure timing policy for the lightweight control snapshot heartbeat. */
public final class SnapshotRefreshPolicy {
	private SnapshotRefreshPolicy() {
	}

	public static Tick advance(int countdown, boolean connected, int intervalTicks) {
		if (countdown < 0) throw new IllegalArgumentException("countdown must not be negative");
		if (intervalTicks <= 0) throw new IllegalArgumentException("intervalTicks must be positive");
		if (!connected) return new Tick(0, false);
		if (countdown == 0) return new Tick(intervalTicks - 1, true);
		return new Tick(countdown - 1, false);
	}

	public record Tick(int nextCountdown, boolean requestSnapshot) {
		public Tick {
			if (nextCountdown < 0) throw new IllegalArgumentException("nextCountdown must not be negative");
		}
	}
}
