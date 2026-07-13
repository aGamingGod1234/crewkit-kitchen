package dev.agaminggod.arenaagents.client.action;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class MinecraftActionContextVerification {
	private MinecraftActionContextVerification() {
	}

	public static int verifyHelpers() {
		int assertions = 0;
		assertions += verifySafetyClassification();
		assertions += verifyRotationIsBounded();
		assertions += verifyDeterministicHotbarSelection();
		assertions += verifyReleaseAttemptsEveryResource();
		assertions += verifyEachKeyReleaseIsIndependent();
		return assertions;
	}

	private static int verifySafetyClassification() {
		assertEquals(
				SafetyState.DISCONNECTED,
				MinecraftActionContext.classifySafety(false, false, false, false, false, false),
				"missing connection is disconnected"
		);
		assertEquals(
				SafetyState.DISCONNECTED,
				MinecraftActionContext.classifySafety(true, false, true, true, true, false),
				"closed connection is disconnected"
		);
		assertEquals(
				SafetyState.WORLD_UNAVAILABLE,
				MinecraftActionContext.classifySafety(true, true, false, false, false, false),
				"missing world is unsafe"
		);
		assertEquals(
				SafetyState.PLAYER_UNAVAILABLE,
				MinecraftActionContext.classifySafety(true, true, true, false, false, false),
				"missing player is unsafe"
		);
		assertEquals(
				SafetyState.PLAYER_DEAD,
				MinecraftActionContext.classifySafety(true, true, true, true, false, false),
				"dead player is unsafe"
		);
		assertEquals(
				SafetyState.SCREEN_OPEN,
				MinecraftActionContext.classifySafety(true, true, true, true, true, true),
				"open screen is unsafe"
		);
		assertEquals(
				SafetyState.READY,
				MinecraftActionContext.classifySafety(true, true, true, true, true, false),
				"connected live player is ready"
		);
		return 7;
	}

	private static int verifyRotationIsBounded() {
		MinecraftActionContext.RotationStep first = MinecraftActionContext.rotationStep(
				0.0F,
				0.0F,
				0.0D,
				64.0D,
				0.0D,
				10.0D,
				74.0D,
				0.0D,
				12.0F,
				8.0F,
				2.0F
		);
		assertTrue(Math.abs(first.yaw()) <= 12.0F, "rotation yaw is bounded");
		assertTrue(Math.abs(first.pitch()) <= 8.0F, "rotation pitch is bounded");
		assertEquals(false, first.withinTolerance(), "distant rotation is not complete");

		MinecraftActionContext.RotationStep aligned = MinecraftActionContext.rotationStep(
				-90.0F,
				0.0F,
				0.0D,
				64.0D,
				0.0D,
				10.0D,
				64.0D,
				0.0D,
				12.0F,
				8.0F,
				2.0F
		);
		assertEquals(true, aligned.withinTolerance(), "aligned rotation is complete");
		assertEquals(-90.0F, aligned.yaw(), "aligned yaw is unchanged");
		assertEquals(0.0F, aligned.pitch(), "aligned pitch is unchanged");
		return 6;
	}

	private static int verifyDeterministicHotbarSelection() {
		List<String> hotbar = List.of("minecraft:air", "minecraft:stone", "minecraft:stone");
		assertEquals(
				1,
				MinecraftActionContext.findFirstMatchingSlot(hotbar, "minecraft:stone"),
				"hotbar selection uses lowest matching slot"
		);
		assertEquals(
				-1,
				MinecraftActionContext.findFirstMatchingSlot(hotbar, "minecraft:diamond_sword"),
				"hotbar selection reports missing item"
		);
		return 2;
	}

	private static int verifyReleaseAttemptsEveryResource() {
		AtomicInteger attempts = new AtomicInteger();
		try {
			MinecraftActionContext.releaseResources(
					() -> {
						attempts.incrementAndGet();
						throw new IllegalStateException("key release failed");
					},
					attempts::incrementAndGet,
					attempts::incrementAndGet
			);
			throw new AssertionError("releaseResources did not report failure");
		} catch (IllegalStateException exception) {
			assertTrue(exception.getMessage().contains("release"), "release failure is explicit");
		}
		assertEquals(3, attempts.get(), "release attempts every resource after a failure");
		return 2;
	}

	private static int verifyEachKeyReleaseIsIndependent() {
		List<String> attempts = new ArrayList<>();
		IllegalStateException aggregate;
		try {
			MinecraftActionContext.releaseResources(
					() -> MinecraftActionContext.releaseKeys(
							() -> {
								attempts.add("key-up");
								throw new IllegalStateException("key-up failed");
							},
							() -> attempts.add("key-left"),
							() -> {
								attempts.add("key-down");
								throw new IllegalStateException("key-down failed");
							},
							() -> attempts.add("key-right")
					),
					() -> attempts.add("stop-item-use"),
					() -> attempts.add("abort-block-breaking")
			);
			throw new AssertionError("independent key release did not report aggregate failure");
		} catch (IllegalStateException exception) {
			aggregate = exception;
		}
		assertEquals(
				List.of(
						"key-up",
						"key-left",
						"key-down",
						"key-right",
						"stop-item-use",
						"abort-block-breaking"
				),
				attempts,
				"later keys and resources run after key release failures"
		);
		assertTrue(aggregate.getCause() instanceof IllegalStateException, "cleanup exposes the first aggregate failure");
		assertEquals(1, aggregate.getCause().getSuppressed().length, "later key failure is suppressed once");
		return 3;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
	}
}
