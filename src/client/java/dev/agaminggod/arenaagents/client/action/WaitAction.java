package dev.agaminggod.arenaagents.client.action;

public final class WaitAction implements RunningAction {
	public static final long COMPLETION_GRACE_MS = 1_000L;

	private static final String RUNNING_MESSAGE = "Waiting for requested duration";
	private static final String COMPLETE_REASON = "WAIT_COMPLETE";
	private static final String COMPLETE_MESSAGE = "Requested wait duration elapsed";

	private final long durationMs;

	public WaitAction(long durationMs) {
		if (durationMs <= 0L) {
			throw new IllegalArgumentException("durationMs must be positive");
		}
		this.durationMs = durationMs;
	}

	@Override
	public long timeoutMs() {
		return durationMs + COMPLETION_GRACE_MS;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		return elapsedMs >= durationMs
				? ActionUpdate.succeeded(COMPLETE_REASON, COMPLETE_MESSAGE)
				: ActionUpdate.running(RUNNING_MESSAGE);
	}
}
