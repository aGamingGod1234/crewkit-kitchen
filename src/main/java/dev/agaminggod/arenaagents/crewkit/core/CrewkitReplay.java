package dev.agaminggod.arenaagents.crewkit.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Plays an NDJSON file of contract events through the dispatcher with their timing, so visuals can be
 * tested without the coordinator. One JSON object per line: {@code {"t":1500,"seq":3,"event":"quote","data":{...}}}.
 * {@code t} is milliseconds from the start (optional; missing means 1 s after the previous line). Lines
 * starting with {@code //} or {@code #} are skipped. Each replay gets a fresh runId so it is never stale.
 */
public final class CrewkitReplay {
	private record Step(int tick, JsonObject payload) {}

	private static final Deque<Step> STEPS = new ArrayDeque<>();
	private static int replayCount;
	private static int startTick;

	private CrewkitReplay() {}

	/** Candidate locations for a relative name: server dir, server dir/crewkit, then the repo's docs/crewkit. */
	public static Path resolve(MinecraftServer server, String name) {
		Path given = Path.of(name);
		if (given.isAbsolute()) return given;
		Path serverDir = server.getServerDirectory().toAbsolutePath().normalize();
		List<Path> candidates = new ArrayList<>(List.of(serverDir.resolve(name), serverDir.resolve("crewkit").resolve(name)));
		if (serverDir.getParent() != null) candidates.add(serverDir.getParent().resolve("docs").resolve("crewkit").resolve(name));
		for (Path candidate : candidates) if (Files.isRegularFile(candidate)) return candidate;
		return candidates.get(0);
	}

	/** Loads and starts a replay; returns the number of events queued. */
	public static int start(MinecraftServer server, Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		String runId = "replay-" + (++replayCount) + "-" + System.currentTimeMillis();
		List<Step> steps = new ArrayList<>();
		long lastMs = -1000;
		long seq = 0;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;
			JsonObject source = JsonParser.parseString(line).getAsJsonObject();
			long ms = source.has("t") ? source.get("t").getAsLong() : lastMs + 1000;
			lastMs = ms;
			seq = source.has("seq") ? source.get("seq").getAsLong() : seq + 1;
			JsonObject payload = new JsonObject();
			payload.addProperty("runId", runId);
			payload.addProperty("seq", seq);
			payload.addProperty("event", source.get("event").getAsString());
			payload.add("data", source.has("data") ? source.getAsJsonObject("data") : new JsonObject());
			steps.add(new Step((int) Math.round(ms / 50.0), payload));
		}
		CrewkitDispatcher.resetAll(server, true);
		STEPS.addAll(steps);
		startTick = server.getTickCount();
		return steps.size();
	}

	static void tick(MinecraftServer server) {
		int elapsed = server.getTickCount() - startTick;
		while (!STEPS.isEmpty() && STEPS.peekFirst().tick() <= elapsed) {
			CrewkitDispatcher.accept(server, STEPS.pollFirst().payload());
		}
	}

	public static boolean running() {
		return !STEPS.isEmpty();
	}

	static void stop() {
		STEPS.clear();
	}
}
