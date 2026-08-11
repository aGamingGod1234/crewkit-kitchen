package dev.agaminggod.arenaagents.client.gui.scenario;

import java.util.Objects;

public final class ScenarioLaunchRegistry {
	private static Handler handler;

	private ScenarioLaunchRegistry() {
	}

	public static synchronized void register(Handler nextHandler) {
		handler = Objects.requireNonNull(nextHandler, "nextHandler must not be null");
	}

	public static synchronized void clear() {
		handler = null;
	}

	public static synchronized boolean isAvailable() {
		return handler != null;
	}

	public static Result launch(ScenarioLaunchPlan plan) {
		Handler active;
		synchronized (ScenarioLaunchRegistry.class) {
			active = handler;
		}
		if (active == null) {
			return new Result(false, "Arena runtime is not connected");
		}
		return Objects.requireNonNull(
				active.launch(Objects.requireNonNull(plan, "plan must not be null")),
				"scenario launch handler returned null"
		);
	}

	@FunctionalInterface
	public interface Handler {
		Result launch(ScenarioLaunchPlan plan);
	}

	public record Result(boolean accepted, String message) {
		public Result {
			message = Objects.requireNonNullElse(message, "");
		}
	}
}
