package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.MovementController;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.PathPlanner;
import dev.agaminggod.arenaagents.client.navigation.StuckDetector;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Map;
import java.util.Objects;

public final class MoveToAction implements RunningAction {
	public static final long OVERALL_TIMEOUT_MS = 120_000L;

	private static final String RUNNING_MESSAGE = "Following local path";
	private static final String REPLANNING_MESSAGE = "Replanning after no movement progress";
	private static final String COMPLETE_REASON = "MOVE_DESTINATION_REACHED";
	private static final String COMPLETE_MESSAGE = "Destination is within requested tolerance";
	private static final String UNSAFE_REASON = "MOVE_PATH_UNSAFE";
	private static final String UNSAFE_MESSAGE = "The next path node is no longer safe";
	private static final String STUCK_REASON = "MOVE_STUCK";
	private static final String STUCK_MESSAGE = "Movement made no progress after three recovery replans";
	private static final String TIMEOUT_REASON = "MOVE_TIMEOUT";
	private static final String TIMEOUT_MESSAGE = "Movement exceeded its overall timeout";
	private static final Map<PathOutcome, Failure> PATH_FAILURES = Map.of(
			PathOutcome.NO_PATH, new Failure("MOVE_NO_PATH", "No safe local path was found"),
			PathOutcome.NODE_LIMIT, new Failure("MOVE_NODE_LIMIT", "Local path planning reached its node limit"),
			PathOutcome.TIME_LIMIT, new Failure(
					"MOVE_PLANNING_TIME_LIMIT",
					"Local path planning reached its time limit"
			),
			PathOutcome.INVALID, new Failure("MOVE_INVALID_PATH", "Movement path inputs are invalid")
	);

	private final double destinationX;
	private final double destinationY;
	private final double destinationZ;
	private final double tolerance;
	private final double toleranceSquared;
	private final boolean sprint;
	private final GridPosition destinationGridPosition;
	private final PathPlanner pathPlanner;
	private final MovementController movementController;
	private final StuckDetector stuckDetector;

	private boolean planned;

	public MoveToAction(
			double destinationX,
			double destinationY,
			double destinationZ,
			double tolerance,
			boolean sprint
	) {
		this(
				destinationX,
				destinationY,
				destinationZ,
				tolerance,
				sprint,
				new LocalPathfinder(),
				new MovementController(),
				new StuckDetector()
		);
	}

	MoveToAction(
			double destinationX,
			double destinationY,
			double destinationZ,
			double tolerance,
			boolean sprint,
			PathPlanner pathPlanner,
			MovementController movementController,
			StuckDetector stuckDetector
	) {
		this.destinationX = requireFinite(destinationX, "destinationX");
		this.destinationY = requireFinite(destinationY, "destinationY");
		this.destinationZ = requireFinite(destinationZ, "destinationZ");
		this.tolerance = requireTolerance(tolerance);
		toleranceSquared = tolerance * tolerance;
		this.sprint = sprint;
		destinationGridPosition = new GridPosition(
				floorToGrid(destinationX, "destinationX"),
				floorToGrid(destinationY, "destinationY"),
				floorToGrid(destinationZ, "destinationZ")
		);
		this.pathPlanner = Objects.requireNonNull(pathPlanner, "pathPlanner must not be null");
		this.movementController = Objects.requireNonNull(
				movementController,
				"movementController must not be null"
		);
		this.stuckDetector = Objects.requireNonNull(stuckDetector, "stuckDetector must not be null");
	}

	@Override
	public long timeoutMs() {
		return Long.MAX_VALUE;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		Objects.requireNonNull(context, "context must not be null");
		if (elapsedMs >= OVERALL_TIMEOUT_MS) {
			movementController.stop(context);
			return new ActionUpdate(ActionState.TIMED_OUT, TIMEOUT_REASON, TIMEOUT_MESSAGE);
		}

		ActionContext.NavigationSnapshot snapshot = context.navigationSnapshot();
		if (destinationReached(snapshot)) {
			movementController.stop(context);
			return ActionUpdate.succeeded(COMPLETE_REASON, COMPLETE_MESSAGE);
		}
		if (!planned) {
			ActionUpdate planningFailure = plan(context, snapshot, false);
			if (planningFailure != null) {
				return planningFailure;
			}
		}

		MovementController.Outcome movementOutcome = movementController.tick(context, sprint);
		if (movementOutcome == MovementController.Outcome.COMPLETED) {
			return ActionUpdate.succeeded(COMPLETE_REASON, COMPLETE_MESSAGE);
		}
		if (movementOutcome == MovementController.Outcome.UNSAFE) {
			return ActionUpdate.failed(UNSAFE_REASON, UNSAFE_MESSAGE);
		}

		StuckDetector.Outcome stuckOutcome = stuckDetector.observe(
				snapshot.x(),
				snapshot.y(),
				snapshot.z(),
				context.monotonicTimeMs()
		);
		if (stuckOutcome == StuckDetector.Outcome.FAILED) {
			movementController.stop(context);
			return ActionUpdate.failed(STUCK_REASON, STUCK_MESSAGE);
		}
		if (stuckOutcome == StuckDetector.Outcome.RECOVER) {
			movementController.stop(context);
			ActionUpdate planningFailure = plan(context, snapshot, true);
			return planningFailure == null ? ActionUpdate.running(REPLANNING_MESSAGE) : planningFailure;
		}
		return ActionUpdate.running(RUNNING_MESSAGE);
	}

	@Override
	public void cancel(ActionContext context) {
		movementController.stop(context);
	}

	private ActionUpdate plan(
			ActionContext context,
			ActionContext.NavigationSnapshot snapshot,
			boolean recovery
	) {
		PathPlan plan = pathPlanner.findPath(
				context.walkabilityView(),
				snapshot.feetPosition(),
				destinationGridPosition
		);
		if (plan == null || plan.outcome() != PathOutcome.FOUND) {
			movementController.stop(context);
			PathOutcome outcome = plan == null ? PathOutcome.INVALID : plan.outcome();
			Failure failure = PATH_FAILURES.get(outcome);
			if (failure == null) {
				failure = PATH_FAILURES.get(PathOutcome.INVALID);
			}
			return ActionUpdate.failed(failure.reasonCode(), failure.message());
		}
		movementController.setPlan(
				plan,
				destinationX,
				destinationY,
				destinationZ,
				tolerance
		);
		planned = true;
		if (recovery) {
			stuckDetector.recoveryStarted(snapshot.x(), snapshot.y(), snapshot.z(), context.monotonicTimeMs());
		} else {
			stuckDetector.reset(snapshot.x(), snapshot.y(), snapshot.z(), context.monotonicTimeMs());
		}
		return null;
	}

	private boolean destinationReached(ActionContext.NavigationSnapshot snapshot) {
		double xDifference = snapshot.x() - destinationX;
		double yDifference = snapshot.y() - destinationY;
		double zDifference = snapshot.z() - destinationZ;
		return xDifference * xDifference + yDifference * yDifference + zDifference * zDifference
				<= toleranceSquared;
	}

	private static double requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new ActionCreationException("INVALID_MOVE_DESTINATION", field + " must be finite");
		}
		return value;
	}

	private static double requireTolerance(double value) {
		if (!Double.isFinite(value)
				|| value < ProtocolConstants.MIN_MOVEMENT_TOLERANCE
				|| value > ProtocolConstants.MAX_MOVEMENT_TOLERANCE) {
			throw new ActionCreationException(
					"INVALID_MOVE_TOLERANCE",
					"tolerance must be finite and within the protocol movement range"
			);
		}
		return value;
	}

	private static int floorToGrid(double value, String field) {
		double floored = Math.floor(value);
		if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
			throw new ActionCreationException("INVALID_MOVE_DESTINATION", field + " is outside the local grid");
		}
		return (int) floored;
	}

	private record Failure(String reasonCode, String message) {
	}
}
