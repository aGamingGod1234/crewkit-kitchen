package dev.agaminggod.arenaagents.client.pov;

import dev.agaminggod.arenaagents.pov.PovMode;
import java.util.UUID;

/**
 * Dependency-free checks for the POV session state machine, view routing, look math, hand bob, body damage, the
 * takeover body position stream and the latency probe.
 */
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
		verifyFreeLook();
		verifyHands();
		verifyBodyMonitor();
		verifyBodyPosition();
		verifyLatencyProbe();
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

	private static void verifyFreeLook() {
		long ms = 1_000_000L;
		Object agent = new Object();
		PovView.reset();
		PovView.bind(agent, false);
		PovView.acceptPose(10.0F, 20.0F);
		check(PovFreeLook.mode(0L) == PovFreeLook.Mode.LOCKED && !PovFreeLook.detached(0L), "spectate starts locked to the agent");
		check(!PovFreeLook.turn(100.0D, 0.0D), "the mouse does not turn a locked view");
		check(PovView.yaw(agent, 1.0F, 42.0F, 0L) == 10.0F && PovView.pitch(agent, 1.0F, 7.0F, 0L) == 20.0F, "a locked view shows the agent's look");

		PovFreeLook.hold(true, 10L * ms);
		check(PovFreeLook.mode(10L * ms) == PovFreeLook.Mode.FREE && PovFreeLook.detached(10L * ms), "holding sneak frees the camera");
		check(PovView.yaw(agent, 1.0F, 42.0F, 10L * ms) == 10.0F && PovView.pitch(agent, 1.0F, 7.0F, 10L * ms) == 20.0F,
				"free look starts from the agent's current view, no jump");
		check(PovFreeLook.turn(100.0D, -100.0D), "the mouse turns a free view");
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 11L * ms), 25.0F) && near(PovView.pitch(agent, 1.0F, 7.0F, 11L * ms), 5.0F),
				"free look uses the Entity.turn mouse scale");
		PovView.tick();
		PovView.acceptPose(-40.0F, 0.0F);
		PovView.tick();
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 12L * ms), 25.0F), "the agent turning does not move a free camera");
		check(PovView.agentYaw(1.0F, 42.0F) == -40.0F && PovView.agentPitch(1.0F, 7.0F) == 0.0F, "the agent's own look stays readable during free look");
		PovFreeLook.turn(1200.0D * 2.0D, 0.0D);
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 13L * ms), 25.0F), "a full 360 degree turn comes back to the same yaw");
		PovFreeLook.turn(1000.0D, 0.0D);
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 13L * ms), 175.0F), "free yaw is not limited");
		PovFreeLook.turn(100.0D, 0.0D);
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 13L * ms), -170.0F), "free yaw wraps past 180");
		PovFreeLook.turn(0.0D, 5000.0D);
		check(PovView.pitch(agent, 1.0F, 7.0F, 13L * ms) == 90.0F, "free pitch stops at straight down");
		PovFreeLook.turn(0.0D, -5000.0D);
		check(PovView.pitch(agent, 1.0F, 7.0F, 13L * ms) == -90.0F, "free pitch stops at straight up");
		PovFreeLook.turn(Double.NaN, 1.0D);
		check(PovView.pitch(agent, 1.0F, 7.0F, 13L * ms) == -90.0F, "non-finite mouse input is ignored");
		PovFreeLook.hold(true, 14L * ms);
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 14L * ms), -170.0F), "holding on does not reseed the free view");

		// Released at -170 / -90 while the agent looks -40 / 0: the return turns 130 degrees the short way and 90 up.
		PovFreeLook.hold(false, 100L * ms);
		check(PovFreeLook.mode(100L * ms) == PovFreeLook.Mode.RETURNING && PovFreeLook.detached(100L * ms), "releasing sneak starts the return");
		check(!PovFreeLook.turn(100.0D, 0.0D), "the mouse does not steer the return");
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 100L * ms), -170.0F) && PovView.pitch(agent, 1.0F, 7.0F, 100L * ms) == -90.0F,
				"the return starts where the free view was");
		float halfway = PovFreeLook.ease(0.5F);
		check(near(halfway, 0.875F), "the return eases out (cubic)");
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 200L * ms), -170.0F + 130.0F * halfway)
				&& near(PovView.pitch(agent, 1.0F, 7.0F, 200L * ms), -90.0F + 90.0F * halfway), "the return glides toward the agent's look");
		check(PovFreeLook.ease(0.0F) == 0.0F && PovFreeLook.ease(1.0F) == 1.0F && PovFreeLook.ease(2.0F) == 1.0F && PovFreeLook.ease(-1.0F) == 0.0F,
				"the ease is clamped to its ends");
		check(PovFreeLook.RETURN_NANOS >= 150L * ms && PovFreeLook.RETURN_NANOS <= 250L * ms, "the return lasts 150 to 250 ms");
		check(PovFreeLook.detached(299L * ms), "the camera is still detached just before the return lands");
		check(PovView.yaw(agent, 1.0F, 42.0F, 300L * ms) == -40.0F && PovView.pitch(agent, 1.0F, 7.0F, 300L * ms) == 0.0F
				&& PovFreeLook.mode(300L * ms) == PovFreeLook.Mode.LOCKED, "the return lands on the agent's look and locks");
		PovView.tick();
		PovView.acceptPose(60.0F, 10.0F);
		PovView.tick();
		check(PovView.yaw(agent, 1.0F, 42.0F, 301L * ms) == 60.0F, "a locked view follows the agent again");

		// Sneak again mid-return: the free view continues from what was on screen.
		PovFreeLook.hold(true, 400L * ms);
		PovFreeLook.turn(200.0D, 0.0D);
		PovFreeLook.hold(false, 500L * ms);
		float midYaw = PovView.yaw(agent, 1.0F, 42.0F, 550L * ms);
		PovView.pitch(agent, 1.0F, 7.0F, 550L * ms);
		PovFreeLook.hold(true, 560L * ms);
		check(near(PovView.yaw(agent, 1.0F, 42.0F, 560L * ms), midYaw), "grabbing during the return continues from the shown view");

		// The return chases the live agent look, across the 180 seam the short way.
		PovFreeLook.reset();
		PovView.bind(agent, false);
		PovView.acceptPose(170.0F, 0.0F);
		PovView.tick();
		PovView.tick();
		PovView.yaw(agent, 1.0F, 42.0F, 0L);
		PovView.pitch(agent, 1.0F, 7.0F, 0L);
		PovFreeLook.hold(true, 0L);
		PovFreeLook.hold(false, 0L);
		PovView.acceptPose(-170.0F, 0.0F);
		PovView.tick();
		PovView.tick();
		float crossing = PovView.yaw(agent, 1.0F, 42.0F, 100L * ms);
		check(near(crossing, 170.0F + 20.0F * PovFreeLook.ease(0.5F)), "the return takes the short way across 180: " + crossing);

		// Takeover never free-looks: the mouse drives the body.
		PovFreeLook.reset();
		PovView.bind(agent, true);
		PovLook.reset(5.0F, 6.0F);
		check(!PovFreeLook.turn(100.0D, 0.0D), "a free-look turn never steals takeover mouse input");
		check(PovView.yaw(agent, 1.0F, 42.0F, 0L) == 5.0F, "takeover reads the predicted look");
		// Free look before any frame was shown waits for one, so it never starts from a made-up view.
		PovView.reset();
		PovFreeLook.hold(true, 0L);
		check(PovFreeLook.mode(0L) == PovFreeLook.Mode.LOCKED, "free look waits for a shown frame");
		PovView.bind(agent, false);
		PovView.acceptPose(30.0F, 0.0F);
		PovView.yaw(agent, 1.0F, 42.0F, 0L);
		PovView.pitch(agent, 1.0F, 7.0F, 0L);
		PovFreeLook.hold(true, 1L);
		check(PovFreeLook.mode(1L) == PovFreeLook.Mode.FREE && PovView.yaw(agent, 1.0F, 42.0F, 1L) == 30.0F, "the next frame frees it");
		PovView.reset();
		check(PovFreeLook.mode(1L) == PovFreeLook.Mode.LOCKED, "ending or switching the view drops free look");
		PovLook.reset(0.0F, 0.0F);
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

	private static void verifyBodyPosition() {
		PovBodyPosition stream = new PovBodyPosition();
		check(stream.next() == null, "no position before the first pose keeps vanilla placement");
		// One pose per tick (the normal case): each tick shows the newest server tick, nothing is held back.
		for (int tick = 1; tick <= 5; tick++) {
			stream.accept(tick, 64.0D, 0.0D);
			PovBodyPosition.Position shown = stream.next();
			check(shown != null && shown.x() == tick && stream.pending() == 0, "steady poses are shown the tick they arrive " + tick);
		}
		check(stream.next() == null, "a tick without a pose holds the current position");
		// Arrivals bunching at the tick boundary (0, 2, 0, 2...) still advance one server tick per client tick.
		stream.reset();
		stream.accept(1.0D, 64.0D, 0.0D);
		stream.accept(2.0D, 64.0D, 0.0D);
		check(stream.next().x() == 1.0D && stream.pending() == 1, "a bunched pair shows the older pose and keeps one in reserve");
		check(stream.next().x() == 2.0D, "the reserve covers the tick that received nothing");
		stream.accept(3.0D, 64.0D, 0.0D);
		stream.accept(4.0D, 64.0D, 0.0D);
		check(stream.next().x() == 3.0D && stream.next().x() == 4.0D, "bunched poses keep moving one step per tick");
		// A burst after a hiccup is caught up at once instead of being replayed late.
		for (int pose = 10; pose < 13; pose++) stream.accept(pose, 64.0D, 0.0D);
		check(stream.next().x() == 11.0D && stream.pending() == 1, "a burst skips to one behind the newest pose");
		for (int pose = 20; pose < 30; pose++) stream.accept(pose, 64.0D, 0.0D);
		check(stream.pending() == PovBodyPosition.MAX_PENDING, "the pending poses are bounded");
		stream.accept(Double.NaN, 64.0D, 0.0D);
		stream.accept(1.0D, Double.POSITIVE_INFINITY, 0.0D);
		check(stream.pending() == PovBodyPosition.MAX_PENDING, "non-finite positions are dropped");
		stream.reset();
		check(stream.pending() == 0 && stream.next() == null, "reset forgets every pending pose");
	}

	private static void verifyLatencyProbe() {
		java.util.List<String> reports = new java.util.ArrayList<>();
		PovLatencyProbe probe = new PovLatencyProbe(reports::add);
		long millis = 1_000_000L;
		probe.sent(5, 0L);
		probe.sent(7, 50L * millis);
		check(probe.acknowledged(5, 80L * millis) == 80L * millis, "the round trip runs from send to the acknowledging pose");
		check(probe.acknowledged(5, 130L * millis) == -1L, "a repeated acknowledgement is not a new sample");
		check(probe.acknowledged(0, 130L * millis) == -1L, "sequence zero acknowledges nothing");
		check(probe.acknowledged(6, 130L * millis) == -1L, "an action sequence that was never a frame is ignored");
		check(probe.acknowledged(7, 130L * millis) == 80L * millis, "frames are matched by sequence");
		check(probe.acknowledged(7 + 128, 200L * millis) == -1L, "a ring slot reused by another sequence is not matched");
		for (int sequence = 1000; sequence < 1000 + PovLatencyProbe.WINDOW; sequence++) {
			probe.sent(sequence, sequence * millis);
			probe.acknowledged(sequence, sequence * millis + 60L * millis);
		}
		check(reports.size() == 1 && reports.get(0).contains("average 61.0 ms") && reports.get(0).contains("worst 80.0 ms"),
				"a full window reports the average and worst round trip: " + reports);
		probe.reset();
		check(probe.acknowledged(1000, 0L) == -1L, "reset forgets sent frames");
	}

	private static boolean near(float actual, float expected) {
		return Math.abs(actual - expected) < 1.0E-4F;
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		checks++;
	}
}
