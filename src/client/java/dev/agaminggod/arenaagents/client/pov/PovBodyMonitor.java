package dev.agaminggod.arenaagents.client.pov;

/** Watches the operator's own body during a takeover so the badge can flash when it is hurt. */
public final class PovBodyMonitor {
	public static final int FLASH_TICKS = 10;
	private float lastHealth = Float.NaN;
	private int flashTicks;

	public void observe(float healthWithAbsorption) {
		if (flashTicks > 0) flashTicks--;
		if (!Float.isNaN(lastHealth) && healthWithAbsorption < lastHealth) flashTicks = FLASH_TICKS;
		lastHealth = healthWithAbsorption;
	}

	public boolean flashing() {
		return flashTicks > 0;
	}

	/** Alternates every two ticks, starting highlighted on the tick the damage lands. */
	public boolean highlighted() {
		return flashTicks > 0 && (flashTicks / 2) % 2 == 1;
	}

	public void reset() {
		lastHealth = Float.NaN;
		flashTicks = 0;
	}
}
