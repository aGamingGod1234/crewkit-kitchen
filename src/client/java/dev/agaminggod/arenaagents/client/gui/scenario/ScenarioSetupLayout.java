package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.client.gui.AgentControlLayout;

/** Arena wizard geometry built on the same responsive field-console shell as agent control. */
public record ScenarioSetupLayout(
		int panelLeft,
		int panelTop,
		int panelRight,
		int panelBottom,
		int navigationTop,
		int contentLeft,
		int contentRight,
		int stepRailBottom,
		int contentTop,
		int contentBottom,
		int statusTop,
		int statusBottom,
		int footerY,
		boolean sideNavigation,
		boolean wideRoster
) {
	public static final int ROW_HEIGHT = AgentControlLayout.CONTROL_HEIGHT;
	private static final int STEP_RAIL_HEIGHT = 20;
	private static final int COMPACT_STEP_RAIL_HEIGHT = 14;

	public static ScenarioSetupLayout calculate(int screenWidth, int screenHeight) {
		AgentControlLayout shell = AgentControlLayout.calculate(screenWidth, screenHeight);
		boolean compact = !shell.sideNavigation();
		int stepRailTop = compact ? shell.navigationBottom() + 3 : shell.contentTop();
		int stepRailBottom = stepRailTop + (compact ? COMPACT_STEP_RAIL_HEIGHT : STEP_RAIL_HEIGHT);
		int contentTop = stepRailBottom + (compact ? 2 : 7);
		int statusBottom = shell.footerY() - 3;
		int statusTop = compact ? statusBottom : statusBottom - 10;
		int contentBottom = compact ? shell.footerY() - 3 : statusTop - 2;
		return new ScenarioSetupLayout(
				shell.panelLeft(), shell.panelTop(), shell.panelRight(), shell.panelBottom(),
				shell.navigationTop(), shell.contentLeft(), shell.contentRight(), stepRailBottom,
				contentTop, contentBottom, statusTop, statusBottom, shell.footerY(),
				shell.sideNavigation(), shell.contentWidth() >= 560
		);
	}

	public int panelWidth() {
		return panelRight - panelLeft;
	}

	public int contentWidth() {
		return contentRight - contentLeft;
	}

	public int contentHeight() {
		return contentBottom - contentTop;
	}

	/** Compact screens replace the large 2x2 scenario cards with one concise selector row. */
	public boolean compactArenaPicker() {
		return !sideNavigation || contentHeight() < 120;
	}

	public int footerButtonWidth() {
		return Math.min(112, Math.max(72, (contentWidth() - 10) / 3));
	}
}
