package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/** Player-like motion: eased turning, smoothed steering, steady gaze and need-based jumps. */
public final class NavigationMotionVerification {
	private static final int LEVEL = 64;
	private static int assertions;

	private NavigationMotionVerification() {
	}

	public static int verify() {
		assertions = 0;
		verifyEasedTurning();
		verifyLookController();
		verifySteering();
		verifyOccupiedNode();
		verifyJumps();
		verifyGaze();
		verifyHeadingHold();
		verifyStairOvershootDoesNotSpin();
		verifyStaircaseGaze();
		return assertions;
	}

	private static void verifyHeadingHold() {
		// Body just past a stair landing's center: the point is 0.2 behind.
		ServerNavigationController.Heading behind = ServerNavigationController.heading(-82.5F, -0.2D, 0.0D, false, Float.NaN);
		assertEquals(-82.5F, behind.viewYaw(), "a passed waypoint inside one block keeps the view's heading");
		assertTrue(Math.abs(AgentInputStates.shortestAngleDelta(90.0F, behind.moveYaw())) < 1.0F,
				"the keys still walk back toward the passed point");
		ServerNavigationController.Heading endpoint = ServerNavigationController.heading(0.0F, 0.6D, 0.0D, true, Float.NaN);
		assertEquals(0.0F, endpoint.viewYaw(), "the final approach sidesteps instead of turning to a point under a block away");
		ServerNavigationController.Heading corner = ServerNavigationController.heading(0.0F, 0.9D, 0.0D, false, Float.NaN);
		assertTrue(Float.isNaN(corner.moveYaw()), "a sideways waypoint that is not behind still turns the view (path corners)");
		ServerNavigationController.Heading far = ServerNavigationController.heading(0.0F, 0.0D, -3.0D, true, Float.NaN);
		assertTrue(Float.isNaN(far.moveYaw()) && Math.abs(AgentInputStates.shortestAngleDelta(far.viewYaw(), 180.0F)) < 1.0F,
				"a destination more than a block behind is turned to and walked to, as before");
		ServerNavigationController.Heading over = ServerNavigationController.heading(33.0F, 0.01D, 0.02D, true, Float.NaN);
		assertEquals(33.0F, over.viewYaw(), "directly over the point the view holds");

		AgentInputStates.MotorStep back = AgentInputStates.stepMotor(
				new AgentInputStates.MotorState(-82.5F, 20.0F, 0.0F, 0.0F, false),
				new AgentInputStates.MotorTarget(-82.5F, 10.0F, true, false, true, 97.5F), 0L);
		assertEquals(-82.5F, back.yaw(), "a held heading never turns");
		assertTrue(back.forward() < 0.0F && Math.abs(back.strafe()) < 1.0E-3F, "a point behind is reached by backing up");
		assertTrue(!back.sprint(), "no sprint while backing up");
	}

	/**
	 * Closed-loop replay of the play-test staircase step: a move_to one block down carries the body about 0.2
	 * past the landing's center. Old steering turned the view toward the point behind (the trace's 180 degree
	 * spin at 45 degrees a tick); the held heading backs onto it without turning.
	 */
	private static void verifyStairOvershootDoesNotSpin() {
		StairRun old = simulateStairStep(false);
		StairRun held = simulateStairStep(true);
		assertTrue(old.yawTravel() > 120.0F, "coupled steering reproduces the play-test spin, travelled " + old.yawTravel());
		assertTrue(held.yawTravel() <= 1.0F, "held heading does not turn during the overshoot, travelled " + held.yawTravel());
		assertTrue(held.maxStep() <= 1.0F, "held heading has no per-tick yaw jump, max " + held.maxStep());
		assertTrue(held.closestAfterLanding() < 0.15D, "the body backs onto the landing, closest " + held.closestAfterLanding());
	}

	private record StairRun(float yawTravel, float maxStep, double closestAfterLanding) {
	}

	private static StairRun simulateStairStep(boolean hold) {
		double targetX = 76.5D;
		double targetZ = 231.5D;
		double x = 76.3D;
		double z = 231.55D;
		double vx = 0.24D;
		double vz = 0.0D;
		AgentInputStates.MotorState motor = new AgentInputStates.MotorState(-82.5F, 28.0F, 1.0F, 0.0F, false);
		float travel = 0.0F;
		float maxStep = 0.0F;
		double closest = Double.MAX_VALUE;
		for (int tick = 0; tick < 30; tick++) {
			boolean airborne = tick < 4;
			double dx = targetX - x;
			double dz = targetZ - z;
			AgentInputStates.MotorTarget target;
			if (hold) {
				ServerNavigationController.Heading heading = ServerNavigationController.heading(motor.yaw(), dx, dz, true, Float.NaN);
				target = new AgentInputStates.MotorTarget(heading.viewYaw(), 10.0F, true, false, false, heading.moveYaw());
			} else {
				float yaw = Math.hypot(dx, dz) < 0.05D ? motor.yaw()
						: net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
				target = new AgentInputStates.MotorTarget(yaw, 10.0F, true, false, false);
			}
			AgentInputStates.MotorStep step = AgentInputStates.stepMotor(motor, target, tick * 50L);
			float turned = Math.abs(AgentInputStates.shortestAngleDelta(motor.yaw(), step.yaw()));
			travel += turned;
			maxStep = Math.max(maxStep, turned);
			motor = step.state();
			// Vanilla moveRelative: strafe is +x in input space, forward +z, rotated by the view yaw.
			double radians = Math.toRadians(step.yaw());
			double sin = Math.sin(radians);
			double cos = Math.cos(radians);
			double ax = step.strafe() * cos - step.forward() * sin;
			double az = step.forward() * cos + step.strafe() * sin;
			double length = Math.hypot(ax, az);
			if (length > 1.0D) {
				ax /= length;
				az /= length;
			}
			double acceleration = airborne ? 0.02D : 0.1D;
			double friction = airborne ? 0.91D : 0.546D;
			vx = vx * friction + ax * acceleration;
			vz = vz * friction + az * acceleration;
			x += vx;
			z += vz;
			if (!airborne) closest = Math.min(closest, Math.hypot(targetX - x, targetZ - z));
		}
		return new StairRun(travel, maxStep, closest);
	}

	/** A diagonal staircase: east and south steps, each one block down. */
	private static void verifyStaircaseGaze() {
		List<PathNode> nodes = new ArrayList<>();
		int x = 0;
		int y = LEVEL;
		int z = 0;
		nodes.add(new PathNode(new GridPosition(x, y, z), TraversalType.WALK));
		for (int step = 0; step < 4; step++) {
			nodes.add(new PathNode(new GridPosition(++x, --y, z), TraversalType.DROP_DOWN));
			nodes.add(new PathNode(new GridPosition(x, --y, ++z), TraversalType.DROP_DOWN));
		}
		float oldMin = 180.0F;
		float oldMax = -180.0F;
		float newMin = 180.0F;
		float newMax = -180.0F;
		float view = -45.0F;
		for (int index = 1; index < nodes.size() - 3; index++) {
			GridPosition from = nodes.get(index - 1).position();
			GridPosition to = nodes.get(index).position();
			Vec3 position = new Vec3(from.x() + 0.5D, from.y(), from.z() + 0.5D);
			double dx = to.x() - from.x();
			double dz = to.z() - from.z();
			float nodeYaw = net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
			oldMin = Math.min(oldMin, nodeYaw);
			oldMax = Math.max(oldMax, nodeYaw);
			float gaze = ServerNavigationController.gazeYaw(position, nodes, index);
			ServerNavigationController.Heading heading = ServerNavigationController.heading(view, dx, dz, false, gaze);
			view = heading.viewYaw();
			newMin = Math.min(newMin, view);
			newMax = Math.max(newMax, view);
			assertTrue(!Float.isNaN(heading.moveYaw()), "the keys still walk each stair cell while the view looks down the stairs");
		}
		assertEquals(90.0F, oldMax - oldMin, "node-by-node steering swung the view 90 degrees at every stair");
		assertTrue(newMax - newMin <= 30.0F, "looking down the stairs keeps the view within 30 degrees, swung "
				+ (newMax - newMin));
		List<PathNode> ladder = List.of(nodes.get(0), new PathNode(new GridPosition(0, LEVEL + 1, 0), TraversalType.CLIMB));
		assertTrue(Float.isNaN(ServerNavigationController.gazeYaw(new Vec3(0.5D, LEVEL, 0.5D), ladder, 0)),
				"climbs keep their own facing");
	}

	private static void verifyEasedTurning() {
		assertEquals(AgentInputStates.MAX_TURN_STEP_DEGREES,
				Math.abs(AgentInputStates.shortestAngleDelta(0.0F, AgentInputStates.turnYaw(0.0F, 180.0F))),
				"a large turn starts at the flick speed limit");
		int flick = ticksToTurn(0.0F, 179.0F);
		assertTrue(flick >= 4 && flick <= 6, "a 179 degree flick settles in 200-300 ms, took " + flick + " ticks");
		int quarter = ticksToTurn(0.0F, 90.0F);
		assertTrue(quarter >= 3 && quarter <= 5, "a 90 degree turn settles in 150-250 ms, took " + quarter + " ticks");
		assertEquals(-178.0F, AgentInputStates.turnYaw(178.0F, -178.0F), "a small turn across the wrap lands exactly");
		assertEquals(-90.0F, AgentInputStates.turnPitch(-85.0F, -120.0F), "pitch turns stay within the vertical range");
	}

	private static int ticksToTurn(float from, float to) {
		float yaw = from;
		float remaining = Math.abs(AgentInputStates.shortestAngleDelta(yaw, to));
		for (int tick = 1; tick <= 40; tick++) {
			yaw = AgentInputStates.turnYaw(yaw, to);
			float next = Math.abs(AgentInputStates.shortestAngleDelta(yaw, to));
			assertTrue(next < remaining, "each eased step closes the turn without overshoot");
			remaining = next;
			if (remaining == 0.0F) return tick;
		}
		throw new AssertionError("turn never settled");
	}

	private static void verifyLookController() {
		Vec3 eye = new Vec3(0.5D, 65.62D, 0.5D);
		ServerLookController.Angles south = ServerLookController.anglesTo(eye, eye.add(0.0D, 0.0D, 3.0D));
		assertTrue(Math.abs(south.yaw()) < 1.0E-3F, "+Z is yaw 0, matching Entity.lookAt");
		ServerLookController.Angles west = ServerLookController.anglesTo(eye, eye.add(-3.0D, 0.0D, 0.0D));
		assertTrue(Math.abs(west.yaw() - 90.0F) < 1.0E-3F, "-X is yaw 90, matching Entity.lookAt");
		ServerLookController.Angles up = ServerLookController.anglesTo(eye, eye.add(0.0D, 2.0D, 2.0D));
		assertTrue(Math.abs(up.pitch() + 45.0F) < 1.0E-3F, "looking up is negative pitch");

		ServerLookController.Angles goal = ServerLookController.anglesTo(eye, new Vec3(-2.5D, 64.5D, -1.5D));
		ServerLookController.Angles current = new ServerLookController.Angles(0.0F, 0.0F);
		ServerLookController.Angles first = ServerLookController.step(current, goal);
		assertTrue(!first.equals(goal), "a large look_at turn is spread over ticks instead of snapping");
		int ticks = 1;
		current = first;
		while (!current.equals(goal)) {
			current = ServerLookController.step(current, goal);
			if (++ticks > 10) throw new AssertionError("look controller never settled");
		}
		assertTrue(ticks <= 6, "look_at settles on the exact target within about 300 ms, took " + ticks);
		ServerLookController.Angles near = new ServerLookController.Angles(goal.yaw() + 2.0F, goal.pitch() - 1.0F);
		assertEquals(goal, ServerLookController.step(near, goal), "an already aimed look completes in one tick");
	}

	private static void verifySteering() {
		// Open field: a cardinal staircase toward the south-east runs as one diagonal.
		WalkabilityView open = world(cells(-2, 8, -2, 8));
		List<PathNode> stairs = staircase(4);
		Vec3 start = new Vec3(0.5D, LEVEL, 0.5D);
		assertEquals(stairs.size() - 1, ServerNavigationController.steeringIndex(open, start, stairs, 1),
				"open ground steers straight at the furthest visible node");

		// Corridor that is exactly the staircase: no corner may be cut.
		Set<Long> corridor = new HashSet<>();
		for (PathNode node : stairs) corridor.add(key(node.position().x(), node.position().z()));
		WalkabilityView narrow = world(corridor);
		assertEquals(1, ServerNavigationController.steeringIndex(narrow, start, stairs, 1),
				"a corridor keeps node-by-node steering so the body never clips a wall");

		List<PathNode> withStep = new ArrayList<>(stairs);
		GridPosition stepPosition = withStep.get(3).position();
		withStep.set(3, new PathNode(new GridPosition(stepPosition.x(), LEVEL + 1, stepPosition.z()), TraversalType.JUMP_UP));
		assertEquals(2, ServerNavigationController.steeringIndex(open, start, withStep, 1),
				"steering never looks past a jump or level change");
		assertEquals(1, ServerNavigationController.steeringIndex(open, new Vec3(0.5D, LEVEL + 1, 0.5D), stairs, 1),
				"steering is only smoothed on the player's own level");

		// Off-center start whose diagonal sweeps the body through cell (1, 0) between samples. The previous
		// 0.25 spacing with a 0.35 half-width checked neither sample square over that cell.
		Vec3 offCenter = new Vec3(0.49D, LEVEL, 0.03D);
		Vec3 diagonalTarget = new Vec3(1.5D, LEVEL, -1.5D);
		Set<Long> hazardBeside = cells(-3, 4, -4, 3);
		hazardBeside.remove(key(1, 0));
		assertTrue(!ServerNavigationController.clearWalkLine(world(hazardBeside), offCenter, diagonalTarget, LEVEL),
				"a hazard the swept hitbox clips between samples rejects the straight line");
		assertTrue(ServerNavigationController.clearWalkLine(world(cells(-3, 4, -4, 3)), offCenter, diagonalTarget, LEVEL),
				"the same line is clear when that cell is standable");
		assertTrue(ServerNavigationController.STEERING_HALF_WIDTH
						> 0.3D + ServerNavigationController.STEERING_SAMPLE_SPACING / 2.0D
						&& ServerNavigationController.STEERING_HALF_WIDTH < 0.5D,
				"the sample envelope covers the hitbox between samples and stays under one block wide");
	}

	private static void verifyOccupiedNode() {
		List<PathNode> stairs = staircase(4);
		assertEquals(3, ServerNavigationController.occupiedWalkNode(stairs, 1, stairs.get(3).position()),
				"standing in a later walk cell marks the nodes before it as passed");
		assertEquals(-1, ServerNavigationController.occupiedWalkNode(stairs, 1, stairs.getLast().position()),
				"the final node keeps its exact arrival check");
		List<PathNode> withStep = new ArrayList<>(stairs);
		withStep.set(2, new PathNode(withStep.get(2).position(), TraversalType.JUMP_UP));
		assertEquals(-1, ServerNavigationController.occupiedWalkNode(withStep, 1, stairs.get(3).position()),
				"a jump node is never skipped");
	}

	private static void verifyJumps() {
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.JUMP_UP, 1.0D, 1.5D),
				"no jump while still a block and a half from the step");
		assertTrue(ServerNavigationController.jumpNeeded(TraversalType.JUMP_UP, 1.0D, 0.9D),
				"jump next to a full block step");
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.JUMP_UP, 0.5D, 0.9D),
				"a slab is climbed by step height, without a jump");
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.JUMP_UP, 0.0D, 0.9D),
				"landing on the step releases the jump instead of hopping again");
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.JUMP_GAP, 0.0D, 2.4D),
				"gap jumps wait for the take-off cell");
		assertTrue(ServerNavigationController.jumpNeeded(TraversalType.JUMP_GAP, 0.0D, 1.6D),
				"gap jumps fire from the take-off cell edge");
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.JUMP_GAP, 0.0D, 0.7D),
				"a landed gap jump does not re-jump");
		assertTrue(!ServerNavigationController.jumpNeeded(TraversalType.WALK, 0.0D, 0.5D), "walking never jumps");
	}

	private static void verifyGaze() {
		float near = ServerNavigationController.walkingGazePitch(0.0D, 0.6D);
		float far = ServerNavigationController.walkingGazePitch(0.0D, 1.0D);
		assertEquals(near, far, "flat walking gaze is steady between nodes instead of nodding toward the feet");
		assertEquals(ServerNavigationController.WALKING_GAZE_PITCH, near, "flat walking looks slightly below the horizon");
		assertTrue(ServerNavigationController.walkingGazePitch(1.0D, 3.0D) < near, "uphill raises the gaze");
		assertTrue(ServerNavigationController.walkingGazePitch(-1.0D, 3.0D) > near, "downhill lowers the gaze");
	}

	/** Alternating east/south steps from (0,0): (0,0) (1,0) (1,1) (2,1) (2,2) ... */
	private static List<PathNode> staircase(int diagonalSteps) {
		List<PathNode> nodes = new ArrayList<>();
		int x = 0;
		int z = 0;
		nodes.add(new PathNode(new GridPosition(x, LEVEL, z), TraversalType.WALK));
		for (int step = 0; step < diagonalSteps; step++) {
			nodes.add(new PathNode(new GridPosition(++x, LEVEL, z), TraversalType.WALK));
			nodes.add(new PathNode(new GridPosition(x, LEVEL, ++z), TraversalType.WALK));
		}
		return nodes;
	}

	private static Set<Long> cells(int minX, int maxX, int minZ, int maxZ) {
		Set<Long> cells = new HashSet<>();
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) cells.add(key(x, z));
		return cells;
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xffffffffL);
	}

	/** Standable at LEVEL wherever the column is listed; everything else is solid. */
	private static WalkabilityView world(Set<Long> walkable) {
		return position -> {
			boolean column = walkable.contains(key(position.x(), position.z()));
			if (position.y() == LEVEL - 1) return column ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.BLOCKED;
			if (position.y() == LEVEL || position.y() == LEVEL + 1) {
				return column ? WalkabilityView.Cell.CLEAR : WalkabilityView.Cell.BLOCKED;
			}
			return WalkabilityView.Cell.CLEAR;
		};
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected " + expected + " but was " + actual);
		}
		assertions++;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		assertions++;
	}
}
