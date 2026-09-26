package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.*;
import java.util.Optional;

/** A saved cast identity, independent of autonomous agent registration. */
public record SkitActor(AgentId agentId, String name, String appearance, boolean dead) {
	public SkitActor {
		java.util.Objects.requireNonNull(agentId);
		name = AgentValidators.requireUserName(name);
		if (!java.util.List.of("codex", "claude", "gemini", "kimi", "cursor").contains(appearance))
			throw new AgentDomainException("INVALID_APPEARANCE", "Choose a supported actor appearance");
	}

	/** The offline-player adapter shares appearance and identity helpers, never an AI runtime. */
	public AgentProfile profile() {
		return new AgentProfile(appearance.equals("claude") ? "gemini" : appearance,
				switch (appearance) {
					case "claude" -> "claude-sonnet-4-6";
					case "gemini" -> "gemini-3.1-pro";
					case "kimi" -> "kimi-code/k3";
					case "cursor" -> "composer-2.5";
					default -> "gpt-6-luna";
				}, "medium", Optional.of(name), 0);
	}
	public SkitActor withDead(boolean value) { return new SkitActor(agentId, name, appearance, value); }
}
