package dev.agaminggod.arenaagents.server.runtime.controller;

public final class CombatNavigationFailureVerification {
	private CombatNavigationFailureVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " combat navigation failure assertions");
	}

	public static int verify() {
		ServerController.TickResult navigationFailure = ServerController.TickResult.failed(
				"PATH_BLOCKED",
				"Navigation could not recover from repeated stalls",
				0.25D
		);

		ServerController.TickResult combatResult = ServerCombatController.resolveNavigationTick(
				navigationFailure,
				0.75D
		);

		assertEquals(ServerController.State.FAILED, combatResult.state(), "nested navigation failure state");
		assertEquals("PATH_BLOCKED", combatResult.reasonCode(), "nested navigation failure reason");
		assertEquals(navigationFailure.message(), combatResult.message(), "nested navigation failure message");
		assertEquals(0.25D, combatResult.progress(), "nested navigation failure progress");

		net.minecraft.world.phys.Vec3 planned = new net.minecraft.world.phys.Vec3(0.0D, 64.0D, 0.0D);
		assertTrue(!ServerCombatController.shouldReplanPursuit(
				true, planned, new net.minecraft.world.phys.Vec3(0.9D, 64.0D, 0.0D), 1_000L, 2_000L),
				"sub-block target jitter reuses the current bounded path");
		assertTrue(!ServerCombatController.shouldReplanPursuit(
				true, planned, new net.minecraft.world.phys.Vec3(1.1D, 64.0D, 0.0D), 1_000L, 1_249L),
				"ordinary pursuit drift observes the bounded replan interval");
		assertTrue(ServerCombatController.shouldReplanPursuit(
				true, planned, new net.minecraft.world.phys.Vec3(1.1D, 64.0D, 0.0D), 1_000L, 1_250L),
				"material target movement replans after hysteresis expires");
		assertTrue(ServerCombatController.shouldReplanPursuit(
				true, planned, new net.minecraft.world.phys.Vec3(4.0D, 64.0D, 0.0D), 1_000L, 1_001L),
				"large target displacement replans immediately");
		return 8;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
