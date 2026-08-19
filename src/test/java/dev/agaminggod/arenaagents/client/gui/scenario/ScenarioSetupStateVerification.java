package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentModelNames;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public final class ScenarioSetupStateVerification {
	private ScenarioSetupStateVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyExactSlotIdentityAndCapacity();
		assertions += verifyOrderedDraftMutation();
		assertions += verifyCatalogSafeEntriesAndRepair();
		assertions += verifyPreviewIdentityAndLaunchBoundary();
		assertions += verifyExistingWizardContract();
		return assertions;
	}

	private static int verifyExactSlotIdentityAndCapacity() {
		ScenarioSetupState state = ScenarioSetupState.defaults();
		assertEquals("scenario-slot:1", state.slotIdAt(0), "one-agent draft uses exact one-based slot ID");
		state.setAgentCount(8);
		assertEquals("scenario-slot:8", state.slotIdAt(7), "eight-agent draft uses exact final slot ID");
		state.setAgentCount(16);
		assertEquals("scenario-slot:16", state.slotIdAt(15), "sixteen-agent draft uses exact final slot ID");
		assertTrue(!state.addDraftSlot(), "draft cannot append above scenario maximum");
		state.setAgentCount(1);
		assertTrue(!state.removeFocusedDraftSlot(), "draft cannot remove below scenario minimum");
		assertEquals(1, state.roster().size(), "minimum-capacity rejection leaves draft intact");
		return 6;
	}

	private static int verifyOrderedDraftMutation() {
		ScenarioSetupState state = ScenarioSetupState.defaults();
		state.setAgentCount(4);
		for (int index = 0; index < 4; index++) {
			state.selectOnly(index);
			state.renameSelected(List.of("Alpha", "Bravo", "Charlie", "Delta").get(index));
		}
		state.selectSlot("scenario-slot:2");
		assertEquals(1, state.selectedIndex(), "exact slot selection changes inspector focus");
		state.selectSlot("scenario-slot:02");
		assertEquals(1, state.selectedIndex(), "near-match slot ID does not change focus");
		assertTrue(state.removeFocusedDraftSlot(), "focused middle slot can be removed");
		assertEquals(List.of("Alpha", "Charlie", "Delta"), names(state),
				"middle removal preserves survivor order");
		assertEquals(1, state.selectedIndex(), "middle removal focuses the survivor now occupying that position");
		assertEquals("scenario-slot:2", state.slotIdAt(state.selectedIndex()),
				"focus remains an exact current slot identity after renumbering");

		state.selectOnly(1);
		state.applyTeam("Blue");
		assertTrue(state.addDraftSlot(), "draft appends below maximum");
		assertEquals(3, state.selectedIndex(), "append focuses the new tail slot");
		assertEquals("Blue", state.roster().getLast().team(), "append uses the focused slot as its template");
		assertEquals("", state.roster().getLast().name(), "append clears the copied custom name");

		state.selectOnly(3);
		state.setAgentCount(2);
		assertEquals(List.of("Alpha", "Charlie"), names(state), "quick count tail-truncates only");
		assertEquals(1, state.selectedIndex(), "tail truncation clamps inspector focus to the last survivor");
		return 12;
	}

	private static int verifyCatalogSafeEntriesAndRepair() {
		List<AgentControlModelOption> original = AgentControlCatalog.currentOptions();
		try {
			ScenarioSetupState missingModel = ScenarioSetupState.defaults();
			missingModel.renameSelected("Keeper");
			missingModel.applyTeam("Gold");
			missingModel.setAgentCount(8);
			String removedModel = missingModel.roster().getFirst().model();
			AgentControlCatalog.installRuntimeCatalog(original.stream()
					.filter(option -> !option.provider().equals("codex") || !option.model().equals(removedModel))
					.toList());
			assertEquals("Model " + AgentModelNames.displayName("codex", removedModel) + " is unavailable",
					missingModel.unavailableReasonAt(0), "removed model exposes the first concise incompatibility");
			List<AgentRosterEntry> unavailableEntries = missingModel.rosterEntries();
			assertEquals(8, unavailableEntries.size(), "catalog removal keeps every draft slot in roster order");
			AgentRosterEntry unavailable = unavailableEntries.getFirst();
			assertEquals("Unavailable", unavailable.state(), "invalid slot remains renderable with unavailable state");
			assertTrue(!unavailable.selectable(), "invalid slot is not presented as launch-ready");
			assertEquals("Keeper", unavailable.name(), "invalid slot retains its current display name");
			assertEquals(AgentModelNames.shortLabel("codex", removedModel), unavailable.modelLabel(),
					"invalid slot retains the canonical short model label");
			assertTrue(!missingModel.validationErrors().isEmpty(), "validation agrees that unavailable slot blocks launch");
			missingModel.next();
			missingModel.next();
			assertTrue(!missingModel.canLaunch(), "unavailable slot cannot launch");
			assertTrue(missingModel.repairFocusedSlot(), "invalid slot can adopt current catalog settings");
			assertEquals("Keeper", missingModel.roster().getFirst().name(), "repair preserves custom name");
			assertEquals("Gold", missingModel.roster().getFirst().team(), "repair preserves team");
			assertEquals("", missingModel.unavailableReasonAt(0), "repair clears the catalog incompatibility");

			ScenarioSetupState missingProvider = ScenarioSetupState.defaults("gemini", "gemini-3.1-pro", "high");
			AgentControlCatalog.installRuntimeCatalog(original.stream()
					.filter(option -> !option.provider().equals("gemini"))
					.toList());
			assertEquals("Provider gemini is unavailable", missingProvider.unavailableReasonAt(0),
					"removed provider is reported before its model settings");
			assertEquals("Gemini 3.1 Pro", missingProvider.displayNameAt(0),
					"removed provider display falls back to canonical model naming");
			assertEquals(1, missingProvider.rosterEntries().size(),
					"removed provider does not make roster entry construction throw");
			assertEquals(1, missingProvider.validationErrors().size(),
					"removed provider produces one centralized slot validation error");
			return 17;
		} finally {
			AgentControlCatalog.installRuntimeCatalog(original);
		}
	}

	private static int verifyPreviewIdentityAndLaunchBoundary() {
		ScenarioSetupState state = ScenarioSetupState.defaults();
		state.setAgentCount(8);
		assertEquals(List.of(0, 1, 2, 3, 0, 1, 2, 3), IntStream.range(0, state.roster().size())
				.mapToObj(index -> state.previewVisualIdentityAt(index).individualVariant())
				.toList(), "ordered preview variants cycle zero through three");
		AgentVisualIdentity.Resolved before = state.previewVisualIdentityAt(1);
		state.selectOnly(1);
		state.applyModel("gpt-5.4");
		AgentVisualIdentity.Resolved after = state.previewVisualIdentityAt(1);
		assertEquals(1, after.individualVariant(), "model edit preserves slot preview variant");
		assertEquals(AgentVisualIdentity.resolve("codex", "gpt-5.4", 1), after,
				"preview resolves the exact edited provider and model family");
		assertEquals(before.individualVariant(), after.individualVariant(),
				"preview identity ordering is stable across model edits");

		state.next();
		state.next();
		ScenarioLaunchPlan plan = state.launchPlan();
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
				plan.roster().stream().map(ScenarioLaunchPlan.Agent::slot).toList(),
				"ordered one-based slots survive review planning");
		assertEquals("gpt-5.4", plan.roster().get(1).model(), "launch preserves exact edited model");
		assertEquals(8, plan.roster().size(), "preview does not reserve or add a launch agent");
		assertTrue(Arrays.stream(ScenarioLaunchPlan.Agent.class.getRecordComponents())
				.noneMatch(component -> component.getName().toLowerCase().contains("preview")
						|| component.getName().toLowerCase().contains("variant")
						|| component.getName().toLowerCase().contains("visual")),
				"preview-only identity never enters the launch payload");

		ScenarioLaunchRegistry.register(ignored -> new ScenarioLaunchRegistry.Result(true, "accepted"));
		try {
			ScenarioLaunchRegistry.launch(plan);
			ScenarioSetupState draft = ScenarioSetupState.fromLaunchPlan(plan);
			draft.previous();
			draft.selectOnly(0);
			draft.renameSelected("Draft only");
			draft.addDraftSlot();
			assertEquals(plan, ScenarioLaunchRegistry.lastAcceptedPlan().orElseThrow(),
					"draft-only edits do not mutate the accepted launch registry");
		} finally {
			ScenarioLaunchRegistry.clear();
		}
		return 10;
	}

	private static int verifyExistingWizardContract() {
		ScenarioSetupState state = ScenarioSetupState.defaults("gemini", "gemini-3.1-pro", "low");
		assertEquals(ScenarioWizardStep.ARENA, state.step(), "arena tab starts at preset selection");
		assertEquals(ScenarioPreset.LAST_VALLEY, state.selectedScenario(), "survival preset is the safe default");
		assertEquals(AgentGameMode.SURVIVAL, state.roster().getFirst().gameMode(), "survival is default game mode");
		state.selectScenario(ScenarioPreset.CITADEL_COLLAPSE);
		assertEquals(2, state.roster().size(), "PvP enforces its two-agent minimum");
		state.next();
		state.next();
		assertTrue(state.canLaunch(), "valid review can launch");
		ScenarioLaunchPlan plan = state.launchPlan();
		assertEquals("citadel-collapse", plan.scenarioId(), "launch plan preserves scenario ID");
		assertEquals(ScenarioPlacementMode.IN_FRONT_OF_PLAYER, plan.placementMode(),
				"launch keeps visible default placement");
		state.previous();
		state.selectScenario(ScenarioPreset.THINKING_TOWER);
		state.setAgentCount(1);
		assertEquals(AgentGameMode.ADVENTURE, state.roster().getFirst().gameMode(),
				"scenario-required game mode stays enforced");
		return 8;
	}

	private static List<String> names(ScenarioSetupState state) {
		return state.roster().stream().map(ScenarioAgentConfig::name).toList();
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) throw new AssertionError(label + ": expected true");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
