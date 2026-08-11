package dev.agaminggod.arenaagents.server.runtime;

public final class BlockPlacementPostconditionVerification {
	private BlockPlacementPostconditionVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " block placement postcondition assertions");
	}

	public static int verify() {
		assertEquals(
				BlockPlacementPostcondition.Decision.SUCCEEDED,
				BlockPlacementPostcondition.evaluate(
						"minecraft:air",
						"minecraft:oak_log",
						"minecraft:oak_log",
						false
				),
				"expected placed block succeeds"
		);
		assertEquals(
				BlockPlacementPostcondition.Decision.WAITING,
				BlockPlacementPostcondition.evaluate(
						"minecraft:air",
						"minecraft:air",
						"minecraft:oak_log",
						false
				),
				"unchanged world waits for the server action"
		);
		assertEquals(
				BlockPlacementPostcondition.Decision.CONFLICT,
				BlockPlacementPostcondition.evaluate(
						"minecraft:air",
						"minecraft:stone",
						"minecraft:oak_log",
						false
				),
				"unexpected world mutation is not reported as success"
		);
		assertEquals(
				BlockPlacementPostcondition.Decision.TIMED_OUT,
				BlockPlacementPostcondition.evaluate(
						"minecraft:air",
						"minecraft:air",
						"minecraft:oak_log",
						true
				),
				"unchanged placement eventually times out"
		);
		assertEquals(
				BlockPlacementPostcondition.Decision.CONFLICT,
				BlockPlacementPostcondition.evaluate(
						"minecraft:oak_log",
						"minecraft:oak_log",
						"minecraft:oak_log",
						false
				),
				"an already-present target is not credited to this action"
		);
		return 5;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
