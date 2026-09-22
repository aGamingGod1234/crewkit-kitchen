package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ItemPickupProgressVerification {
	private ItemPickupProgressVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " item pickup assertions");
	}

	public static int verify() {
		assertTrue(ServerItemPickupController.remainsInDimension(Level.OVERWORLD, Level.OVERWORLD),
				"item pickup remains valid in its starting dimension");
		assertTrue(!ServerItemPickupController.remainsInDimension(Level.OVERWORLD, Level.NETHER),
				"item pickup rejects an item coordinate after the player changes dimension");
		assertEquals(7_500L, ServerItemPickupController.remainingNavigationTimeout(10_000L, 2_500L),
				"item pickup passes its remaining action timeout to navigation");
		assertEquals(1L, ServerItemPickupController.remainingNavigationTimeout(10_000L, Long.MAX_VALUE),
				"item pickup bounds remaining navigation timeout after elapsed-time saturation");
		assertEquals(ItemPickupProgress.Decision.RUNNING,
				ItemPickupProgress.evaluate(2, 2, true, false), "approaching a live item keeps moving");
		assertEquals(ItemPickupProgress.Decision.SUCCEEDED,
				ItemPickupProgress.evaluate(2, 3, false, false), "only an observed inventory increase succeeds");
		assertEquals(ItemPickupProgress.Decision.ITEM_UNAVAILABLE,
				ItemPickupProgress.evaluate(2, 2, false, false), "a vanished item never becomes synthetic loot");
		assertEquals(ItemPickupProgress.Decision.TIMED_OUT,
				ItemPickupProgress.evaluate(2, 2, true, true), "a live unreachable item times out");
		List<String> lifecycle = new ArrayList<>();
		Object previous = new Object();
		Object replacement = new Object();
		assertEquals(replacement, ServerItemPickupController.replaceNavigation(
				previous,
				ignored -> lifecycle.add("released"),
				() -> {
					lifecycle.add("created");
					return replacement;
				}
		), "moving-item replanning installs the replacement controller");
		assertEquals(List.of("released", "created"), lifecycle,
				"moving-item replanning releases the old navigation lease before replacement");
		var unsupported = ServerController.TickResult.failed("NO_STANDABLE_PATH", "No standing position", 0.0D);
		assertTrue(ServerItemPickupController.awaitingDropLanding(unsupported, false, false, false),
				"an airborne mining drop can land before its pickup deadline");
		assertTrue(!ServerItemPickupController.awaitingDropLanding(unsupported, true, false, false),
				"a grounded unreachable drop remains a real navigation failure");
		assertTrue(!ServerItemPickupController.awaitingDropLanding(unsupported, false, true, false),
				"a gravity-free target cannot recover by landing");
		assertTrue(!ServerItemPickupController.awaitingDropLanding(unsupported, false, false, true),
				"a floating target must not be treated as falling to ground");
		assertTrue(!ServerItemPickupController.awaitingDropLanding(
				ServerController.TickResult.failed("NO_PATH", "Blocked", 0.0D), false, false, false),
				"other route failures remain visible to the agent");
		AABB standing = ServerItemPickupController.pickupStandingRegion(
				new AABB(0.2D, 64.0D, 0.2D, 0.8D, 65.8D, 0.8D), new Vec3(0.5D, 64.0D, 0.5D),
				new AABB(3.375D, 65.0D, 0.375D, 3.625D, 65.25D, 0.625D));
		assertTrue(standing.contains(2.5D, 64.0D, 0.5D), "a player beside a drop under a canopy can collect it");
		assertTrue(!standing.contains(0.5D, 64.0D, 0.5D), "distant feet positions are outside vanilla pickup reach");
		assertTrue(!standing.contains(3.5D, 61.0D, 0.5D), "horizontal proximity alone does not reach an elevated drop");
		return 18;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
	}
}
