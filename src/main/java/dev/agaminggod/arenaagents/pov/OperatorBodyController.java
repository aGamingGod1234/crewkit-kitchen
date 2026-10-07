package dev.agaminggod.arenaagents.pov;

import net.minecraft.server.level.ServerPlayer;

/**
 * Drives one agent's body from an operator's takeover input. One instance serves one takeover session and is only
 * used on the server thread.
 */
public interface OperatorBodyController {
	/**
	 * Acquires exclusive control of the agent's body (the operator input lease) so model-driven input loses
	 * arbitration. {@link #active()} reports whether control was actually acquired.
	 */
	void begin(ServerPlayer operator);

	/** Records continuous input to apply on the next server tick; a newer frame replaces an older one. */
	void applyFrame(OperatorInputPayload frame);

	/** Performs a one-shot action against the agent immediately. */
	void applyAction(ServerPlayer operator, OperatorActionPayload action);

	/** Relays text typed into a vanilla screen (anvil name, sign, book) to the agent's body. */
	default void applyText(OperatorTextPayload text) {
	}

	/** Called every server tick while active; re-acquires leases that lifecycle events cleared and applies input. */
	void tick();

	/** Releases every lease and leaves the body neutral (no movement, no held keys). Safe to call more than once. */
	void end();

	boolean active();

	/** Sequence of the last input frame applied to the body, 0 before any; echoed in poses for latency checks. */
	default int lastInputSequence() {
		return 0;
	}
}
