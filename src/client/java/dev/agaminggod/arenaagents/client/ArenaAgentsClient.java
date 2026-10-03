package dev.agaminggod.arenaagents.client;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.control.DesktopAwtInitialization;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderers;
import net.fabricmc.api.ClientModInitializer;

public final class ArenaAgentsClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		DesktopAwtInitialization.initialize();
		dev.agaminggod.arenaagents.client.control.PlanItemIcons.register();
		CodexAgentRenderers.register();
		CameraDirectorClient.register();
		AgentControlClient.register();
	}
}
