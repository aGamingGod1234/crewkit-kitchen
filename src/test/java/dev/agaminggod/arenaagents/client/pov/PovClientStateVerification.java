package dev.agaminggod.arenaagents.client.pov;

import dev.agaminggod.arenaagents.pov.PovMode;
import java.util.UUID;

/** Dependency-free checks for the POV session state machine, view routing, look math, hand bob and body damage. */
public final class PovClientStateVerification {
	private static final UUID AGENT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID AGENT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static int checks;

	private PovClientStateVerification() {
	}

	public static int verify() {
		checks = 0;
		verifySession();
		verifyTracker();
		verifyLook();
		verifyView();
		verifyHands();
		verifyBodyMonitor();
		return checks;
	}

	private static void verifySession() {
		check(new PovClientSession(1L, PovMode.TAKEOVER, AGENT_A, "Alpha").takeover(), "takeover sessions report takeover");
		check(!new PovClientSession(1L, PovMode.SPECTATE, AGENT_A, "Alpha").takeover(), "spectate sessions do not report takeover");
		check(new PovClientSession(1L, PovMode.SPECTATE, AGENT_A, null).agentName().isEmpty(), "a missing agent name becomes empty");
	}

	private static void verifyTracker() {
		PovSessionTracker tracker = new PovSessionTracker();
		PovClientSession alpha = new PovClientSession(7L, PovMode.SPECTATE, AGENT_A, "Alpha");
		check(tracker.phase() == PovSessionTracker.Phase.NONE && tracker.session().isEmpty(), "tracker starts with no session");
		check(tracker.acceptPose(7L) == PovSessionTracker.PoseResult.IGNORED, "poses before any state are ignored");

		check(tracker.acceptState(alpha, 1, 3) == PovSessionTracker.StateResult.STARTED, "first state starts the session");
		check(tracker.phase() == PovSessionTracker.Phase.ACTIVE, "a started session is active");
		check(tracker.lookResetPending() && tracker.lookResetSeq() == 2, "a new session waits for a pose before acknowledging its look reset");
		check(tracker.acceptPose(8L) == PovSessionTracker.PoseResult.IGNORED, "poses for another session are ignored");
		check(tracker.acceptPose(7L) == PovSessionTracker.PoseResult.LOOK_RESET, "the first pose seeds the local look");
		check(tracker.lookResetSeq() == 3 && !tracker.lookResetPending(), "the seeded look acknowledges the server reset");
		check(tracker.acceptPose(7L) == PovSessionTracker.PoseResult.APPLIED, "later poses only update the view");

		check(tracker.acceptState(alpha, 2, 3) == PovSessionTracker.StateResult.UPDATED, "a newer revision updates the session");
		check(tracker.acceptState(alpha, 1, 3) == PovSessionTracker.StateResult.STALE, "an older revision is stale");
		check(tracker.revision() == 2, "a stale revision does not move the session back");
		check(tracker.acceptState(alpha, 3, 4) == PovSessionTracker.StateResult.UPDATED && tracker.lookResetPending(), "a new look reset sequence waits for the next pose");
		check(tracker.lookResetSeq() == 3, "the reset is not acknowledged before a pose arrives");
		check(tracker.acceptPose(7L) == PovSessionTracker.PoseResult.LOOK_RESET && tracker.lookResetSeq() == 4, "the next pose applies the new look reset");

		check(tracker.entityMissing(), "a missing agent entity loses the signal");
		check(tracker.phase() == PovSessionTracker.Phase.SIGNAL_LOST, "signal lost is its own phase");
		check(!tracker.entityMissing(), "losing the signal twice is not a transition");
		check(tracker.entityFound() && tracker.phase() == PovSessionTracker.Phase.ACTIVE, "the agent reappearing rebinds the view");
		check(!tracker.entityFound(), "finding the agent while active is not a transition");

		tracker.entityMissing();
		PovClientSession beta = new PovClientSession(9L, PovMode.TAKEOVER, AGENT_B, "Beta");
		check(tracker.acceptState(beta, 1, 0) == PovSessionTracker.StateResult.SWITCHED, "a different session id switches the view");
		check(tracker.phase() == PovSessionTracker.Phase.ACTIVE && tracker.session().orElseThrow().equals(beta), "a switch rebinds to the new agent");
		check(tracker.acceptState(new PovClientSession(9L, PovMode.SPECTATE, AGENT_B, "Beta"), 2, 0) == PovSessionTracker.StateResult.SWITCHED,
				"a mode change inside a session is treated as a fresh binding");
		check(tracker.acceptState(beta, 3, 0) == PovSessionTracker.StateResult.SWITCHED, "switching back to takeover is also a fresh binding");

		check(!tracker.acceptStop(8L) && tracker.session().isPresent(), "a stop for another session keeps the view");
		check(tracker.acceptStop(9L), "a stop for the current session ends it");
		check(tracker.clear() && tracker.phase() == PovSessionTracker.Phase.NONE && tracker.session().isEmpty(), "clearing returns to no session");
		check(!tracker.clear(), "clearing twice reports nothing was active");
		check(tracker.acceptState(beta, 4, 0) == PovSessionTracker.StateResult.STALE && tracker.session().isEmpty(), "a state already in flight after its stop cannot reopen the view");
		check(tracker.acceptState(alpha, 1, 0) == PovSessionTracker.StateResult.STARTED, "a later session can still start");

		tracker.clear();
		PovClientSession gamma = new PovClientSession(11L, PovMode.SPECTATE, AGENT_A, "Alpha");
		tracker.acceptState(gamma, Integer.MAX_VALUE, 0);
		check(tracker.acceptState(gamma, Integer.MIN_VALUE, 0) == PovSessionTracker.StateResult.UPDATED, "revision ordering survives integer wrap");
		check(tracker.matches(11L) && !tracker.matches(12L), "matching uses the session id");
	}

	private static void verifyLook() {
		PovLook.reset(0.0F, 0.0F);
		PovLook.turn(100.0D, 0.0D);
		check(PovLook.yaw() == (float) 100.0D * 0.15F && near(PovLook.yaw(), 15.0F), "mouse yaw uses the Entity.turn scale");
		PovLook.reset(0.0F, 0.0F);
		PovLook.turn(0.3D, 0.7D);
		check(PovLook.yaw() == (float) 0.3D * 0.15F && PovLook.pitch() == (float) 0.7D * 0.15F, "look math matches Entity.turn float arithmetic");
		PovLook.turn(0.0D, 1000.0D);
		check(PovLook.pitch() == 90.0F, "pitch clamps at straight down");
		PovLook.turn(0.0D, -2000.0D);
		check(PovLook.pitch() == -90.0F, "pitch clamps at straight up");
		PovLook.reset(170.0F, 0.0F);
		PovLook.turn(100.0D, 0.0D);
		check(near(PovLook.yaw(), -175.0F), "yaw wraps past 180");
		PovLook.reset(-170.0F, 0.0F);
		PovLook.turn(-100.0D, 0.0D);
		check(near(PovLook.yaw(), 175.0F), "yaw wraps past -180");
		PovLook.reset(540.0F, 120.0F);
		check(PovLook.yaw() == -180.0F && PovLook.pitch() == 90.0F, "resets wrap yaw and clamp pitch");
		PovLook.reset(10.0F, 5.0F);
		PovLook.turn(Double.NaN, 1.0D);
		check(PovLook.yaw() == 10.0F && PovLook.pitch() == 5.0F, "non-finite mouse input is ignored");
		PovLook.reset(Float.NaN, Float.POSITIVE_INFINITY);
		check(PovLook.yaw() == 0.0F && PovLook.pitch() == 0.0F, "non-finite resets fall back to level look");
		check(PovLook.wrapDegrees(180.0F) == -180.0F && PovLook.wrapDegrees(359.0F) == -1.0F && PovLook.wrapDegrees(-180.0F) == -180.0F,
				"wrapDegrees matches the vanilla [-180, 180) range");
		check(PovLook.lerpYaw(170.0F, -170.0F, 0.5F) == 180.0F, "yaw interpolation takes the short way across 180");
		check(PovLook.lerpYaw(-170.0F, 170.0F, 0.5F) == -180.0F, "yaw interpolation takes the short way across -180");
		PovLook.reset(0.0F, 0.0F);
	}

	private static void verifyView() {
		Object agent = new Object();
		Object other = new Object();
		PovView.reset();
		check(PovView.yaw(agent, 0.5F, 42.0F) == 42.0F, "an unbound view leaves rotations alone");
		PovView.bind(agent, false);
		check(PovView.isTarget(agent) && !PovView.isTarget(other) && !PovView.isTarget(null), "only the bound entity is the target");
		check(PovView.yaw(other, 0.5F, 42.0F) == 42.0F && PovView.pitch(other, 0.5F, 7.0F) == 7.0F, "other entities keep their own rotation");
		check(PovView.yaw(agent, 0.5F, 42.0F) == 42.0F, "spectating without a pose falls back to the entity");
		PovView.acceptPose(10.0F, 20.0F);
		check(PovView.yaw(agent, 0.5F, 42.0F) == 10.0F && PovView.pitch(agent, 0.5F, 7.0F) == 20.0F, "the first pose is shown without lerping from zero");
		PovView.tick();
		PovView.acceptPose(30.0F, 40.0F);
		PovView.tick();
		check(PovView.yaw(agent, 0.0F, 42.0F) == 10.0F && PovView.yaw(agent, 1.0F, 42.0F) == 30.0F, "spectator yaw spans one tick of poses");
		check(PovView.yaw(agent, 0.5F, 42.0F) == 20.0F && PovView.pitch(agent, 0.5F, 7.0F) == 30.0F, "spectator view interpolates by partial tick");
		PovView.acceptPose(Float.NaN, 0.0F);
		PovView.tick();
		check(PovView.yaw(agent, 1.0F, 42.0F) == 30.0F, "non-finite poses are ignored");
		PovView.acceptPose(0.0F, 120.0F);
		PovView.tick();
		check(PovView.pitch(agent, 1.0F, 7.0F) == 90.0F, "server pitch is clamped");
		PovView.bind(agent, true);
		PovLook.reset(55.0F, -10.0F);
		check(PovView.yaw(agent, 0.5F, 42.0F) == 55.0F && PovView.pitch(agent, 0.5F, 7.0F) == -10.0F, "takeover reads the predicted look");
		check(PovView.currentYaw() == 55.0F && PovView.currentPitch() == -10.0F, "takeover reports the predicted look for the signal-lost anchor");
		PovView.reset();
		PovLook.reset(0.0F, 0.0F);
		check(PovView.yaw(agent, 0.5F, 42.0F) == 42.0F && !PovView.isTarget(agent), "reset releases the view");
	}

	private static void verifyHands() {
		PovHands hands = new PovHands();
		check(!hands.seeded(), "hands start unseeded");
		hands.tick(Float.NaN, 20.0F);
		check(!hands.seeded(), "a non-finite view never seeds");
		hands.tick(10.0F, 20.0F);
		check(hands.seeded() && hands.xBob(0.0F) == 10.0F && hands.xBob(1.0F) == 10.0F && hands.yBob(0.5F) == 20.0F,
				"the first tick seeds the bob at the view, so hands start where the agent looks");
		hands.tick(20.0F, 20.0F);
		check(hands.xBob(0.0F) == 10.0F && hands.xBob(1.0F) == 15.0F && hands.xBob(0.5F) == 12.5F,
				"pitch chases the view by half the gap per tick, like LocalPlayer.xBob");
		hands.tick(20.0F, 20.0F);
		check(hands.xBob(1.0F) == 17.5F && hands.xBob(0.0F) == 15.0F, "the previous tick is kept for interpolation");
		check(hands.viewYaw(1.0F, 20.0F) == 20.0F, "an aligned yaw is reported unchanged");
		hands.reset();
		check(!hands.seeded(), "reset forgets the bob");
		hands.tick(0.0F, 170.0F);
		hands.tick(0.0F, -170.0F);
		check(hands.yBob(1.0F) == 180.0F, "yaw chases the short way across 180");
		check(hands.viewYaw(1.0F, -170.0F) == 190.0F, "the view yaw is lifted onto the bob's scale, so the offset stays 10 degrees");
		hands.tick(0.0F, Float.POSITIVE_INFINITY);
		check(hands.yBob(1.0F) == 180.0F, "a non-finite view is ignored");
	}

	private static void verifyBodyMonitor() {
		PovBodyMonitor monitor = new PovBodyMonitor();
		check(!monitor.observe(20.0F), "the first reading is a baseline");
		check(!monitor.observe(20.0F) && !monitor.flashing(), "steady health does not flash");
		check(monitor.observe(18.0F) && monitor.flashing(), "damage is reported on the tick it lands");
		int flashingTicks = 1;
		for (int tick = 0; tick < 20 && monitor.flashing(); tick++) {
			monitor.observe(18.0F);
			if (monitor.flashing()) flashingTicks++;
		}
		check(flashingTicks == PovBodyMonitor.FLASH_TICKS, "the flash lasts ten ticks");
		check(!monitor.observe(20.0F) && !monitor.flashing(), "healing does not flash");
		check(monitor.observe(19.5F) && monitor.flashing(), "absorption or health loss is reported again");
		monitor.reset();
		check(!monitor.flashing(), "reset clears the flash");
		check(!monitor.observe(4.0F) && !monitor.flashing(), "the first reading after reset is a baseline, not damage");
	}

	private static boolean near(float actual, float expected) {
		return Math.abs(actual - expected) < 1.0E-4F;
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		checks++;
	}
}
