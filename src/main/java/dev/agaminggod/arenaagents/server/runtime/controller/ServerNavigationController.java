package dev.agaminggod.arenaagents.server.runtime.controller;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
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
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

public final class ServerNavigationController implements ServerController {
	private static final Logger LOGGER = LoggerFactory.getLogger(ServerNavigationController.class);
	private static final boolean TICK_DIAGNOSTICS_ENABLED = Boolean.getBoolean("arenaagents.navigation.debugTicks");
	public static final int DEFAULT_MAX_PATH_LENGTH = 256;
	public static final double MAX_LOCAL_PLANNING_DISTANCE = 32.0D;
	private static final int MIN_AIR_RESERVE = SwimPlanning.AIR_RESERVE_TICKS;
	private static final long STALL_TIMEOUT_MS = 4_000L;
	private static final int MAX_REPLANS = 3;
	private static final double INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED = 0.36D;
	private static final double INTERMEDIATE_WAYPOINT_VERTICAL_TOLERANCE = 0.25D;
	private static final double ENDPOINT_STABILITY_DISTANCE = 0.1D;
	/** Straight-line steering looks this many walk nodes ahead, so cardinal grid paths run as diagonals. */
	static final int STEERING_LOOKAHEAD_NODES = 8;
	/**
	 * Clear-line envelope. Between samples the body is at most half a spacing from the nearest sample on
	 * each axis, so a sample square of half-width player (0.3) + spacing / 2 + slack covers the swept
	 * hitbox; the slack also absorbs small drift off the line. The square stays under one block wide, so
	 * its four corners name every cell it touches.
	 */
	private static final double PLAYER_HALF_WIDTH = 0.3D;
	static final double STEERING_SAMPLE_SPACING = 0.1D;
	static final double STEERING_HALF_WIDTH = PLAYER_HALF_WIDTH + STEERING_SAMPLE_SPACING / 2.0D + 0.05D;
	/** Walking gaze: a little below the horizon, the way a player watches the ground ahead. */
	static final float WALKING_GAZE_PITCH = 10.0F;
	private static final double GAZE_MIN_HORIZONTAL = 3.0D;
	/** A step-up jump is pressed only next to the step; vanilla step height covers 0.6 without one. */
	static final double JUMP_UP_TRIGGER_DISTANCE = 1.2D;
	private static final double STEP_HEIGHT = 0.6D;
	/** Gap jumps take off from the edge cell: between its center (2.0) and the landing's near side (1.0). */
	static final double GAP_TAKEOFF_MAX_DISTANCE = 2.0D;
	static final double GAP_TAKEOFF_MIN_DISTANCE = 1.0D;
	/** Inside this horizontal distance the view holds its heading on the final approach and after an overshoot. */
	static final double HOLD_HEADING_DISTANCE = 1.0D;
	private static final double PASSED_LEVEL_TOLERANCE = 0.1D;
	/** A steer point further round than this has been passed; the path never asks for such a turn this close. */
	static final float BEHIND_DEGREES = 100.0F;
	static final int GAZE_LOOKAHEAD_NODES = 3;
	static final float MAX_GAZE_OFFSET_DEGREES = 50.0F;
	private static final double GAZE_MIN_DISTANCE = 1.5D;

	private final Vec3 destination;
	private final AABB arrivalRegion;
	private final double tolerance;
	private final boolean sprint;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final ServerPathPlanner planner = new ServerPathPlanner();
	private final BlockedMoveDetector blockedMoveDetector = new BlockedMoveDetector();
	private MinecraftNavigationWorld navigationWorld;
	private LocalPathfinder.Search search;
	private Preparation preparation;
	private MinecraftNavigationWorld searchWorld;
	private GridPosition searchOrigin;
	private Set<GridPosition> searchGoals = Set.of();
	private final LinkedHashSet<GridPosition> previousFrontiers = new LinkedHashSet<>();
	private boolean searchRecovery;
	private PathPlan plan;
	private int waypointIndex;
	private WaypointProgress progress;
	private double lastProgressValue;
	private Vec3 navigationStartPosition;
	private GridPosition resolvedEndpointPosition;
	private Vec3 resolvedEndpointTarget;
	private Vec3 endpointStabilityPosition;
	private boolean endpointStabilityConfirmed;
	private InputLease inputLease;
	private LeasedServerInputController inputController;
	private AgentInputStates.MotorState motorState;
	private boolean lastSprint;
	private boolean blockedMoveTracking;
	private boolean blockedMoveJumpPending;
	private boolean blockedMoveStepUp;
	private ResourceKey<Level> startingDimension;
	/** Elapsed ms when the current rise to the water surface began, or -1 while not surfacing. */
	private long surfacingSinceMs = -1L;
	private long surfacingCheckMs;
	private double surfacingCheckDistance;
	/** Set when the route ahead needs more breath than is left, or no route starts under water: rise for air first. */
	private boolean mustSurface;
	private Vec3 diagnosticPreviousPosition;
	private Vec3 diagnosticTarget;

	public ServerNavigationController(
			Vec3 destination,
			double tolerance,
			boolean sprint,
			long startedAt,
			long timeoutMs
	) {
		this(destination, tolerance, sprint, startedAt, timeoutMs, null);
	}

	ServerNavigationController(Vec3 destination, double tolerance, boolean sprint, long startedAt, long timeoutMs, AABB arrivalRegion) {
		this.destination = Objects.requireNonNull(destination, "destination must not be null");
		this.arrivalRegion = arrivalRegion;
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
		if (!TICK_DIAGNOSTICS_ENABLED) return tickInternal(player, nowEpochMs);
		long startedNanos = System.nanoTime();
		try {
			return tickInternal(player, nowEpochMs);
		} finally {
			logTickDiagnostics(player, System.nanoTime() - startedNanos);
		}
	}

	private TickResult tickInternal(ServerPlayer player, long nowEpochMs) {
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
		if (player.isPassenger() || player.isFallFlying()) {
			return fail(player, "UNSUPPORTED_NAVIGATION_MODE", "Mounted travel and gliding require player control inputs", currentProgress());
		}
		if (elapsedMs >= timeoutMs) {
			return fail(player, "ACTION_TIMEOUT", "Navigation timed out", currentProgress());
		}
		// Routes may swim under water while the breath left covers the submerged stretch ahead (flooded tunnels and
		// caves). When it does not, or the air reserve is reached, or no route starts from under water, rise to the
		// surface first like a player holding space, then plan from there. Stopping the action instead handed a sinking,
		// drowning body back to the model (the play-test's NO_STANDABLE_PATH / AIR_RESERVE_REACHED).
		boolean eyesUnderWater = SwimPlanning.needsSurfacing(player.isInWater(), player.isUnderWater());
		if (!eyesUnderWater) mustSurface = false;
		if (eyesUnderWater && plan != null && !SwimPlanning.breathCovers(player.getAirSupply(),
				submergedNodesAhead(navigationWorld(player.level()), plan.nodes(), waypointIndex))) {
			discardPlanning();
			mustSurface = true;
		}
		if (eyesUnderWater && (mustSurface || !hasAirReserve(true, player.getAirSupply()))) {
			// Head for the nearest open surface (not the destination): ice, an overhang, a sealed tunnel or a downward
			// bubble column above means another column, or none, and the model must hear that while it has breath.
			Vec3 eye = player.getEyePosition();
			SwimPlanning.Surface open = SwimPlanning.nearestSurface(columnView(player.level()),
					net.minecraft.util.Mth.floor(eye.x), net.minecraft.util.Mth.floor(eye.y), net.minecraft.util.Mth.floor(eye.z));
			String reasonCode = player.getAirSupply() <= MIN_AIR_RESERVE ? "AIR_RESERVE_REACHED" : "NO_STANDABLE_PATH";
			double air = SwimPlanning.airSecondsLeft(player.getAirSupply());
			if (open == null) {
				return fail(player, reasonCode, String.format(java.util.Locale.ROOT,
						"No open water surface within %d blocks (ice, overhang or sealed water above); %.1f s of air left",
						SwimPlanning.SURFACE_SEARCH_RADIUS, air), currentProgress());
			}
			double distance = eye.distanceTo(new Vec3(open.x() + 0.5D, open.openY(), open.z() + 0.5D));
			if (!SwimPlanning.surfacingFeasible(player.getAirSupply(), distance)) {
				return fail(player, reasonCode, String.format(java.util.Locale.ROOT,
						"The nearest open surface is %.1f blocks away but only %.1f s of air are left", distance, air), currentProgress());
			}
			if (surfacingSinceMs < 0L) {
				surfacingSinceMs = elapsedMs;
				surfacingCheckMs = elapsedMs;
				surfacingCheckDistance = distance;
			} else if (elapsedMs - surfacingCheckMs >= SwimPlanning.SURFACING_PROGRESS_WINDOW_MS) {
				if (surfacingCheckDistance - distance < SwimPlanning.SURFACING_MIN_PROGRESS) {
					return fail(player, reasonCode, String.format(java.util.Locale.ROOT,
							"Rising to the surface made no progress for 1 s (a current or obstruction); %.1f blocks to go, %.1f s of air left",
							distance, air), currentProgress());
				}
				surfacingCheckMs = elapsedMs;
				surfacingCheckDistance = distance;
			}
			if (elapsedMs - surfacingSinceMs >= SwimPlanning.SURFACING_TIMEOUT_MS) {
				return fail(player, reasonCode,
						String.format(java.util.Locale.ROOT, "Could not rise to the water surface within %d s (%.1f s of air left)",
								SwimPlanning.SURFACING_TIMEOUT_MS / 1_000L, air),
						currentProgress());
			}
			plan = null;
			surface(player, nowEpochMs, open);
			return TickResult.running(currentProgress());
		}
		surfacingSinceMs = -1L;
		MinecraftNavigationWorld world = navigationWorld(player.level());
		if (navigationStartPosition == null) navigationStartPosition = player.position();
		if (plan == null && blockedMoveTracking) {
			BlockedMoveDetector.Decision blockedDecision = blockedMoveDetector.observe(
					player.horizontalCollision, player.onGround(), blockedMoveJumpPending, blockedMoveStepUp,
					player.getX(), player.getZ(), nowEpochMs);
			blockedMoveTracking = blockedDecision != BlockedMoveDetector.Decision.CONTINUE
					|| player.horizontalCollision && player.onGround() && !blockedMoveJumpPending && !blockedMoveStepUp;
			if (blockedDecision == BlockedMoveDetector.Decision.FAIL) {
				return fail(player, "PATH_BLOCKED", "Navigation remained blocked after three seconds", currentProgress());
			}
		}
		if (plan == null) {
			TickResult planned = replan(player, world, nowEpochMs, elapsedMs, false);
			if (planned != null) return planned;
		}
		lastProgressValue = navigationProgress(player.position());
		TickResult endpointResult = verifyEndpoint(world, player);
		if (endpointResult != null) return endpointResult;
		List<PathNode> nodes = plan.nodes();
		if (waypointIndex >= nodes.size()) {
			noteReplan("waypoints-exhausted", player);
			return replanOrResult(player, world, nowEpochMs, elapsedMs);
		}
		PathNode waypoint = nodes.get(waypointIndex);
		if (!world.isTraversable(waypoint.position())) {
			noteReplan("waypoint-not-traversable", player);
			TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, true);
			if (replanned != null) return replanned;
			nodes = plan.nodes();
			waypoint = nodes.get(waypointIndex);
		}
		int occupied = occupiedWalkNode(nodes, waypointIndex, grid(player.position()));
		if (occupied >= 0 && player.onGround()) {
			// Corner-cut steering crosses walk cells away from their centers; standing in a later
			// path cell means every node up to it is behind the player.
			waypointIndex = occupied + 1;
			waypoint = nodes.get(waypointIndex);
			progress.waypointAdvanced(player.position().distanceTo(
					targetFor(world, waypoint, waypointIndex == nodes.size() - 1)), nowEpochMs);
		}
		if (player.onGround() && waypointIndex < nodes.size() - 1) {
			Vec3 waypointCenter = center(waypoint.position());
			if (passedWaypoint(player.position(), nodes, waypointIndex,
					world.supportHeight(waypoint.position(), waypointCenter.x, waypointCenter.z))) {
				// A body that clears a short step lands on the supporting block's far side: the waypoint is behind it.
				waypointIndex++;
				waypoint = nodes.get(waypointIndex);
				progress.waypointAdvanced(player.position().distanceTo(
						targetFor(world, waypoint, waypointIndex == nodes.size() - 1)), nowEpochMs);
			}
		}
		if (!eyesUnderWater && world.isSubmerged(waypoint.position())
				&& !SwimPlanning.breathCovers(player.getAirSupply(), submergedNodesAhead(world, nodes, waypointIndex))) {
			// Catch breath before the dive, as a player does: air refills while the eyes are above water.
			coast(player);
			return TickResult.running(lastProgressValue);
		}
		boolean finalWaypoint = waypointIndex == nodes.size() - 1;
		Vec3 target = targetFor(world, waypoint, finalWaypoint);
		boolean reached = reachedTarget(world, player.position(), waypoint, finalWaypoint);
		boolean jumpPending = jumpPending(player, world, waypoint, target);
		boolean stepUp = waypoint.traversal() == TraversalType.JUMP_UP;
		if (reached) {
			blockedMoveDetector.reset();
			blockedMoveTracking = false;
		} else {
			blockedMoveJumpPending = jumpPending;
			blockedMoveStepUp = stepUp;
			blockedMoveTracking = player.horizontalCollision && player.onGround() && !jumpPending && !stepUp;
		}
		BlockedMoveDetector.Decision blockedDecision = reached ? BlockedMoveDetector.Decision.CONTINUE
				: blockedMoveDetector.observe(player.horizontalCollision, player.onGround(), jumpPending, stepUp,
						player.getX(), player.getZ(), nowEpochMs);
		if (blockedDecision == BlockedMoveDetector.Decision.FAIL) {
			return fail(player, "PATH_BLOCKED", "Navigation remained blocked after three seconds", lastProgressValue);
		}
		if (blockedDecision == BlockedMoveDetector.Decision.REPLAN) {
			noteReplan("blocked", player);
			TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, true);
			if (replanned != null) return replanned;
			nodes = plan.nodes();
			waypoint = nodes.get(waypointIndex);
			finalWaypoint = waypointIndex == nodes.size() - 1;
			target = targetFor(world, waypoint, finalWaypoint);
			reached = reachedTarget(world, player.position(), waypoint, finalWaypoint);
		}
		double activeWaypointDistance = player.position().distanceTo(target);
		WaypointProgress.Update update = progress.observe(activeWaypointDistance, reached, nowEpochMs);
		lastProgressValue = navigationProgress(player.position());
		if (reached) {
			waypointIndex++;
			if (waypointIndex >= nodes.size()) {
				noteReplan("waypoints-exhausted-after-reach", player);
				return replanOrResult(player, world, nowEpochMs, elapsedMs);
			}
			waypoint = nodes.get(waypointIndex);
			finalWaypoint = waypointIndex == nodes.size() - 1;
			target = targetFor(world, waypoint, finalWaypoint);
			progress.waypointAdvanced(player.position().distanceTo(target), nowEpochMs);
		}
		if (update.decision() == WaypointProgress.Decision.FAIL) {
			return fail(player, "PATH_BLOCKED", "Navigation could not recover from repeated stalls", lastProgressValue);
		}
		if (update.decision() == WaypointProgress.Decision.REPLAN) {
			noteReplan("stalled", player);
			TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, true);
			if (replanned != null) return replanned;
			waypoint = plan.nodes().get(waypointIndex);
			target = targetFor(world, waypoint, waypointIndex == plan.nodes().size() - 1);
		}
		List<PathNode> active = plan.nodes();
		int steerIndex = waypoint.traversal() == TraversalType.WALK && player.onGround()
				? steeringIndex(world, player.position(), active, waypointIndex)
				: fallingSteerIndex(active, waypointIndex, player.onGround());
		Vec3 steer = steerIndex == waypointIndex ? target
				: targetFor(world, active.get(steerIndex), steerIndex == active.size() - 1);
		diagnosticTarget = target;
		drive(player, world, waypoint, target, steer, steerIndex == active.size() - 1, steerIndex == waypointIndex, nowEpochMs);
		return TickResult.running(lastProgressValue);
	}

	@Override
	public void cancel(ServerPlayer player) {
		discardPlanning();
		releaseInput();
	}

	private void discardPlanning() {
		search = null;
		preparation = null;
		searchWorld = null;
		searchOrigin = null;
		searchGoals = Set.of();
	}

	void invalidatePlanning(GridPosition actualOrigin, boolean terrainCurrent) {
		if (!terrainCurrent) {
			discardPlanning();
			previousFrontiers.clear();
		}
		if (searchOrigin != null && !actualOrigin.equals(searchOrigin)) discardPlanning();
	}

	private void releaseInput() {
		if (inputLease == null) {
			motorState = null;
			return;
		}
		try {
			inputController.release(inputLease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		inputLease = null;
		inputController = null;
		motorState = null;
	}

	private TickResult replanOrResult(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			long nowEpochMs,
			long elapsedMs
	) {
		TickResult endpointResult = verifyEndpoint(world, player);
		if (endpointResult != null) return endpointResult;
		TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, false);
		if (replanned != null) return replanned;
		lastProgressValue = navigationProgress(player.position());
		return TickResult.running(currentProgress());
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
		if (finalWaypoint && waypoint.position().equals(resolvedEndpointPosition)) {
			return withinEndpoint(playerPosition);
		}
		if (finalWaypoint && arrivalRegion != null) {
			return arrivalRegion.contains(playerPosition) && playerPosition.distanceTo(target) <= tolerance;
		}
		return targetsExactDestination(waypoint, finalWaypoint)
				? satisfiesDestinationTolerance(playerPosition.distanceTo(target), tolerance)
				: reachedWaypoint(playerPosition, target);
	}

	private Vec3 targetFor(MinecraftNavigationWorld world, PathNode waypoint, boolean finalWaypoint) {
		if (finalWaypoint && waypoint.position().equals(resolvedEndpointPosition) && resolvedEndpointTarget != null) {
			return resolvedEndpointTarget;
		}
		if (!supportedEndpoint(world, waypoint.position()) && !(finalWaypoint && submergedFloor(world, waypoint.position()))) {
			if (waypoint.traversal() == TraversalType.SWIM) return center(waypoint.position()).add(0.0D, 0.4D, 0.0D);
			if (waypoint.traversal() == TraversalType.CLIMB) return center(waypoint.position());
		}
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

	/**
	 * Returns the last server-observed navigation facts for callers that publish action evidence.
	 * The endpoint is retained from the selected final path node, never inferred from raw proximity.
	 */
	public AuthoritativeState authoritativeState(ServerPlayer player, long observedAtEpochMs) {
		Objects.requireNonNull(player, "player must not be null");
		MinecraftNavigationWorld world = navigationWorld(player.level());
		Vec3 position = player.position();
		lastProgressValue = navigationProgress(position);
		boolean endpointStandable = resolvedEndpointPosition != null && resolvedEndpointTarget != null
				&& goalEndpoint(world, resolvedEndpointPosition)
				&& Double.isFinite(world.supportHeight(
						resolvedEndpointPosition,
						resolvedEndpointTarget.x,
						resolvedEndpointTarget.z));
		boolean withinTolerance = withinEndpoint(position);
		boolean noCollision = player.level().noCollision(player.getBoundingBox());
		boolean physicallyValid = endpointStandable && withinTolerance && noCollision
				&& !player.isInWall() && player.onGround();
		boolean stable = physicallyValid && endpointStabilityConfirmed && endpointStabilityPosition != null
				&& position.distanceTo(endpointStabilityPosition) <= ENDPOINT_STABILITY_DISTANCE;
		return new AuthoritativeState(
				position,
				grid(position),
				destination,
				resolvedEndpointTarget,
				resolvedEndpointPosition,
				resolvedEndpointTarget == null ? null : position.distanceTo(resolvedEndpointTarget),
				tolerance,
				lastProgressValue,
				endpointStandable,
				withinTolerance,
				noCollision,
				player.isInWall(),
				player.onGround(),
				stable,
				observedAtEpochMs
		);
	}

	public record AuthoritativeState(
			Vec3 position,
			GridPosition blockPosition,
			Vec3 requestedDestination,
			Vec3 resolvedEndpoint,
			GridPosition resolvedEndpointBlock,
			Double distanceToEndpoint,
			double tolerance,
			double progress,
			boolean endpointStandable,
			boolean withinTolerance,
			boolean noCollision,
			boolean inWall,
			boolean onGround,
			boolean stable,
			long observedAtEpochMs
	) {
	}

	private TickResult verifyEndpoint(MinecraftNavigationWorld world, ServerPlayer player) {
		if (resolvedEndpointPosition == null || resolvedEndpointTarget == null) {
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
			return null;
		}
		boolean endpointStandable = goalEndpoint(world, resolvedEndpointPosition)
				&& Double.isFinite(world.supportHeight(
						resolvedEndpointPosition,
						resolvedEndpointTarget.x,
						resolvedEndpointTarget.z));
		boolean withinTolerance = withinEndpoint(player.position());
		boolean physicallyValid = endpointStandable && withinTolerance
				&& player.level().noCollision(player.getBoundingBox())
				&& !player.isInWall() && player.onGround();
		if (!physicallyValid) {
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
			return null;
		}
		if (endpointStabilityPosition != null
				&& player.position().distanceTo(endpointStabilityPosition) <= ENDPOINT_STABILITY_DISTANCE) {
			endpointStabilityConfirmed = true;
			return succeed(player, "DESTINATION_REACHED", "Destination reached at a verified standing position");
		}
		endpointStabilityPosition = player.position();
		endpointStabilityConfirmed = false;
		cancel(player);
		return TickResult.running(lastProgressValue);
	}

	private double navigationProgress(Vec3 position) {
		Vec3 target = resolvedEndpointTarget == null ? destination : resolvedEndpointTarget;
		if (navigationStartPosition == null) navigationStartPosition = position;
		return progressFromActualDistance(navigationStartPosition, target, position, lastProgressValue);
	}

	boolean withinEndpoint(Vec3 position) {
		return resolvedEndpointTarget != null
				&& satisfiesDestinationTolerance(position.distanceTo(resolvedEndpointTarget), tolerance)
				&& (arrivalRegion != null ? arrivalRegion.contains(position)
						: satisfiesRequestedEndpoint(position, resolvedEndpointPosition, resolvedEndpointTarget));
	}

	boolean satisfiesRequestedEndpoint(Vec3 position, GridPosition endpoint, Vec3 endpointTarget) {
		// Exact-cell partial support deliberately resolves requested Y to its collision surface.
		// A relocated endpoint must also satisfy the original radius, rather than granting it twice.
		Vec3 requestedTarget = endpoint.equals(grid(destination)) ? endpointTarget : destination;
		return satisfiesDestinationTolerance(position.distanceTo(requestedTarget), tolerance);
	}

	static double progressFromActualDistance(Vec3 start, Vec3 endpoint, Vec3 current, double previousProgress) {
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		Objects.requireNonNull(current, "current must not be null");
		double startDistance = start.distanceTo(endpoint);
		double remaining = current.distanceTo(endpoint);
		if (!Double.isFinite(startDistance) || !Double.isFinite(remaining)) return previousProgress;
		double currentProgress = startDistance <= 1.0E-7D
				? (remaining <= 1.0E-7D ? 1.0D : 0.0D)
				: 1.0D - remaining / startDistance;
		if (!Double.isFinite(currentProgress)) return previousProgress;
		return Math.max(0.0D, Math.min(1.0D, currentProgress));
	}

	private boolean reachedTarget(
			MinecraftNavigationWorld world,
			Vec3 playerPosition,
			PathNode waypoint,
			boolean finalWaypoint
	) {
		if (!supportedEndpoint(world, waypoint.position())
				&& !(finalWaypoint && submergedFloor(world, waypoint.position()))
				&& (waypoint.traversal() == TraversalType.SWIM || waypoint.traversal() == TraversalType.CLIMB)) {
			Vec3 target = targetFor(world, waypoint, finalWaypoint);
			double dx = playerPosition.x - target.x;
			double dz = playerPosition.z - target.z;
			return dx * dx + dz * dz <= INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED
					&& Math.abs(playerPosition.y - target.y) <= (waypoint.traversal() == TraversalType.SWIM ? 0.7D : 0.25D);
		}
		return reachedTarget(
				playerPosition,
				waypoint,
				finalWaypoint,
				world.supportHeight(waypoint.position(), playerPosition.x, playerPosition.z)
		);
	}

	private static boolean hasFullBlockSupportHeight(GridPosition position, double supportHeight) {
		return Math.abs(supportHeight - position.y()) < 1.0E-7D;
	}

	private boolean targetsExactDestination(PathNode waypoint, boolean finalWaypoint) {
		return finalWaypoint && arrivalRegion == null && waypoint.position().equals(grid(destination));
	}

	private TickResult replan(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			long nowEpochMs,
			long elapsedMs,
			boolean recovery
	) {
		if (ServerPathPlanner.currentBudget() == null) {
			try (ServerPathPlanner.TickScope ignored = ServerPathPlanner.beginServerTick()) {
				return replan(player, world, nowEpochMs, elapsedMs, recovery);
			}
		}
		GridPosition actualOrigin = grid(player.position());
		invalidatePlanning(actualOrigin, searchWorld == null || searchWorld.isCurrent());
		if (search == null) {
			if (preparation == null) {
				// Keep the lease and sprint through planning; releasing here made every replan a visible
				// stop, sprint drop and re-acceleration (the sprint-walk-sprint gait).
				coast(player);
				plan = null;
				searchWorld = world;
				searchOrigin = actualOrigin;
				searchRecovery = recovery;
				preparation = new Preparation(actualOrigin, destination, tolerance, arrivalRegion, player.position());
			}
			ServerPathPlanner.TickBudget budget = ServerPathPlanner.currentBudget();
			if (!preparation.advance(searchWorld, position -> searchWorld.supportHeight(
					position, position.x() + 0.5D, position.z() + 0.5D), budget::tryPrepare)) {
				coast(player);
				return TickResult.running(currentProgress());
			}
			GridPosition start = preparation.start;
			if (start == null) return fail(player, "NO_STANDABLE_PATH", "Start has no supported, climbable or swimmable position", currentProgress());
			searchGoals = Set.copyOf(preparation.goals);
			if (preparation.destinationHasNoSupport) {
				return fail(player, "NO_STANDABLE_PATH", "Destination has no supported arrival region that can be approached within the requested tolerance", currentProgress());
			}
			preparation = null;
			search = planner.beginSearch(start, searchGoals, grid(destination), (int) MAX_LOCAL_PLANNING_DISTANCE, previousFrontiers,
					SwimPlanning.breathNodes(player.getAirSupply()));
		}
		ServerPathPlanner.PlanningResult planning = planner.resume(search, searchWorld);
		if (planning.deferred()) {
			coast(player);
			return TickResult.running(currentProgress());
		}
		PathPlan candidate = planning.plan();
		search = null;
		if (candidate.outcome() != PathOutcome.FOUND) {
			if (SwimPlanning.needsSurfacing(player.isInWater(), player.isUnderWater()) && !mustSurface) {
				// No route from under water within this breath: surface and plan again from the air.
				mustSurface = true;
				return TickResult.running(currentProgress());
			}
			return fail(player, candidate.outcome() == PathOutcome.NODE_LIMIT ? "PATH_LIMIT_REACHED" : "NO_PATH",
					"No reachable local route or unexplored route boundary is available", currentProgress());
		}
		if (candidate.nodes().size() > DEFAULT_MAX_PATH_LENGTH) {
			candidate = new PathPlan(candidate.nodes().subList(0, DEFAULT_MAX_PATH_LENGTH), PathOutcome.FOUND, candidate.expandedNodes());
		}
		GridPosition endpoint = candidate.nodes().getLast().position();
		boolean finalSegment = searchGoals.contains(endpoint);
		if (!finalSegment) {
			previousFrontiers.add(endpoint);
			while (previousFrontiers.size() > 64) previousFrontiers.remove(previousFrontiers.getFirst());
		}
		plan = candidate;
		if (TICK_DIAGNOSTICS_ENABLED) {
			LOGGER.info("NAV_PLAN tick={} nodes={} finalSegment={} recovery={} expanded={}",
					player.level().getGameTime(), candidate.nodes().size(), finalSegment, recovery, candidate.expandedNodes());
		}
		if (finalSegment) {
			PathNode finalNode = candidate.nodes().get(candidate.nodes().size() - 1);
			resolvedEndpointTarget = null;
			resolvedEndpointPosition = finalNode.position();
			resolvedEndpointTarget = targetFor(world, finalNode, true);
			if (arrivalRegion == null && !targetsExactDestination(finalNode, true)) {
				Vec3 inward = world.inwardStandingTarget(finalNode.position(), destination, player.getBoundingBox());
				if (inward != null && inward.distanceTo(destination) < tolerance) resolvedEndpointTarget = inward;
			}
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
		} else {
			resolvedEndpointPosition = null;
			resolvedEndpointTarget = null;
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
		}
		waypointIndex = Math.min(1, Math.max(0, candidate.nodes().size() - 1));
		PathNode activeWaypoint = candidate.nodes().get(waypointIndex);
		boolean finalWaypoint = waypointIndex == candidate.nodes().size() - 1;
		double activeWaypointDistance = player.position().distanceTo(targetFor(world, activeWaypoint, finalWaypoint));
		if (progress == null) {
			progress = new WaypointProgress(activeWaypointDistance, nowEpochMs, STALL_TIMEOUT_MS, MAX_REPLANS);
		} else if (searchRecovery) {
			progress.replanned(activeWaypointDistance, nowEpochMs);
		} else {
			progress.waypointAdvanced(activeWaypointDistance, nowEpochMs);
		}
		return null;
	}

	/** How the world above looks to a swimmer: water, open air (the surface) or solid (a downward bubble column too). */
	static SwimPlanning.ColumnView columnView(net.minecraft.server.level.ServerLevel level) {
		return (x, y, z) -> {
			if (level.isOutsideBuildHeight(y) || !level.hasChunk(x >> 4, z >> 4)) return SwimPlanning.Cell.SOLID;
			net.minecraft.core.BlockPos position = new net.minecraft.core.BlockPos(x, y, z);
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(position);
			if (!state.getCollisionShape(level, position).isEmpty()) return SwimPlanning.Cell.SOLID;
			if (state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)
					&& state.getValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN)) return SwimPlanning.Cell.SOLID;
			if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return SwimPlanning.Cell.WATER;
			return state.getFluidState().isEmpty() ? SwimPlanning.Cell.OPEN : SwimPlanning.Cell.SOLID;
		};
	}

	/**
	 * One surfacing tick: hold jump, look up and sprint-swim toward the nearest open surface, which is how a player
	 * gets out of deep water; the route is planned once the eyes are above the surface.
	 */
	private void surface(ServerPlayer player, long nowEpochMs, SwimPlanning.Surface open) {
		if (inputLease == null) {
			inputController = AgentInputRuntime.controller(player);
			inputLease = inputController.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.NAVIGATION, 100);
		}
		if (motorState == null) motorState = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		double dx = open.x() + 0.5D - player.getX();
		double dz = open.z() + 0.5D - player.getZ();
		boolean across = dx * dx + dz * dz > 0.16D;
		float yaw = !across ? motorState.yaw()
				: net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
		boolean swimSprint = across && SwimPlanning.swimSprint(true, true, 1.0F, player.getFoodData().getFoodLevel());
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(motorState,
				new AgentInputStates.MotorTarget(yaw, SwimPlanning.SURFACING_PITCH, across, SwimPlanning.holdJump(true), swimSprint),
				nowEpochMs);
		motorState = step.state();
		lastSprint = step.sprint();
		inputController.apply(inputLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				step.forward(), step.strafe(), step.jump(), false, step.sprint(),
				false, false, step.state().yaw(), step.state().pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	/** Holds the lease, view and sprint with no movement keys while a route is being planned. */
	private void coast(ServerPlayer player) {
		if (inputLease == null || motorState == null) return;
		// Afloat, keep holding jump while planning; releasing it sank the body back under between plans.
		inputController.apply(inputLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				0.0F, 0.0F, SwimPlanning.floatJump(player.isInWater(), player.onGround(), player.isUnderWater()), false,
				lastSprint && player.getFoodData().getFoodLevel() > 6,
				false, false, motorState.yaw(), motorState.pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	private void drive(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			PathNode waypoint,
			Vec3 target,
			Vec3 steer,
			boolean steerIsEndpoint,
			boolean steerIsWaypoint,
			long nowEpochMs
	) {
		boolean gapJump = waypoint.traversal() == TraversalType.JUMP_GAP;
		boolean swimming = waypoint.traversal() == TraversalType.SWIM || player.isInWater();
		// Over a submerged-floor endpoint let go of space and sink onto it, as a player does to stand on a flooded floor.
		boolean settleOnFloor = waypoint.position().equals(resolvedEndpointPosition) && submergedFloor(world, waypoint.position());
		boolean climbing = waypoint.traversal() == TraversalType.CLIMB;
		boolean crouching = waypoint.traversal() == TraversalType.CROUCH;
		double climbDx = player.getX() - target.x;
		double climbDz = player.getZ() - target.z;
		boolean atClimbColumn = climbDx * climbDx + climbDz * climbDz <= 0.16D;
		boolean descendingClimb = climbing && atClimbColumn && target.y < player.getY() - 0.15D;
		if (inputLease == null) {
			inputController = AgentInputRuntime.controller(player);
			inputLease = inputController.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.NAVIGATION, 100);
		}
		if (motorState == null) motorState = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		double steerDx = steer.x - player.getX();
		double steerDz = steer.z - player.getZ();
		double steerHorizontal = Math.sqrt(steerDx * steerDx + steerDz * steerDz);
		boolean walkingOnGround = !swimming && !climbing && !crouching && !gapJump && player.onGround();
		// Swimming and climbing move where the view points (no separate walk direction), so they never hold the
		// heading near a point: a swimmer that held it swam on past the point, looped back and stalled.
		Heading heading = heading(motorState.yaw(), steerDx, steerDz, steerIsEndpoint,
				walkingOnGround && steerIsWaypoint ? gazeYaw(player.position(), plan.nodes(), waypointIndex) : Float.NaN,
				!swimming && !climbing);
		float targetYaw = heading.viewYaw();
		float moveYaw = swimming || climbing ? Float.NaN : heading.moveYaw();
		float targetPitch;
		if (swimming || climbing) {
			Vec3 delta = target.add(0.0D, 0.85D, 0.0D).subtract(player.getEyePosition());
			targetPitch = net.minecraft.util.Mth.clamp((float) -Math.toDegrees(
					Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z))), -90.0F, 90.0F);
		} else {
			targetPitch = walkingGazePitch(steer.y - player.getY(), steerHorizontal);
		}
		if (climbing && atClimbColumn) {
			net.minecraft.core.Direction wall = world.climbDirection(waypoint.position());
			if (wall != null) targetYaw = (float) Math.toDegrees(Math.atan2(-wall.getStepX(), wall.getStepZ()));
			moveYaw = Float.NaN;
		}
		boolean jump = jumpPending(player, world, waypoint, target);
		// With sprint requested, sprint-swim while the eyes are under water (faster, steered by the view pitch).
		boolean sprintSwim = sprint && !settleOnFloor && SwimPlanning.swimSprint(player.isInWater(), player.isUnderWater(),
				1.0F, player.getFoodData().getFoodLevel());
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(
				motorState,
				new AgentInputStates.MotorTarget(
						targetYaw,
						targetPitch,
						!descendingClimb,
						jump,
						sprintSwim || !swimming && !crouching && !climbing && (sprint || gapJump) && player.getFoodData().getFoodLevel() > 6,
						moveYaw
				),
				nowEpochMs
		);
		motorState = step.state();
		lastSprint = step.sprint();
		inputController.apply(inputLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				step.forward(), step.strafe(), step.jump(), crouching, step.sprint(),
				false, false, step.state().yaw(), step.state().pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	private boolean jumpPending(ServerPlayer player, MinecraftNavigationWorld world, PathNode waypoint, Vec3 target) {
		boolean shallowWater = world.isShallowWater(waypoint.position());
		boolean swimming = waypoint.traversal() == TraversalType.SWIM || player.isInWater();
		boolean settleOnFloor = waypoint.position().equals(resolvedEndpointPosition) && submergedFloor(world, waypoint.position());
		boolean climbing = waypoint.traversal() == TraversalType.CLIMB;
		double targetDx = target.x - player.getX();
		double targetDz = target.z - player.getZ();
		return jumpNeeded(waypoint.traversal(), target.y - player.getY(),
				Math.sqrt(targetDx * targetDx + targetDz * targetDz))
				|| shallowWater || SwimPlanning.navigationJump(swimming, target.y - player.getY(), settleOnFloor)
				|| (climbing && target.y > player.getY() + 0.15D);
	}

	private MinecraftNavigationWorld navigationWorld(net.minecraft.server.level.ServerLevel level) {
		if (navigationWorld == null || !navigationWorld.belongsTo(level) || !navigationWorld.isCurrent()) {
			navigationWorld = new MinecraftNavigationWorld(level);
		}
		return navigationWorld;
	}

	private void noteReplan(String reason, ServerPlayer player) {
		if (!TICK_DIAGNOSTICS_ENABLED) return;
		LOGGER.info("NAV_REPLAN tick={} reason={} onGround={} waypoint={}/{}",
				player.level().getGameTime(), reason, player.onGround(), waypointIndex, plan == null ? 0 : plan.nodes().size());
	}

	private void logTickDiagnostics(ServerPlayer player, long durationNanos) {
		Vec3 position = player.position();
		Vec3 velocity = player.getDeltaMovement();
		Vec3 previous = diagnosticPreviousPosition;
		diagnosticPreviousPosition = position;
		String delta = previous == null ? "-" : String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f",
				position.x - previous.x, position.y - previous.y, position.z - previous.z);
		String lease = "-";
		String winner = "-";
		try {
			var agentId = AgentInputRuntime.findAgentId(player).orElse(null);
			if (agentId != null) {
				var controller = AgentInputRuntime.existingController(player.level().getServer()).orElse(null);
				if (controller == null) throw new IllegalStateException("input controller is unavailable");
				var winningLease = controller.currentWinner(agentId).orElse(null);
				var winningState = controller.currentState(agentId).orElse(null);
				if (winningLease != null) lease = winningLease.owner() + ":" + winningLease.priority() + ":" + winningLease.sequence();
				if (winningState != null) winner = String.format(java.util.Locale.ROOT,
						"f%.2f,s%.2f,j%s,sp%s,y%.1f,p%.1f", winningState.forward(), winningState.strafe(),
						winningState.jump(), winningState.sprint(), winningState.yaw(), winningState.pitch());
			}
		} catch (RuntimeException error) {
			lease = "unavailable:" + error.getClass().getSimpleName();
		}
		String traversal = plan == null || waypointIndex >= plan.nodes().size()
				? "-" : plan.nodes().get(waypointIndex).traversal().name();
		LOGGER.info("NAV_TICK tick={} elapsedMs={} durationUs={} pos={},{},{} dpos={} vel={},{},{} "
				+ "ground={} hcoll={} vcoll={} motor={} winner={} lease={} planNodes={} prep={} search={} waypoint={}/{} traversal={} "
				+ "fallDistance={} hurtTime={} hurtMarked={} target={}",
				player.level().getGameTime(), elapsedTime.elapsedMs(), durationNanos / 1_000L,
				position.x, position.y, position.z, delta, velocity.x, velocity.y, velocity.z,
				player.onGround(), player.horizontalCollision, player.verticalCollision,
				motorState == null ? "-" : String.format(java.util.Locale.ROOT, "f%.2f,s%.2f,j%s,sp%s",
						motorState.forward(), motorState.strafe(), motorState.jumpHeld(), lastSprint),
				winner, lease, plan == null ? 0 : plan.nodes().size(), preparation != null,
				search != null, waypointIndex, plan == null ? 0 : plan.nodes().size(), traversal,
				player.fallDistance, player.hurtTime, player.hurtMarked,
				diagnosticTarget == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", diagnosticTarget.x, diagnosticTarget.y, diagnosticTarget.z));
	}

	/**
	 * Jump only where the terrain needs it: next to a step taller than vanilla's step height, or from the
	 * take-off cell of a gap. Holding jump for the whole waypoint re-jumped on every landing short of the
	 * waypoint center, which read as a jump-walk-jump gait.
	 */
	static boolean jumpNeeded(TraversalType traversal, double rise, double horizontalDistance) {
		return switch (traversal) {
			case JUMP_UP -> rise > STEP_HEIGHT && horizontalDistance <= JUMP_UP_TRIGGER_DISTANCE;
			case JUMP_GAP -> horizontalDistance > GAP_TAKEOFF_MIN_DISTANCE
					&& horizontalDistance <= GAP_TAKEOFF_MAX_DISTANCE;
			default -> false;
		};
	}

	/** Steady gaze toward the steering point, tilted by the slope; avoids nodding at each node near the feet. */
	static float walkingGazePitch(double rise, double horizontalDistance) {
		double run = Math.max(GAZE_MIN_HORIZONTAL, horizontalDistance);
		return net.minecraft.util.Mth.clamp(
				WALKING_GAZE_PITCH - (float) Math.toDegrees(Math.atan2(rise, run)), -60.0F, 60.0F);
	}

	/** View and walk direction for one tick; {@code moveYaw} is NaN when the body walks where it looks. */
	record Heading(float viewYaw, float moveYaw) {
	}

	/**
	 * Where to look and where to walk. Within {@link #HOLD_HEADING_DISTANCE} of the endpoint, or of a waypoint that
	 * has fallen behind (stepping down a stair carries the body past the cell center), the view keeps its heading
	 * and the keys step onto the point, as a player backs up half a block instead of spinning round to face it.
	 * Turning toward a point that close also swings wildly as atan2 sweeps around it; the play-test showed a
	 * 180 degree spin at the end of 76 of 117 short moves. Otherwise the view follows {@code gazeYaw} (the path
	 * ahead) when it is within {@link #MAX_GAZE_OFFSET_DEGREES} of the walk direction, else the walk direction.
	 */
	static Heading heading(float currentYaw, double dx, double dz, boolean endpoint, float gazeYaw) {
		return heading(currentYaw, dx, dz, endpoint, gazeYaw, true);
	}

	/** As above; with {@code mayHold} false (swimming, climbing) the view always turns toward a nearby point. */
	static Heading heading(float currentYaw, double dx, double dz, boolean endpoint, float gazeYaw, boolean mayHold) {
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// Directly over the point atan2 has no direction; holding the heading avoids a spurious turn.
		if (horizontal < 0.05D) return new Heading(currentYaw, Float.NaN);
		float moveYaw = net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
		float offView = Math.abs(AgentInputStates.shortestAngleDelta(currentYaw, moveYaw));
		if (mayHold && horizontal < HOLD_HEADING_DISTANCE && (endpoint || offView > BEHIND_DEGREES)) {
			return new Heading(currentYaw, moveYaw);
		}
		if (!Float.isNaN(gazeYaw) && offView <= 90.0F
				&& Math.abs(AgentInputStates.shortestAngleDelta(moveYaw, gazeYaw)) <= MAX_GAZE_OFFSET_DEGREES) {
			return new Heading(gazeYaw, moveYaw);
		}
		return new Heading(moveYaw, Float.NaN);
	}

	/**
	 * Direction to the furthest of the next {@link #GAZE_LOOKAHEAD_NODES} walk, step and drop nodes, or NaN. Used
	 * where the straight-line lookahead stops (each level change of a stair): steering node by node there turns
	 * the view left and right at every step of a diagonal staircase, while a player looks down the stairs.
	 */
	static float gazeYaw(Vec3 position, List<PathNode> nodes, int index) {
		int furthest = -1;
		int last = Math.min(nodes.size() - 1, index + GAZE_LOOKAHEAD_NODES);
		for (int candidate = index; candidate <= last; candidate++) {
			TraversalType traversal = nodes.get(candidate).traversal();
			if (traversal != TraversalType.WALK && traversal != TraversalType.DROP_DOWN
					&& traversal != TraversalType.JUMP_UP) break;
			furthest = candidate;
		}
		if (furthest <= index) return Float.NaN;
		GridPosition far = nodes.get(furthest).position();
		double dx = far.x() + 0.5D - position.x;
		double dz = far.z() + 0.5D - position.z;
		if (dx * dx + dz * dz < GAZE_MIN_DISTANCE * GAZE_MIN_DISTANCE) return Float.NaN;
		return net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
	}

	/**
	 * Index of the furthest node, up to {@link #STEERING_LOOKAHEAD_NODES} ahead, that the player can walk
	 * to in a straight line over standable cells at its own level. The planner only links cardinal
	 * neighbours, so following each node turns a diagonal route into a staircase of 90 degree turns.
	 */
	static int steeringIndex(WalkabilityView world, Vec3 position, List<PathNode> nodes, int index) {
		PathNode first = nodes.get(index);
		int level = first.position().y();
		if (first.traversal() != TraversalType.WALK || grid(position).y() != level) return index;
		int best = index;
		int last = Math.min(nodes.size() - 1, index + STEERING_LOOKAHEAD_NODES);
		for (int candidate = index + 1; candidate <= last; candidate++) {
			PathNode node = nodes.get(candidate);
			if (node.traversal() != TraversalType.WALK || node.position().y() != level) break;
			if (!clearWalkLine(world, position, center(node.position()), level)) break;
			best = candidate;
		}
		return best;
	}

	/**
	 * Index of a node within the lookahead window whose cell holds the player's feet, or -1. Nodes before it must be
	 * walk or drop nodes, which a body can step past or fly over, except that the waypoint itself may also be the
	 * landing of a gap jump or step-up. A body that overshoots a landing, or clears a short step on the way down, is
	 * already standing in a later cell; requiring it to walk back onto the skipped cell first cost about a second
	 * after most drops (measured on a headless server).
	 */
	static int occupiedWalkNode(List<PathNode> nodes, int index, GridPosition feet) {
		int last = Math.min(nodes.size() - 2, index + STEERING_LOOKAHEAD_NODES);
		for (int candidate = index; candidate <= last; candidate++) {
			PathNode node = nodes.get(candidate);
			TraversalType traversal = node.traversal();
			boolean passable = traversal == TraversalType.WALK || traversal == TraversalType.DROP_DOWN
					|| candidate == index && (traversal == TraversalType.JUMP_GAP || traversal == TraversalType.JUMP_UP);
			if (!passable) return -1;
			if (node.position().equals(feet)) return candidate;
		}
		return -1;
	}

	/**
	 * True when a grounded body at a walk or drop waypoint's level has gone past it toward the next walk or drop node,
	 * within {@link #HOLD_HEADING_DISTANCE} of its center. Without this the controller stepped back onto the waypoint
	 * (the heading hold below), though a player just keeps going down the stairs.
	 */
	static boolean passedWaypoint(Vec3 position, List<PathNode> nodes, int index, double supportY) {
		if (index < 0 || index + 1 >= nodes.size() || !Double.isFinite(supportY)) return false;
		PathNode node = nodes.get(index);
		PathNode next = nodes.get(index + 1);
		if (!walksOrFalls(node.traversal()) || !walksOrFalls(next.traversal())) return false;
		if (Math.abs(position.y - supportY) > PASSED_LEVEL_TOLERANCE) return false;
		double dx = position.x - (node.position().x() + 0.5D);
		double dz = position.z - (node.position().z() + 0.5D);
		if (dx * dx + dz * dz > HOLD_HEADING_DISTANCE * HOLD_HEADING_DISTANCE) return false;
		return dx * (next.position().x() - node.position().x()) + dz * (next.position().z() - node.position().z()) > 0.0D;
	}

	private static boolean walksOrFalls(TraversalType traversal) {
		return traversal == TraversalType.WALK || traversal == TraversalType.DROP_DOWN;
	}

	/**
	 * While falling toward a drop landing that is not the endpoint, keep walking along the path: a player holds the
	 * same key through the fall instead of pressing back toward the landing cell's center, which cancelled sprint
	 * in the air and slowed every landing.
	 */
	static int fallingSteerIndex(List<PathNode> nodes, int index, boolean onGround) {
		if (onGround || index + 1 >= nodes.size() || nodes.get(index).traversal() != TraversalType.DROP_DOWN) return index;
		return index + 1;
	}

	static boolean clearWalkLine(WalkabilityView world, Vec3 from, Vec3 to, int level) {
		return clearWalkLineWithScratch(world, from, to, level, CLEAR_WALK_LINE_SCRATCH.get());
	}

	static boolean clearWalkLineWithScratch(WalkabilityView world, Vec3 from, Vec3 to, int level, long[] checked) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / STEERING_SAMPLE_SPACING));
		if (checked.length == 0) throw new IllegalArgumentException("clearWalkLine scratch must not be empty");
		int cursor = 0;
		int checkedCount = 0;
		for (int sample = 0; sample <= samples; sample++) {
			double t = (double) sample / samples;
			double x = from.x + dx * t;
			double z = from.z + dz * t;
			for (int corner = 0; corner < 4; corner++) {
				int cellX = (int) Math.floor(x + ((corner & 1) == 0 ? -STEERING_HALF_WIDTH : STEERING_HALF_WIDTH));
				int cellZ = (int) Math.floor(z + ((corner & 2) == 0 ? -STEERING_HALF_WIDTH : STEERING_HALF_WIDTH));
				long key = (long) cellX << 32 | cellZ & 0xffffffffL;
				boolean seen = false;
				for (int previous = 0; previous < checkedCount; previous++) {
					if (checked[previous] == key) {
						seen = true;
						break;
					}
				}
				if (seen) continue;
				checked[cursor] = key;
				cursor = (cursor + 1) % checked.length;
				checkedCount = Math.min(checkedCount + 1, checked.length);
				if (world.traversalAt(new GridPosition(cellX, level, cellZ)) != TraversalType.WALK) return false;
			}
		}
		return true;
	}

	private static final ThreadLocal<long[]> CLEAR_WALK_LINE_SCRATCH = ThreadLocal.withInitial(() -> new long[8]);

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

	/** Retains enumeration order and terrain facts across server-tick budget boundaries. */
	static final class Preparation {
		private final Vec3 destination;
		private final double tolerance;
		private final AABB region;
		private final Vec3 actualStart;
		private final List<GridPosition> starts = new ArrayList<>();
		final List<GridPosition> goals = new ArrayList<>();
		GridPosition start;
		boolean destinationHasNoSupport;
		private int startIndex;
		private int x, y, z, minY, minZ, maxX, maxY, maxZ;
		private boolean initializedGoals;
		private boolean enumerated;
		private boolean complete;

		Preparation(GridPosition origin, Vec3 destination, double tolerance, AABB region) {
			this(origin, destination, tolerance, region, center(origin));
		}

		Preparation(GridPosition origin, Vec3 destination, double tolerance, AABB region, Vec3 actualStart) {
			this.actualStart = actualStart;
			this.destination = destination;
			this.tolerance = tolerance;
			this.region = region;
			for (int radius = 0; radius <= 1; radius++) {
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
						for (int vertical = 0; vertical <= 2; vertical++) {
							starts.add(origin.offset(dx, vertical, dz));
							if (vertical > 0) starts.add(origin.offset(dx, -vertical, dz));
						}
					}
				}
			}
		}

		boolean advance(WalkabilityView world, ToDoubleFunction<GridPosition> supportHeight, BooleanSupplier claimWork) {
			if (complete) return true;
			while (start == null && startIndex < starts.size()) {
				if (!claimWork.getAsBoolean()) return false;
				GridPosition candidate = starts.get(startIndex++);
				if (world.isTraversable(candidate)) start = candidate;
			}
			if (start == null || center(start).distanceTo(destination) > MAX_LOCAL_PLANNING_DISTANCE) {
				complete = true;
				return true;
			}
			if (!initializedGoals) {
				GridPosition origin = grid(destination);
				int radius = (int) Math.ceil(tolerance) + 1;
				x = region == null ? origin.x() - radius : (int) Math.floor(region.minX);
				y = minY = region == null ? origin.y() - radius : (int) Math.floor(region.minY);
				z = minZ = region == null ? origin.z() - radius : (int) Math.floor(region.minZ);
				maxX = region == null ? origin.x() + radius : (int) Math.ceil(region.maxX);
				maxY = region == null ? origin.y() + radius : (int) Math.ceil(region.maxY);
				maxZ = region == null ? origin.z() + radius : (int) Math.ceil(region.maxZ);
				initializedGoals = true;
			}
			while (!enumerated) {
				if (!claimWork.getAsBoolean()) return false;
				GridPosition candidate = new GridPosition(x, y, z);
				// Point pruning uses the possible support interval, then checks the actual collision height.
				// Region Y also requires that actual surface, so only its X/Z can be pruned.
				boolean geometryMatches = region == null ? candidateSatisfiesTolerance(candidate, destination, tolerance)
						: x + 0.5D >= region.minX && x + 0.5D < region.maxX
						&& z + 0.5D >= region.minZ && z + 0.5D < region.maxZ;
				if (geometryMatches && goalEndpoint(world, candidate)) {
					if (region == null) {
						if (approachableEndpoint(candidate, destination, tolerance,
								supportHeight.applyAsDouble(candidate), actualStart)) goals.add(candidate);
					} else {
						double support = supportHeight.applyAsDouble(candidate);
						if (Double.isFinite(support) && region.contains(x + 0.5D, support, z + 0.5D)) goals.add(candidate);
					}
				}
				if (++y > maxY) {
					y = minY;
					if (++z > maxZ) { z = minZ; if (++x > maxX) enumerated = true; }
				}
			}
			if (goals.isEmpty()) {
				if (!claimWork.getAsBoolean()) return false;
				destinationHasNoSupport = world.cellAt(grid(destination)) != WalkabilityView.Cell.UNLOADED;
			}
			// The planner consumes goal membership, not list order; retain enumeration order.
			complete = true;
			return true;
		}
	}

	static boolean approachableEndpoint(GridPosition candidate, Vec3 destination, double tolerance,
			double supportHeight, Vec3 actualPosition) {
		if (!Double.isFinite(supportHeight)) return false;
		if (candidate.equals(grid(destination))) return true;
		Vec3 surface = new Vec3(candidate.x() + 0.5D, supportHeight, candidate.z() + 0.5D);
		if (surface.distanceTo(destination) > tolerance) return false;
		// A sphere tangent to the standing plane has no horizontal arrival area to steer into.
		// Preserve an already satisfied boundary instead of weakening the requested radius.
		return Math.abs(supportHeight - destination.y) < tolerance
				|| (actualPosition.distanceTo(destination) <= tolerance
						&& actualPosition.distanceTo(surface) <= tolerance);
	}

	static boolean candidateSatisfiesTolerance(GridPosition candidate, Vec3 destination, double tolerance) {
		if (candidate.equals(grid(destination))) return true;
		// Safe support lies in the block below the feet cell, and may be fractional (slabs/stairs).
		double closestY = Math.max(candidate.y() - 1D, Math.min(candidate.y(), destination.y));
		return new Vec3(candidate.x() + 0.5D, closestY, candidate.z() + 0.5D).distanceTo(destination) <= tolerance;
	}

	private static boolean supportedEndpoint(WalkabilityView world, GridPosition position) {
		TraversalType traversal = world.traversalAt(position);
		return traversal == TraversalType.WALK || traversal == TraversalType.CROUCH;
	}

	/** The floor of flooded space: a player can stand on it under water (ore in a water cave is mined from here). */
	static boolean submergedFloor(WalkabilityView world, GridPosition position) {
		return world.isSubmerged(position) && world.cellAt(position.below()) == WalkabilityView.Cell.SAFE_SUPPORT;
	}

	/** A navigation may end standing on dry support or on a submerged floor; both are verified on the ground. */
	static boolean goalEndpoint(WalkabilityView world, GridPosition position) {
		return supportedEndpoint(world, position) || submergedFloor(world, position);
	}

	/** Consecutive submerged route cells from {@code index}: the swim the breath must cover before reaching air. */
	static int submergedNodesAhead(WalkabilityView world, List<PathNode> nodes, int index) {
		int count = 0;
		for (int i = Math.max(0, index); i < nodes.size() && world.isSubmerged(nodes.get(i).position()); i++) count++;
		return count;
	}

	static boolean hasAirReserve(boolean inWater, int air) {
		return !inWater || air > MIN_AIR_RESERVE;
	}
}
