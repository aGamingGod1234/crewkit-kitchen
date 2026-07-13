package dev.agaminggod.arenaagents.client.action;

public enum SafetyState {
	READY("READY", "Action context is ready"),
	WORLD_UNAVAILABLE("WORLD_UNAVAILABLE", "Minecraft world is unavailable"),
	PLAYER_UNAVAILABLE("PLAYER_UNAVAILABLE", "Minecraft player is unavailable"),
	PLAYER_DEAD("PLAYER_DEAD", "Minecraft player is dead"),
	SCREEN_OPEN("SCREEN_OPEN", "A Minecraft screen is open"),
	DISCONNECTED("DISCONNECTED", "Minecraft client is disconnected");

	private final String reasonCode;
	private final String message;

	SafetyState(String reasonCode, String message) {
		this.reasonCode = reasonCode;
		this.message = message;
	}

	public String reasonCode() {
		return reasonCode;
	}

	public String message() {
		return message;
	}
}
