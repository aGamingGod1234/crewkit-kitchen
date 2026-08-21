package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;

public final class BuildSequenceProgressVerification {
	private BuildSequenceProgressVerification() {
	}

	public static int verify() {
		List<ServerBuildSequenceController.Placement> placements = List.of(
				placement(0), placement(1), placement(2));
		List<Integer> completedOrder = new ArrayList<>();
		ServerBuildSequenceController successful = new ServerBuildSequenceController(
				placements, 0L, 60_000L, new ImmediateDriver(completedOrder, -1));
		ServerController.TickResult success = successful.tick(null, 1L);
		while (success.state() == ServerController.State.RUNNING) success = successful.tick(null, 2L);
		assertEquals(List.of(0, 1, 2), completedOrder, "placements execute in model order");
		assertEquals(ServerController.State.SUCCEEDED, success.state(), "all placements complete");

		List<Integer> failedOrder = new ArrayList<>();
		ServerBuildSequenceController failing = new ServerBuildSequenceController(
				placements, 0L, 60_000L, new ImmediateDriver(failedOrder, 1));
		ServerController.TickResult failure = failing.tick(null, 1L);
		while (failure.state() == ServerController.State.RUNNING) failure = failing.tick(null, 2L);
		assertEquals(List.of(0, 1), failedOrder, "failure prevents later placements");
		assertEquals(ServerController.State.FAILED, failure.state(), "placement failure stops the sequence");
		assertContains(failure.message(), "completed=1", "failure reports completed count");
		assertContains(failure.message(), "failedIndex=1", "failure reports failed index");
		assertContains(failure.message(), "PLACEMENT_CONFLICT", "failure reports placement reason");
		return 7;
	}

	private static ServerBuildSequenceController.Placement placement(int x) {
		return new ServerBuildSequenceController.Placement(
				x, 64, 0, Direction.UP, "minecraft:stone", null);
	}

	private static final class ImmediateDriver implements ServerBuildSequenceController.PlacementDriver {
		private final List<Integer> order;
		private final int failedIndex;

		private ImmediateDriver(List<Integer> order, int failedIndex) {
			this.order = order;
			this.failedIndex = failedIndex;
		}

		@Override
		public boolean isInRange(net.minecraft.server.level.ServerPlayer player,
				ServerBuildSequenceController.Placement placement) {
			return true;
		}

		@Override
		public ServerController.TickResult tick(net.minecraft.server.level.ServerPlayer player,
				ServerBuildSequenceController.Placement placement, int index, long nowEpochMs) {
			order.add(index);
			return index == failedIndex
					? ServerController.TickResult.failed("PLACEMENT_CONFLICT", "conflict", 0.0D)
					: ServerController.TickResult.succeeded("BLOCK_PLACED", "placed");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertContains(String value, String expected, String label) {
		if (!value.contains(expected)) throw new AssertionError(label + ": " + value);
	}
}
