package dev.agaminggod.arenaagents.client.network;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.GoalPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GoalReceiverVerification {
	private GoalReceiverVerification() {
	}

	public static int verify() {
		List<String> order = new ArrayList<>();
		List<JsonObject> events = new ArrayList<>();
		GoalReceiver receiver = new GoalReceiver(
				reason -> order.add("stop:" + reason),
				(type, payload) -> {
					order.add("event:" + type);
					events.add(payload);
				}
		);

		receiver.receive(GoalPayload.set("enter arena"));
		assertEquals(
				List.of("stop:" + GoalReceiver.GOAL_REPLACED_REASON, "event:" + GoalReceiver.EVENT_GOAL),
				order,
				"replacement goal cancels before publishing"
		);
		JsonObject goalEvent = events.getFirst();
		assertEquals(Set.of("operation", "goal"), goalEvent.keySet(), "set goal event has strict fields");
		assertEquals("set", goalEvent.get("operation").getAsString(), "set goal event operation");
		assertEquals("enter arena", goalEvent.get("goal").getAsString(), "set goal event text");

		order.clear();
		events.clear();
		receiver.receive(GoalPayload.stop());
		assertEquals(
				List.of("stop:" + GoalReceiver.SERVER_STOP_REASON, "event:" + GoalReceiver.EVENT_GOAL),
				order,
				"server stop cancels before publishing"
		);
		JsonObject event = events.getFirst();
		assertEquals(Set.of("operation", "goal"), event.keySet(), "goal event has strict fields");
		assertEquals("stop", event.get("operation").getAsString(), "stop event operation");
		assertEquals("", event.get("goal").getAsString(), "stop event has empty goal");
		return 8;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
