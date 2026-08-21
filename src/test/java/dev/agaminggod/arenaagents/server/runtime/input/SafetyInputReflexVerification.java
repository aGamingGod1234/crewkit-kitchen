package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SafetyInputReflexVerification {
	private SafetyInputReflexVerification() {
	}

	public static int verify() {
		int assertions = 0;
		SafetyInputReflex.Threat damaged = new SafetyInputReflex.Threat(
				true, false, false, 300, 300, true,
				15.0F, -5.0F, 2, Optional.of(135.0F)
		);
		SafetyInputReflex.Decision escape = SafetyInputReflex.choose(damaged).orElseThrow();
		assertEquals(SafetyInputReflex.Reason.RECENT_DAMAGE, escape.reason(), "damage reason"); assertions++;
		assertEquals(1.0F, escape.input().forward(), "damage moves away"); assertions++;
		assertEquals(135.0F, escape.input().yaw(), "damage uses observed escape direction"); assertions++;
		assertEquals(true, escape.input().jump(), "grounded escape jumps"); assertions++;
		assertEquals(true, escape.input().sprint(), "damage escape sprints"); assertions++;
		assertEquals(false, escape.input().attack(), "reflex never chooses an attack"); assertions++;
		assertEquals(2_000L, escape.durationMs(), "reflex lease is bounded"); assertions++;

		SafetyInputReflex.Threat unseenDamage = new SafetyInputReflex.Threat(
				true, false, false, 300, 300, true,
				15.0F, -5.0F, 2, Optional.empty()
		);
		assertEquals(Optional.empty(), SafetyInputReflex.choose(unseenDamage),
				"damage without an observed attacker invents no direction"); assertions++;
		assertEquals(Optional.of(90.0F), SafetyInputReflex.escapeYaw(0.0D, 0.0D, 1.0D, 0.0D),
				"escape yaw points directly away from an east attacker"); assertions++;
		assertEquals(Optional.empty(), SafetyInputReflex.escapeYaw(0.0D, 0.0D, 0.0D, 0.0D),
				"overlapping positions invent no escape direction"); assertions++;

		SafetyInputReflex.Decision lowAir = SafetyInputReflex.choose(new SafetyInputReflex.Threat(
				false, false, false, 40, 300, false,
				20.0F, 10.0F, 4, Optional.empty()
		)).orElseThrow();
		assertEquals(SafetyInputReflex.Reason.LOW_AIR, lowAir.reason(), "low air reason"); assertions++;
		assertEquals(true, lowAir.input().jump(), "low air swims upward"); assertions++;
		assertEquals(4, lowAir.input().selectedSlot(), "reflex preserves selected slot"); assertions++;

		SafetyInputReflex.Decision suffocating = SafetyInputReflex.choose(new SafetyInputReflex.Threat(
				false, true, true, 10, 300, true,
				20.0F, 10.0F, 4, Optional.empty()
		)).orElseThrow();
		assertEquals(SafetyInputReflex.Reason.SUFFOCATING, suffocating.reason(),
				"suffocation wins over other hazards"); assertions++;

		assertEquals(Optional.empty(), SafetyInputReflex.choose(new SafetyInputReflex.Threat(
				false, false, false, 300, 300, true,
				0.0F, 0.0F, 0, Optional.empty()
		)), "safe player has no synthetic input"); assertions++;

		LeasedServerInputController controller = new LeasedServerInputController(new InputStateSink() {
			@Override public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) { }
			@Override public void clear(AgentId agentId, AgentInputState previous) { }
		});
		AgentId agentId = new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000071"));
		InputLease navigation = controller.acquire(agentId, InputOwner.NAVIGATION, 100);
		AgentInputState walking = AgentInputState.idle(0.0F, 0.0F, 0);
		controller.apply(navigation, walking);
		SafetyInputLease safety = new SafetyInputLease(controller, agentId);
		safety.activate(escape.input(), 100L, escape.durationMs());
		assertEquals(escape.input(), controller.currentState(agentId).orElseThrow(),
				"safety lease preempts model movement"); assertions++;
		assertEquals(false, safety.releaseExpired(2_099L), "lease remains active before its deadline"); assertions++;
		assertEquals(true, safety.releaseExpired(2_100L), "lease releases at its deadline"); assertions++;
		assertEquals(walking, controller.currentState(agentId).orElseThrow(),
				"model movement resumes after safety release"); assertions++;

		RecentDamageTracker damage = new RecentDamageTracker();
		assertEquals(false, damage.observe(agentId, 20.0F), "first health sample establishes a baseline"); assertions++;
		assertEquals(true, damage.observe(agentId, 17.0F), "a health drop is recent damage"); assertions++;
		assertEquals(false, damage.observe(agentId, 17.0F), "unchanged health does not retrigger damage"); assertions++;
		damage.retainAgents(Set.of());
		assertEquals(false, damage.observe(agentId, 16.0F), "removed agents do not retain stale health"); assertions++;
		return assertions;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
