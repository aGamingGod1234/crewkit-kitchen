package dev.agaminggod.arenaagents.client.pov;

/** Watches the operator's own body during a takeover so a hit can be announced on the action bar. */
public final class PovBodyMonitor {
	public static final int FLASH_TICKS = 10;
	private float lastHealth = Float.NaN;
	private int flashTicks;

	/** Returns true on the tick damage lands; the first reading after a reset is only a baseline. */
	public boolean observe(float healthWithAbsorption) {
		if (flashTicks > 0) flashTicks--;
		boolean damaged = !Float.isNaN(lastHealth) && healthWithAbsorption < lastHealth;
		if (damaged) flashTicks = FLASH_TICKS;
		lastHealth = healthWithAbsorption;
		return damaged;
	}

	public boolean flashing() {
		return flashTicks > 0;
	}

	public void reset() {
		lastHealth = Float.NaN;
		flashTicks = 0;
	}
}
