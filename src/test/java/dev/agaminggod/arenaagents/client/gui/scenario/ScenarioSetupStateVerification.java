package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import java.util.List;
import java.util.Set;

public final class ScenarioSetupStateVerification {
	private ScenarioSetupStateVerification() {
	}

	public static int verify() {
		int assertions = 0;

		ScenarioSetupState state = ScenarioSetupState.defaults();
		assertEquals(ScenarioWizardStep.MODE, state.step(), "wizard starts at mode");
		assertEquals(ScenarioPreset.LAST_VALLEY, state.selectedScenario(), "survival preset is the safe default");
		assertEquals(1, state.roster().size(), "wizard starts with one agent");
		assertEquals("GPT 5.6 Sol", state.displayNameAt(0), "default model has a readable name");
		assertEquals(AgentGameMode.SURVIVAL, state.roster().getFirst().gameMode(), "survival is the default game mode");
		assertions += 5;

		state.choosePresetWorkflow();
		assertEquals(ScenarioWizardStep.ARENA, state.step(), "preset workflow advances to arena library");
		state.selectScenario(ScenarioPreset.CITADEL_COLLAPSE);
		assertEquals(2, state.roster().size(), "PvP enforces its two-agent minimum");
		assertEquals("GPT 5.6 Sol", state.displayNameAt(0), "first duplicate has no suffix");
		assertEquals("GPT 5.6 Sol (1)", state.displayNameAt(1), "second duplicate starts at one");
		assertions += 4;

		state.setAgentCount(4);
		assertEquals(4, state.roster().size(), "agent count expands exactly");
		state.setSelectedIndices(Set.of(1, 3));
		state.applyProvider("gemini");
		assertEquals("gemini", state.roster().get(1).provider(), "bulk provider applies to first selection");
		assertEquals("gemini", state.roster().get(3).provider(), "bulk provider applies to second selection");
		assertEquals("codex", state.roster().get(0).provider(), "bulk provider leaves unselected rows unchanged");
		assertEquals("gemini-3.1-pro", state.roster().get(1).model(), "provider change selects a valid default model");
		assertEquals("high", state.roster().get(1).reasoning(), "provider change selects a valid reasoning level");
		assertions += 6;

		state.next();
		assertEquals(ScenarioWizardStep.ROSTER, state.step(), "arena advances to roster");
		state.next();
		assertEquals(ScenarioWizardStep.REVIEW, state.step(), "roster advances to review");
		assertTrue(state.validationErrors().isEmpty(), "valid roster has no launch blockers");
		assertTrue(state.canLaunch(), "valid review can launch");
		ScenarioLaunchPlan plan = state.launchPlan();
		assertEquals("citadel-collapse", plan.scenarioId(), "launch plan preserves scenario id");
		assertEquals(4, plan.roster().size(), "launch plan preserves roster count");
		assertEquals(List.of(1, 2, 3, 4), plan.roster().stream().map(ScenarioLaunchPlan.Agent::slot).toList(),
				"launch plan uses stable one-based slots");
		assertTrue(!ScenarioLaunchRegistry.isAvailable(), "launch bridge is unavailable until runtime registers");
		ScenarioLaunchRegistry.register(request -> new ScenarioLaunchRegistry.Result(
				true,
				"Queued " + request.scenarioTitle()
		));
		assertTrue(ScenarioLaunchRegistry.isAvailable(), "registered runtime makes launch available");
		ScenarioLaunchRegistry.Result result = ScenarioLaunchRegistry.launch(plan);
		assertTrue(result.accepted(), "registered runtime accepts launch");
		assertEquals("Queued Citadel Collapse", result.message(), "launch result preserves runtime feedback");
		ScenarioLaunchRegistry.clear();
		assertTrue(!ScenarioLaunchRegistry.isAvailable(), "clearing runtime disables launch");
		assertions += 12;

		state.previous();
		assertEquals(ScenarioWizardStep.ROSTER, state.step(), "previous returns to roster");
		state.setAgentCount(1);
		assertEquals(2, state.roster().size(), "PvP count cannot fall below two");
		state.selectScenario(ScenarioPreset.THINKING_TOWER);
		state.setAgentCount(1);
		assertEquals(1, state.roster().size(), "single-agent parkour is allowed");
		assertEquals(AgentGameMode.ADVENTURE, state.roster().getFirst().gameMode(),
				"scenario-required game mode is visibly enforced");
		state.selectScenario(ScenarioPreset.IMPOSSIBLE_BRIEF);
		state.selectAll();
		state.applyGameMode(AgentGameMode.SURVIVAL);
		assertEquals(AgentGameMode.SURVIVAL, state.roster().getFirst().gameMode(),
				"configurable building arena accepts survival mode");
		state.selectScenario(ScenarioPreset.LAST_VALLEY);
		assertThrows(
				() -> state.applyGameMode(AgentGameMode.CREATIVE),
				"The Last Valley requires Survival",
				"locked survival arena rejects creative mode"
		);
		assertions += 6;

		return assertions;
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertThrows(Runnable operation, String expectedMessage, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected exception");
		} catch (IllegalArgumentException exception) {
			assertEquals(expectedMessage, exception.getMessage(), label + " message");
		}
	}
}
