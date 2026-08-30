package dev.agaminggod.arenaagents.scenario;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ScenarioLaunchRequest(
		String scenarioId,
		String mapVersion,
		boolean deterministicEvents,
		ScenarioPlacementMode placementMode,
		List<ScenarioAgentSpec> roster,
		String confirmationToken
) {
	public ScenarioLaunchRequest(
			String scenarioId,
			String mapVersion,
			boolean deterministicEvents,
			ScenarioPlacementMode placementMode,
			List<ScenarioAgentSpec> roster
	) {
		this(scenarioId, mapVersion, deterministicEvents, placementMode, roster, "");
	}

	public ScenarioLaunchRequest {
		ScenarioPreset preset = ScenarioPresets.require(scenarioId);
		scenarioId = preset.id();
		mapVersion = Objects.requireNonNull(mapVersion, "mapVersion must not be null").trim();
		if (!preset.mapVersion().equals(mapVersion)) {
			throw new IllegalArgumentException("scenario map version does not match the installed preset");
		}
		placementMode = Objects.requireNonNull(placementMode, "placementMode must not be null");
		confirmationToken = Objects.requireNonNull(confirmationToken, "confirmationToken must not be null").trim();
		if (confirmationToken.length() > 80 || confirmationToken.indexOf('\n') >= 0
				|| confirmationToken.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("confirmation token is invalid");
		}
		roster = List.copyOf(Objects.requireNonNull(roster, "roster must not be null"));
		preset.validateAgentCount(roster.size());
		HashSet<Integer> slots = new HashSet<>();
		for (ScenarioAgentSpec agent : roster) {
			Objects.requireNonNull(agent, "roster must not contain null");
			if (!slots.add(agent.slot())) {
				throw new IllegalArgumentException("scenario roster slots must be unique");
			}
			if (!"CONFIGURABLE".equals(preset.requiredGameMode())
					&& !preset.requiredGameMode().equals(agent.gameMode().name())) {
				throw new IllegalArgumentException(
						preset.title() + " requires " + preset.requiredGameMode() + " game mode"
				);
			}
		}
	}
}
