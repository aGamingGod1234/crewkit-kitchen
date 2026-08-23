package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import net.minecraft.world.phys.Vec3;

public final class NavigationProgressVerification {
	private NavigationProgressVerification() {
	}

	public static int verify() {
		new ServerNavigationController(new net.minecraft.world.phys.Vec3(1.0D, 64.0D, 1.0D), 0.01D, false, 0L, 1_000L);
		WaypointProgress progress = new WaypointProgress(10.0D, 1_000L, 4_000L, 3);

		WaypointProgress.Update advanced = progress.observe(8.0D, true, 1_100L);
		assertTrue(advanced.advanceWaypoint(), "waypoint tolerance advances the plan");
		assertEquals(WaypointProgress.Decision.CONTINUE, advanced.decision(), "advance keeps navigation running");
		assertBounded(advanced.progress());

		WaypointProgress.Update stalled = progress.observe(8.0D, false, 5_200L);
		assertEquals(WaypointProgress.Decision.REPLAN, stalled.decision(), "four seconds without progress replans");
		progress.replanned(8.0D, 5_200L);
		progress.observe(8.0D, false, 9_300L);
		progress.replanned(8.0D, 9_300L);
		progress.observe(8.0D, false, 13_400L);
		progress.replanned(8.0D, 13_400L);
		WaypointProgress.Update exhausted = progress.observe(8.0D, false, 17_500L);
		assertEquals(WaypointProgress.Decision.FAIL, exhausted.decision(), "three replans exhaust recovery");
		assertBounded(exhausted.progress());

		WaypointProgress detour = new WaypointProgress(10.0D, 1_000L, 4_000L, 3);
		WaypointProgress.Update waypointReached = detour.observe(10.5D, true, 5_200L);
		assertEquals(WaypointProgress.Decision.CONTINUE, waypointReached.decision(),
				"reaching a detour waypoint resets the stall clock even when final distance increases");
		assertEquals(WaypointProgress.Decision.CONTINUE, detour.observe(10.5D, false, 9_199L).decision(),
				"detour progress retains the full stall recovery window");
		assertEquals(WaypointProgress.Decision.REPLAN, detour.observe(10.5D, false, 9_200L).decision(),
				"a reached detour replans only after its new stall deadline");

		assertTrue(ServerNavigationController.satisfiesDestinationTolerance(1.0D, 1.0D),
				"the requested tolerance includes its exact boundary");
		assertTrue(!ServerNavigationController.satisfiesDestinationTolerance(1.01D, 1.0D),
				"path exhaustion outside the requested tolerance must replan");

		Vec3 exactDestination = new Vec3(5.1D, 64.0D, 7.9D);
		ServerNavigationController exactNavigation = new ServerNavigationController(
				exactDestination, 0.2D, false, 0L, 1_000L);
		PathNode finalNode = new PathNode(new GridPosition(5, 64, 7), TraversalType.WALK);
		Vec3 finalTarget = exactNavigation.targetFor(finalNode, true);
		assertEquals(5.1D, finalTarget.x, "the final waypoint retains the requested x coordinate");
		assertEquals(7.9D, finalTarget.z, "the final waypoint retains the requested z coordinate");
		assertTrue(!exactNavigation.reachedTarget(new Vec3(5.5D, 64.0D, 7.5D), finalNode, true),
				"the block center does not finish a precise destination outside tolerance");
		assertTrue(exactNavigation.reachedTarget(new Vec3(5.2D, 64.0D, 7.9D), finalNode, true),
				"the exact final target finishes inside the requested tolerance");
		Vec3 adjustedTarget = exactNavigation.targetFor(
				new PathNode(new GridPosition(4, 64, 7), TraversalType.WALK), true);
		assertEquals(4.5D, adjustedTarget.x, "an adjusted safe goal retains its block-center x coordinate");
		assertEquals(7.5D, adjustedTarget.z, "an adjusted safe goal retains its block-center z coordinate");
		return 17;
	}

	private static void assertBounded(double value) {
		if (value < 0.0D || value > 1.0D || !Double.isFinite(value)) {
			throw new AssertionError("progress must remain in [0, 1]: " + value);
		}
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
