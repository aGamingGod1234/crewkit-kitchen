package dev.agaminggod.arenaagents.client;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.action.ActionEventPublisher;
import dev.agaminggod.arenaagents.client.action.ClientActionRuntime;
import dev.agaminggod.arenaagents.client.action.MinecraftActionContext;
import dev.agaminggod.arenaagents.client.bridge.BridgeEventSink;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.client.network.GoalReceiver;
import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.client.perception.ObservationCollector;
import dev.agaminggod.arenaagents.client.perception.ObservationWireBudget;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArenaAgentsClient implements ClientModInitializer {
	private static final String EVENT_OBSERVATION = "observation";
	private static final String COORDINATOR_CANCEL_REASON = "coordinator_cancelled";
	private static final String CLIENT_STOPPING_REASON = "client_stopping";
	private static final Logger LOGGER = LoggerFactory.getLogger(ArenaAgentsClient.class);

	private BridgeServer bridgeServer;
	private ProtocolCodec protocolCodec;
	private ObservationCollector observationCollector;
	private ClientActionRuntime actionRuntime;

	@Override
	public void onInitializeClient() {
		AgentConfig config = loadConfig();
		if (!config.enabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		protocolCodec = new ProtocolCodec();
		bridgeServer = new BridgeServer(
				config,
				protocolCodec,
				minecraft::execute,
				new ClientBridgeEventSink()
		);
		ActionEventPublisher actionPublisher = new ActionEventPublisher(
				bridgeServer,
				exception -> LOGGER.warn("Could not publish an Arena Agents action event", exception)
		);
		actionRuntime = new ClientActionRuntime(new MinecraftActionContext(minecraft), actionPublisher);
		GoalReceiver.register(actionRuntime, bridgeServer);
		observationCollector = new ObservationCollector(
				minecraft,
				config.observationRadius(),
				actionRuntime::currentStatus,
				actionRuntime::lastResult
		);
		bridgeServer.start();
		ClientTickEvents.END_CLIENT_TICK.register(client -> actionRuntime.tick());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopClient());
	}

	private void stopClient() {
		actionRuntime.stop(CLIENT_STOPPING_REASON);
		RuntimeException lifecycleFailure = actionRuntime.lastLifecycleFailure();
		if (lifecycleFailure != null) {
			LOGGER.warn("Could not release all Arena Agents resources during client shutdown", lifecycleFailure);
		}
		bridgeServer.close();
	}

	private void publishObservation() {
		Observation observation = observationCollector.collect();
		ObservationWireBudget.FittedObservation fitted = ObservationWireBudget.fit(
				observation,
				protocolCodec,
				bridgeServer,
				EVENT_OBSERVATION
		);
		JsonObject payload = fitted.payload();
		bridgeServer.sendEvent(EVENT_OBSERVATION, payload);
	}

	private final class ClientBridgeEventSink implements BridgeEventSink {
		@Override
		public void onActionCommand(ActionCommand command) {
			actionRuntime.onActionCommand(command);
		}

		@Override
		public void onCancelAction(String commandId) {
			actionRuntime.onCancelAction(commandId, COORDINATOR_CANCEL_REASON);
		}

		@Override
		public void onObservationRequested() {
			publishObservation();
		}
	}

	private static AgentConfig loadConfig() {
		try {
			return AgentConfigLoader.loadOrCreate();
		} catch (IOException exception) {
			throw new IllegalStateException("Could not load Arena Agents client config", exception);
		}
	}
}
