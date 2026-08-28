package dev.agaminggod.arenaagents.server.voice;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

public final class VoiceConsentRegistry {
	private static final Map<MinecraftServer, Set<UUID>> ENABLED = new WeakHashMap<>();

	private VoiceConsentRegistry() {
	}

	public static synchronized void grant(MinecraftServer server, UUID playerId) {
		ENABLED.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(playerId);
	}

	public static synchronized void revoke(MinecraftServer server, UUID playerId) {
		Set<UUID> players = ENABLED.get(server);
		if (players == null) return;
		players.remove(playerId);
		if (players.isEmpty()) ENABLED.remove(server);
	}

	public static synchronized boolean granted(MinecraftServer server, UUID playerId) {
		return ENABLED.getOrDefault(server, Set.of()).contains(playerId);
	}

	public static synchronized void clear(MinecraftServer server) {
		ENABLED.remove(server);
	}
}
