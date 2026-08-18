package dev.agaminggod.arenaagents.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class ChunkedSavedPayloadVerification {
	private ChunkedSavedPayloadVerification() {
	}

	public static int verify() throws IOException {
		String payload = ("agent-state-☃-" + "x".repeat(997)).repeat(90);
		List<String> chunks = ChunkedSavedPayload.split(payload);
		assertTrue(chunks.size() > 1, "oversized payload is split");
		for (String chunk : chunks) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeUTF(chunk);
			}
			assertTrue(bytes.size() <= 65_537, "each NBT UTF payload remains encodable");
		}
		assertEquals(payload, ChunkedSavedPayload.join("legacy", chunks), "chunked payload round trip");
		assertEquals("legacy", ChunkedSavedPayload.join("legacy", List.of()), "legacy payload migration");
		return 4 + chunks.size();
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
