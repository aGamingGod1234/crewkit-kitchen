package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.server.goal.GoalSafetyController.HazardSnapshot;
import dev.agaminggod.arenaagents.server.goal.GoalSafetyController.SafetyDirective;
import dev.agaminggod.arenaagents.server.goal.GoalSafetyController.DamageState;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public final class GoalSafetyControllerVerification {
	private GoalSafetyControllerVerification() {
	}

	public static int verify() {
		assertEquals(SafetyDirective.SWIM_UP, decide(false, true, false, false, 20, false, false, false, false), "surface while drowning");
		assertEquals(SafetyDirective.LEAVE_LAVA, decide(false, false, true, true, 300, false, false, true, false), "leave lava before lesser hazards");
		assertEquals(SafetyDirective.LEAVE_FIRE, decide(false, false, false, true, 300, false, false, true, false), "leave fire only over a verified route");
		assertEquals(SafetyDirective.RAISE_EQUIPPED_SHIELD, decide(false, false, false, false, 300, true, true, false, true), "raise an already equipped shield after repeated damage");
		assertEquals(SafetyDirective.RETREAT_FROM_REPEATED_DAMAGE, decide(false, false, false, false, 300, true, false, false, true), "retreat only over a verified route");
		assertEquals(SafetyDirective.NONE, decide(false, false, false, false, 300, false, false, false, false), "healthy bodies receive no invented strategy");
		assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("ATTACK")), "safety cannot represent attack");
		assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("CONSUME_ITEM")), "safety cannot represent item spending");
		DamageState firstHit = GoalSafetyController.trackDamage(new DamageState(20.0F, Long.MIN_VALUE, 0), 18.0F, 100L);
		DamageState secondHit = GoalSafetyController.trackDamage(firstHit, 16.0F, 120L);
		assertTrue(secondHit.repeatedAt(120L), "second nearby hit activates the repeated-damage reflex");
		DamageState betweenHits = GoalSafetyController.trackDamage(secondHit, 16.0F, 121L);
		assertTrue(betweenHits.repeatedAt(159L), "repeated-damage reflex remains active between attacks");
		assertTrue(!betweenHits.repeatedAt(161L), "repeated-damage reflex expires after its bounded window");
		DamageState laterHit = GoalSafetyController.trackDamage(betweenHits, 14.0F, 200L);
		assertTrue(!laterHit.repeatedAt(200L), "a hit after expiry starts a new damage streak");
		verifyHazardousEscapeRoutesAreRejected();
		verifySafeEscapeWinsOverHazardousFirstCandidate();
		verifyRetreatSkipsHazardousFarthestCandidate();
		return 15;
	}

	private static void verifyHazardousEscapeRoutesAreRejected() {
		GridPosition origin = new GridPosition(0, 64, 0);
		GridPosition northFire = origin.offset(0, 0, -1);
		GridPosition eastLava = origin.offset(1, 0, 0);
		GridPosition southDeepWater = origin.offset(0, 0, 1);
		GridPosition westMagmaSupport = origin.offset(-1, 0, 0);
		Map<GridPosition, WalkabilityView.Cell> cells = openRouteCells(origin);
		cells.put(northFire, WalkabilityView.Cell.HAZARD);
		cells.put(eastLava, WalkabilityView.Cell.HAZARD);
		cells.put(southDeepWater, WalkabilityView.Cell.HAZARD);
		cells.put(westMagmaSupport.below(), WalkabilityView.Cell.HAZARD);

		GridPosition selected = GoalSafetyController.selectEscapeCandidate(
				origin,
				center(origin),
				null,
				cells::get
		);
		assertEquals(null, selected, "escape selection rejects fire, lava, deep water, and magma support");
	}

	private static void verifySafeEscapeWinsOverHazardousFirstCandidate() {
		GridPosition origin = new GridPosition(0, 64, 0);
		GridPosition northFire = origin.offset(0, 0, -1);
		GridPosition eastSafe = origin.offset(1, 0, 0);
		Map<GridPosition, WalkabilityView.Cell> cells = openRouteCells(origin);
		cells.put(northFire, WalkabilityView.Cell.HAZARD);
		cells.put(origin.offset(0, 0, 1), WalkabilityView.Cell.BLOCKED);
		cells.put(origin.offset(-1, 0, 0), WalkabilityView.Cell.BLOCKED);

		GridPosition selected = GoalSafetyController.selectEscapeCandidate(
				origin,
				center(origin),
				null,
				cells::get
		);
		assertEquals(eastSafe, selected, "escape selection skips an intrinsic hazard for the next safe route");
	}

	private static void verifyRetreatSkipsHazardousFarthestCandidate() {
		GridPosition origin = new GridPosition(0, 64, 0);
		GridPosition northSafe = origin.offset(0, 0, -1);
		GridPosition westHazard = origin.offset(-1, 0, 0);
		Map<GridPosition, WalkabilityView.Cell> cells = openRouteCells(origin);
		cells.put(westHazard, WalkabilityView.Cell.HAZARD);
		cells.put(origin.offset(0, 0, 1), WalkabilityView.Cell.BLOCKED);

		GridPosition selected = GoalSafetyController.selectEscapeCandidate(
				origin,
				center(origin),
				new Vec3(2.5D, 64.0D, 0.5D),
				cells::get
		);
		assertEquals(northSafe, selected,
				"retreat selection skips the hazardous farthest cell and chooses the farthest safe route");
	}

	private static Map<GridPosition, WalkabilityView.Cell> openRouteCells(GridPosition origin) {
		Map<GridPosition, WalkabilityView.Cell> cells = new HashMap<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				GridPosition feet = origin.offset(dx, 0, dz);
				cells.put(feet, WalkabilityView.Cell.CLEAR);
				cells.put(feet.above(), WalkabilityView.Cell.CLEAR);
				cells.put(feet.below(), WalkabilityView.Cell.SAFE_SUPPORT);
			}
		}
		return cells;
	}

	private static Vec3 center(GridPosition position) {
		return new Vec3(position.x() + 0.5D, position.y(), position.z() + 0.5D);
	}

	private static SafetyDirective decide(
			boolean dead, boolean inWater, boolean inLava, boolean onFire, int air,
			boolean repeatedDamage, boolean shield, boolean escape, boolean retreat
	) {
		return GoalSafetyController.decide(new HazardSnapshot(
				dead, inWater, inLava, onFire, air, 40, repeatedDamage, shield, escape, retreat));
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		System.out.println("PASS: " + label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
		System.out.println("PASS: " + label);
	}
}
