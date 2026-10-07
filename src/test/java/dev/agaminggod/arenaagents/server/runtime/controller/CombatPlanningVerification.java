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
		verifyFollowThrough();
		verifyMultiThreatFlee();
		verifyWater();
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

	private static void verifyFollowThrough() {
		// The live trace: zombie 1 died while zombie 2 was already hitting the agent and a creeper stood nearby.
		List<CombatPlanning.AttackerCandidate> afterKill = List.of(
				new CombatPlanning.AttackerCandidate(5.0D, false, false, false),
				new CombatPlanning.AttackerCandidate(2.0D, true, false, true),
				new CombatPlanning.AttackerCandidate(3.5D, false, true, false),
				new CombatPlanning.AttackerCandidate(2.8D, true, false, false));
		check(CombatPlanning.nextAttacker(afterKill) == 3, "follow-through picks the nearest mob already attacking the agent");
		check(CombatPlanning.nextAttacker(List.of(new CombatPlanning.AttackerCandidate(3.5D, false, true, false))) == 0,
				"the mob that just hurt the agent counts as attacking even before it re-targets");
		check(CombatPlanning.nextAttacker(List.of(new CombatPlanning.AttackerCandidate(4.0D, false, false, false))) < 0,
				"a hostile that is not attacking is never engaged (follow-through starts no new fight)");
		check(CombatPlanning.nextAttacker(List.of(new CombatPlanning.AttackerCandidate(2.0D, true, true, true))) < 0,
				"a creeper is reported back to the model, never chased into melee");
		check(CombatPlanning.nextAttacker(List.of(new CombatPlanning.AttackerCandidate(CombatPlanning.THREAT_RANGE + 1.0D, true, false, false))) < 0,
				"an attacker beyond the threat range is left for the model");
		check(CombatPlanning.nextAttacker(List.of()) < 0, "with no attackers left the fight ends");
	}

	private static void verifyMultiThreatFlee() {
		// Zombie 3 blocks along +z (yaw 0), creeper 6 blocks along +x (yaw -90).
		CombatPlanning.FleeThreat zombie = new CombatPlanning.FleeThreat(0.0D, 3.0D, 3.0D, false, false, true);
		CombatPlanning.FleeThreat creeper = new CombatPlanning.FleeThreat(6.0D, 0.0D, 6.0D, true, false, true);
		Float alone = CombatPlanning.fleeAwayYaw(List.of(zombie));
		check(alone != null && Math.abs(Math.abs(alone) - 180.0F) < 1.0E-3F, "one threat: straight away (unchanged single-target flee)");
		Float both = CombatPlanning.fleeAwayYaw(List.of(zombie, creeper));
		// Away from the zombie is yaw 180, away from the creeper is yaw 90; the creeper pushes harder.
		check(both != null && both > 90.0F && both < 180.0F, "two threats: the flee heads away from both at once");
		CombatPlanning.FleeThreat swelling = new CombatPlanning.FleeThreat(6.0D, 0.0D, 6.0D, true, true, true);
		Float fromSwelling = CombatPlanning.fleeAwayYaw(List.of(zombie, swelling));
		check(fromSwelling != null && both != null && fromSwelling < both, "a swelling creeper pulls the heading further away from it");
		check(CombatPlanning.fleeWeight(swelling) > CombatPlanning.fleeWeight(creeper)
				&& CombatPlanning.fleeWeight(creeper) > CombatPlanning.fleeWeight(new CombatPlanning.FleeThreat(6.0D, 0.0D, 6.0D, false, false, true)),
				"swelling creeper > creeper > zombie at the same distance");
		Float surrounded = CombatPlanning.fleeAwayYaw(List.of(
				new CombatPlanning.FleeThreat(0.0D, 3.0D, 3.0D, false, false, true),
				new CombatPlanning.FleeThreat(0.0D, -3.0D, 3.0D, false, false, true)));
		check(surrounded != null, "evenly surrounded still yields a heading (away from the heaviest threat)");
		check(CombatPlanning.fleeAwayYaw(List.of(new CombatPlanning.FleeThreat(0.0D, 0.0D, 2.0D, false, false, true))) == null,
				"a threat directly overhead gives no horizontal away direction");

		// Escape: the zombie is 16 blocks away and not closing, but the creeper the agent ran into is 5 blocks away.
		check(CombatPlanning.escapeBlocker(List.of(new CombatPlanning.FleeThreat(5.0D, 0.0D, 5.0D, true, false, false)), 10.0D) == 0,
				"never escaped while a creeper is within blast range, even a creeper that is not approaching");
		check(CombatPlanning.escapeBlocker(List.of(new CombatPlanning.FleeThreat(8.0D, 0.0D, 8.0D, true, false, false)), 10.0D) < 0,
				"a creeper beyond 7 blocks that is not closing does not block the escape");
		check(CombatPlanning.escapeBlocker(List.of(new CombatPlanning.FleeThreat(8.0D, 0.0D, 8.0D, false, false, true)), 10.0D) == 0,
				"another hostile inside the requested distance that is still closing blocks the escape");
		check(CombatPlanning.escapeBlocker(List.of(new CombatPlanning.FleeThreat(12.0D, 0.0D, 12.0D, false, false, true)), 10.0D) < 0,
				"a closing hostile already beyond the requested distance does not block");
		check(CombatPlanning.escapeBlocker(List.of(
				new CombatPlanning.FleeThreat(9.0D, 0.0D, 9.0D, false, false, true),
				new CombatPlanning.FleeThreat(6.0D, 0.0D, 6.0D, true, true, true)), 10.0D) == 1,
				"the nearest blocking threat is reported");

		CombatPlanning.ClosingTracker tracker = new CombatPlanning.ClosingTracker();
		boolean closing = false;
		for (int tick = 0; tick <= CombatPlanning.NOT_CLOSING_WINDOW_TICKS; tick++) closing = tracker.observe("zombie", 8.0D);
		check(closing, "a threat is treated as closing until it has been watched for a full window");
		closing = tracker.observe("zombie", 8.0D);
		check(!closing, "a threat holding its distance for half a second is not closing");
		for (int tick = 0; tick <= CombatPlanning.NOT_CLOSING_WINDOW_TICKS; tick++) closing = tracker.observe("zombie", 8.0D - tick * 0.2D);
		check(closing, "a threat gaining ground is closing");
		tracker.retain(Set.of());
		check(tracker.observe("zombie", 8.0D), "a threat that left and returned is watched afresh");
	}

	/** The play-test: a flee into a lake ended afloat, the agent sank and drowned while mobs closed in. */
	private static void verifyWater() {
		GridPosition feet = new GridPosition(0, 64, 0);
		float south = 0.0F;
		// A lake south of the agent (surface y 63, two deep), solid ground everywhere else.
		WalkabilityView lake = position -> {
			boolean inLake = position.z() >= 1 && Math.abs(position.x()) <= 1;
			if (inLake && (position.y() == 63 || position.y() == 62)) return WalkabilityView.Cell.WATER;
			if (position.y() <= 63) return WalkabilityView.Cell.SAFE_SUPPORT;
			return WalkabilityView.Cell.CLEAR;
		};
		check(CombatPlanning.headingCrossesWater(lake, feet, south), "the heading straight into the lake is a swim");
		CombatPlanning.Heading land = CombatPlanning.fleeHeading(lake, feet, south, null);
		check(land.clear() && !CombatPlanning.headingCrossesWater(lake, feet, land.yaw()) && Math.abs(land.yaw()) <= 90.0F,
				"with land within 90 degrees of away the flee stays on land instead of walking into the water");
		check(!CombatPlanning.headingCrossesWater(lake, feet, CombatPlanning.fleeHeading(lake, feet, south, 0.0F).yaw()),
				"a previous heading into the water is dropped for a land heading");
		// Water on every side except straight back: costed, not blocked, so the flee still swims away.
		WalkabilityView shore = position -> {
			boolean water = position.z() >= 0 && !(position.x() == 0 && position.z() == 0);
			if (water && (position.y() == 63 || position.y() == 62)) return WalkabilityView.Cell.WATER;
			if (position.y() <= 63) return WalkabilityView.Cell.SAFE_SUPPORT;
			return WalkabilityView.Cell.CLEAR;
		};
		CombatPlanning.Heading swim = CombatPlanning.fleeHeading(shore, feet, south, null);
		check(swim.clear() && swim.yaw() == 0.0F, "deep water straight away beats running back toward the threat");
		CombatPlanning.Heading wary = CombatPlanning.fleeHeading(shore, feet, south, null,
				SwimPlanning.AQUATIC_THREAT_WATER_PENALTY_DEGREES);
		check(wary.clear() && !CombatPlanning.headingCrossesWater(shore, feet, wary.yaw()),
				"with a drowned among the threats any land heading beats the water");
		// Submerged: every cell around is water. Swimming is passable (no FLEE_BLOCKED, fight can approach).
		WalkabilityView deep = position -> position.y() <= 70 && position.y() > 55
				? WalkabilityView.Cell.WATER : position.y() <= 55 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		GridPosition submerged = new GridPosition(0, 60, 0);
		check(CombatPlanning.fleeHeading(deep, submerged, south, null).clear(), "a submerged agent can still flee by swimming");
		check(CombatPlanning.canStep(deep, submerged, south) && CombatPlanning.canStep(deep, submerged, 180.0F),
				"a submerged fight can approach and back off by swimming");
		// Lava stays a hazard even next to water.
		WalkabilityView lavaLake = position -> position.y() == 63 && position.z() >= 1
				? WalkabilityView.Cell.HAZARD : position.y() <= 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		check(CombatPlanning.fleeHeading(lavaLake, feet, south, null).yaw() != 0.0F, "water rules never make lava passable");

		// Executed swimming (the model chose the movement; this only makes it work in water).
		check(SwimPlanning.holdJump(true) && !SwimPlanning.holdJump(false), "in water the movement holds jump like a player");
		check(SwimPlanning.swimSprint(true, true, 1.0F, 20) && !SwimPlanning.swimSprint(true, false, 1.0F, 20)
				&& !SwimPlanning.swimSprint(true, true, 0.0F, 20) && !SwimPlanning.swimSprint(true, true, 1.0F, 6),
				"sprint-swim only while submerged, moving forward and fed");
		check(SwimPlanning.swimPitch(true, true, 0.0F) < 0.0F && SwimPlanning.swimPitch(true, false, 7.0F) == 7.0F,
				"a submerged agent looks up to surface; at the surface the requested pitch stays");
		check(SwimPlanning.fightJump(true, 0.0D) && SwimPlanning.fightJump(true, 2.0D) && !SwimPlanning.fightJump(true, -3.0D)
				&& !SwimPlanning.fightJump(false, 2.0D), "a water fight stays afloat unless the target is clearly below");
		check(SwimPlanning.unreachableTicks(true, false, ServerFightController.UNREACHABLE_TICKS) > ServerFightController.UNREACHABLE_TICKS
				&& SwimPlanning.unreachableTicks(false, false, ServerFightController.UNREACHABLE_TICKS) == ServerFightController.UNREACHABLE_TICKS,
				"TARGET_UNREACHABLE only after a fair swim attempt; land fights keep 5 s");
		// The play-test flee ended ESCAPED while the agent was in the lake.
		check(!SwimPlanning.fleeMayEnd(true, true, false), "never escaped while under water");
		check(!SwimPlanning.fleeMayEnd(true, false, false), "never escaped while afloat in deep water (it sinks once released)");
		check(SwimPlanning.fleeMayEnd(true, false, true) && SwimPlanning.fleeMayEnd(false, false, true)
				&& SwimPlanning.fleeMayEnd(false, false, false), "escaped on land, in shallow water, or mid-jump on land");
		// navigate_to from under water rises first instead of NO_STANDABLE_PATH.
		check(SwimPlanning.needsSurfacing(true, true) && !SwimPlanning.needsSurfacing(true, false)
				&& !SwimPlanning.needsSurfacing(false, false), "navigation surfaces first only while the eyes are under water");
		check(SwimPlanning.airSecondsLeft(150) == 7.5D && SwimPlanning.airSecondsLeft(-20) == 0.0D,
				"air seconds count down from 15 s and never go negative");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		assertions++;
	}
}
