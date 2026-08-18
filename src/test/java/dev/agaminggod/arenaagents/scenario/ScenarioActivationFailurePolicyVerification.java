package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioActivationFailurePolicy;

public final class ScenarioActivationFailurePolicyVerification {
	private ScenarioActivationFailurePolicyVerification() {
	}

	public static int verify() {
		assertTrue(ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("COORDINATOR_DISCONNECTED", "not authenticated")),
				"temporary coordinator disconnect waits without invalidating the arena build");
		assertTrue(ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("AUTOMATION_UNAVAILABLE", "waiting")),
				"automation startup waits without rebuilding the arena");
		assertTrue(!ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("INVALID_MODEL_PROFILE", "bad model")),
				"permanent roster errors are not retried forever");
		assertTrue(!ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new IllegalStateException("world failure")),
				"unrelated runtime failures remain actionable");
		assertTrue(ScenarioActivationFailurePolicy.mayReplacePendingLaunch(false, true, false),
				"a completed arena waiting for automation never locks out a replacement build");
		assertTrue(!ScenarioActivationFailurePolicy.mayReplacePendingLaunch(true, true, false),
				"an arena still mutating the world cannot be replaced concurrently");
		assertTrue(!ScenarioActivationFailurePolicy.mayReplacePendingLaunch(false, true, true),
				"a live scenario cannot be replaced through pending-launch cleanup");
		return 7;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
