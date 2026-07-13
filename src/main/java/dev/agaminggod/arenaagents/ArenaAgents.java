package dev.agaminggod.arenaagents;

import dev.agaminggod.arenaagents.server.ArenaAgentCommands;
import dev.agaminggod.arenaagents.server.GoalPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class ArenaAgents implements ModInitializer {
	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(GoalPayload.TYPE, GoalPayload.CODEC);
		ArenaAgentCommands.register();
	}
}
