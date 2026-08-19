package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentModelNames;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import java.util.Objects;
import java.util.Optional;

/** Pure friendly-name policy for snapshot-authoritative agent world tags. */
public final class AgentWorldNamePolicy {
	private AgentWorldNamePolicy() {
	}

	public static Optional<String> tag(AgentControlAgent agent) {
		Objects.requireNonNull(agent, "agent must not be null");
		if (agent.friendlyName().isBlank()) return Optional.empty();
		AgentVisualIdentity.Resolved identity = AgentVisualIdentity.resolve(
				agent.provider(), agent.model(), agent.skinVariant());
		return Optional.of(identity.providerGlyph() + " " + agent.friendlyName() + " · "
				+ AgentModelNames.shortLabel(agent.provider(), agent.model()));
	}
}
