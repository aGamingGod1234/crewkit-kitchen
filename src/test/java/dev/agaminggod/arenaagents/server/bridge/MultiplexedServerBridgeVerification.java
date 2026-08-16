package dev.agaminggod.arenaagents.server.bridge;

import java.util.ArrayList;
import java.util.List;

public final class MultiplexedServerBridgeVerification {
	private MultiplexedServerBridgeVerification() {
	}

	public static int verify() {
		List<String> events = new ArrayList<>();
		assertThrows(IllegalStateException.class, () -> MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> { throw new IllegalStateException("publication failed"); },
				() -> events.add("action"),
				() -> events.add("state")
		), "failed respawn publication emits no scenario success or state");
		assertTrue(events.isEmpty(), "failed respawn publication leaves scenario records untouched");
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> events.add("publication"),
				() -> events.add("action"),
				() -> events.add("state")
		);
		assertEquals(List.of("publication", "action", "state"), events,
				"respawn scenario success and PAUSED/IDLE state follow committed paired publication");
		List<String> committed = new ArrayList<>();
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> committed.add("paired-messages-and-commit"),
				() -> { committed.add("action-attempted"); throw new IllegalStateException("telemetry unavailable"); },
				() -> committed.add("state-after-telemetry-failure")
		);
		assertEquals(List.of("paired-messages-and-commit", "action-attempted", "state-after-telemetry-failure"), committed,
				"scenario callback failure cannot escape or roll back committed respawn publication");
		return 4;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + type.getSimpleName());
	}
}
