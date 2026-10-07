package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Verifies the pure fight/flee rules behind the model-chosen fight_target and flee_from actions. */
public final class CombatPlanningVerification {
	private static int assertions;

	private CombatPlanningVerification() {
	}

	public static int verify() {
		assertions = 0;
		verifyWeaponChoice();
		verifyFightPacing();
		verifyFleeProgress();
		verifyFleeSteering();
		return assertions;
	}

	private static void verifyWeaponChoice() {
		List<CombatPlanning.WeaponCandidate> hotbar = List.of(
				new CombatPlanning.WeaponCandidate(0, 1, 250),
				new CombatPlanning.WeaponCandidate(1, 2, 1561),
				new CombatPlanning.WeaponCandidate(2, 3, 59),
				new CombatPlanning.WeaponCandidate(3, 3, 250),
				new CombatPlanning.WeaponCandidate(4, 0, 0));
		check(CombatPlanning.bestWeaponSlot(hotbar, 0) == 3, "a sword beats an axe, and the more durable sword wins");
		check(CombatPlanning.bestWeaponSlot(List.of(
				new CombatPlanning.WeaponCandidate(0, 2, 250),
				new CombatPlanning.WeaponCandidate(5, 2, 250)), 5) == 5, "an equal weapon keeps the current slot (no visible swap)");
		check(CombatPlanning.bestWeaponSlot(List.of(new CombatPlanning.WeaponCandidate(0, 0, 0)), 6) == 6,
				"with no weapon the current slot is kept");
		check(CombatPlanning.weaponRank(true, false, false) == 3 && CombatPlanning.weaponRank(false, true, false) == 2
				&& CombatPlanning.weaponRank(false, false, true) == 1 && CombatPlanning.weaponRank(false, false, false) == 0,
				"weapon ranks are sword > axe/spear > other tool > none");
	}

	private static void verifyFightPacing() {
		CombatPlanning.FightStep far = CombatPlanning.fightStep(input(8.0D, false, false, 1.0F, true, 0));
		check(far.forward() == 1.0F && far.sprint() && !far.attack(), "a distant target is approached at a sprint");
		CombatPlanning.FightStep charging = CombatPlanning.fightStep(input(2.4D, true, true, 0.5F, true, 0));
		check(!charging.attack() && charging.forward() == 0.0F, "no spam click: hold position until attack strength recharges");
		CombatPlanning.FightStep swing = CombatPlanning.fightStep(input(2.4D, true, true, 0.95F, true, 0));
		check(swing.attack() && swing.backoffTicks() == CombatPlanning.BACKOFF_TICKS, "a full charge in reach and aimed swings once");
		CombatPlanning.FightStep back = CombatPlanning.fightStep(input(2.4D, true, true, 1.0F, true, swing.backoffTicks()));
		check(!back.attack() && back.forward() < 0.0F && back.backoffTicks() == CombatPlanning.BACKOFF_TICKS - 1,
				"after a hit the agent steps back instead of swinging again");
		check(!CombatPlanning.fightStep(input(2.4D, true, false, 1.0F, true, 0)).attack(), "an unaimed view never swings");
		check(!CombatPlanning.fightStep(input(2.4D, true, true, 1.0F, false, 0)).attack(),
				"the swing waits until the selected slot is the chosen weapon");
		check(CombatPlanning.fightStep(input(0.8D, true, true, 0.2F, true, 0)).forward() < 0.0F,
				"too close while recharging backs off to the edge of reach");
		check(CombatPlanning.shouldBailOut(6.0F, 6.0F) && !CombatPlanning.shouldBailOut(6.5F, 6.0F)
				&& !CombatPlanning.shouldBailOut(1.0F, null), "only a model-chosen fleeAtHealth ends a fight early");
	}

	private static CombatPlanning.FightInput input(double distance, boolean inReach, boolean aimed, float strength,
			boolean weaponReady, int backoff) {
		return new CombatPlanning.FightInput(distance, CombatPlanning.DEFAULT_FIGHT_RANGE, inReach, aimed, true,
				strength, weaponReady, backoff);
	}

	private static void verifyFleeProgress() {
		CombatPlanning.FleeProgress closing = new CombatPlanning.FleeProgress(8.0D);
		CombatPlanning.FleeProgress.Outcome outcome = CombatPlanning.FleeProgress.Outcome.RUNNING;
		for (int tick = 0; tick < 30; tick++) outcome = closing.observe(12.0D - tick * 0.1D, true);
		check(outcome == CombatPlanning.FleeProgress.Outcome.RUNNING,
				"beyond the distance but a chaser still gaining keeps the flee running (no run-then-stop)");
		CombatPlanning.FleeProgress escaping = new CombatPlanning.FleeProgress(8.0D);
		outcome = CombatPlanning.FleeProgress.Outcome.RUNNING;
		int ticks = 0;
		for (double distance = 3.0D; outcome == CombatPlanning.FleeProgress.Outcome.RUNNING && ticks < 200; distance += 0.25D, ticks++) {
			outcome = escaping.observe(distance, true);
		}
		check(outcome == CombatPlanning.FleeProgress.Outcome.ESCAPED, "opening distance past the request escapes");
		CombatPlanning.FleeProgress lost = new CombatPlanning.FleeProgress(10.0D);
		outcome = CombatPlanning.FleeProgress.Outcome.RUNNING;
		for (int tick = 0; tick < CombatPlanning.LOST_TICKS; tick++) outcome = lost.observe(6.0D, false);
		check(outcome == CombatPlanning.FleeProgress.Outcome.LOST, "a chaser without target or sight for a second has lost the agent");
		CombatPlanning.FleeProgress nearby = new CombatPlanning.FleeProgress(10.0D);
		for (int tick = 0; tick < 40; tick++) outcome = nearby.observe(3.0D, false);
		check(outcome == CombatPlanning.FleeProgress.Outcome.RUNNING, "an unaware mob right beside the agent does not end the flee");
	}

	private static void verifyFleeSteering() {
		// Flat floor at y=63 with a wall straight south (+z) of the agent: fleeing south must detour.
		Set<GridPosition> walls = new HashSet<>();
		for (int y = 64; y <= 66; y++) for (int x = -1; x <= 1; x++) walls.add(new GridPosition(x, y, 1));
		WalkabilityView world = position -> {
			if (walls.contains(position)) return WalkabilityView.Cell.BLOCKED;
			return position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		};
		GridPosition feet = new GridPosition(0, 64, 0);
		float south = 0.0F;
		CombatPlanning.Heading detour = CombatPlanning.fleeHeading(world, feet, south, null);
		check(detour.clear() && Math.abs(detour.yaw()) >= 60.0F, "a wall straight ahead is steered around");
		WalkabilityView open = position -> position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		check(CombatPlanning.fleeHeading(open, feet, south, null).yaw() == 0.0F, "open ground flees straight away");
		check(CombatPlanning.fleeHeading(open, feet, south, 30.0F).yaw() == 30.0F,
				"a still-valid previous heading within 45 degrees is kept (no dithering)");
		// One-block step up straight ahead is jumped.
		WalkabilityView step = position -> {
			if (position.z() >= 1 && position.y() == 64) return WalkabilityView.Cell.SAFE_SUPPORT;
			return position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		};
		CombatPlanning.Heading up = CombatPlanning.fleeHeading(step, feet, south, null);
		check(up.yaw() == 0.0F && up.jump(), "a one-block step on the flee line is jumped, not avoided");
		// Lava (hazard support) straight ahead is avoided.
		WalkabilityView lava = position -> {
			if (position.y() == 63 && position.z() >= 1 && Math.abs(position.x()) <= 1) return WalkabilityView.Cell.HAZARD;
			return position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		};
		check(CombatPlanning.fleeHeading(lava, feet, south, null).yaw() != 0.0F, "the flee never runs onto hazardous ground");
		// A one-block pillar over lava: every heading is a hazard, so the flee reports blocked instead of jumping.
		WalkabilityView pillar = position -> {
			if (position.x() == 0 && position.z() == 0 && position.y() == 63) return WalkabilityView.Cell.SAFE_SUPPORT;
			return position.y() <= 63 ? WalkabilityView.Cell.HAZARD : WalkabilityView.Cell.CLEAR;
		};
		CombatPlanning.Heading boxed = CombatPlanning.fleeHeading(pillar, feet, south, null);
		check(!boxed.clear() && !boxed.jump(), "boxed in on a pillar the flee neither moves nor jumps off");
		check(!CombatPlanning.canStep(pillar, feet, south) && !CombatPlanning.canStep(pillar, feet, 180.0F),
				"a fight on the pillar cannot approach or step back into the hazard");
		// A deep ravine directly behind: stepping back is refused, stepping forward on solid ground is fine.
		WalkabilityView ravine = position -> {
			if (position.z() < 0) return WalkabilityView.Cell.CLEAR;
			return position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		};
		check(CombatPlanning.canStep(ravine, feet, south) && !CombatPlanning.canStep(ravine, feet, 180.0F),
				"a step back toward a deep drop is refused while the approach stays allowed");
		// A crouch-only gap is not walkable for flee/fight, which never crouch.
		WalkabilityView crouch = new WalkabilityView() {
			@Override public Cell cellAt(GridPosition position) {
				return position.y() == 63 ? Cell.SAFE_SUPPORT : Cell.CLEAR;
			}
			@Override public dev.agaminggod.arenaagents.client.navigation.TraversalType traversalAt(GridPosition position) {
				return position.z() >= 1 ? dev.agaminggod.arenaagents.client.navigation.TraversalType.CROUCH
						: WalkabilityView.super.traversalAt(position);
			}
		};
		check(!CombatPlanning.canStep(crouch, feet, south), "a crouch-only passage is not a flee route");
		check(CombatPlanning.alignedForward(1.0F, 0.0F) == 1.0F && CombatPlanning.alignedForward(1.0F, 120.0F) == 0.0F
				&& Math.abs(CombatPlanning.alignedForward(1.0F, 60.0F) - 0.5F) < 1.0E-4F,
				"movement waits for the turn: none while facing 90+ degrees away from the target");
		check(Math.abs(CombatPlanning.yawToward(0.0D, 0.0D, 0.0D, 5.0D)) < 1.0E-4F
				&& Math.abs(CombatPlanning.yawToward(0.0D, 0.0D, -5.0D, 0.0D) - 90.0F) < 1.0E-4F,
				"yaw uses the Minecraft convention (+z is 0, -x is 90)");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		assertions++;
	}
}
