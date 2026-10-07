package dev.agaminggod.arenaagents.server.runtime.controller;

/** Test access to ServerFlightController's package-private outcome rule. */
public final class ServerFlightControllerAccess {
	private ServerFlightControllerAccess() {
	}

	public static ServerController.TickResult outcome(boolean stillFlying) {
		return ServerFlightController.outcome(stillFlying);
	}
}
