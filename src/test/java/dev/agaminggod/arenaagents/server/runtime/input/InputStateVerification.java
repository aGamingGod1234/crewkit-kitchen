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

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError(label + ": expected IllegalStateException");
	}
}
