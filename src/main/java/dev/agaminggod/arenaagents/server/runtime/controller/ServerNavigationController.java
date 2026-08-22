package dev.agaminggod.arenaagents.server.runtime.controller;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class ServerNavigationController implements ServerController {
	public static final int DEFAULT_MAX_PATH_LENGTH = 256;
	private static final long STALL_TIMEOUT_MS = 4_000L;
	private static final int MAX_REPLANS = 3;

	private final Vec3 destination;
	private final double tolerance;
	private final boolean sprint;
	private final long startedAt;
	private final long timeoutMs;
	private final ServerPathPlanner planner = new ServerPathPlanner();
	private PathPlan plan;
	private int waypointIndex;
	private WaypointProgress progress;
	private double lastProgressValue;
	private InputLease inputLease;
	private AgentInputStates.MotorState motorState;

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
		this.startedAt = startedAt;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		Objects.requireNonNull(player, "player must not be null");
		if (!player.isAlive()) {
			return fail(player, "AGENT_DEAD", "Agent player died", currentProgress());
		}
		if (Math.max(0L, nowEpochMs - startedAt) >= timeoutMs) {
			return fail(player, "ACTION_TIMEOUT", "Navigation timed out", currentProgress());
		}
		double remaining = player.position().distanceTo(destination);
		if (remaining <= tolerance) {
			return succeed(player, "DESTINATION_REACHED", "Destination reached");
		}
		if (plan == null) {
			TickResult planned = replan(player, nowEpochMs, remaining, false);
			if (planned != null) return planned;
		}
		List<PathNode> nodes = plan.nodes();
		if (waypointIndex >= nodes.size()) {
			return replanOrResult(player, nowEpochMs, remaining);
		}
		PathNode waypoint = nodes.get(waypointIndex);
		Vec3 target = center(waypoint.position());
		boolean reached = reachedWaypoint(player.position(), target);
		WaypointProgress.Update update = progress.observe(remaining, reached, nowEpochMs);
		lastProgressValue = update.progress();
		if (reached) {
			waypointIndex++;
			if (waypointIndex >= nodes.size()) {
				return replanOrResult(player, nowEpochMs, remaining);
			}
			waypoint = nodes.get(waypointIndex);
			target = center(waypoint.position());
		}
		if (update.decision() == WaypointProgress.Decision.FAIL) {
			return fail(player, "PATH_BLOCKED", "Navigation could not recover from repeated stalls", update.progress());
		}
		if (update.decision() == WaypointProgress.Decision.REPLAN) {
			TickResult replanned = replan(player, nowEpochMs, remaining, true);
			if (replanned != null) return replanned;
			waypoint = plan.nodes().get(waypointIndex);
			target = center(waypoint.position());
		}
		drive(player, waypoint, target, nowEpochMs);
		return TickResult.running(update.progress());
	}

	@Override
	public void cancel(ServerPlayer player) {
		if (inputLease == null) {
			OfflineAgentPlayers.stop(player);
			motorState = null;
			return;
		}
		try {
			AgentInputRuntime.controller(player).release(inputLease);
		} catch (IllegalStateException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		inputLease = null;
		motorState = null;
	}

	private TickResult replanOrResult(ServerPlayer player, long nowEpochMs, double remaining) {
		if (remaining <= tolerance + 0.5D) {
			return succeed(player, "DESTINATION_REACHED", "Destination reached");
		}
		TickResult replanned = replan(player, nowEpochMs, remaining, true);
		return replanned == null ? TickResult.running(currentProgress()) : replanned;
	}

	private TickResult replan(
			ServerPlayer player,
			long nowEpochMs,
			double remaining,
			boolean recovery
	) {
		MinecraftNavigationWorld world = new MinecraftNavigationWorld(player.level());
		GridPosition start = nearestStandable(world, new GridPosition(
				player.blockPosition().getX(),
				player.blockPosition().getY(),
				player.blockPosition().getZ()
		), 1, 2);
		GridPosition goal = nearestStandable(world, grid(destination), 4, 3);
		if (start == null || goal == null) {
			return fail(player, "NO_STANDABLE_PATH", "Start or destination has no safe standing position", currentProgress());
		}
		PathPlan candidate = planner.findPath(world, start, goal);
		if (candidate.outcome() != PathOutcome.FOUND || candidate.nodes().size() > DEFAULT_MAX_PATH_LENGTH) {
			String reason = candidate.outcome() == PathOutcome.NODE_LIMIT
					|| candidate.outcome() == PathOutcome.TIME_LIMIT
					? "PATH_LIMIT_REACHED" : "NO_PATH";
			return fail(player, reason, "No bounded safe path is currently available", currentProgress());
		}
		plan = candidate;
		waypointIndex = Math.min(1, Math.max(0, candidate.nodes().size() - 1));
		if (progress == null) {
			progress = new WaypointProgress(remaining, nowEpochMs, STALL_TIMEOUT_MS, MAX_REPLANS);
		} else if (recovery) {
			progress.replanned(remaining, nowEpochMs);
		}
		return null;
	}

	private void drive(ServerPlayer player, PathNode waypoint, Vec3 target, long nowEpochMs) {
		boolean gapJump = waypoint.traversal() == TraversalType.JUMP_GAP;
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
						waypoint.traversal() == TraversalType.JUMP_UP || gapJump,
						(sprint || gapJump) && player.getFoodData().getFoodLevel() > 6
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
		return dx * dx + dz * dz <= 0.36D && Math.abs(player.y - waypoint.y) <= 1.25D;
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
}
