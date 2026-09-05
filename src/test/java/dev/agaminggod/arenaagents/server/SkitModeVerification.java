package dev.agaminggod.arenaagents.server;

/** Focused checks for the opt-in skit timeline's bounded, immutable domain values. */
public final class SkitModeVerification {
	private SkitModeVerification() {
	}

	public static int verify() {
		SkitPlacement pose = new SkitPlacement("minecraft:overworld", 1.5D, 70.0D, -2.0D, 180.0F, -20.0F);
		SkitScript script = new SkitScript("takeoff", "ChatGPT", java.util.List.of())
				.append(new SkitStep(0, pose))
				.append(new SkitStep(20, new SkitPlacement("minecraft:overworld", 4.5D, 75.0D, -2.0D, 180.0F, -10.0F)));
		assertEquals(2, script.steps().size(), "timeline append preserves order");
		assertThrows(() -> new SkitPlacement("minecraft:overworld", 0, 0, 0, 0, 91), "pitch is bounded");
		assertThrows(() -> new SkitStep(-1, pose), "negative delays are rejected");
		assertThrows(() -> new SkitScript("bad name", "agent", java.util.List.of()), "script names are command-safe");
		assertEquals(2, new SkitStep(0, pose, java.util.List.of(SkitAction.move(20), SkitAction.swing())).actions().size(),
				"action steps preserve order");
		assertThrows(() -> SkitAction.move(0), "move requires a duration");
		assertThrows(() -> SkitAction.equip("diamond_sword"), "equip requires a namespaced item id");
		return 7;
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
