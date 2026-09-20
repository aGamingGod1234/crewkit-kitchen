package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.mixin.CachedProfileInfoAccessor;
import dev.agaminggod.arenaagents.mixin.CachedUserNameToIdResolverAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** A global lookup cache is not ownership: only this world's player artifacts reserve a name. */
final class AgentPlayerNameReservations {
	private AgentPlayerNameReservations() {}

	static boolean isReserved(MinecraftServer server, String name) {
		WorldPlayerNames names = WorldPlayerNames.get(server);
		if (names.contains(name)) return true;
		Path world = server.getWorldPath(LevelResource.ROOT);
		var cache = server.services().nameToIdCache();
		Object entry = ((CachedUserNameToIdResolverAccessor) cache).arenaagents$cachedProfilesByName().get(name.toLowerCase(Locale.ROOT));
		UUID cachedId = entry == null ? null : ((CachedProfileInfoAccessor) entry).arenaagents$nameAndId().id();
		boolean reserved = isReserved(world, cachedId, name);
		if (reserved) names.remember(name, hasArtifacts(world, AgentIdentity.offlinePlayerUuid(name)) ? AgentIdentity.offlinePlayerUuid(name) : cachedId);
		return reserved;
	}

	static boolean isReserved(Path world, UUID cachedId, String name) {
		UUID offlineId = AgentIdentity.offlinePlayerUuid(name);
		return hasArtifacts(world, offlineId) || cachedId != null && !cachedId.equals(offlineId) && hasArtifacts(world, cachedId);
	}

	private static boolean hasArtifacts(Path world, UUID id) {
		String uuid = id.toString();
		for (String directory : java.util.List.of(LevelResource.PLAYER_DATA_DIR.id(), LevelResource.PLAYER_OLD_DATA_DIR.id(), "playerdata")) {
			Path data = world.resolve(directory);
			if (Files.exists(data.resolve(uuid + ".dat")) || Files.exists(data.resolve(uuid + ".dat_old"))) return true;
		}
		for (String directory : java.util.List.of(LevelResource.PLAYER_STATS_DIR.id(), LevelResource.PLAYER_ADVANCEMENTS_DIR.id(), "stats", "advancements")) {
			if (Files.exists(world.resolve(directory).resolve(uuid + ".json"))) return true;
		}
		return false;
	}
}
