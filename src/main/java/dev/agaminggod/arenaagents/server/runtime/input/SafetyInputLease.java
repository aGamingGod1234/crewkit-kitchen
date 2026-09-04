package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;

public final class SafetyInputLease implements AutoCloseable {
	private static final int SAFETY_PRIORITY = 1_000;

	private final LeasedServerInputController controller;
	private final AgentId agentId;
	private InputLease lease;
	private long expiresAtEpochMs;

	public SafetyInputLease(LeasedServerInputController controller, AgentId agentId) {
		this.controller = Objects.requireNonNull(controller, "controller must not be null");
		this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
	}

	public void activate(AgentInputState input, long nowEpochMs, long durationMs) {
		Objects.requireNonNull(input, "input must not be null");
		if (nowEpochMs < 0L || durationMs < 1L) throw new IllegalArgumentException("safety lease timing is invalid");
		if (lease == null) lease = controller.acquire(agentId, InputOwner.SYSTEM, SAFETY_PRIORITY);
		try {
			controller.apply(lease, input);
		} catch (LeasedServerInputController.StaleInputLeaseException staleLease) {
			lease = controller.acquire(agentId, InputOwner.SYSTEM, SAFETY_PRIORITY);
			controller.apply(lease, input);
		}
		expiresAtEpochMs = saturatingAdd(nowEpochMs, durationMs);
	}

	public boolean releaseExpired(long nowEpochMs) {
		if (nowEpochMs < 0L) throw new IllegalArgumentException("nowEpochMs must not be negative");
		if (lease == null || nowEpochMs < expiresAtEpochMs) return false;
		release();
		return true;
	}

	@Override
	public void close() {
		release();
	}

	private void release() {
		if (lease == null) return;
		try {
			controller.release(lease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
			// The agent-wide input controller was already cleared during removal or death.
		}
		lease = null;
		expiresAtEpochMs = 0L;
	}

	private static long saturatingAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}
}
