package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.Objects;

/** Server rules for a model's takeTask: who may turn a conversational request into the agent's goal. */
public final class ModelTaskAdoptionVerification {
	private ModelTaskAdoptionVerification() {
	}

	public static int verify() {
		int assertions = 0;
		for (AgentLifecycleState state : new AgentLifecycleState[] {
				AgentLifecycleState.IDLE, AgentLifecycleState.COMPLETED, AgentLifecycleState.PAUSED }) {
			assertEquals(ModelTaskAdoption.Operation.START, ModelTaskAdoption.operation(state, false, false, false),
					"any player who asked can start work on a " + state + " agent");
			assertions++;
		}
		assertEquals(ModelTaskAdoption.Operation.RESUME,
				ModelTaskAdoption.operation(AgentLifecycleState.PAUSED, true, false, false),
				"a paused task resumes when the model asks to continue it");
		assertions++;
		assertRefused("NOTHING_TO_RESUME", AgentLifecycleState.COMPLETED, true, false, false);
		assertions++;
		for (AgentLifecycleState state : new AgentLifecycleState[] {
				AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING, AgentLifecycleState.ACTING }) {
			assertEquals(ModelTaskAdoption.Operation.REPLACE, ModelTaskAdoption.operation(state, false, true, false),
					"an operator's request may replace active work");
			assertRefused("AGENT_BUSY", state, false, false, false);
			assertions += 2;
		}
		assertRefused("AGENT_TAKEN_OVER", AgentLifecycleState.IDLE, false, true, true);
		assertRefused("AGENT_TAKEN_OVER", AgentLifecycleState.PAUSED, true, true, true);
		assertRefused("AGENT_UNAVAILABLE", AgentLifecycleState.DEAD, false, true, false);
		assertRefused("AGENT_UNAVAILABLE", AgentLifecycleState.ERROR, false, false, false);
		assertions += 4;
		return assertions;
	}

	private static void assertRefused(String code, AgentLifecycleState state, boolean resume, boolean operator, boolean reserved) {
		try {
			ModelTaskAdoption.operation(state, resume, operator, reserved);
		} catch (AgentDomainException exception) {
			assertEquals(code, exception.code(), "refusal code for " + state);
			if (exception.getMessage() == null || exception.getMessage().isBlank()) {
				throw new AssertionError("refusal for " + state + " must carry a message the model can relay");
			}
			return;
		}
		throw new AssertionError("expected " + code + " for " + state);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + " but was " + actual);
		}
	}
}
