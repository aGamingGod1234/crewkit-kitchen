package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;

import java.util.HashSet;
import java.util.Set;

public final class ServerPathPlannerVerification {
	private ServerPathPlannerVerification() {
	}

	public static int verify() {
		ServerPathPlanner planner = new ServerPathPlanner();
		Set<GridPosition> blocked = new HashSet<>();
		blocked.add(new GridPosition(1, 64, 0));

		WalkabilityView view = position -> {
			if (blocked.contains(position)) {
				return WalkabilityView.Cell.BLOCKED;
			}
			if (position.y() == 63) {
				return WalkabilityView.Cell.SAFE_SUPPORT;
			}
			return position.y() >= 64 && position.y() <= 65
					? WalkabilityView.Cell.CLEAR
					: WalkabilityView.Cell.BLOCKED;
		};

		PathPlan first = planner.findPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(2, 64, 0)
		);
		PathPlan second = planner.findPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(2, 64, 0)
		);

		assertEquals(PathOutcome.FOUND, first.outcome(), "server planner finds a bounded route");
		assertEquals(first.nodes(), second.nodes(), "server planning is deterministic");
		assertTrue(first.nodes().stream().noneMatch(node -> blocked.contains(node.position())),
				"server plan does not cross blocked cells");
		return 3;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
