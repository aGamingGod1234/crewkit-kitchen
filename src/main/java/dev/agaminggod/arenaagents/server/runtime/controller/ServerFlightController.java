package dev.agaminggod.arenaagents.server.runtime.controller;

import net.minecraft.server.level.ServerPlayer;

/**
 * set_flight(true). Like vanilla's double-tap, flight starts with a jump when the body is on the ground; if the body
 * cannot rise (a ceiling right above it) it is still on the ground after the next tick, landing ends flight there,
 * and the action reports that instead of a success that already undid itself.
 */
public final class ServerFlightController implements ServerController {
	private boolean started;

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		if (!started) {
			started = true;
			player.getAbilities().flying = true;
			if (player.onGround()) player.jumpFromGround();
			player.onUpdateAbilities();
			return TickResult.running(0.5D);
		}
		return outcome(player.getAbilities().flying);
	}

	static TickResult outcome(boolean stillFlying) {
		return stillFlying
				? TickResult.succeeded("FLIGHT_STARTED", "Flying; landing ends flight like vanilla")
				: TickResult.failed("FLIGHT_ENDED_ON_LANDING",
						"Flight ended on landing: the body could not leave the ground, for example under a low ceiling", 0.5D);
	}
}
