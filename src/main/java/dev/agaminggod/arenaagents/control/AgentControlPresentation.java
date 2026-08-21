package dev.agaminggod.arenaagents.control;

import java.util.Locale;
import java.util.Objects;

public final class AgentControlPresentation {
	private AgentControlPresentation() {
	}

	public static String stateLabel(String state) {
		return switch (Objects.requireNonNull(state, "state must not be null").toUpperCase(Locale.ROOT)) {
			case "IDLE" -> "Ready";
			case "STARTING" -> "Starting up...";
			case "PLANNING" -> "Thinking...";
			case "ACTING" -> "Working";
			case "PAUSED" -> "Paused";
			case "COMPLETED" -> "Task complete";
			case "ERROR" -> "Needs attention";
			case "DEAD" -> "Dead - ready to respawn";
			case "DISCONNECTED" -> "Disconnected";
			default -> "Unknown";
		};
	}

	public static String profileLabel(AgentControlAgent agent) {
		Objects.requireNonNull(agent, "agent must not be null");
		return readableModel(agent.model()) + " | " + capitalize(agent.reasoning());
	}

	public static String readableModel(String model) {
		String value = Objects.requireNonNull(model, "model must not be null");
		if (value.startsWith("kimi-code/")) value = value.substring("kimi-code/".length());
		StringBuilder result = new StringBuilder();
		for (String part : value.replace('_', '-').split("-")) {
			if (part.isBlank()) continue;
			if (!result.isEmpty()) result.append(' ');
			if (part.equalsIgnoreCase("gpt")) result.append("GPT");
			else result.append(capitalize(part));
		}
		return result.isEmpty() ? value : result.toString();
	}

	public static String speedLabel(String serviceTier) {
		String value = Objects.requireNonNull(serviceTier, "serviceTier must not be null");
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "fast" -> "Fast mode";
			case "priority" -> "Normal";
			default -> capitalize(value);
		};
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
	}
}
