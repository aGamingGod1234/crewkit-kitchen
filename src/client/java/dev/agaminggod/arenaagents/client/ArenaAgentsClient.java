package dev.agaminggod.arenaagents.client;

import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

public final class ArenaAgentsClient implements ClientModInitializer {
	private BridgeServer bridgeServer;

	@Override
	public void onInitializeClient() {
		AgentConfig config = loadConfig();
		if (!config.enabled()) {
			return;
		}

		bridgeServer = new BridgeServer(
				config,
				new ProtocolCodec(),
				Minecraft.getInstance()::execute,
				command -> { }
		);
		bridgeServer.start();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bridgeServer.close());
	}

	private static AgentConfig loadConfig() {
		try {
			return AgentConfigLoader.loadOrCreate();
		} catch (IOException exception) {
			throw new IllegalStateException("Could not load Arena Agents client config", exception);
		}
	}
}
