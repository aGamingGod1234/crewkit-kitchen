package dev.agaminggod.arenaagents.crewkit.core;

/**
 * Eased number over server ticks. Retargeting mid-flight starts from the current value, so a counter
 * never jumps. Drive it with the server tick count: {@code tween.value(server.getTickCount())}.
 */
public final class CrewkitTween {
	private double from;
	private double to;
	private int startTick;
	private int duration;

	public CrewkitTween(double initial) {
		this.from = initial;
		this.to = initial;
	}

	/** Ease from the current value to {@code target} over {@code ticks}. */
	public void retarget(double target, int ticks, int nowTick) {
		this.from = value(nowTick);
		this.to = target;
		this.startTick = nowTick;
		this.duration = Math.max(1, ticks);
	}

	/** Jump without easing (initial state only). */
	public void snap(double value) {
		this.from = value;
		this.to = value;
		this.duration = 0;
	}

	public double value(int nowTick) {
		if (duration <= 0) return to;
		double t = (nowTick - startTick) / (double) duration;
		if (t >= 1) return to;
		if (t <= 0) return from;
		return from + (to - from) * easeOutCubic(t);
	}

	public boolean active(int nowTick) {
		return duration > 0 && nowTick - startTick < duration;
	}

	public double target() {
		return to;
	}

	public static double easeOutCubic(double t) {
		double u = 1 - t;
		return 1 - u * u * u;
	}

	public static double easeInOutCubic(double t) {
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}
}
