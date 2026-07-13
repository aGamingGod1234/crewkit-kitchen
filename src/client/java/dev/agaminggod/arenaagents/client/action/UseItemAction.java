package dev.agaminggod.arenaagents.client.action;

public final class UseItemAction implements RunningAction {
	public static final long COMPLETION_GRACE_MS = 1_000L;

	private static final String RUNNING_MESSAGE = "Using item for requested duration";
	private static final String COMPLETE_REASON = "ITEM_USE_COMPLETE";
	private static final String COMPLETE_MESSAGE = "Requested item-use duration elapsed";

	private final ActionContext.Hand hand;
	private final long durationMs;
	private boolean started;
	private boolean stopped;
	private ActionUpdate startFailure;

	public UseItemAction(ActionContext.Hand hand, long durationMs) {
		if (hand == null) {
			throw new IllegalArgumentException("hand must not be null");
		}
		if (durationMs <= 0L) {
			throw new IllegalArgumentException("durationMs must be positive");
		}
		this.hand = hand;
		this.durationMs = durationMs;
	}

	@Override
	public long timeoutMs() {
		return durationMs + COMPLETION_GRACE_MS;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		if (startFailure != null) {
			return startFailure;
		}
		if (!started) {
			ActionContext.OperationResult result = context.startUsingItem(hand);
			if (!result.successful()) {
				startFailure = ActionUpdate.failed(result.reasonCode(), result.message());
				return startFailure;
			}
			started = true;
		}
		if (elapsedMs >= durationMs) {
			stop(context);
			return ActionUpdate.succeeded(COMPLETE_REASON, COMPLETE_MESSAGE);
		}
		return ActionUpdate.running(RUNNING_MESSAGE);
	}

	@Override
	public void cancel(ActionContext context) {
		stop(context);
	}

	private void stop(ActionContext context) {
		if (started && !stopped) {
			context.stopUsingItem();
			stopped = true;
		}
	}
}
