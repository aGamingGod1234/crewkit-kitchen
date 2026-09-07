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
		return Files.exists(world.resolve("playerdata").resolve(uuid + ".dat"))
				|| Files.exists(world.resolve("playerdata").resolve(uuid + ".dat_old"))
				|| Files.exists(world.resolve("stats").resolve(uuid + ".json"))
				|| Files.exists(world.resolve("advancements").resolve(uuid + ".json"));
	}
}
