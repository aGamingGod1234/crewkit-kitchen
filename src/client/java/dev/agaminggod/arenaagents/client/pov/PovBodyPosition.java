package dev.agaminggod.arenaagents.client.pov;

import java.util.ArrayDeque;

/**
 * Where the takeover camera puts the agent's body, fed by the per-tick pose stream instead of vanilla entity
 * tracking (sent every second tick, before physics, then lerped over three client ticks). One position is
 * consumed per client tick. Normally nothing is left over, so the view is at most one tick behind the server;
 * if arrivals bunch up around the client's tick boundary, one position stays buffered so motion stays even
 * instead of stalling and double-stepping. Plain state so it can be verified without Minecraft.
 */
public final class PovBodyPosition {
	/** A burst beyond this (a server hiccup) is skipped rather than replayed late. */
	static final int MAX_PENDING = 4;

	public record Position(double x, double y, double z) {
	}

	private final ArrayDeque<Position> pending = new ArrayDeque<>();

	public void accept(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
		pending.addLast(new Position(x, y, z));
		while (pending.size() > MAX_PENDING) pending.pollFirst();
	}

	/** The position to show from this client tick on, or null to keep the current one (nothing new arrived). */
	public Position next() {
		Position next = pending.pollFirst();
		// Keep at most one in reserve: more means the view fell behind, so catch up now.
		while (pending.size() > 1) next = pending.pollFirst();
		return next;
	}

	public int pending() {
		return pending.size();
	}

	public void reset() {
		pending.clear();
	}
}
