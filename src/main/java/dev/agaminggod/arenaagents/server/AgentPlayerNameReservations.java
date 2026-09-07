package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Checks existing local identities without resolving or manufacturing a new profile. */
final class AgentPlayerNameReservations {
	private AgentPlayerNameReservations() {}

	static boolean isReserved(Path world, Map<String, ?> cachedProfiles, String name) {
		if (cachedProfiles.containsKey(name.toLowerCase(Locale.ROOT))) return true;
		String uuid = AgentIdentity.offlinePlayerUuid(name).toString();
		return Files.exists(world.resolve("playerdata").resolve(uuid + ".dat"))
				|| Files.exists(world.resolve("playerdata").resolve(uuid + ".dat_old"))
				|| Files.exists(world.resolve("stats").resolve(uuid + ".json"))
				|| Files.exists(world.resolve("advancements").resolve(uuid + ".json"));
	}
}
