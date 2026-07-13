package dev.agaminggod.arenaagents.client.network;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.action.ClientActionRuntime;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.server.GoalPayload;
import java.util.Objects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GoalReceiver {
	public static final String EVENT_GOAL = "goal_event";
	public static final String GOAL_REPLACED_REASON = "goal_replaced";
	public static final String SERVER_STOP_REASON = "server_stop";

	private static final Logger LOGGER = LoggerFactory.getLogger(GoalReceiver.class);

	private final ActiveActionStopper actionStopper;
	private final GoalEventPublisher eventPublisher;

	GoalReceiver(ActiveActionStopper actionStopper, GoalEventPublisher eventPublisher) {
		this.actionStopper = Objects.requireNonNull(actionStopper, "actionStopper must not be null");
		this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
	}

	public static void register(ClientActionRuntime actionRuntime, BridgeServer bridgeServer) {
		Objects.requireNonNull(actionRuntime, "actionRuntime must not be null");
		Objects.requireNonNull(bridgeServer, "bridgeServer must not be null");
		GoalReceiver receiver = new GoalReceiver(actionRuntime::stop, bridgeServer::sendEvent);
		boolean registered = ClientPlayNetworking.registerGlobalReceiver(
				GoalPayload.TYPE,
				(payload, context) -> {
					try {
						context.client().execute(() -> receiver.receive(payload));
					} catch (RuntimeException exception) {
						LOGGER.warn("Could not dispatch Arena Agents goal payload to the client thread", exception);
					}
				}
		);
		if (!registered) {
			throw new IllegalStateException("Arena Agents goal payload receiver is already registered");
		}
	}

	void receive(GoalPayload payload) {
		Objects.requireNonNull(payload, "payload must not be null");
		String stopReason = switch (payload.operation()) {
			case SET -> GOAL_REPLACED_REASON;
			case STOP -> SERVER_STOP_REASON;
		};
		try {
			actionStopper.stop(stopReason);
		} catch (RuntimeException exception) {
			LOGGER.error("Could not cancel and release the active Arena Agents action", exception);
		}

		JsonObject event = new JsonObject();
		event.addProperty("operation", payload.operation().wireName());
		event.addProperty("goal", payload.goal());
		try {
			eventPublisher.publish(EVENT_GOAL, event);
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish Arena Agents goal event to the coordinator bridge", exception);
		}
	}

	@FunctionalInterface
	interface ActiveActionStopper {
		void stop(String reason);
	}

	@FunctionalInterface
	interface GoalEventPublisher {
		void publish(String type, JsonObject payload);
	}
}
