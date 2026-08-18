package dev.agaminggod.arenaagents.server;

import java.util.ArrayList;
import java.util.List;

/** Keeps SavedData strings below Java's modified-UTF record limit while preserving legacy saves. */
public final class ChunkedSavedPayload {
	private static final int CHUNK_CHARACTERS = 20_000;

	private ChunkedSavedPayload() {
	}

	public static List<String> split(String payload) {
		if (payload == null || payload.isEmpty()) return List.of();
		ArrayList<String> chunks = new ArrayList<>((payload.length() + CHUNK_CHARACTERS - 1) / CHUNK_CHARACTERS);
		for (int start = 0; start < payload.length(); start += CHUNK_CHARACTERS) {
			chunks.add(payload.substring(start, Math.min(payload.length(), start + CHUNK_CHARACTERS)));
		}
		return List.copyOf(chunks);
	}

	public static String join(String legacyPayload, List<String> chunks) {
		if (chunks != null && !chunks.isEmpty()) return String.join("", chunks);
		return legacyPayload == null ? "" : legacyPayload;
	}
}
