package dev.agaminggod.arenaagents.client.gui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class ConsoleNavigationVerification {
	private ConsoleNavigationVerification() {
	}

	public static int verify() {
		ConsoleNavigationDestination[] destinations = ConsoleNavigationDestination.values();
		assertEquals(4, destinations.length, "every console screen exposes four destinations");
		assertEquals(List.of("AGENTS", "GROUP", "LIVE", "BUILD"),
				Arrays.stream(destinations).map(ConsoleNavigationDestination::compactLabel).toList(),
				"console destinations stay in the shared display order");
		assertEquals(4, new HashSet<>(Arrays.stream(destinations)
				.map(ConsoleNavigationDestination::translationKey).toList()).size(),
				"console destinations keep distinct localization keys");
		return 3;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
