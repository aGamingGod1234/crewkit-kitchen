package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import net.minecraft.server.level.ServerPlayer;

/**
 * The input lease shared by fight_target and flee_from. COMBAT sits above navigation (100) and below
 * interaction (300); an operator takeover (1000) always wins arbitration without cancelling the action.
 */
final class CombatInputLease {
	static final int PRIORITY = 200;

	private LeasedServerInputController controller;
	private InputLease lease;

	void apply(ServerPlayer player, AgentInputState state) {
		if (lease == null) {
			controller = AgentInputRuntime.controller(player);
			lease = controller.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.COMBAT, PRIORITY);
		}
		controller.apply(lease, state);
	}

	void release() {
		if (lease == null) return;
		try {
			controller.release(lease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		lease = null;
	}
}
