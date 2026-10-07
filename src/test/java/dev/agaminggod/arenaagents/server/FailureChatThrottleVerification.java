package dev.agaminggod.arenaagents.server;

public final class FailureChatThrottleVerification {
	private FailureChatThrottleVerification() {
	}

	public static int verify() {
		FailureChatThrottle throttle = new FailureChatThrottle();
		String upper = "Mining a block needs attention: Cannot mine stone at y=17";
		String lower = "Mining a block needs attention: Cannot mine stone at y=16";
		check(throttle.allow("agent-a", upper, 0L), "the first failure line is shown");
		check(throttle.allow("agent-a", lower, 50L), "a different failure line is shown");
		check(!throttle.allow("agent-a", upper, 100L), "an alternating repeat inside the window is suppressed");
		check(!throttle.allow("agent-a", lower, 150L), "the other alternating repeat is suppressed too");
		check(throttle.allow("agent-b", upper, 200L), "the same line from another agent is still shown");
		check(throttle.allow("agent-a", upper, FailureChatThrottle.WINDOW_MILLIS), "the line returns once the window has passed");
		check(!AgentActivityPresentation.shouldAnnounceFailure("TARGET_AIR"),
				"mining a block that is already air is routine replanning evidence, not a red error");
		return 7;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
