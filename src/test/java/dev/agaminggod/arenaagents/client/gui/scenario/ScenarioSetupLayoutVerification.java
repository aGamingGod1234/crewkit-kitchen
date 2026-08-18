package dev.agaminggod.arenaagents.client.gui.scenario;

public final class ScenarioSetupLayoutVerification {
	private ScenarioSetupLayoutVerification() {
	}

	public static int verify() {
		ScenarioSetupLayout standard = ScenarioSetupLayout.calculate(960, 540);
		assertTrue(standard.sideNavigation(), "wide setup uses the command-center navigation rail");
		assertTrue(standard.wideRoster(), "1080p GUI scale uses the two-column roster");
		assertTrue(standard.contentTop() > standard.stepRailBottom(), "content starts below the step rail");
		assertTrue(standard.contentLeft() >= standard.panelLeft() + 132,
				"wide setup reserves a readable navigation rail");
		assertTrue(standard.contentBottom() < standard.statusTop(), "content ends before status");
		assertTrue(standard.statusBottom() < standard.footerY(), "status ends before footer controls");

		ScenarioSetupLayout compact = ScenarioSetupLayout.calculate(320, 240);
		assertTrue(!compact.sideNavigation(), "compact setup uses a top command strip");
		assertTrue(compact.compactArenaPicker(), "compact setup uses a one-row scenario picker");
		assertTrue(!compact.wideRoster(), "compact GUI uses the stacked roster editor");
		assertTrue(compact.contentHeight() >= 105, "compact content keeps enough room for the sectioned roster editor");
		assertTrue(compact.panelLeft() >= 0 && compact.panelRight() <= 320, "compact panel stays on screen");
		assertTrue(compact.footerY() + ScenarioSetupLayout.ROW_HEIGHT <= compact.panelBottom(),
				"footer stays inside the panel");
		assertTrue(compact.footerButtonWidth() * 3 + 10 <= compact.panelWidth() - 28,
				"compact footer actions never overlap");
		assertTrue(ScenarioSetupLayout.ROW_HEIGHT >= 24, "setup controls meet the minimum target height");
		return 14;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
