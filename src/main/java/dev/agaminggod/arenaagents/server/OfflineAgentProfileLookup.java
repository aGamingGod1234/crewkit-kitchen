package dev.agaminggod.arenaagents.server;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Scopes the remote-profile bypass to Arena Agents currently entering Carpet's fake-player factory. */
public final class OfflineAgentProfileLookup {
	private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();

	private OfflineAgentProfileLookup() {
	}

	public static void begin(UUID uuid) {
		PENDING.add(java.util.Objects.requireNonNull(uuid, "uuid must not be null"));
	}

	public static void end(UUID uuid) {
		PENDING.remove(java.util.Objects.requireNonNull(uuid, "uuid must not be null"));
	}

	public static boolean shouldBypassRemoteLookup(UUID uuid) {
		return PENDING.contains(java.util.Objects.requireNonNull(uuid, "uuid must not be null"));
	}
}
