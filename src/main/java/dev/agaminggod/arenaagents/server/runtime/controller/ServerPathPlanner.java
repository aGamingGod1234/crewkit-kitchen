package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;

import java.util.Objects;

/**
 * Common-server boundary for the deterministic, bounded local pathfinder.
 */
public final class ServerPathPlanner {
	private final LocalPathfinder pathfinder;

	public ServerPathPlanner() {
		this(new LocalPathfinder());
	}

	ServerPathPlanner(LocalPathfinder pathfinder) {
		this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder must not be null");
	}

	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination
	) {
		return pathfinder.findPath(view, start, destination);
	}
}
