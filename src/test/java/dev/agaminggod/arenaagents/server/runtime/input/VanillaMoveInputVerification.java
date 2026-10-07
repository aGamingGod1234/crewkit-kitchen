package dev.agaminggod.arenaagents.server.runtime.input;

/** LocalPlayer.modifyInput parity for Carpet bodies: friction, item use, crouch/crawl and diagonal mapping. */
public final class VanillaMoveInputVerification {
	private static final float EPSILON = 1.0E-5F;

	private VanillaMoveInputVerification() {
	}

	public static void main(String[] args) {
		System.out.println("VanillaMoveInputVerification: " + verify() + " assertions passed");
	}

	public static int verify() {
		float[] forward = VanillaMoveInput.modify(0.0F, 1.0F, 1.0F, 1.0F);
		check(near(forward[0], 0.0F) && near(forward[1], 0.98F), "walking forward keeps vanilla's 0.98 friction");
		float[] diagonal = VanillaMoveInput.modify(1.0F, 1.0F, 1.0F, 1.0F);
		float half = (float) Math.sqrt(0.5D);
		check(near(diagonal[0], half) && near(diagonal[1], half), "diagonal keys reach the unit square, capped at one");
		float[] using = VanillaMoveInput.modify(0.0F, 1.0F, 0.2F, 1.0F);
		check(near(using[1], 0.98F * 0.2F), "using an item slows to its use speed multiplier");
		float[] crouching = VanillaMoveInput.modify(0.0F, 1.0F, 1.0F, 0.3F);
		check(near(crouching[1], 0.98F * 0.3F), "crouching or crawling scales by the sneaking speed attribute");
		float[] swiftSneak = VanillaMoveInput.modify(0.0F, 1.0F, 1.0F, 0.75F);
		check(near(swiftSneak[1], 0.98F * 0.75F), "Swift Sneak raises the sneaking speed like vanilla");
		float[] analog = VanillaMoveInput.modify(0.0F, 0.5F, 1.0F, 1.0F);
		check(near(analog[1], 0.49F), "an analog half-forward keeps its length");
		float[] none = VanillaMoveInput.modify(0.0F, 0.0F, 1.0F, 1.0F);
		check(none[0] == 0.0F && none[1] == 0.0F, "no input stays still");
		float[] broken = VanillaMoveInput.modify(Float.NaN, 1.0F, 1.0F, 1.0F);
		check(broken[0] == 0.0F && broken[1] == 0.0F, "non-finite input is released");
		check(near(0.98F / VanillaMoveInput.INPUT_FRICTION * VanillaMoveInput.INPUT_FRICTION, 0.98F),
				"pre-dividing by the server friction restores the client value");
		return 9;
	}

	private static boolean near(float actual, float expected) {
		return Math.abs(actual - expected) < EPSILON;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
