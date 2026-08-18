package dev.agaminggod.arenaagents.client.gui;

public final class LiveArenaLayoutVerification {
	private LiveArenaLayoutVerification() {
	}

	public static int verify() {
		AgentControlLayout wideShell = AgentControlLayout.calculate(960, 540);
		LiveArenaLayout wide = LiveArenaLayout.calculate(
				wideShell.contentWidth(), wideShell.contentHeight(), 8);
		assertTrue(wide.columns() == 2, "wide live arena uses two readable columns");
		assertTrue(wide.visibleCount() == 8, "wide live arena shows the full default roster");
		assertTrue(wide.maximumScroll() == 0, "wide default roster does not pretend to scroll");

		AgentControlLayout compactShell = AgentControlLayout.calculate(320, 240);
		LiveArenaLayout compact = LiveArenaLayout.calculate(
				compactShell.contentWidth(), compactShell.contentHeight(), 8);
		assertTrue(compact.columns() == 1, "compact live arena uses one column");
		assertTrue(compact.cardHeight() >= 42, "compact stat cards retain a readable health row");
		assertTrue(compact.visibleCount() >= 1 && compact.visibleCount() < 8,
				"compact live arena scrolls instead of drawing off-screen");
		assertTrue(compact.maximumScroll() == 8 - compact.visibleCount(),
				"compact scroll range reaches every agent");
		return 7;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
