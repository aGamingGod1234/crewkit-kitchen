package dev.agaminggod.arenaagents.client.gui.scenario;

import java.util.Objects;
import java.util.Optional;

public final class ScenarioLaunchRegistry {
	private static Handler handler;
	private static ScenarioLaunchPlan lastAcceptedPlan;

	private ScenarioLaunchRegistry() {
	}

	public static synchronized void register(Handler nextHandler) {
		handler = Objects.requireNonNull(nextHandler, "nextHandler must not be null");
	}

	public static synchronized void clear() {
		handler = null;
		lastAcceptedPlan = null;
	}

	public static synchronized void clearRetainedPlan() {
		lastAcceptedPlan = null;
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
		Result result = Objects.requireNonNull(
				active.launch(Objects.requireNonNull(plan, "plan must not be null")),
				"scenario launch handler returned null"
		);
		if (result.accepted()) {
			synchronized (ScenarioLaunchRegistry.class) {
				lastAcceptedPlan = plan;
			}
		}
		return result;
	}

	public static synchronized Optional<ScenarioLaunchPlan> lastAcceptedPlan() {
		return Optional.ofNullable(lastAcceptedPlan);
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
