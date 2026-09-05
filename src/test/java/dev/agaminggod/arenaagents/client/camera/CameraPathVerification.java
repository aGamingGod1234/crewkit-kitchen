package dev.agaminggod.arenaagents.client.camera;

/** Focused checks for camera path bounds, replacement and smooth interpolation. */
public final class CameraPathVerification {
	private CameraPathVerification() {
	}

	public static int verify() {
		CameraPath path = new CameraPath("intro", java.util.List.of(
				new CameraKeyframe(0, 0.0D, 64.0D, 0.0D, 350.0F, 0.0F),
				new CameraKeyframe(20, 10.0D, 66.0D, 0.0D, 10.0F, 20.0F),
				new CameraKeyframe(40, 20.0D, 64.0D, 4.0D, 30.0F, 0.0F)
		));
		CameraPose middle = path.sample(10.0D);
		assertTrue(middle.x() > 0.0D && middle.x() < 10.0D, "position interpolation stays in the segment");
		assertTrue(Math.abs(middle.yaw()) > 350.0F || Math.abs(middle.yaw()) < 20.0F, "yaw takes the short turn across zero");
		assertEquals(20.0D, path.sample(100.0D).x(), "samples after the end hold the final frame");
		CameraPath replaced = path.append(new CameraKeyframe(40, 99.0D, 64.0D, 0.0D, 0.0F, 0.0F));
		assertEquals(99.0D, replaced.sample(40.0D).x(), "same-tick frame replaces the old frame");
		assertThrows(() -> new CameraPath("bad name", java.util.List.of(new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F))), "path names are command-safe");
		assertThrows(() -> new CameraPath("duplicate", java.util.List.of(
				new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F),
				new CameraKeyframe(0, 1.0D, 0.0D, 0.0D, 0.0F, 0.0F))), "duplicate frame times are rejected");
		return 5;
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(double expected, double actual, String label) {
		if (Math.abs(expected - actual) > 0.000001D) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
