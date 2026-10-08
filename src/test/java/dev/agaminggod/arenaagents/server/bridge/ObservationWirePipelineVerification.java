package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.perception.ServerObservationWireBudget;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * The owned observation pipeline (no deep copies, UTF-8 written straight to the frame) must put exactly the bytes on the
 * wire that the copying pipeline and a plain {@code String.getBytes(UTF_8)} produced.
 */
public final class ObservationWirePipelineVerification {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();
	private static final String MAX_MESSAGE_ID = "m".repeat(128);
	private static final AgentId AGENT = AgentId.parse("00000000-0000-0000-0000-000000000001");

	private ObservationWirePipelineVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyUtf8Framing();
		assertions += verifyOwnedPublishMatchesCopyingPublish("fits", observation(24, 3, 0), false);
		assertions += verifyOwnedPublishMatchesCopyingPublish("tags dropped", observation(130, 60, 0), true);
		assertions += verifyOwnedPublishMatchesCopyingPublish("tail trimmed", observation(200, 30, 400), true);
		assertions += verifyOwnedFitMatchesCopyingFit();
		assertions += verifyHeartbeatWindow();
		return assertions;
	}

	/** Counting and framing agree with String.getBytes for ASCII, BMP, astral and unpaired surrogates. */
	private static int verifyUtf8Framing() {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		Random random = new Random(20261009L);
		String alphabet = "ab \"\\<>&=' é世界 😀\ud83d😀\ude00\ud800";
		for (int round = 0; round < 200; round++) {
			JsonObject payload = new JsonObject();
			JsonArray rows = new JsonArray();
			int count = 1 + random.nextInt(6);
			for (int row = 0; row < count; row++) {
				StringBuilder text = new StringBuilder();
				int length = random.nextInt(40);
				for (int index = 0; index < length; index++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
				if (round % 7 == 0) text.append('\ud83d');
				JsonObject entry = new JsonObject();
				entry.addProperty("name", text.toString());
				entry.addProperty("distance", random.nextDouble() * 100.0D);
				entry.add("none", JsonNull.INSTANCE);
				rows.add(entry);
			}
			payload.add("rows", rows);
			String messageId = "server-" + round;
			byte[] reference = referenceFrame(messageId, payload);
			BridgeEnvelope envelope = BridgeEnvelope.fromDecoded(2, "srv", AGENT.toString(), "observation", messageId, payload);
			assertTrue(Arrays.equals(reference, codec.encodeFrame(envelope).bytes()), "frame bytes equal getBytes(UTF_8) + newline, round " + round);
			assertEquals(reference.length - 1, codec.encodedLineBytes(envelope), "line byte count, round " + round);
			assertEquals(reference.length - 1,
					codec.encodedLineBytesForPayload(2, "srv", AGENT.toString(), "observation", messageId, payload),
					"counting-only size equals the frame, round " + round);
		}
		return 600;
	}

	private static int verifyOwnedPublishMatchesCopyingPublish(String label, JsonObject source, boolean expectReductions) {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		List<String> copyingNotes = new ArrayList<>();
		List<String> ownedNotes = new ArrayList<>();
		BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> copying =
				(agent, payload) -> record(copyingNotes, ServerObservationWireBudget.fit(payload, fits(codec)));
		BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> owned =
				(agent, payload) -> record(ownedNotes, ServerObservationWireBudget.fitOwned(payload, fits(codec)));

		Object session = new Object();
		MultiplexedServerBridge.ObservationPublication reference = new MultiplexedServerBridge.ObservationPublication(16, 16, copying);
		MultiplexedServerBridge.ObservationPublication optimized = new MultiplexedServerBridge.ObservationPublication(16, 16, owned);
		MultiplexedServerBridge.onSessionAccepted(reference, session);
		MultiplexedServerBridge.onSessionAccepted(optimized, session);

		AtomicReference<byte[]> referenceBytes = new AtomicReference<>();
		AtomicReference<byte[]> ownedBytes = new AtomicReference<>();
		JsonObject untouched = source.deepCopy();
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED, reference.publish(AGENT, session, source,
				(agent, payload) -> {
					referenceBytes.set(referenceFrame("server-1", payload));
					return true;
				}, true, copying), label + ": reference publishes");
		assertEquals(untouched, source, label + ": the copying publish leaves the caller's tree alone");
		JsonObject handedOver = source.deepCopy();
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED, optimized.publishOwned(AGENT, session, handedOver,
				(agent, payload) -> {
					BridgeEnvelope envelope = BridgeEnvelope.fromDecoded(2, "srv", agent.toString(), "observation", "server-1", payload);
					ownedBytes.set(codec.encodeFrame(envelope).bytes());
					return true;
				}, true, owned), label + ": owned publish commits");
		assertTrue(Arrays.equals(referenceBytes.get(), ownedBytes.get()), label + ": owned and copying pipelines put identical bytes on the wire");
		assertTrue(ownedBytes.get().length - 1 <= BridgeEnvelopeCodec.MAX_LINE_BYTES, label + ": the line respects the wire limit");
		assertEquals(copyingNotes.subList(0, ownedNotes.size()), ownedNotes, label + ": identical reductions at every fitting step");
		assertEquals(expectReductions, !ownedNotes.get(0).equals("[]"), label + ": reductions as expected (" + ownedNotes.get(0) + ")");

		// The baseline each pipeline kept must drive the same next delta.
		JsonObject next = source.deepCopy();
		next.addProperty("observedAtEpochMs", next.get("observedAtEpochMs").getAsLong() + 50L);
		next.getAsJsonArray("entities").add(entity("00000000-0000-0000-0000-0000000000ff", 9.0D));
		AtomicReference<JsonObject> referenceNext = new AtomicReference<>();
		AtomicReference<JsonObject> ownedNext = new AtomicReference<>();
		reference.publish(AGENT, session, next, (agent, payload) -> {
			referenceNext.set(payload.deepCopy());
			return true;
		}, false, copying);
		optimized.publishOwned(AGENT, session, next.deepCopy(), (agent, payload) -> {
			ownedNext.set(payload.deepCopy());
			return true;
		}, false, owned);
		assertEquals(referenceNext.get(), ownedNext.get(), label + ": the delivered baseline yields the same next observation and delta");
		return 8;
	}

	private static int verifyOwnedFitMatchesCopyingFit() {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		JsonObject source = observation(120, 20, 150);
		AtomicInteger copyingChecks = new AtomicInteger();
		AtomicInteger ownedChecks = new AtomicInteger();
		ServerObservationWireBudget.Fitted copying = ServerObservationWireBudget.fit(source, candidate -> {
			copyingChecks.incrementAndGet();
			return fits(codec).test(candidate);
		});
		JsonObject owned = source.deepCopy();
		ServerObservationWireBudget.Fitted trimmed = ServerObservationWireBudget.fitOwned(owned, candidate -> {
			ownedChecks.incrementAndGet();
			return fits(codec).test(candidate);
		});
		assertEquals(copying.observation(), trimmed.observation(), "in-place fit yields the tree the copying fit yields");
		assertEquals(copying.reductions(), trimmed.reductions(), "in-place fit reports the same reductions");
		assertEquals(copyingChecks.get(), ownedChecks.get(), "in-place fit asks the same questions");
		assertTrue(trimmed.takeObservation() == owned, "an owner gets its own tree back, trimmed in place");

		JsonObject small = observation(4, 2, 0);
		JsonObject smallOwned = small.deepCopy();
		ServerObservationWireBudget.Fitted untrimmed = ServerObservationWireBudget.fitOwned(smallOwned, fits(codec));
		assertTrue(untrimmed.reductions().isEmpty() && untrimmed.takeObservation() == smallOwned,
				"an observation that fits is returned as is, without a copy");
		assertEquals(small, smallOwned, "and unchanged");
		return 6;
	}

	private static int verifyHeartbeatWindow() {
		MultiplexedServerBridge.ObservationPublication publication = new MultiplexedServerBridge.ObservationPublication(16, 16, 3);
		Object session = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, session);
		List<AgentId> roster = List.of(AGENT);
		assertTrue(!publication.heartbeatWindowOpen(AGENT), "nothing delivered yet: no heartbeat is coming, so requests queue");
		publication.publish(AGENT, session, observation(2, 1, 0), (agent, payload) -> true, true);
		assertTrue(publication.heartbeatWindowOpen(AGENT), "just delivered: the heartbeat will answer a routine request");
		publication.scheduleIdleHeartbeat(roster);
		publication.scheduleIdleHeartbeat(roster);
		assertTrue(publication.heartbeatWindowOpen(AGENT), "still inside the interval");
		publication.scheduleIdleHeartbeat(roster);
		publication.scheduleIdleHeartbeat(roster);
		assertTrue(!publication.heartbeatWindowOpen(AGENT), "once the heartbeat is due a request is no longer skipped");
		MultiplexedServerBridge.onSessionClosed(publication, session);
		assertTrue(!publication.heartbeatWindowOpen(AGENT), "a reset session has no window");
		return 5;
	}

	private static ServerObservationWireBudget.Fitted record(List<String> notes, ServerObservationWireBudget.Fitted fitted) {
		notes.add(fitted.reductions().toString());
		return fitted;
	}

	private static Predicate<JsonObject> fits(BridgeEnvelopeCodec codec) {
		return candidate -> codec.encodedLineBytesForPayload(2, "srv", AGENT.toString(), "observation", MAX_MESSAGE_ID, candidate)
				<= BridgeEnvelopeCodec.MAX_LINE_BYTES;
	}

	private static byte[] referenceFrame(String messageId, JsonObject payload) {
		JsonObject object = new JsonObject();
		object.addProperty("protocolVersion", 2);
		object.addProperty("serverInstanceId", "srv");
		object.addProperty("agentId", AGENT.toString());
		object.addProperty("type", "observation");
		object.addProperty("messageId", messageId);
		object.add("payload", payload);
		byte[] json = GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
		byte[] frame = Arrays.copyOf(json, json.length + 1);
		frame[json.length] = (byte) 10;
		return frame;
	}

	/** Rows shaped like the collector's: ~950 bytes each, seven tags, a shared handful of block types. */
	static JsonObject observation(int blocks, int landmarks, int entities) {
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", 1_000L);
		observation.addProperty("ready", true);
		JsonObject player = new JsonObject();
		player.addProperty("health", 20.0D);
		player.addProperty("air", 300);
		observation.add("player", player);
		JsonArray blockRows = new JsonArray();
		String[] types = {"minecraft:stone", "minecraft:dirt", "minecraft:oak_log", "minecraft:water"};
		for (int index = 0; index < blocks; index++) blockRows.add(block(types[index % types.length], index));
		observation.add("blocks", blockRows);
		JsonArray landmarkRows = new JsonArray();
		for (int index = 0; index < landmarks; index++) {
			JsonObject row = block(types[index % types.length], index);
			row.addProperty("distance", 10.5D + index);
			landmarkRows.add(row);
		}
		observation.add("landmarks", landmarkRows);
		JsonArray entityRows = new JsonArray();
		for (int index = 0; index < entities; index++) {
			entityRows.add(entity(String.format("00000000-0000-0000-0000-%012d", index + 1), index));
		}
		observation.add("entities", entityRows);
		observation.add("nearbyContainers", new JsonArray());
		JsonObject sections = new JsonObject();
		for (String section : List.of("blocks", "landmarks", "entities", "nearbyContainers")) {
			JsonObject entry = new JsonObject();
			entry.addProperty("returned", observation.getAsJsonArray(section).size());
			entry.addProperty("complete", false);
			sections.add(section, entry);
		}
		JsonObject coverage = new JsonObject();
		coverage.addProperty("mode", "sampled_visible");
		coverage.add("sections", sections);
		observation.add("coverage", coverage);
		JsonObject perception = new JsonObject();
		perception.addProperty("latestSequence", 3);
		observation.add("perception", perception);
		return observation;
	}

	private static JsonObject block(String blockId, int index) {
		JsonObject row = new JsonObject();
		JsonObject state = new JsonObject();
		state.addProperty("level", "0");
		row.add("state", state);
		row.add("bounds", new JsonArray());
		row.addProperty("replaceable", index % 2 == 0);
		row.addProperty("x", index % 13);
		row.addProperty("y", 60 + index % 5);
		row.addProperty("z", index / 13);
		row.addProperty("blockId", blockId);
		row.add("placeableFaces", new JsonArray());
		JsonArray tags = new JsonArray();
		for (String tag : List.of("#minecraft:enchantment_power_transmitter", "#minecraft:geode_invalid_blocks",
				"#minecraft:overworld_carver_replaceables", "#minecraft:replaceable", "#minecraft:replaceable_by_mushrooms",
				"#minecraft:replaceable_by_trees", "#minecraft:" + blockId.substring(blockId.indexOf(':') + 1))) tags.add(tag);
		row.add("tags", tags);
		return row;
	}

	private static JsonObject entity(String uuid, double distance) {
		JsonObject row = new JsonObject();
		row.addProperty("uuid", uuid);
		row.addProperty("type", "minecraft:zombie");
		row.addProperty("name", "Zombie é😀 " + uuid.substring(24));
		row.addProperty("distance", distance);
		JsonArray tags = new JsonArray();
		for (int index = 0; index < 12; index++) tags.add("#minecraft:tag_" + index);
		row.add("tags", tags);
		return row;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " observation wire pipeline assertions");
	}
}
