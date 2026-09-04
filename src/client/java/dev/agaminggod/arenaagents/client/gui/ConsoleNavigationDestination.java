package dev.agaminggod.arenaagents.client.gui;

/** The destinations shared by every Field Console screen, in display order. */
public enum ConsoleNavigationDestination {
	AGENTS("screen.arenaagents.navigation.agents", "AGENTS"),
	GROUP("screen.arenaagents.navigation.group", "GROUP"),
	LIVE("screen.arenaagents.navigation.live", "LIVE"),
	BUILD("screen.arenaagents.navigation.build", "BUILD");

	private final String translationKey;
	private final String compactLabel;

	ConsoleNavigationDestination(String translationKey, String compactLabel) {
		this.translationKey = translationKey;
		this.compactLabel = compactLabel;
	}

	public String translationKey() {
		return translationKey;
	}

	public String compactLabel() {
		return compactLabel;
	}
}
