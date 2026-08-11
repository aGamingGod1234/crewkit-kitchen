package dev.agaminggod.arenaagents.client.gui.scenario;

public enum ScenarioWizardStep {
	MODE("Mode"),
	ARENA("Arena"),
	ROSTER("Roster"),
	REVIEW("Review");

	private final String displayName;

	ScenarioWizardStep(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
