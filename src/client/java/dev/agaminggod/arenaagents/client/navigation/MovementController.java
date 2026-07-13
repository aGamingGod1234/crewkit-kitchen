package dev.agaminggod.arenaagents.client.navigation;

import dev.agaminggod.arenaagents.client.action.ActionContext;
import java.util.List;
import java.util.Objects;

public final class MovementController {
	public static final float MAX_YAW_DEGREES_PER_TICK = 10.0F;
	public static final float MAX_PITCH_DEGREES_PER_TICK = 6.0F;
	public static final float VIEW_TOLERANCE_DEGREES = 3.0F;

	private static final double INTERMEDIATE_NODE_TOLERANCE = 0.35D;
	private static final double INTERMEDIATE_NODE_TOLERANCE_SQUARED =
			INTERMEDIATE_NODE_TOLERANCE * INTERMEDIATE_NODE_TOLERANCE;
	private static final double VIEW_TARGET_HEIGHT = 1.0D;
	private static final double FULL_ROTATION_DEGREES = 360.0D;
	private static final double HALF_ROTATION_DEGREES = 180.0D;
	private static final double FORWARD_SECTOR_DEGREES = 45.0D;
	private static final double BACKWARD_SECTOR_DEGREES = 135.0D;

	private List<PathNode> nodes = List.of();
	private int currentNodeIndex;
	private double destinationX;
	private double destinationY;
	private double destinationZ;
	private double destinationToleranceSquared;
	private boolean planActive;

	public void setPlan(
			PathPlan plan,
			double destinationX,
			double destinationY,
			double destinationZ,
			double destinationTolerance
	) {
		Objects.requireNonNull(plan, "plan must not be null");
		if (plan.outcome() != PathOutcome.FOUND) {
			throw new IllegalArgumentException("movement requires a found path");
		}
		requireFinite(destinationX, "destinationX");
		requireFinite(destinationY, "destinationY");
		requireFinite(destinationZ, "destinationZ");
		if (!Double.isFinite(destinationTolerance) || destinationTolerance <= 0.0D) {
			throw new IllegalArgumentException("destinationTolerance must be positive and finite");
		}
		nodes = plan.nodes();
		currentNodeIndex = nodes.size() == 1 ? 0 : 1;
		this.destinationX = destinationX;
		this.destinationY = destinationY;
		this.destinationZ = destinationZ;
		destinationToleranceSquared = destinationTolerance * destinationTolerance;
		planActive = true;
	}

	public Outcome tick(ActionContext context, boolean sprintRequested) {
		Objects.requireNonNull(context, "context must not be null");
		if (!planActive || nodes.isEmpty()) {
			throw new IllegalStateException("movement plan is not active");
		}

		ActionContext.NavigationSnapshot snapshot = context.navigationSnapshot();
		advanceReachedIntermediateNodes(snapshot);
		PathNode targetNode = nodes.get(currentNodeIndex);
		if (currentNodeIndex == nodes.size() - 1 && destinationReached(snapshot)) {
			stop(context);
			return Outcome.COMPLETED;
		}
		if (!context.walkabilityView().isStandable(targetNode.position())) {
			stop(context);
			return Outcome.UNSAFE;
		}

		Target target = targetFor(targetNode);
		context.lookAt(
				target.x(),
				target.y() + VIEW_TARGET_HEIGHT,
				target.z(),
				MAX_YAW_DEGREES_PER_TICK,
				MAX_PITCH_DEGREES_PER_TICK,
				VIEW_TOLERANCE_DEGREES
		);
		ActionContext.MovementInput movement = steeringInput(
				snapshot,
				target,
				targetNode.traversal(),
				sprintRequested
		);
		context.setMovement(movement);
		return Outcome.RUNNING;
	}

	public void stop(ActionContext context) {
		Objects.requireNonNull(context, "context must not be null");
		context.setMovement(ActionContext.MovementInput.stopped());
		planActive = false;
	}

	public int currentNodeIndex() {
		return currentNodeIndex;
	}

	private void advanceReachedIntermediateNodes(ActionContext.NavigationSnapshot snapshot) {
		while (currentNodeIndex < nodes.size() - 1) {
			GridPosition position = nodes.get(currentNodeIndex).position();
			double xDifference = snapshot.x() - blockCenter(position.x());
			double yDifference = snapshot.y() - position.y();
			double zDifference = snapshot.z() - blockCenter(position.z());
			double distanceSquared = xDifference * xDifference
					+ yDifference * yDifference
					+ zDifference * zDifference;
			if (distanceSquared > INTERMEDIATE_NODE_TOLERANCE_SQUARED) {
				return;
			}
			currentNodeIndex++;
		}
	}

	private boolean destinationReached(ActionContext.NavigationSnapshot snapshot) {
		double xDifference = snapshot.x() - destinationX;
		double yDifference = snapshot.y() - destinationY;
		double zDifference = snapshot.z() - destinationZ;
		return xDifference * xDifference + yDifference * yDifference + zDifference * zDifference
				<= destinationToleranceSquared;
	}

	private Target targetFor(PathNode targetNode) {
		if (currentNodeIndex == nodes.size() - 1) {
			return new Target(destinationX, destinationY, destinationZ);
		}
		GridPosition position = targetNode.position();
		return new Target(blockCenter(position.x()), position.y(), blockCenter(position.z()));
	}

	private static ActionContext.MovementInput steeringInput(
			ActionContext.NavigationSnapshot snapshot,
			Target target,
			TraversalType traversal,
			boolean sprintRequested
	) {
		double desiredYaw = Math.toDegrees(Math.atan2(target.z() - snapshot.z(), target.x() - snapshot.x()))
				- 90.0D;
		double yawDifference = wrapDegrees(desiredYaw - snapshot.yaw());
		double absoluteYawDifference = Math.abs(yawDifference);
		boolean forward = absoluteYawDifference <= FORWARD_SECTOR_DEGREES;
		boolean backward = absoluteYawDifference >= BACKWARD_SECTOR_DEGREES;
		boolean left = !forward && !backward && yawDifference < 0.0D;
		boolean right = !forward && !backward && yawDifference >= 0.0D;
		boolean jump = traversal == TraversalType.JUMP_UP;
		boolean sprint = sprintRequested && traversal == TraversalType.WALK && forward;
		return new ActionContext.MovementInput(forward, backward, left, right, jump, sprint);
	}

	private static double wrapDegrees(double degrees) {
		double wrapped = degrees % FULL_ROTATION_DEGREES;
		if (wrapped >= HALF_ROTATION_DEGREES) {
			wrapped -= FULL_ROTATION_DEGREES;
		}
		if (wrapped < -HALF_ROTATION_DEGREES) {
			wrapped += FULL_ROTATION_DEGREES;
		}
		return wrapped;
	}

	private static double blockCenter(int coordinate) {
		return coordinate + 0.5D;
	}

	private static void requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
	}

	public enum Outcome {
		RUNNING,
		COMPLETED,
		UNSAFE
	}

	private record Target(double x, double y, double z) {
	}
}
