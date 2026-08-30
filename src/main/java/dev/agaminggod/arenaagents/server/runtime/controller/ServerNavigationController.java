package dev.agaminggod.arenaagents.server.runtime.controller;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;

public final class ServerNavigationController implements ServerController {
	public static final int DEFAULT_MAX_PATH_LENGTH = 256;
	public static final double MAX_LOCAL_PLANNING_DISTANCE = 32.0D;
	private static final double INTERMEDIATE_GOAL_TOLERANCE = 6.0D;
	private static final int MAX_SHALLOW_WATER_PATH_BLOCKS = MinecraftNavigationWorld.MAX_SHALLOW_WATER_CROSSING;
	private static final long STALL_TIMEOUT_MS = 4_000L;
	private static final int MAX_REPLANS = 3;
	private static final double INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED = 0.36D;
	private static final double INTERMEDIATE_WAYPOINT_VERTICAL_TOLERANCE = 0.25D;
	// Plans are rebuilt from current world state; the replan cap and action deadline bound identical retries
	// without retaining stale edge bans that could reject terrain after it changes.

	private final Vec3 destination;
	private final double tolerance;
	private final boolean sprint;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final ServerPathPlanner planner = new ServerPathPlanner();
	private PathPlan plan;
	private int waypointIndex;
	private WaypointProgress progress;
	private double lastProgressValue;
	private InputLease inputLease;
	private AgentInputStates.MotorState motorState;
	private ResourceKey<Level> startingDimension;

	public ServerNavigationController(
			Vec3 destination,
			double tolerance,
			boolean sprint,
			long startedAt,
			long timeoutMs
	) {
		this.destination = Objects.requireNonNull(destination, "destination must not be null");
		if (!Double.isFinite(tolerance)
				|| tolerance < ProtocolConstants.MIN_MOVEMENT_TOLERANCE
				|| tolerance > ProtocolConstants.MAX_MOVEMENT_TOLERANCE
				|| timeoutMs <= 0L || timeoutMs > ProtocolConstants.MAX_DURATION_MS) {
			throw new IllegalArgumentException("invalid navigation tolerance or timeout");
		}
		this.tolerance = tolerance;
		this.sprint = sprint;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		Objects.requireNonNull(player, "player must not be null");
		ResourceKey<Level> currentDimension = player.level().dimension();
		if (startingDimension == null) {
			startingDimension = currentDimension;
		} else if (!remainsInDimension(startingDimension, currentDimension)) {
			return fail(player, "ACTION_DIMENSION_CHANGED",
					"Agent player changed dimension during navigation", currentProgress());
		}
		long elapsedMs = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) {
			return fail(player, "AGENT_DEAD", "Agent player died", currentProgress());
		}
		if (elapsedMs >= timeoutMs) {
			return fail(player, "ACTION_TIMEOUT", "Navigation timed out", currentProgress());
		}
		MinecraftNavigationWorld world = new MinecraftNavigationWorld(player.level());
		double remaining = player.position().distanceTo(destinationTarget(world));
		if (satisfiesDestinationTolerance(remaining, tolerance)) {
			return succeed(player, "DESTINATION_REACHED", "Destination reached");
		}
		if (plan == null) {
			TickResult planned = replan(player, nowEpochMs, elapsedMs, remaining, false);
			if (planned != null) return planned;
		}
		List<PathNode> nodes = plan.nodes();
		if (waypointIndex >= nodes.size()) {
			return replanOrResult(player, nowEpochMs, elapsedMs, remaining);
		}
		PathNode waypoint = nodes.get(waypointIndex);
		if (!world.isStandable(waypoint.position())) {
			TickResult replanned = replan(player, nowEpochMs, elapsedMs, remaining, true);
			if (replanned != null) return replanned;
			nodes = plan.nodes();
			waypoint = nodes.get(waypointIndex);
		}
		boolean finalWaypoint = waypointIndex == nodes.size() - 1;
		Vec3 target = targetFor(world, waypoint, finalWaypoint);
		boolean reached = reachedTarget(world, player.position(), waypoint, finalWaypoint);
		double activeWaypointDistance = player.position().distanceTo(target);
		WaypointProgress.Update update = progress.observe(activeWaypointDistance, reached, nowEpochMs);
		lastProgressValue = update.progress();
		if (reached) {
			waypointIndex++;
			if (waypointIndex >= nodes.size()) {
				return replanOrResult(player, nowEpochMs, elapsedMs, remaining);
			}
			waypoint = nodes.get(waypointIndex);
			finalWaypoint = waypointIndex == nodes.size() - 1;
			target = targetFor(world, waypoint, finalWaypoint);
			progress.waypointAdvanced(player.position().distanceTo(target), nowEpochMs);
		}
		if (update.decision() == WaypointProgress.Decision.FAIL) {
			return fail(player, "PATH_BLOCKED", "Navigation could not recover from repeated stalls", update.progress());
		}
		if (update.decision() == WaypointProgress.Decision.REPLAN) {
			TickResult replanned = replan(player, nowEpochMs, elapsedMs, remaining, true);
			if (replanned != null) return replanned;
			waypoint = plan.nodes().get(waypointIndex);
			target = targetFor(world, waypoint, waypointIndex == plan.nodes().size() - 1);
		}
		drive(player, waypoint, target, nowEpochMs);
		return TickResult.running(update.progress());
	}

	@Override
	public void cancel(ServerPlayer player) {
		if (inputLease == null) {
			motorState = null;
			return;
		}
		try {
			AgentInputRuntime.controller(player).release(inputLease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		inputLease = null;
		motorState = null;
	}

	private TickResult replanOrResult(ServerPlayer player, long nowEpochMs, long elapsedMs, double remaining) {
		if (satisfiesDestinationTolerance(remaining, tolerance)) {
			return succeed(player, "DESTINATION_REACHED", "Destination reached");
		}
		TickResult replanned = replan(player, nowEpochMs, elapsedMs, remaining, false);
		return replanned == null ? TickResult.running(currentProgress()) : replanned;
	}

	static boolean satisfiesDestinationTolerance(double remaining, double tolerance) {
		return remaining <= tolerance;
	}

	static boolean remainsInDimension(ResourceKey<Level> startingDimension, ResourceKey<Level> currentDimension) {
		return Objects.requireNonNull(startingDimension, "startingDimension must not be null")
				.equals(Objects.requireNonNull(currentDimension, "currentDimension must not be null"));
	}

	Vec3 targetFor(PathNode waypoint, boolean finalWaypoint) {
		Objects.requireNonNull(waypoint, "waypoint must not be null");
		return targetFor(waypoint, finalWaypoint, waypoint.position().y());
	}

	boolean reachedTarget(Vec3 playerPosition, PathNode waypoint, boolean finalWaypoint) {
		return reachedTarget(playerPosition, waypoint, finalWaypoint, waypoint.position().y());
	}

	boolean reachedTarget(Vec3 playerPosition, PathNode waypoint, boolean finalWaypoint, double supportHeight) {
		Objects.requireNonNull(playerPosition, "playerPosition must not be null");
		if (!Double.isFinite(supportHeight)) return false;
		Vec3 target = targetFor(waypoint, finalWaypoint, supportHeight);
		return targetsExactDestination(waypoint, finalWaypoint)
				? satisfiesDestinationTolerance(playerPosition.distanceTo(target), tolerance)
				: reachedWaypoint(playerPosition, target);
	}

	private Vec3 targetFor(MinecraftNavigationWorld world, PathNode waypoint, boolean finalWaypoint) {
		Vec3 horizontalTarget = targetFor(waypoint, finalWaypoint);
		double supportHeight = world.supportHeight(
				waypoint.position(), horizontalTarget.x, horizontalTarget.z);
		return Double.isFinite(supportHeight)
				? targetFor(waypoint, finalWaypoint, supportHeight)
				: horizontalTarget;
	}

	private Vec3 targetFor(PathNode waypoint, boolean finalWaypoint, double supportHeight) {
		boolean exactDestination = targetsExactDestination(waypoint, finalWaypoint);
		Vec3 horizontalTarget = exactDestination ? destination : center(waypoint.position());
		double targetY = exactDestination && hasFullBlockSupportHeight(waypoint.position(), supportHeight)
				? destination.y
				: supportHeight;
		return new Vec3(horizontalTarget.x, targetY, horizontalTarget.z);
	}

	private boolean reachedTarget(
			MinecraftNavigationWorld world,
			Vec3 playerPosition,
			PathNode waypoint,
			boolean finalWaypoint
	) {
		return reachedTarget(
				playerPosition,
				waypoint,
				finalWaypoint,
				world.supportHeight(waypoint.position(), playerPosition.x, playerPosition.z)
		);
	}

	private Vec3 destinationTarget(MinecraftNavigationWorld world) {
		GridPosition destinationPosition = grid(destination);
		if (!world.isStandable(destinationPosition)) return destination;
		double supportHeight = world.supportHeight(destinationPosition, destination.x, destination.z);
		return Double.isFinite(supportHeight) && !hasFullBlockSupportHeight(destinationPosition, supportHeight)
				? new Vec3(destination.x, supportHeight, destination.z)
				: destination;
	}

	private static boolean hasFullBlockSupportHeight(GridPosition position, double supportHeight) {
		return Math.abs(supportHeight - position.y()) < 1.0E-7D;
	}

	private boolean targetsExactDestination(PathNode waypoint, boolean finalWaypoint) {
		return finalWaypoint && waypoint.position().equals(grid(destination));
	}

	private TickResult replan(
			ServerPlayer player,
			long nowEpochMs,
			long elapsedMs,
			double remaining,
			boolean recovery
	) {
		MinecraftNavigationWorld world = new MinecraftNavigationWorld(player.level());
		GridPosition start = nearestStandable(world, new GridPosition(
				player.blockPosition().getX(),
				player.blockPosition().getY(),
				player.blockPosition().getZ()
		), 1, 2);
		if (start == null) {
			return fail(player, "NO_STANDABLE_PATH", "Start has no safe standing position", currentProgress());
		}
		Vec3 localDestination = localPlanningDestination(center(start), destination);
		boolean finalSegment = localDestination.equals(destination);
		List<GridPosition> goals = standableGoalsWithinTolerance(
				world,
				localDestination,
				finalSegment ? tolerance : INTERMEDIATE_GOAL_TOLERANCE,
				4,
				finalSegment ? 3 : 6
		);
		if (goals.isEmpty()) {
			return fail(player, "NO_STANDABLE_PATH", "Destination has no safe standing position", currentProgress());
		}
		PathPlan candidate = null;
		boolean pathLimitReached = false;
		for (GridPosition goal : goals) {
			ServerPathPlanner.PlanningResult planning = planner.planPath(world, start, goal);
			if (planning.deferred()) {
				return shouldRetryPlanning(planning.plan().outcome(), true, elapsedMs, timeoutMs)
						? TickResult.running(currentProgress())
						: fail(player, "PATH_LIMIT_REACHED", "Path planning exceeded its navigation deadline", currentProgress());
			}
			PathPlan planned = planning.plan();
			if (planned.outcome() == PathOutcome.FOUND
					&& planned.nodes().size() <= DEFAULT_MAX_PATH_LENGTH
					&& hasBoundedShallowWaterRun(planned.nodes(), world::isShallowWater)) {
				candidate = planned;
				break;
			}
			pathLimitReached |= planned.outcome() == PathOutcome.NODE_LIMIT || planned.outcome() == PathOutcome.TIME_LIMIT;
		}
		if (candidate == null) {
			if (pathLimitReached && shouldRetryPlanning(PathOutcome.NODE_LIMIT, false, elapsedMs, timeoutMs)) {
				return TickResult.running(currentProgress());
			}
			return fail(player, pathLimitReached ? "PATH_LIMIT_REACHED" : "NO_PATH",
					"No bounded safe path is currently available", currentProgress());
		}
		plan = candidate;
		waypointIndex = Math.min(1, Math.max(0, candidate.nodes().size() - 1));
		PathNode activeWaypoint = candidate.nodes().get(waypointIndex);
		boolean finalWaypoint = waypointIndex == candidate.nodes().size() - 1;
		double activeWaypointDistance = player.position().distanceTo(targetFor(world, activeWaypoint, finalWaypoint));
		if (progress == null) {
			progress = new WaypointProgress(activeWaypointDistance, nowEpochMs, STALL_TIMEOUT_MS, MAX_REPLANS);
		} else if (recovery) {
			progress.replanned(activeWaypointDistance, nowEpochMs);
		} else {
			progress.waypointAdvanced(activeWaypointDistance, nowEpochMs);
		}
		return null;
	}

	private void drive(ServerPlayer player, PathNode waypoint, Vec3 target, long nowEpochMs) {
		boolean gapJump = waypoint.traversal() == TraversalType.JUMP_GAP;
		boolean shallowWater = new MinecraftNavigationWorld(player.level()).isShallowWater(waypoint.position());
		LeasedServerInputController controller = AgentInputRuntime.controller(player);
		if (inputLease == null) {
			inputLease = controller.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.NAVIGATION, 100);
		}
		Vec3 lookTarget = target.add(0.0D, 0.85D, 0.0D);
		Vec3 delta = lookTarget.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float targetYaw = net.minecraft.util.Mth.wrapDegrees(
				(float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float targetPitch = net.minecraft.util.Mth.clamp(
				(float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		if (motorState == null) motorState = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(
				motorState,
				new AgentInputStates.MotorTarget(
						targetYaw,
						targetPitch,
						true,
						waypoint.traversal() == TraversalType.JUMP_UP || gapJump || shallowWater,
						!shallowWater && (sprint || gapJump) && player.getFoodData().getFoodLevel() > 6
				),
				nowEpochMs
		);
		motorState = step.state();
		controller.apply(inputLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				step.forward(), step.strafe(), step.jump(), false, step.sprint(),
				false, false, step.state().yaw(), step.state().pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	private TickResult succeed(ServerPlayer player, String reasonCode, String message) {
		cancel(player);
		return TickResult.succeeded(reasonCode, message);
	}

	private TickResult fail(ServerPlayer player, String reasonCode, String message, double progressValue) {
		cancel(player);
		return TickResult.failed(reasonCode, message, progressValue);
	}

	private double currentProgress() {
		return lastProgressValue;
	}

	private static boolean reachedWaypoint(Vec3 player, Vec3 waypoint) {
		double dx = player.x - waypoint.x;
		double dz = player.z - waypoint.z;
		return dx * dx + dz * dz <= INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED
				&& Math.abs(player.y - waypoint.y) <= INTERMEDIATE_WAYPOINT_VERTICAL_TOLERANCE;
	}

	static boolean shouldRetryPlanning(
			PathOutcome outcome,
			boolean deferred,
			long elapsedMs,
			long timeoutMs
	) {
		Objects.requireNonNull(outcome, "outcome must not be null");
		if (!deferred && outcome != PathOutcome.NODE_LIMIT && outcome != PathOutcome.TIME_LIMIT) return false;
		return elapsedMs >= 0L && timeoutMs > 0L && elapsedMs < timeoutMs;
	}

	private static Vec3 center(GridPosition position) {
		return new Vec3(position.x() + 0.5D, position.y(), position.z() + 0.5D);
	}

	private static GridPosition grid(Vec3 position) {
		return new GridPosition(
				(int) Math.floor(position.x),
				(int) Math.floor(position.y),
				(int) Math.floor(position.z)
		);
	}

	private static GridPosition nearestStandable(
			MinecraftNavigationWorld world,
			GridPosition origin,
			int horizontalRadius,
			int verticalRadius
	) {
		for (int radius = 0; radius <= horizontalRadius; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					for (int vertical = 0; vertical <= verticalRadius; vertical++) {
						GridPosition above = new GridPosition(
								origin.x() + dx,
								origin.y() + vertical,
								origin.z() + dz
						);
						if (world.isStandable(above)) return above;
						if (vertical > 0) {
							GridPosition below = new GridPosition(
									origin.x() + dx,
									origin.y() - vertical,
									origin.z() + dz
							);
							if (world.isStandable(below)) return below;
						}
					}
				}
			}
		}
		return null;
	}

	private static List<GridPosition> standableGoalsWithinTolerance(
			MinecraftNavigationWorld world,
			Vec3 destination,
			double tolerance,
			int horizontalRadius,
			int verticalRadius
	) {
		GridPosition origin = grid(destination);
		ArrayList<GridPosition> candidates = new ArrayList<>();
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
					GridPosition candidate = new GridPosition(origin.x() + dx, origin.y() + dy, origin.z() + dz);
					if (world.isStandable(candidate) && candidateSatisfiesTolerance(candidate, destination, tolerance)) {
						candidates.add(candidate);
					}
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(value -> center(value).distanceToSqr(destination)));
		return List.copyOf(candidates);
	}

	static boolean candidateSatisfiesTolerance(GridPosition candidate, Vec3 destination, double tolerance) {
		return candidate.equals(grid(destination)) || center(candidate).distanceTo(destination) <= tolerance;
	}

	static Vec3 localPlanningDestination(Vec3 start, Vec3 destination) {
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		double distance = start.distanceTo(destination);
		if (distance <= MAX_LOCAL_PLANNING_DISTANCE) return destination;
		return start.add(destination.subtract(start).scale(MAX_LOCAL_PLANNING_DISTANCE / distance));
	}

	static boolean hasBoundedShallowWaterRun(
			List<PathNode> nodes,
			Predicate<GridPosition> shallowWater
	) {
		Objects.requireNonNull(nodes, "nodes must not be null");
		Objects.requireNonNull(shallowWater, "shallowWater must not be null");
		int run = 0;
		for (PathNode node : nodes) {
			run = shallowWater.test(node.position()) ? run + 1 : 0;
			if (run > MAX_SHALLOW_WATER_PATH_BLOCKS) return false;
		}
		return true;
	}
}
