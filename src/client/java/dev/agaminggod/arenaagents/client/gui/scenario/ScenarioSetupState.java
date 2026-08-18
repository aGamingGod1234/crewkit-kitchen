package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ScenarioSetupState {
	private ScenarioWizardStep step = ScenarioWizardStep.ARENA;
	private ScenarioPreset selectedScenario = ScenarioPreset.LAST_VALLEY;
	private final List<ScenarioAgentConfig> roster = new ArrayList<>();
	private int selectedIndex;
	private boolean deterministicEvents = true;
	private ScenarioPlacementMode placementMode = ScenarioPlacementMode.IN_FRONT_OF_PLAYER;

	private ScenarioSetupState(ScenarioAgentConfig initialConfig) {
		roster.add(Objects.requireNonNull(initialConfig, "initialConfig must not be null"));
	}

	public static ScenarioSetupState defaults() {
		return new ScenarioSetupState(ScenarioAgentConfig.defaults(AgentGameMode.SURVIVAL));
	}

	public static ScenarioSetupState defaults(String provider, String model, String reasoning) {
		return new ScenarioSetupState(ScenarioAgentConfig.configuredDefaults(
				provider, model, reasoning, AgentGameMode.SURVIVAL));
	}

	public static ScenarioSetupState fromLaunchPlan(ScenarioLaunchPlan plan) {
		Objects.requireNonNull(plan, "plan must not be null");
		ScenarioPreset preset = java.util.Arrays.stream(ScenarioPreset.values())
				.filter(candidate -> candidate.id().equals(plan.scenarioId()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown arena preset: " + plan.scenarioId()));
		List<ScenarioLaunchPlan.Agent> ordered = plan.roster().stream()
				.sorted(java.util.Comparator.comparingInt(ScenarioLaunchPlan.Agent::slot))
				.toList();
		if (ordered.isEmpty()) throw new IllegalArgumentException("launch plan roster must not be empty");
		ScenarioSetupState restored = new ScenarioSetupState(configFrom(ordered.getFirst()));
		restored.roster.clear();
		for (ScenarioLaunchPlan.Agent agent : ordered) restored.roster.add(configFrom(agent));
		restored.selectedScenario = preset;
		restored.deterministicEvents = plan.deterministicEvents();
		restored.placementMode = plan.placementMode();
		restored.step = ScenarioWizardStep.REVIEW;
		return restored;
	}

	private static ScenarioAgentConfig configFrom(ScenarioLaunchPlan.Agent agent) {
		return new ScenarioAgentConfig(
				agent.provider(), agent.model(), agent.reasoning(), agent.serviceTier(),
				agent.displayName(), agent.team(), agent.gameMode()
		);
	}

	public ScenarioWizardStep step() {
		return step;
	}

	public ScenarioPreset selectedScenario() {
		return selectedScenario;
	}

	public List<ScenarioAgentConfig> roster() {
		return List.copyOf(roster);
	}

	public int selectedIndex() {
		return selectedIndex;
	}

	public boolean deterministicEvents() {
		return deterministicEvents;
	}

	public void setDeterministicEvents(boolean deterministicEvents) {
		this.deterministicEvents = deterministicEvents;
	}

	public ScenarioPlacementMode placementMode() {
		return placementMode;
	}

	public void setPlacementMode(ScenarioPlacementMode placementMode) {
		this.placementMode = Objects.requireNonNull(placementMode, "placementMode must not be null");
	}

	public void choosePresetWorkflow() {
		step = ScenarioWizardStep.ARENA;
	}

	public void showBuildDashboard() {
		step = ScenarioWizardStep.REVIEW;
	}

	public void selectScenario(ScenarioPreset preset) {
		selectedScenario = Objects.requireNonNull(preset, "preset must not be null");
		setAgentCount(Math.clamp(roster.size(), preset.minimumAgents(), preset.maximumAgents()));
		if (preset.gameModeLocked()) {
			for (int index = 0; index < roster.size(); index++) {
				roster.set(index, roster.get(index).withGameMode(preset.defaultGameMode()));
			}
		}
	}

	public void next() {
		step = switch (step) {
			case ARENA -> ScenarioWizardStep.ROSTER;
			case ROSTER -> validationErrors().isEmpty() ? ScenarioWizardStep.REVIEW : ScenarioWizardStep.ROSTER;
			case REVIEW -> ScenarioWizardStep.REVIEW;
		};
	}

	public void previous() {
		step = switch (step) {
			case ARENA -> ScenarioWizardStep.ARENA;
			case ROSTER -> ScenarioWizardStep.ARENA;
			case REVIEW -> ScenarioWizardStep.ROSTER;
		};
	}

	public void setAgentCount(int requestedCount) {
		int count = Math.clamp(requestedCount, selectedScenario.minimumAgents(), selectedScenario.maximumAgents());
		while (roster.size() < count) {
			ScenarioAgentConfig template = roster.isEmpty()
					? ScenarioAgentConfig.defaults(selectedScenario.defaultGameMode())
					: roster.getLast().withName("");
			roster.add(selectedScenario.gameModeLocked()
					? template.withGameMode(selectedScenario.defaultGameMode())
					: template);
		}
		while (roster.size() > count) {
			roster.removeLast();
		}
		selectedIndex = Math.clamp(selectedIndex, 0, roster.size() - 1);
	}

	public void selectOnly(int index) {
		checkIndex(index);
		selectedIndex = index;
	}

	public void applyProvider(String provider) {
		AgentControlCatalog.requireProvider(provider);
		updateSelected(roster.get(selectedIndex).withProvider(provider));
	}

	public void applyModel(String model) {
		updateSelected(roster.get(selectedIndex).withModel(model));
	}

	public void applyReasoning(String reasoning) {
		updateSelected(roster.get(selectedIndex).withReasoning(reasoning));
	}

	public void applyServiceTier(String serviceTier) {
		updateSelected(roster.get(selectedIndex).withServiceTier(serviceTier));
	}

	public void applyTeam(String team) {
		updateSelected(roster.get(selectedIndex).withTeam(team));
	}

	public void applyGameMode(AgentGameMode gameMode) {
		if (selectedScenario.gameModeLocked() && gameMode != selectedScenario.defaultGameMode()) {
			throw new IllegalArgumentException(selectedScenario.title() + " requires "
					+ selectedScenario.defaultGameMode().displayName());
		}
		updateSelected(roster.get(selectedIndex).withGameMode(gameMode));
	}

	public void renameSelected(String name) {
		updateSelected(roster.get(selectedIndex).withName(name));
	}

	public String displayNameAt(int index) {
		checkIndex(index);
		ScenarioAgentConfig config = roster.get(index);
		String base = config.name().isBlank()
				? AgentControlCatalog.displayName(config.provider(), config.model()) : config.name();
		int duplicateIndex = 0;
		for (int current = 0; current < index; current++) {
			ScenarioAgentConfig previous = roster.get(current);
			String previousBase = previous.name().isBlank()
					? AgentControlCatalog.displayName(previous.provider(), previous.model()) : previous.name();
			if (base.equalsIgnoreCase(previousBase)) {
				duplicateIndex++;
			}
		}
		return duplicateIndex == 0 ? base : base + " (" + duplicateIndex + ")";
	}

	public List<String> validationErrors() {
		List<String> errors = new ArrayList<>();
		if (roster.size() < selectedScenario.minimumAgents() || roster.size() > selectedScenario.maximumAgents()) {
			errors.add("Agent count is outside the arena's supported range");
		}
		for (int index = 0; index < roster.size(); index++) {
			ScenarioAgentConfig config = roster.get(index);
			if (selectedScenario.gameModeLocked() && config.gameMode() != selectedScenario.defaultGameMode()) {
				errors.add("Agent " + (index + 1) + " has the wrong game mode");
			}
			if (!AgentControlCatalog.models(config.provider()).contains(config.model())) {
				errors.add("Agent " + (index + 1) + " has an unavailable model");
			}
			if (!AgentControlCatalog.reasoningEfforts(config.provider(), config.model()).contains(config.reasoning())) {
				errors.add("Agent " + (index + 1) + " has an unavailable thinking level");
			}
			if (!AgentControlCatalog.serviceTiers(config.provider(), config.model()).contains(config.serviceTier())) {
				errors.add("Agent " + (index + 1) + " has an unavailable speed mode");
			}
		}
		return List.copyOf(errors);
	}

	public boolean canLaunch() {
		return step == ScenarioWizardStep.REVIEW && validationErrors().isEmpty();
	}

	public ScenarioLaunchPlan launchPlan() {
		List<String> errors = validationErrors();
		if (!errors.isEmpty()) {
			throw new IllegalStateException(String.join("; ", errors));
		}
		List<ScenarioLaunchPlan.Agent> agents = new ArrayList<>(roster.size());
		for (int index = 0; index < roster.size(); index++) {
			ScenarioAgentConfig config = roster.get(index);
			agents.add(new ScenarioLaunchPlan.Agent(
					index + 1,
					displayNameAt(index),
					config.provider(),
					config.model(),
					config.reasoning(),
					config.serviceTier(),
					config.team(),
					config.gameMode()
			));
		}
		return new ScenarioLaunchPlan(
				selectedScenario.id(),
				selectedScenario.title(),
				selectedScenario.mapVersion(),
				deterministicEvents,
				placementMode,
				agents
		);
	}

	public static String readableModelName(String model) {
		String value = Objects.requireNonNull(model, "model must not be null");
		if (value.startsWith("kimi-code/")) {
			value = "kimi-" + value.substring("kimi-code/".length());
		}
		String[] pieces = value.replace('_', '-').split("-");
		StringBuilder result = new StringBuilder();
		for (String piece : pieces) {
			if (piece.isBlank() || piece.matches("\\d+")) {
				continue;
			}
			if (!result.isEmpty()) {
				result.append(' ');
			}
			if (piece.equalsIgnoreCase("gpt")) {
				result.append("GPT");
			} else if (piece.matches("\\d+(\\.\\d+)+")) {
				result.append(piece);
			} else if (piece.matches("[a-zA-Z]+\\d+")) {
				result.append(piece.toUpperCase(Locale.ROOT));
			} else {
				result.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
			}
		}
		return result.isEmpty() ? model : result.toString();
	}

	private void updateSelected(ScenarioAgentConfig config) {
		roster.set(selectedIndex, Objects.requireNonNull(config, "config must not be null"));
	}

	private void checkIndex(int index) {
		if (index < 0 || index >= roster.size()) {
			throw new IndexOutOfBoundsException("agent index: " + index);
		}
	}
}
