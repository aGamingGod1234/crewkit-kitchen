package dev.agaminggod.arenaagents.client.control;

import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotStore;
import java.util.Optional;

/** Presentation data may be read by skin lookup before Minecraft's keybindings exist. */
public final class AgentClientRoster {
	private static final AgentControlSnapshotStore SNAPSHOTS = new AgentControlSnapshotStore();

	private AgentClientRoster() {
	}

	static AgentControlSnapshotStore snapshots() {
		return SNAPSHOTS;
	}

	public static Optional<AgentControlAgent> agentForPlayer(String name) {
		return SNAPSHOTS.current().flatMap(snapshot -> AgentPlayerIdentity.find(snapshot, name));
	}

	public static Optional<String> displayName(GameProfile profile) {
		return agentForPlayer(profile.name())
				.filter(agent -> profile.id().equals(AgentIdentity.offlinePlayerUuid(agent.playerName())))
				.map(AgentControlAgent::displayName)
				.or(() -> DirectorClientState.snapshot().stream().flatMap(value -> value.actors().stream())
						.filter(actor -> actor.playerName().equals(profile.name())
								&& profile.id().equals(AgentIdentity.offlinePlayerUuid(actor.playerName())))
						.map(actor -> actor.name()).findFirst());
	}
}
