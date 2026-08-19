package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid.Direction;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid.Mode;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid.PagerMetrics;
import dev.agaminggod.arenaagents.client.gui.widget.AgentRosterTile;
import dev.agaminggod.arenaagents.client.gui.widget.AgentRosterTile.IntentType;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.control.AgentRosterPage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

public final class AgentRosterGridVerification {
	private AgentRosterGridVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyTileIdentityAndNarration();
		assertions += verifyTileIntentMapping();
		assertions += verifyDirectionalMovement();
		assertions += verifyWheelContainment();
		assertions += verifyPagerContract();
		assertions += verifyImmutableSnapshots();
		return assertions;
	}

	private static int verifyTileIdentityAndNarration() {
		AgentRosterEntry available = entry("agent:rook", "Rook", "Working", true, "");
		AgentRosterEntry unavailable = entry(
				"agent:kestrel", "Kestrel with a complete operator name", "Disconnected", false,
				"Provider session unavailable"
		);
		assertEquals("agent-tile:agent:rook", AgentRosterTile.focusIdentity(available.id()),
				"focus identity uses the exact stable roster ID");
		assertEquals(
				"Agent 3 of 16. Rook. Sol. Working. Selected.",
				AgentRosterTile.narrationText(available, 3, 16, true),
				"selected narration includes position and complete identity"
		);
		String narration = AgentRosterTile.narrationText(unavailable, 16, 16, false);
		assertEquals(
				"Agent 16 of 16. Kestrel with a complete operator name. Sol. Disconnected. Not selected. "
						+ "Unavailable: Provider session unavailable.",
				narration,
				"unavailable narration keeps the full name and reason"
		);
		String fitted = AgentRosterTile.ellipsize(
				unavailable.name(), 12, String::length
		);
		assertEquals("Kestrel wit…", fitted, "visual name is ellipsized within its measured width");
		assertTrue(!narration.contains(fitted), "visual ellipsis never replaces the full narrated name");
		assertEquals("Rook", AgentRosterTile.ellipsize("Rook", 4, String::length),
				"short visual names remain unchanged");
		assertTrue(AgentRosterTile.shouldEmitFocus(false, true, false),
				"gaining keyboard focus updates semantic focus");
		assertTrue(!AgentRosterTile.shouldEmitFocus(true, true, false),
				"unchanged keyboard focus does not repeat its callback");
		assertTrue(!AgentRosterTile.shouldEmitFocus(false, true, true),
				"restoring an already-focused context does not recurse through focus state");
		assertTrue(!AgentRosterTile.shouldEmitFocus(true, false, false),
				"losing keyboard focus does not change semantic focus");
		assertTrue(AgentRosterTile.focusVisible(false, true),
				"semantic context focus keeps the neutral tile outline visible");
		assertTrue(AgentRosterTile.focusVisible(true, false),
				"keyboard focus keeps the neutral tile outline visible");
		assertTrue(!AgentRosterTile.focusVisible(false, false),
				"an unfocused tile uses its subtle ordinary boundary");
		return 13;
	}

	private static int verifyTileIntentMapping() {
		assertEquals(List.of(IntentType.FOCUS), AgentRosterTile.clickIntentTypes(Mode.FOCUS_ONLY, false, false),
				"single-mode click focuses only");
		assertEquals(List.of(IntentType.FOCUS, IntentType.TOGGLE),
				AgentRosterTile.clickIntentTypes(Mode.MULTI_SELECT, false, false),
				"multi-mode click focuses and toggles");
		assertEquals(List.of(IntentType.FOCUS, IntentType.SELECT_RANGE),
				AgentRosterTile.clickIntentTypes(Mode.MULTI_SELECT, true, false),
				"shift-click focuses and selects a range");
		assertEquals(List.of(IntentType.FOCUS, IntentType.OPEN),
				AgentRosterTile.clickIntentTypes(Mode.MULTI_SELECT, false, true),
				"double-click focuses and opens without toggling twice");
		assertEquals(IntentType.OPEN,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_ENTER, false, Mode.FOCUS_ONLY).orElseThrow(),
				"Enter opens in focus-only mode");
		assertEquals(IntentType.OPEN,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_KP_ENTER, false, Mode.MULTI_SELECT).orElseThrow(),
				"keypad Enter opens in multi-select mode");
		assertTrue(AgentRosterTile.keyIntent(GLFW.GLFW_KEY_SPACE, false, Mode.FOCUS_ONLY).isEmpty(),
				"Space does not mutate focus-only mode");
		assertEquals(IntentType.TOGGLE,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_SPACE, false, Mode.MULTI_SELECT).orElseThrow(),
				"Space toggles in multi-select mode");
		assertTrue(AgentRosterTile.keyIntent(GLFW.GLFW_KEY_A, true, Mode.FOCUS_ONLY).isEmpty(),
				"platform Select all does not mutate focus-only mode");
		assertEquals(IntentType.SELECT_ALL,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_A, true, Mode.MULTI_SELECT).orElseThrow(),
				"platform Select all targets the filtered multi-selection");
		assertEquals(IntentType.MOVE_LEFT,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_LEFT, false, Mode.FOCUS_ONLY).orElseThrow(),
				"left arrow emits semantic movement");
		assertEquals(IntentType.MOVE_RIGHT,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_RIGHT, false, Mode.FOCUS_ONLY).orElseThrow(),
				"right arrow emits semantic movement");
		assertEquals(IntentType.MOVE_UP,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_UP, false, Mode.FOCUS_ONLY).orElseThrow(),
				"up arrow emits semantic movement");
		assertEquals(IntentType.MOVE_DOWN,
				AgentRosterTile.keyIntent(GLFW.GLFW_KEY_DOWN, false, Mode.FOCUS_ONLY).orElseThrow(),
				"down arrow emits semantic movement");
		return 14;
	}

	private static int verifyDirectionalMovement() {
		List<AgentRosterEntry> eight = entries(8);
		assertEquals("agent-3", AgentRosterGrid.directionalTarget(eight, "agent-1", 2, Direction.DOWN),
				"two-column movement stays on the visible page");
		assertEquals("agent-6", AgentRosterGrid.directionalTarget(eight, "agent-2", 4, Direction.DOWN),
				"four-column movement preserves the column");
		assertEquals("agent-5", AgentRosterGrid.directionalTarget(eight, "agent-5", 4, Direction.LEFT),
				"left movement clamps at its visible row edge");
		assertEquals("agent-8", AgentRosterGrid.directionalTarget(eight, "agent-8", 4, Direction.RIGHT),
				"right movement clamps at its visible row edge");
		List<AgentRosterEntry> six = entries(6);
		assertEquals("agent-6", AgentRosterGrid.directionalTarget(six, "agent-4", 4, Direction.DOWN),
				"movement clamps into an incomplete final row");
		assertEquals("agent-2", AgentRosterGrid.directionalTarget(six, "agent-6", 4, Direction.UP),
				"movement returns from an incomplete final row by column");
		assertEquals("agent-1", AgentRosterGrid.directionalTarget(six, "missing", 4, Direction.RIGHT),
				"missing focus recovers to the first visible entry");
		assertEquals("", AgentRosterGrid.directionalTarget(List.of(), "missing", 4, Direction.DOWN),
				"empty pages have no directional target");
		return 8;
	}

	private static int verifyWheelContainment() {
		AgentRosterGridLayout layout = AgentRosterGridLayout.calculate(20, 20, 500, 260, 17);
		AgentRosterPage middle = page(2, 3);
		assertEquals(0, AgentRosterGrid.wheelPageDelta(layout, middle, 5, 5, 0, -1),
				"wheel outside roster bounds is ignored");
		assertEquals(0, AgentRosterGrid.wheelPageDelta(layout, middle, 25, 25, 0, 0),
				"zero vertical wheel delta is ignored");
		assertEquals(1, AgentRosterGrid.wheelPageDelta(layout, middle, 25, 25, 99, -1),
				"horizontal delta is ignored while valid downward wheel paging advances");
		assertEquals(-1, AgentRosterGrid.wheelPageDelta(layout, middle, 25, 25, 0, 1),
				"upward wheel paging requests the previous page");
		assertEquals(0, AgentRosterGrid.wheelPageDelta(layout, page(1, 3), 25, 25, 0, 1),
				"first page cannot wheel before its range");
		assertEquals(0, AgentRosterGrid.wheelPageDelta(layout, page(3, 3), 25, 25, 0, -1),
				"last page cannot wheel after its range");
		assertEquals(0, AgentRosterGrid.wheelPageDelta(layout, middle, 25, 25, 0, Double.NaN),
				"non-finite wheel input is ignored");
		AgentRosterGridLayout unpaged = AgentRosterGridLayout.calculate(20, 20, 500, 260, 16);
		assertEquals(0, AgentRosterGrid.wheelPageDelta(unpaged, middle, 25, 25, 0, -1),
				"wheel never pages geometry without pager controls");
		return 8;
	}

	private static int verifyPagerContract() {
		AgentRosterGridLayout layout = AgentRosterGridLayout.calculate(20, 20, 500, 260, 17);
		AgentRosterPage middle = page(2, 3);
		assertTrue(AgentRosterGrid.hasPager(layout, middle), "pager appears only for multi-page pager geometry");
		assertTrue(!AgentRosterGrid.hasPager(layout, page(1, 1)), "single-page rosters omit pager controls");
		assertEquals("9–16 of 16", AgentRosterGrid.pagerLabel(
				new AgentRosterPage(entries(8), 9, 16, 16, 2, 2, 0)),
				"pager label reports the concise visible range");
		PagerMetrics previous = AgentRosterGrid.pagerMetrics(layout, false);
		PagerMetrics next = AgentRosterGrid.pagerMetrics(layout, true);
		assertEquals(24, previous.interactionBounds().height(),
				"previous pager interaction spans the reserve gap and visible band");
		assertEquals(18, previous.drawBounds().height(), "previous pager draws only in the visible band");
		assertEquals(24, next.interactionBounds().height(),
				"next pager interaction spans the reserve gap and visible band");
		assertEquals(18, next.drawBounds().height(), "next pager draws only in the visible band");
		assertEquals(layout.pagerBounds().top(), previous.drawBounds().top(),
				"pager drawing starts at the reserved visible band");
		assertEquals(layout.pagerBounds().top() - 6, previous.interactionBounds().top(),
				"pager interaction claims exactly the six-pixel reserve above the band");
		assertTrue(previous.interactionBounds().right() <= next.interactionBounds().left(),
				"pager hit targets never overlap");
		return 10;
	}

	private static int verifyImmutableSnapshots() {
		ArrayList<String> source = new ArrayList<>(List.of("one"));
		List<String> snapshot = AgentRosterGrid.immutableSnapshot(source);
		source.add("two");
		assertEquals(List.of("one"), snapshot, "widget snapshots are copied away from mutable inputs");
		assertThrows(() -> snapshot.add("three"), "widget snapshots reject mutation");
		HashSet<String> selectedSource = new HashSet<>(Set.of("agent-1"));
		Set<String> selectedSnapshot = AgentRosterGrid.immutableSelectedSnapshot(selectedSource);
		selectedSource.add("agent-2");
		assertEquals(Set.of("agent-1"), selectedSnapshot,
				"selected snapshots are copied away from mutable inputs");
		assertThrows(() -> selectedSnapshot.add("agent-3"), "selected snapshots reject mutation");
		return 4;
	}

	private static AgentRosterEntry entry(
			String id,
			String name,
			String state,
			boolean selectable,
			String reason
	) {
		return new AgentRosterEntry(id, name, "Codex", "Sol", state, selectable, reason);
	}

	private static List<AgentRosterEntry> entries(int count) {
		ArrayList<AgentRosterEntry> entries = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			entries.add(entry("agent-" + index, "Agent " + index, "Ready", true, ""));
		}
		return List.copyOf(entries);
	}

	private static AgentRosterPage page(int page, int pageCount) {
		return new AgentRosterPage(entries(8), 1, 8, pageCount * 8, page, pageCount, 0);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (UnsupportedOperationException expected) {
			return;
		}
		throw new AssertionError(label);
	}
}
