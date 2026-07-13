package dev.agaminggod.arenaagents.client.action;

public final class LookAtAction implements RunningAction {
	public static final long TIMEOUT_MS = 5_000L;
	public static final float MAX_YAW_DEGREES_PER_TICK = 12.0F;
	public static final float MAX_PITCH_DEGREES_PER_TICK = 8.0F;
	public static final float ANGLE_TOLERANCE_DEGREES = 2.0F;

	private static final String RUNNING_MESSAGE = "Rotating toward target";
	private static final String COMPLETE_REASON = "LOOK_TARGET_REACHED";
	private static final String COMPLETE_MESSAGE = "View is within target tolerance";

	private final double x;
	private final double y;
	private final double z;

	public LookAtAction(double x, double y, double z) {
		this.x = requireFinite(x, "x");
		this.y = requireFinite(y, "y");
		this.z = requireFinite(z, "z");
	}

	@Override
	public long timeoutMs() {
		return TIMEOUT_MS;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		ActionContext.LookResult result = context.lookAt(
				x,
				y,
				z,
				MAX_YAW_DEGREES_PER_TICK,
				MAX_PITCH_DEGREES_PER_TICK,
				ANGLE_TOLERANCE_DEGREES
		);
		return result.withinTolerance()
				? ActionUpdate.succeeded(COMPLETE_REASON, COMPLETE_MESSAGE)
				: ActionUpdate.running(RUNNING_MESSAGE);
	}

	private static double requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
		return value;
	}
}
