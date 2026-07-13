package dev.agaminggod.arenaagents.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.bridge.BridgeEventSink;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.client.perception.ObservationCollector;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

public final class ArenaAgentsClient implements ClientModInitializer {
	private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
	private static final String EVENT_OBSERVATION = "observation";

	private BridgeServer bridgeServer;
	private ProtocolCodec protocolCodec;
	private ObservationCollector observationCollector;

	@Override
	public void onInitializeClient() {
		AgentConfig config = loadConfig();
		if (!config.enabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		protocolCodec = new ProtocolCodec();
		observationCollector = new ObservationCollector(
				minecraft,
				config.observationRadius(),
				Observation.ActionStatus::none,
				() -> null
		);
		bridgeServer = new BridgeServer(
				config,
				protocolCodec,
				minecraft::execute,
				new ClientBridgeEventSink()
		);
		bridgeServer.start();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bridgeServer.close());
	}

	private void publishObservation() {
		Observation observation = observationCollector.collect();
		JsonObject payload = JsonParser.parseString(protocolCodec.encode(observation)).getAsJsonObject();
		payload.remove(FIELD_PROTOCOL_VERSION);
		bridgeServer.sendEvent(EVENT_OBSERVATION, payload);
	}

	private final class ClientBridgeEventSink implements BridgeEventSink {
		@Override
		public void onActionCommand(ActionCommand command) {
			// Task 5 owns action execution; Task 4 only preserves the callback boundary.
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
