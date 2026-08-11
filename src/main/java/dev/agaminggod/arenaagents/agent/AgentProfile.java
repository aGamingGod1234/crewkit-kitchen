package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.Optional;

public record AgentProfile(
		String provider,
		String model,
		String reasoning,
		Optional<String> userName,
		int skinVariant,
		AgentGameMode gameMode
) {
	public AgentProfile {
		provider = AgentValidators.requireProvider(provider);
		model = AgentValidators.requireModel(model);
		reasoning = AgentValidators.requireReasoning(reasoning);
		userName = Objects.requireNonNull(userName, "userName must not be null")
				.map(AgentValidators::requireUserName);
		if (skinVariant < 0) {
			throw new AgentDomainException("INVALID_SKIN_VARIANT", "skinVariant must not be negative");
		}
		gameMode = Objects.requireNonNull(gameMode, "gameMode must not be null");
	}

	public AgentProfile(String provider, String model, String reasoning, Optional<String> userName, int skinVariant) {
		this(provider, model, reasoning, userName, skinVariant, AgentGameMode.SURVIVAL);
	}

	public AgentProfile(String model, String reasoning, Optional<String> userName, int skinVariant) {
		this("codex", model, reasoning, userName, skinVariant, AgentGameMode.SURVIVAL);
	}

	public String nameTag() {
		return model + " · " + reasoning;
	}
}
