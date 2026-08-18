package dev.agaminggod.arenaagents.client.gui;

/** Responsive geometry for the field console, independent from Minecraft rendering state. */
public record AgentControlLayout(
		int panelLeft,
		int panelTop,
		int panelRight,
		int panelBottom,
		int navigationTop,
		int navigationBottom,
		int contentLeft,
		int contentTop,
		int contentRight,
		int contentBottom,
		int canvasLeft,
		int canvasRight,
		int contextLeft,
		int contextRight,
		int footerY,
		boolean sideNavigation,
		boolean splitWorkspace
) {
	public static final int CONTROL_HEIGHT = 24;
	private static final int MINIMUM_WIDTH = 320;
	private static final int MINIMUM_HEIGHT = 240;
	private static final int OUTER_MARGIN = 8;
	private static final int INNER_MARGIN = 14;
	private static final int MAXIMUM_PANEL_WIDTH = 920;
	private static final int SIDE_NAVIGATION_BREAKPOINT = 680;
	private static final int SIDE_NAVIGATION_WIDTH = 132;
	private static final int CONTEXT_RAIL_BREAKPOINT = 700;
	private static final int CONTEXT_RAIL_WIDTH = 208;
	private static final int REGION_GAP = 12;

	public static AgentControlLayout calculate(int screenWidth, int screenHeight) {
		if (screenWidth < MINIMUM_WIDTH || screenHeight < MINIMUM_HEIGHT) {
			throw new IllegalArgumentException("Agent control requires at least a 320x240 GUI");
		}
		int panelWidth = Math.min(MAXIMUM_PANEL_WIDTH, screenWidth - OUTER_MARGIN * 2);
		int panelLeft = (screenWidth - panelWidth) / 2;
		int verticalMargin = screenHeight <= 260 ? 4 : OUTER_MARGIN;
		int panelTop = verticalMargin;
		int panelRight = panelLeft + panelWidth;
		int panelBottom = screenHeight - verticalMargin;
		boolean sideNavigation = panelWidth >= SIDE_NAVIGATION_BREAKPOINT;
		int navigationTop = sideNavigation ? panelTop + 65 : panelTop + 45;
		int navigationBottom = sideNavigation ? panelBottom - 66 : navigationTop + CONTROL_HEIGHT;
		int contentLeft = panelLeft + INNER_MARGIN + (sideNavigation ? SIDE_NAVIGATION_WIDTH : 0);
		int contentRight = panelRight - INNER_MARGIN;
		int contentTop = sideNavigation ? panelTop + 54 : navigationBottom + 10;
		int footerY = panelBottom - CONTROL_HEIGHT - 7;
		int contentBottom = footerY - 10;
		int contentWidth = contentRight - contentLeft;
		boolean splitWorkspace = contentWidth >= CONTEXT_RAIL_BREAKPOINT;
		int contextLeft = splitWorkspace ? contentRight - CONTEXT_RAIL_WIDTH : contentRight;
		int canvasRight = splitWorkspace ? contextLeft - REGION_GAP : contentRight;
		return new AgentControlLayout(
				panelLeft, panelTop, panelRight, panelBottom,
				navigationTop, navigationBottom,
				contentLeft, contentTop, contentRight, contentBottom,
				contentLeft, canvasRight, contextLeft, contentRight,
				footerY, sideNavigation, splitWorkspace
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

	public int canvasWidth() {
		return canvasRight - canvasLeft;
	}

	public int contextWidth() {
		return splitWorkspace ? contextRight - contextLeft : 0;
	}

	public int minimumTargetHeight() {
		return CONTROL_HEIGHT;
	}
}
