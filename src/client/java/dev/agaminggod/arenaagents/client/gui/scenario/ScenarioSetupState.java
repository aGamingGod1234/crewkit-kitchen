package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class ScenarioSetupState {
	private ScenarioWizardStep step = ScenarioWizardStep.MODE;
	private ScenarioPreset selectedScenario = ScenarioPreset.LAST_VALLEY;
	private final List<ScenarioAgentConfig> roster = new ArrayList<>();
	private final Set<Integer> selectedIndices = new LinkedHashSet<>();
	private boolean deterministicEvents = true;

	private ScenarioSetupState() {
		roster.add(ScenarioAgentConfig.defaults(selectedScenario.defaultGameMode()));
		selectedIndices.add(0);
	}

	public static ScenarioSetupState defaults() {
		return new ScenarioSetupState();
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

	public Set<Integer> selectedIndices() {
		return Set.copyOf(selectedIndices);
	}

	public boolean deterministicEvents() {
		return deterministicEvents;
	}

	public void setDeterministicEvents(boolean deterministicEvents) {
		this.deterministicEvents = deterministicEvents;
	}

	public void choosePresetWorkflow() {
		step = ScenarioWizardStep.ARENA;
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
			case MODE -> ScenarioWizardStep.ARENA;
			case ARENA -> ScenarioWizardStep.ROSTER;
			case ROSTER -> validationErrors().isEmpty() ? ScenarioWizardStep.REVIEW : ScenarioWizardStep.ROSTER;
			case REVIEW -> ScenarioWizardStep.REVIEW;
		};
	}

	public void previous() {
		step = switch (step) {
			case MODE -> ScenarioWizardStep.MODE;
			case ARENA -> ScenarioWizardStep.MODE;
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
		selectedIndices.removeIf(index -> index < 0 || index >= roster.size());
		if (selectedIndices.isEmpty() && !roster.isEmpty()) {
			selectedIndices.add(0);
		}
	}

	public void setSelectedIndices(Set<Integer> indices) {
		selectedIndices.clear();
		for (Integer index : Objects.requireNonNull(indices, "indices must not be null")) {
			if (index != null && index >= 0 && index < roster.size()) {
				selectedIndices.add(index);
			}
		}
	}

	public void selectOnly(int index) {
		checkIndex(index);
		selectedIndices.clear();
		selectedIndices.add(index);
	}

	public void toggleSelected(int index) {
		checkIndex(index);
		if (!selectedIndices.add(index)) {
			selectedIndices.remove(index);
		}
	}

	public void selectAll() {
		selectedIndices.clear();
		for (int index = 0; index < roster.size(); index++) {
			selectedIndices.add(index);
		}
	}

	public void clearSelection() {
		selectedIndices.clear();
	}

	public void applyProvider(String provider) {
		AgentControlCatalog.requireProvider(provider);
		applySelected(config -> config.withProvider(provider));
	}

	public void applyModel(String model) {
		applySelected(config -> config.withModel(model));
	}

	public void applyReasoning(String reasoning) {
		applySelected(config -> config.withReasoning(reasoning));
	}

	public void applyTeam(String team) {
		applySelected(config -> config.withTeam(team));
	}

	public void applyGameMode(AgentGameMode gameMode) {
		if (selectedScenario.gameModeLocked() && gameMode != selectedScenario.defaultGameMode()) {
			throw new IllegalArgumentException(selectedScenario.title() + " requires "
					+ selectedScenario.defaultGameMode().displayName());
		}
		applySelected(config -> config.withGameMode(gameMode));
	}

	public void renameSelected(String name) {
		if (selectedIndices.size() != 1) {
			throw new IllegalStateException("Select one agent to set a custom name");
		}
		int index = selectedIndices.iterator().next();
		roster.set(index, roster.get(index).withName(name));
	}

	public String displayNameAt(int index) {
		checkIndex(index);
		ScenarioAgentConfig config = roster.get(index);
		String base = config.name().isBlank() ? readableModelName(config.model()) : config.name();
		int duplicateIndex = 0;
		for (int current = 0; current < index; current++) {
			ScenarioAgentConfig previous = roster.get(current);
			String previousBase = previous.name().isBlank() ? readableModelName(previous.model()) : previous.name();
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
					config.team(),
					config.gameMode()
			));
		}
		return new ScenarioLaunchPlan(
				selectedScenario.id(),
				selectedScenario.title(),
				selectedScenario.mapVersion(),
				deterministicEvents,
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

	private void applySelected(UnaryOperator<ScenarioAgentConfig> operation) {
		for (Integer index : selectedIndices) {
			roster.set(index, operation.apply(roster.get(index)));
		}
	}

	private void checkIndex(int index) {
		if (index < 0 || index >= roster.size()) {
			throw new IndexOutOfBoundsException("agent index: " + index);
		}
	}
}
