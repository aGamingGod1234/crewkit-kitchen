package dev.agaminggod.arenaagents.client.navigation;

import dev.agaminggod.arenaagents.client.action.ActionContext;
import dev.agaminggod.arenaagents.client.action.SafetyState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NavigationMovementVerification {
	private NavigationMovementVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyGradualForwardAndJumpSteering();
		assertions += verifyNodeAdvancementAndRelease();
		assertions += verifyUnsafeNodeStopsMovement();
		assertions += verifyStuckRecoveryLimitAndReset();
		return assertions;
	}

	private static int verifyGradualForwardAndJumpSteering() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 65, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		MovementController controller = new MovementController();
		controller.setPlan(
				new PathPlan(
						List.of(
								new PathNode(start, TraversalType.START),
								new PathNode(destination, TraversalType.JUMP_UP)
						),
						PathOutcome.FOUND,
						1
				),
				1.5D,
				65.0D,
				0.5D,
				0.2D
		);

		MovementController.Outcome outcome = controller.tick(context, true);
		assertEquals(MovementController.Outcome.RUNNING, outcome, "jump steering remains active");
		assertEquals(1, context.lookCalls, "movement rotates view gradually");
		assertEquals(true, context.lastMovement.forward(), "aligned movement presses forward");
		assertEquals(true, context.lastMovement.jump(), "jump edge presses jump");
		assertEquals(false, context.lastMovement.sprint(), "jump edge suppresses sprint");
		assertEquals(MovementController.MAX_YAW_DEGREES_PER_TICK, context.lastMaxYawDelta, "movement yaw step");
		return 6;
	}

	private static int verifyNodeAdvancementAndRelease() {
		GridPosition start = position(0, 64, 0);
		GridPosition middle = position(1, 64, 0);
		GridPosition destination = position(2, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(middle).standable(destination);
		FakeContext context = new FakeContext(world, snapshot(1.5D, 64.0D, 0.5D, -90.0F, middle));
		MovementController controller = new MovementController();
		controller.setPlan(
				new PathPlan(
						List.of(
								new PathNode(start, TraversalType.START),
								new PathNode(middle, TraversalType.WALK),
								new PathNode(destination, TraversalType.WALK)
						),
						PathOutcome.FOUND,
						2
				),
				2.5D,
				64.0D,
				0.5D,
				0.2D
		);

		assertEquals(MovementController.Outcome.RUNNING, controller.tick(context, true), "middle node advances");
		assertEquals(2, controller.currentNodeIndex(), "controller advances to final node");
		assertEquals(true, context.lastMovement.sprint(), "safe aligned walk can sprint");

		context.snapshot = snapshot(2.5D, 64.0D, 0.5D, -90.0F, destination);
		assertEquals(MovementController.Outcome.COMPLETED, controller.tick(context, true), "destination completes");
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "completion releases movement");
		controller.stop(context);
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "explicit stop is idempotent");
		return 6;
	}

	private static int verifyUnsafeNodeStopsMovement() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(1, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		FakeContext context = new FakeContext(world, snapshot(0.5D, 64.0D, 0.5D, -90.0F, start));
		MovementController controller = new MovementController();
		controller.setPlan(
				new PathPlan(
						List.of(
								new PathNode(start, TraversalType.START),
								new PathNode(destination, TraversalType.WALK)
						),
						PathOutcome.FOUND,
						1
				),
				1.5D,
				64.0D,
				0.5D,
				0.2D
		);
		world.cell(destination, WalkabilityView.Cell.HAZARD);

		assertEquals(MovementController.Outcome.UNSAFE, controller.tick(context, false), "unsafe node outcome");
		assertEquals(ActionContext.MovementInput.stopped(), context.lastMovement, "unsafe node releases movement");
		return 2;
	}

	private static int verifyStuckRecoveryLimitAndReset() {
		StuckDetector detector = new StuckDetector();
		detector.reset(0.0D, 64.0D, 0.0D, 0L);
		assertEquals(StuckDetector.Outcome.MONITORING, detector.observe(0.0D, 64.0D, 0.0D, 2_499L), "stuck window not early");

		for (int recovery = 1; recovery <= StuckDetector.MAX_RECOVERY_ATTEMPTS; recovery++) {
			long now = recovery * StuckDetector.NO_PROGRESS_WINDOW_MS;
			assertEquals(StuckDetector.Outcome.RECOVER, detector.observe(0.0D, 64.0D, 0.0D, now), "recovery request " + recovery);
			assertEquals(recovery, detector.recoveryAttempts(), "recovery count " + recovery);
			detector.recoveryStarted(0.0D, 64.0D, 0.0D, now);
		}
		long failedAt = (StuckDetector.MAX_RECOVERY_ATTEMPTS + 1L) * StuckDetector.NO_PROGRESS_WINDOW_MS;
		assertEquals(StuckDetector.Outcome.FAILED, detector.observe(0.0D, 64.0D, 0.0D, failedAt), "three unsuccessful recoveries fail");

		detector.reset(0.0D, 64.0D, 0.0D, failedAt);
		detector.observe(StuckDetector.MIN_PROGRESS_DISTANCE, 64.0D, 0.0D, failedAt + 1L);
		assertEquals(0, detector.recoveryAttempts(), "measurable progress resets recoveries");
		assertEquals(StuckDetector.Outcome.MONITORING, detector.observe(StuckDetector.MIN_PROGRESS_DISTANCE, 64.0D, 0.0D, failedAt + 2L), "progress resets window");
		return 11;
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
		private int lookCalls;
		private float lastMaxYawDelta;

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
			return 0L;
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
			lookCalls++;
			lastMaxYawDelta = maxYawDelta;
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
