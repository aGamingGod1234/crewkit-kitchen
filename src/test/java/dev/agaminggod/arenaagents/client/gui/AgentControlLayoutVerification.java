package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.control.AgentRosterFilter;

public final class AgentControlLayoutVerification {
	private AgentControlLayoutVerification() {
	}

	public static int verify() {
		int assertions = 0;
		AgentControlLayout wide = AgentControlLayout.calculate(960, 540);
		assertTrue(wide.sideNavigation(), "wide command center keeps persistent side navigation");
		assertions++;
		assertTrue(wide.splitWorkspace(), "wide command center reserves a readable context rail");
		assertTrue(wide.canvasWidth() >= 420, "wide command center keeps a useful primary canvas");
		assertTrue(wide.contextWidth() >= 180, "wide command center context rail remains readable");
		assertTrue(wide.navigationTop() - wide.panelTop() >= 57,
				"wide navigation leaves breathing room below the console header");
		assertTrue(wide.contentBottom() < wide.footerY(), "wide content never collides with footer actions");
		assertions += 5;

		AgentControlLayout compact = AgentControlLayout.calculate(320, 240);
		assertTrue(!compact.sideNavigation(), "compact command center reflows navigation above the canvas");
		assertTrue(!compact.splitWorkspace(), "compact command center stacks context below the primary task");
		assertTrue(compact.canvasWidth() >= 276, "compact canvas stays wide enough for prompt and roster controls");
		assertTrue(compact.contentTop() > compact.navigationBottom(), "compact content begins after navigation");
		assertTrue(compact.navigationTop() - compact.panelTop() >= 45,
				"compact navigation leaves breathing room below the console header");
		assertTrue(compact.footerY() + AgentControlLayout.CONTROL_HEIGHT <= compact.panelBottom(),
				"compact footer remains fully on screen");
		assertTrue(compact.minimumTargetHeight() >= 24, "custom controls meet the minimum target-height contract");
		assertions += 7;

		AgentControlLayout mid = AgentControlLayout.calculate(700, 360);
		assertTrue(mid.sideNavigation(), "medium screens preserve stable side navigation when space permits");
		assertTrue(!mid.splitWorkspace(), "medium screens avoid cramped three-column layouts");
		assertTrue(mid.canvasWidth() > 500, "medium workspace uses the freed context-rail width");
		assertions += 3;

		assertTrue(!AgentControlLayout.rosterFiltersVisible(8), "eight agents keep roster filters hidden");
		assertTrue(AgentControlLayout.rosterFiltersVisible(9), "nine agents reveal roster filters");
		assertions += 2;

		assertRosterGeometry(wide, 1, false, 1, 48, false, "wide single agent");
		assertRosterGeometry(wide, 8, false, 4, 48, false, "wide eight agents");
		assertRosterGeometry(wide, 16, true, 4, 48, false, "wide filtered sixteen agents");
		assertRosterGeometry(mid, 1, false, 1, 48, false, "medium single agent");
		assertRosterGeometry(mid, 8, false, 4, 48, false, "medium eight agents");
		assertRosterGeometry(mid, 16, true, 4, 48, false, "medium filtered sixteen agents");
		assertRosterGeometry(compact, 1, false, 1, 48, false, "compact single agent");
		assertRosterGeometry(compact, 8, false, 2, 24, true, "compact eight agents");
		assertRosterGeometry(compact, 16, true, 2, 24, true, "compact filtered sixteen agents");
		assertions += 45;

		AgentControlLayout.Bounds compactSelection = compact.rosterBounds(true, true);
		AgentControlLayout.Bounds compactActions = compact.workspaceActionBounds();
		AgentControlLayout.Bounds compactComposer = compact.composerBounds();
		assertTrue(compactSelection.bottom() <= compactActions.top(),
				"compact selection grid remains disjoint from Compose and Clear actions");
		assertTrue(compactComposer.bottom() <= compactActions.top(),
				"compact composer remains disjoint from Start Queue Adjust and Back actions");
		assertTrue(compactActions.bottom() <= compact.contentBottom(),
				"compact workspace actions remain above the global footer");
		assertions += 3;

		AgentRosterFilter all = AgentRosterFilter.all();
		AgentRosterFilter working = new AgentRosterFilter("", "", "Working");
		assertTrue(AgentControlLayout.rosterPageAfterFilterUpdate(all, all, 3) == 3,
				"unchanged heartbeat filter preserves the current roster page");
		assertTrue(AgentControlLayout.rosterPageAfterFilterUpdate(working,
				new AgentRosterFilter("", "", "Working"), 2) == 2,
				"equal normalized filter values preserve the current roster page");
		assertTrue(AgentControlLayout.rosterPageAfterFilterUpdate(working, all, 3) == 1,
				"an effective filter change returns the roster to page one");
		assertions += 3;

		assertEquals("3 selected \u00b7 2 hidden", AgentControlLayout.groupScopeLabel(3, 2),
				"group scope names selected and hidden agents");
		assertEquals("3 selected", AgentControlLayout.groupScopeLabel(3, 0),
				"group scope stays concise when no selected agents are hidden");
		AgentControlLayout.Bounds compactScope = compact.groupScopeBounds();
		assertTrue(compactScope.top() >= compact.panelTop() + 19,
				"compact scope starts below the primary header title");
		assertTrue(compactScope.bottom() <= compact.navigationTop(),
				"compact scope remains above navigation");
		assertTrue(compactScope.bottom() <= compact.contentTop(),
				"compact scope remains above roster and composer content");
		assertions += 5;

		assertThrows(IllegalArgumentException.class, () -> AgentControlLayout.calculate(319, 240),
				"unsupported widths fail explicitly instead of creating negative widgets");
		assertThrows(IllegalArgumentException.class, () -> AgentControlLayout.calculate(320, 239),
				"unsupported heights fail explicitly instead of creating overlapping widgets");
		assertions += 2;
		return assertions;
	}

	private static void assertRosterGeometry(
			AgentControlLayout shell,
			int entries,
			boolean filters,
			int expectedColumns,
			int minimumTileHeight,
			boolean expectedPager,
			String label
	) {
		AgentControlLayout.Bounds region = shell.rosterBounds(filters, !shell.sideNavigation());
		AgentRosterGridLayout grid = AgentRosterGridLayout.calculate(
				region.left(), region.top(), region.right(), region.bottom(), entries);
		assertTrue(grid.columns() == expectedColumns, label + " uses the intended column count");
		assertTrue(grid.tileHeight() >= minimumTileHeight, label + " keeps a readable tile height");
		assertTrue(grid.hasPager() == expectedPager, label + " reserves paging only when needed");
		assertTrue(grid.gridBounds().left() >= region.left() && grid.gridBounds().right() <= region.right()
				&& grid.gridBounds().top() >= region.top() && grid.gridBounds().bottom() <= region.bottom(),
				label + " stays inside its roster region");
		assertTrue(!grid.hasPager() || grid.gridBounds().bottom() <= grid.pagerBounds().top(),
				label + " keeps tiles disjoint from its pager");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected " + expected + " but was " + actual);
		}
	}

	private static void assertThrows(Class<? extends Throwable> expected, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable thrown) {
			if (expected.isInstance(thrown)) return;
			throw new AssertionError(label + ": expected " + expected.getSimpleName()
					+ " but caught " + thrown.getClass().getSimpleName(), thrown);
		}
		throw new AssertionError(label + ": expected " + expected.getSimpleName());
	}
}
