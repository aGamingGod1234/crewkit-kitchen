package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.InteractionHand;

public final class InputStateVerification {
	private static final AgentId AGENT = new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000070"));

	private InputStateVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyCompleteInputState();
		assertions += verifyLeasePreemptionAndRestoration();
		assertions += verifyOwnedReleasePreservesSystemLease();
		assertions += verifyClearReleasesEveryPressedInput();
		assertions += verifyFailedApplyRemainsRetryable();
		assertions += verifyFailedReleaseRemainsRetryable();
		assertions += verifyFailedPreemptingReleaseRemainsRetryable();
		assertions += verifyBoundedMotor();
		return assertions;
	}

	private static int verifyCompleteInputState() {
		AgentInputState state = new AgentInputState(
				-1.0F, 0.75F, true, true, true, true, true,
				135.0F, -25.0F, 7, InteractionHand.OFF_HAND
		);
		assertEquals(-1.0F, state.forward(), "reverse movement");
		assertEquals(0.75F, state.strafe(), "strafe movement");
		assertEquals(true, state.jump(), "jump pressed");
		assertEquals(true, state.sneak(), "sneak pressed");
		assertEquals(true, state.sprint(), "sprint pressed");
		assertEquals(true, state.attack(), "attack pressed");
		assertEquals(true, state.use(), "use pressed");
		assertEquals(135.0F, state.yaw(), "look yaw");
		assertEquals(-25.0F, state.pitch(), "look pitch");
		assertEquals(7, state.selectedSlot(), "selected slot");
		assertEquals(InteractionHand.OFF_HAND, state.hand(), "interaction hand");
		return 11;
	}

	private static int verifyLeasePreemptionAndRestoration() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease combat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		AgentInputState attacking = state(0.0F, true, false);
		controller.apply(combat, attacking);
		controller.apply(navigation, state(1.0F, false, true));
		assertEquals(attacking, controller.currentState(AGENT).orElseThrow(), "higher-priority combat owns inputs");
		controller.release(combat);
		assertEquals(state(1.0F, false, true), controller.currentState(AGENT).orElseThrow(), "navigation state restores after combat");
		assertEquals(List.of(walking, attacking, state(1.0F, false, true)), sink.applied, "only winning states reach the sink");
		return 3;
	}

	private static int verifyClearReleasesEveryPressedInput() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		controller.apply(lease, new AgentInputState(
				1.0F, -1.0F, true, true, true, true, true,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		));
		controller.clear(AGENT);
		assertEquals(true, controller.currentState(AGENT).isEmpty(), "clear removes current state");
		assertEquals(List.of(AGENT), sink.cleared, "clear reaches the physical sink once");
		assertThrows(() -> controller.apply(lease, state(1.0F, true, true)), "cleared lease cannot affect a respawned player");
		return 3;
	}

	private static int verifyOwnedReleasePreservesSystemLease() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease safety = controller.acquire(AGENT, InputOwner.SYSTEM, 1_000);
		AgentInputState escaping = state(1.0F, false, true);
		controller.apply(safety, escaping);

		controller.release(navigation);
		assertEquals(escaping, controller.currentState(AGENT).orElseThrow(),
				"action-owned release preserves the system lease");
		assertEquals(List.of(walking, escaping), sink.applied,
				"releasing a preempted action lease does not disturb physical system input");
		controller.release(safety);
		assertEquals(List.of(AGENT), sink.cleared, "system input clears only when its own lease releases");
		return 3;
	}

	private static int verifyFailedApplyRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		AgentInputState requested = state(1.0F, true, false);
		long revisionAfterAcquire = controller.mutationRevision();

		sink.failNextApply();
		assertThrows(() -> controller.apply(lease, requested), "failed physical apply is reported");
		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"failed physical apply does not become the current state");
		assertEquals(revisionAfterAcquire, controller.mutationRevision(),
				"failed physical apply does not advance the mutation revision");

		controller.apply(lease, requested);
		assertEquals(2, sink.applyAttempts, "identical apply retries the physical transition");
		assertEquals(requested, controller.currentState(AGENT).orElseThrow(),
				"successful retry becomes the current state");
		assertEquals(revisionAfterAcquire + 1L, controller.mutationRevision(),
				"successful apply advances the mutation revision once");
		return 6;
	}

	private static int verifyFailedReleaseRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		AgentInputState applied = state(0.0F, false, true);
		controller.apply(lease, applied);
		long revisionBeforeRelease = controller.mutationRevision();

		sink.failNextClear();
		assertThrows(() -> controller.release(lease), "failed physical release is reported");
		assertEquals(applied, controller.currentState(AGENT).orElseThrow(),
				"failed physical release keeps the successful current state");
		assertEquals(revisionBeforeRelease, controller.mutationRevision(),
				"failed physical release does not advance the mutation revision");

		controller.release(lease);
		assertEquals(2, sink.clearAttempts, "release retries the physical transition with the same lease");
		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"successful release removes the current state");
		assertEquals(revisionBeforeRelease + 1L, controller.mutationRevision(),
				"successful release advances the mutation revision once");
		return 6;
	}

	private static int verifyFailedPreemptingReleaseRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease combat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		AgentInputState attacking = state(0.0F, true, false);
		controller.apply(combat, attacking);
		long revisionBeforeRelease = controller.mutationRevision();

		sink.failNextApply();
		assertThrows(() -> controller.release(combat), "failed restoration apply is reported");
		assertEquals(attacking, controller.currentState(AGENT).orElseThrow(),
				"failed restoration keeps the preempting lease authoritative");
		assertEquals(revisionBeforeRelease, controller.mutationRevision(),
				"failed restoration does not advance the mutation revision");

		controller.release(combat);
		assertEquals(4, sink.applyAttempts, "release retries restoring the lower-priority physical state");
		assertEquals(walking, controller.currentState(AGENT).orElseThrow(),
				"successful retry restores the lower-priority lease");
		assertEquals(revisionBeforeRelease + 1L, controller.mutationRevision(),
				"successful preempting release advances the mutation revision once");
		return 6;
	}

	private static int verifyBoundedMotor() {
		AgentInputStates.MotorState initial = AgentInputStates.MotorState.initial(170.0F, 20.0F);
		AgentInputStates.MotorStep turn = AgentInputStates.stepMotor(
				initial,
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, false, true),
				0L
		);
		assertEquals(AgentInputStates.MAX_YAW_STEP_DEGREES,
				Math.abs(AgentInputStates.shortestAngleDelta(initial.yaw(), turn.state().yaw())),
				"yaw takes the shortest bounded step across wrap");
		assertEquals(AgentInputStates.MAX_PITCH_STEP_DEGREES,
				Math.abs(initial.pitch() - turn.state().pitch()),
				"pitch takes a bounded step");
		assertTrue(turn.forward() >= 0.0F, "target-relative motor does not reverse unnecessarily");
		assertTrue(Math.abs(turn.strafe()) > 0.0F, "target-relative motor supplies useful strafing");

		AgentInputStates.MotorState accelerating = AgentInputStates.MotorState.initial(0.0F, 0.0F);
		AgentInputStates.MotorStep first = AgentInputStates.stepMotor(
				accelerating,
				new AgentInputStates.MotorTarget(0.0F, 0.0F, true, false, true),
				0L
		);
		assertEquals(AgentInputStates.MOVE_ACCELERATION, first.forward(), "motor accelerates by a bounded amount");
		AgentInputStates.MotorStep braking = AgentInputStates.stepMotor(
				first.state(),
				new AgentInputStates.MotorTarget(0.0F, 0.0F, false, false, false),
				1L
		);
		assertEquals(0.0F, braking.forward(), "motor brakes to zero without overshoot");

		AgentInputStates.MotorStep jumped = AgentInputStates.stepMotor(
				initial,
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				3L
		);
		assertEquals(true, jumped.jump(), "jump request produces an edge pulse");
		AgentInputStates.MotorStep held = AgentInputStates.stepMotor(
				jumped.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				4L
		);
		assertEquals(true, held.jump(), "held jump keeps Carpet's continuous jump action active across waypoints");
		AgentInputStates.MotorStep released = AgentInputStates.stepMotor(
				held.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, false, true),
				5L
		);
		AgentInputStates.MotorStep repulsed = AgentInputStates.stepMotor(
				released.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				6L
		);
		assertEquals(true, repulsed.jump(), "a released jump request can pulse again");
		AgentInputStates.MotorState facingEast = new AgentInputStates.MotorState(-90.0F, 0.0F, 0.0F, 1.0F, false);
		AgentInputStates.MotorStep turningNorth = AgentInputStates.stepMotor(
				facingEast,
				new AgentInputStates.MotorTarget(0.0F, 0.0F, true, false, false),
				7L
		);
		float remainingYaw = AgentInputStates.shortestAngleDelta(turningNorth.state().yaw(), 0.0F);
		assertEquals((float) Math.sin(Math.toRadians(remainingYaw)), turningNorth.strafe(),
				"movement is relative to the yaw applied this tick instead of the stale previous yaw");
		return 14;
	}

	private static AgentInputState state(float forward, boolean attack, boolean use) {
		return new AgentInputState(
				forward, 0.0F, false, false, forward > 0.0F, attack, use,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		);
	}

	private static final class RecordingSink implements InputStateSink {
		private final List<AgentInputState> applied = new ArrayList<>();
		private final List<AgentId> cleared = new ArrayList<>();

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			applied.add(state);
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			cleared.add(agentId);
		}
	}

	private static final class FailingSink implements InputStateSink {
		private int applyAttempts;
		private int clearAttempts;
		private boolean failApply;
		private boolean failClear;

		private void failNextApply() {
			failApply = true;
		}

		private void failNextClear() {
			failClear = true;
		}

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			applyAttempts++;
			if (failApply) {
				failApply = false;
				throw new IllegalStateException("physical apply failed");
			}
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			clearAttempts++;
			if (failClear) {
				failClear = false;
				throw new IllegalStateException("physical clear failed");
			}
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError(label + ": expected IllegalStateException");
	}
}
