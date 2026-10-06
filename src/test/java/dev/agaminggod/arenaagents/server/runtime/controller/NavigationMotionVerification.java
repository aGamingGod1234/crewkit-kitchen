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
		return assertions;
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
