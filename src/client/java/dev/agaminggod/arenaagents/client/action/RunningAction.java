package dev.agaminggod.arenaagents.client.action;

public interface RunningAction {
	long timeoutMs();

	ActionUpdate tick(ActionContext context, long elapsedMs);

	default void cancel(ActionContext context) {
	}
}
