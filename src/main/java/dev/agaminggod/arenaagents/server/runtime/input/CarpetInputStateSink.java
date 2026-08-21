package dev.agaminggod.arenaagents.server.runtime.input;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

public final class CarpetInputStateSink implements InputStateSink {
	private final CodexAgentManager manager;

	public CarpetInputStateSink(CodexAgentManager manager) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
	}

	@Override
	public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) return;
		EntityPlayerActionPack actions = OfflineAgentPlayers.actions(player);
		boolean resetActions = previous != null && (
				(previous.jump() && !state.jump())
						|| (previous.attack() && !state.attack())
						|| (previous.use() && !state.use())
		);
		if (resetActions) actions.stopAll();
		actions.look(state.yaw(), state.pitch())
				.setForward(state.forward())
				.setStrafing(state.strafe())
				.setSneaking(state.sneak())
				.setSprinting(state.sprint());
		actions.setSlot(state.selectedSlot() + 1);
		if (state.jump() && (resetActions || previous == null || !previous.jump())) {
			actions.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
		}
		if (state.attack() && (resetActions || previous == null || !previous.attack())) {
			actions.start(EntityPlayerActionPack.ActionType.ATTACK, EntityPlayerActionPack.Action.continuous());
		}
		if (state.use() && (resetActions || previous == null || !previous.use())) {
			actions.start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.continuous());
		}
	}

	@Override
	public void clear(AgentId agentId, AgentInputState previous) {
		manager.findAgentPlayer(agentId).ifPresent(player -> {
			OfflineAgentPlayers.actions(player).stopAll();
			player.stopUsingItem();
		});
	}
}
