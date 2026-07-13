package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.MovementController;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.PathPlanner;
import dev.agaminggod.arenaagents.client.navigation.StuckDetector;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.protocol.ActionState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MoveToActionVerification {
	private MoveToActionVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyPlansMovesAndCompletesWithinTolerance();
		assertions += verifyPathOutcomesAreExplicit();
		assertions += verifyUnsafePathIsExplicitAndReleased();
		assertions += verifyThreeRecoveriesThenStuckFailure();
		assertions += verifyTimeoutAndCancelReleaseMovement();
		return assertions;
	}

	private static int verifyPlansMovesAndCompletesWithinTolerance() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		RecordingPlanner planner = new RecordingPlanner(foundPlan(start, destination));
		MoveToAction action = action(planner, 1.5D, 64.0D, 0.5D, 0.2D, true);

		ActionUpdate moving = action.tick(context, 0L);
		assertEquals(ActionState.RUNNING, moving.state(), "move action starts running");
		assertEquals(1, planner.calls, "move action plans on first tick");
		assertEquals(true, context.lastMovement.forward(), "move action drives normal input");
		assertEquals(true, context.lastMovement.sprint(), "move action honors safe sprint request");

		context.snapshot = snapshot(1.5D, 64.0D, 0.5D, -90.0F, destination);
		ActionUpdate complete = action.tick(context, 50L);
		assertEquals(ActionState.SUCCEEDED, complete.state(), "move succeeds inside tolerance");
		assertEquals("MOVE_DESTINATION_REACHED", complete.reasonCode(), "move completion reason");
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "move completion releases input");
		return 7;
	}

	private static int verifyPathOutcomesAreExplicit() {
		Map<PathOutcome, String> expectedReasons = Map.of(
				PathOutcome.NO_PATH, "MOVE_NO_PATH",
				PathOutcome.NODE_LIMIT, "MOVE_NODE_LIMIT",
				PathOutcome.TIME_LIMIT, "MOVE_PLANNING_TIME_LIMIT",
				PathOutcome.INVALID, "MOVE_INVALID_PATH"
		);
		int assertions = 0;
		for (Map.Entry<PathOutcome, String> entry : expectedReasons.entrySet()) {
			GridPosition start = position(0, 64, 0);
			GridPosition destination = position(1, 64, 0);
			TestWorld world = new TestWorld().standable(start).standable(destination);
			FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
			MoveToAction action = action(
					new RecordingPlanner(PathPlan.failed(entry.getKey(), 1)),
					1.5D,
					64.0D,
					0.5D,
					0.2D,
					false
			);
			ActionUpdate update = action.tick(context, 0L);
			assertEquals(ActionState.FAILED, update.state(), entry.getKey() + " fails move");
			assertEquals(entry.getValue(), update.reasonCode(), entry.getKey() + " reason");
			assertions += 2;
		}
		return assertions;
	}

	private static int verifyUnsafePathIsExplicitAndReleased() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		world.cell(destination, WalkabilityView.Cell.HAZARD);
		FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		MoveToAction action = action(
				new RecordingPlanner(foundPlan(start, destination)),
				1.5D,
				64.0D,
				0.5D,
				0.2D,
				false
		);

		ActionUpdate update = action.tick(context, 0L);
		assertEquals(ActionState.FAILED, update.state(), "unsafe path fails move");
		assertEquals("MOVE_PATH_UNSAFE", update.reasonCode(), "unsafe path reason");
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "unsafe path releases input");
		return 3;
	}

	private static int verifyThreeRecoveriesThenStuckFailure() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		RecordingPlanner planner = new RecordingPlanner(foundPlan(start, destination));
		MoveToAction action = action(planner, 1.5D, 64.0D, 0.5D, 0.2D, false);
		action.tick(context, 0L);

		for (int recovery = 1; recovery <= StuckDetector.MAX_RECOVERY_ATTEMPTS; recovery++) {
			context.monotonicTimeMs = recovery * StuckDetector.NO_PROGRESS_WINDOW_MS;
			ActionUpdate update = action.tick(context, context.monotonicTimeMs);
			assertEquals(ActionState.RUNNING, update.state(), "recovery " + recovery + " keeps action running");
			assertEquals(recovery + 1, planner.calls, "recovery " + recovery + " replans deterministically");
		}

		context.monotonicTimeMs = (StuckDetector.MAX_RECOVERY_ATTEMPTS + 1L)
				* StuckDetector.NO_PROGRESS_WINDOW_MS;
		ActionUpdate failed = action.tick(context, context.monotonicTimeMs);
		assertEquals(ActionState.FAILED, failed.state(), "stuck action fails after recovery cap");
		assertEquals("MOVE_STUCK", failed.reasonCode(), "stuck failure reason");
		assertEquals(4, planner.calls, "stuck failure performs exactly three recovery replans");
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "stuck failure releases input");
		return 10;
	}

	private static int verifyTimeoutAndCancelReleaseMovement() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		FakeContext timeoutContext = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		MoveToAction timeoutAction = action(
				new RecordingPlanner(foundPlan(start, destination)),
				1.5D,
				64.0D,
				0.5D,
				0.2D,
				false
		);
		timeoutAction.tick(timeoutContext, 0L);
		ActionUpdate timedOut = timeoutAction.tick(timeoutContext, MoveToAction.OVERALL_TIMEOUT_MS);
		assertEquals(ActionState.TIMED_OUT, timedOut.state(), "move owns explicit timeout state");
		assertEquals("MOVE_TIMEOUT", timedOut.reasonCode(), "move timeout reason");
		assertEquals(ActionContext.MovementInput.stopped(), timeoutContext.lastMovement, "timeout releases input");

		FakeContext cancelContext = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		MoveToAction cancelAction = action(
				new RecordingPlanner(foundPlan(start, destination)),
				1.5D,
				64.0D,
				0.5D,
				0.2D,
				false
		);
		cancelAction.tick(cancelContext, 0L);
		cancelAction.cancel(cancelContext);
		assertEquals(ActionContext.MovementInput.stopped(), cancelContext.lastMovement, "cancel releases input");
		return 4;
	}

	private static MoveToAction action(
			PathPlanner planner,
			double x,
			double y,
			double z,
			double tolerance,
			boolean sprint
	) {
		return new MoveToAction(
				x,
				y,
				z,
				tolerance,
				sprint,
				planner,
				new MovementController(),
				new StuckDetector()
		);
	}

	private static PathPlan foundPlan(GridPosition start, GridPosition destination) {
		return new PathPlan(
				List.of(
						new PathNode(start, TraversalType.START),
						new PathNode(destination, TraversalType.WALK)
				),
				PathOutcome.FOUND,
				1
		);
	}

	private static ActionContext.NavigationSnapshot snapshot(
			double x,
			double y,
			double z,
			float yaw,
			GridPosition feet
	) {
		return new ActionContext.NavigationSnapshot(x, y, z, yaw, 0.0F, feet);
	}

	private static GridPosition position(int x, int y, int z) {
		return new GridPosition(x, y, z);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static final class RecordingPlanner implements PathPlanner {
		private final PathPlan plan;
		private int calls;

		private RecordingPlanner(PathPlan plan) {
			this.plan = plan;
		}

		@Override
		public PathPlan findPath(WalkabilityView view, GridPosition start, GridPosition destination) {
			calls++;
			return plan;
		}
	}

	private static final class TestWorld implements WalkabilityView {
		private final Map<GridPosition, Cell> cells = new HashMap<>();

		private TestWorld standable(GridPosition feet) {
			cell(feet.below(), Cell.SAFE_SUPPORT);
			cell(feet, Cell.CLEAR);
			cell(feet.above(), Cell.CLEAR);
			return this;
		}

		private void cell(GridPosition position, Cell cell) {
			cells.put(position, cell);
		}

		@Override
		public Cell cellAt(GridPosition position) {
			return cells.getOrDefault(position, Cell.UNLOADED);
		}
	}

	private static final class FakeContext implements ActionContext {
		private final WalkabilityView view;
		private NavigationSnapshot snapshot;
		private MovementInput lastMovement = MovementInput.stopped();
		private long monotonicTimeMs;

		private FakeContext(WalkabilityView view, NavigationSnapshot snapshot) {
			this.view = view;
			this.snapshot = snapshot;
		}

		@Override
		public boolean isClientThread() {
			return true;
		}

		@Override
		public long monotonicTimeMs() {
			return monotonicTimeMs;
		}

		@Override
		public long epochTimeMs() {
			return 1L;
		}

		@Override
		public SafetyState safetyState() {
			return SafetyState.READY;
		}

		@Override
		public LookResult lookAt(
				double x,
				double y,
				double z,
				float maxYawDelta,
				float maxPitchDelta,
				float toleranceDegrees
		) {
			return new LookResult(true, 0.0F, 0.0F);
		}

		@Override
		public OperationResult sendChat(String message) {
			return OperationResult.succeeded("CHAT_SENT", "Chat sent");
		}

		@Override
		public OperationResult selectHotbarItem(String itemId) {
			return OperationResult.succeeded("ITEM_SELECTED", "Item selected");
		}

		@Override
		public OperationResult startUsingItem(Hand hand) {
			return OperationResult.succeeded("ITEM_USE_STARTED", "Item use started");
		}

		@Override
		public void stopUsingItem() {
		}

		public NavigationSnapshot navigationSnapshot() {
			return snapshot;
		}

		public WalkabilityView walkabilityView() {
			return view;
		}

		public void setMovement(MovementInput movement) {
			lastMovement = movement;
		}

		@Override
		public void releaseAll() {
			lastMovement = MovementInput.stopped();
		}
	}
}
