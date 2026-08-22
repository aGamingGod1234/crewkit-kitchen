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
		assertions += verifyClearReleasesEveryPressedInput();
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
		assertEquals(false, held.jump(), "held jump request does not repeat every tick");
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
		return 13;
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
