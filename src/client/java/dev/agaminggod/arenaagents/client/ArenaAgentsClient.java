package dev.agaminggod.arenaagents.client;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.control.DesktopAwtInitialization;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
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
		PovClient.register();
		OperatorInputSender.register();
		// Mirrored container screens read the same stand-in player the HUD renders from.
		PovScreens.setProxyInventory(() -> {
			var proxy = PovHudProxy.current();
			return proxy == null ? null : proxy.getInventory();
		});
	}
}
