package dev.agaminggod.arenaagents.crewkit.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeatures;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for {@code crewkit_state} bridge messages ({@code {runId, seq, event, data}}, see
 * docs/crewkit/CONTRACT.md). Drops events from retired runs and any seq at or below the last applied
 * seq of the current run, then fans out to every {@link CrewkitFeature} on the server thread.
 */
public final class CrewkitDispatcher {
	public static final Logger LOGGER = LoggerFactory.getLogger("arenaagents/crewkit");

	private static List<CrewkitFeature> features;
	private static String currentRun;
	private static long lastSeq = Long.MIN_VALUE;
	private static final Set<String> retiredRuns = new HashSet<>();

	private CrewkitDispatcher() {}

	/** Call once from the mod initializer. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CrewkitDispatcher::tick);
		// Displays persist in the world save but feature state does not, so start every session clean.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> resetAll(server, true));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			CrewkitReplay.stop();
			CrewkitSchedule.clear();
			forgetRuns();
			clearRetired();
		});
		CrewkitCommands.register();
	}

	/** Bridge intake. Safe to call from any thread. */
	public static void accept(MinecraftServer server, JsonObject payload) {
		if (server == null || payload == null) return;
		if (!server.isSameThread()) {
			server.execute(() -> accept(server, payload));
			return;
		}
		String runId = string(payload, "runId");
		String event = string(payload, "event");
		JsonElement seqElement = payload.get("seq");
		if (runId == null || event == null || seqElement == null || !seqElement.isJsonPrimitive()) {
			LOGGER.warn("CrewKit: dropped malformed crewkit_state payload (needs runId, seq, event)");
			return;
		}
		long seq;
		try {
			seq = seqElement.getAsLong();
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit: dropped crewkit_state with non-numeric seq");
			return;
		}
		JsonObject data = payload.has("data") && payload.get("data").isJsonObject() ? payload.getAsJsonObject("data") : new JsonObject();
		if (!admit(runId, seq)) {
			LOGGER.debug("CrewKit: dropped stale {} run={} seq={}", event, runId, seq);
			return;
		}
		dispatch(server, event, data, seq);
	}

	/** Fan out without run/seq checks (manual tests). */
	public static void dispatch(MinecraftServer server, String event, JsonObject data, long seq) {
		LOGGER.info("CrewKit event {} seq={}", event, seq);
		if ("reset".equals(event)) {
			resetAll(server, false);
			return;
		}
		for (CrewkitFeature feature : features()) {
			try {
				feature.onEvent(server, event, data, seq);
			} catch (RuntimeException e) {
				LOGGER.warn("CrewKit feature {} failed on {}", feature.getClass().getSimpleName(), event, e);
			}
		}
	}

	/** Clear every feature's displays and pending motions. {@code forget} also clears run/seq memory. */
	public static void resetAll(MinecraftServer server, boolean forget) {
		CrewkitSchedule.clear();
		if (forget) {
			CrewkitReplay.stop();
			forgetRuns();
		}
		for (CrewkitFeature feature : features()) {
			try {
				feature.reset(server);
			} catch (RuntimeException e) {
				LOGGER.warn("CrewKit feature {} failed to reset", feature.getClass().getSimpleName(), e);
			}
		}
		try {
			dev.agaminggod.arenaagents.crewkit.set.SetBuilder.resetDynamic(server);
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit set failed to reset", e);
		}
	}

	static synchronized boolean admit(String runId, long seq) {
		if (retiredRuns.contains(runId)) return false;
		if (!runId.equals(currentRun)) {
			if (currentRun != null) retiredRuns.add(currentRun);
			currentRun = runId;
			lastSeq = Long.MIN_VALUE;
		}
		if (seq <= lastSeq) return false;
		lastSeq = seq;
		return true;
	}

	/** Retire the current run so a still-polling old run cannot redraw the boards after a reset. */
	static synchronized void forgetRuns() {
		if (currentRun != null) retiredRuns.add(currentRun);
		currentRun = null;
		lastSeq = Long.MIN_VALUE;
	}

	static synchronized void clearRetired() {
		retiredRuns.clear();
	}

	private static void tick(MinecraftServer server) {
		CrewkitSchedule.tick(server);
		CrewkitReplay.tick(server);
		for (CrewkitFeature feature : features()) {
			try {
				feature.tick(server);
			} catch (RuntimeException e) {
				LOGGER.warn("CrewKit feature {} failed to tick", feature.getClass().getSimpleName(), e);
			}
		}
	}

	/** Features are stateful, so build the registry list once. */
	private static List<CrewkitFeature> features() {
		if (features == null) features = List.copyOf(CrewkitFeatures.all());
		return features;
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
