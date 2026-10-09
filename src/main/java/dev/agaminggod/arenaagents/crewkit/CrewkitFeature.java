package dev.agaminggod.arenaagents.crewkit;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/**
 * One CrewKit kitchen feature (counters, head stack, gate, delivery, set, cast...).
 * Each feature owns its own package and entities, tagged "crewkit" plus its own tag,
 * so reset can kill exactly what it spawned. Called on the server thread.
 */
public interface CrewkitFeature {
	/** Contract event from docs/crewkit/CONTRACT.md, already de-duplicated by runId/seq. */
	void onEvent(MinecraftServer server, String event, JsonObject data, long seq);

	/** Every server tick; drive easing/timelines here. */
	default void tick(MinecraftServer server) {}

	/** Remove everything this feature spawned. */
	void reset(MinecraftServer server);
}
