package dev.agaminggod.arenaagents.server.runtime.controller;

import net.minecraft.server.level.ServerPlayer;

public interface ServerController {
	TickResult tick(ServerPlayer player, long nowEpochMs);

	default void cancel(ServerPlayer player) {
	}

	enum State {
		RUNNING,
		SUCCEEDED,
		FAILED,
		/** The requested duration ran out before the controller's goal; reported as a TIMED_OUT action. */
		TIMED_OUT
	}

	record TickResult(State state, String reasonCode, String message, double progress) {
		public TickResult {
			if (state == null || reasonCode == null || message == null) {
				throw new IllegalArgumentException("controller result fields must not be null");
			}
			if (!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D) {
				throw new IllegalArgumentException("controller progress must be in [0, 1]");
			}
		}

		public static TickResult running(double progress) {
			return new TickResult(State.RUNNING, "ACTION_RUNNING", "Action is running", progress);
		}

		public static TickResult succeeded(String reasonCode, String message) {
			return new TickResult(State.SUCCEEDED, reasonCode, message, 1.0D);
		}

		public static TickResult failed(String reasonCode, String message, double progress) {
			return new TickResult(State.FAILED, reasonCode, message, progress);
		}

		public static TickResult timedOut(String reasonCode, String message, double progress) {
			return new TickResult(State.TIMED_OUT, reasonCode, message, progress);
		}
	}
}
