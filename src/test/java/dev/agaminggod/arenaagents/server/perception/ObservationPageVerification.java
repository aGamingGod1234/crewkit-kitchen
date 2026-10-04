package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservationPageVerification {
	private ObservationPageVerification() { }

	public static int verify() {
		AtomicInteger reads = new AtomicInteger();
		JsonObject page = ObservationPage.collect(90, 64, 16, index -> {
			reads.incrementAndGet();
			JsonObject entry = new JsonObject();
			entry.addProperty("slot", index);
			return entry;
		}, "open_menu");
		check(reads.get() == 16, "page reads only its requested slots");
		check(page.getAsJsonArray("entries").get(0).getAsJsonObject().get("slot").getAsInt() == 64, "slots beyond 63 are addressable");
		check(page.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "partial page signals more slots");
		check(page.getAsJsonObject("coverage").get("nextOffset").getAsInt() == 80, "cursor advances by delivered entries");
		JsonObject last = ObservationPage.collect(90, 80, 16, index -> new JsonObject(), "open_menu");
		check(!last.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "last page signals exhaustion");
		check(!last.getAsJsonObject("coverage").get("complete").getAsBoolean(), "last page is not the full menu");
		JsonObject large = ObservationPage.collect(20, 0, 32, index -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("text", "x".repeat(2_000));
			return entry;
		}, "book");
		check(large.getAsJsonObject("coverage").get("byteLimited").getAsBoolean(), "byte limit is explicit");
		check(large.getAsJsonObject("coverage").get("nextOffset").getAsInt() == large.getAsJsonArray("entries").size(), "byte limit does not skip undelivered entries");
		check(bytes(large) <= ObservationPage.MAX_BYTES, "complete page stays within budget");
		verifyOversizedEntries();
		JsonObject original = new JsonObject();
		JsonArray blocks = new JsonArray();
		for (int index = 0; index < 8; index++) {
			JsonObject block = new JsonObject();
			block.addProperty("x", index);
			block.addProperty("padding", "x".repeat(100));
			blocks.add(block);
		}
		original.add("blocks", blocks);
		original.add("coverage", ObservationPage.coverage(original));
		JsonObject reduced = ServerObservationWireBudget.fit(original, value -> value.toString().length() <= 650).observation();
		JsonObject section = reduced.getAsJsonObject("coverage").getAsJsonObject("sections").getAsJsonObject("blocks");
		check(section.get("returned").getAsInt() == reduced.getAsJsonArray("blocks").size(), "wire coverage matches delivered candidates");
		check(section.get("returned").getAsInt() + section.get("omittedByWire").getAsInt() == 8, "wire omissions retain original count");
		JsonObject twice = ServerObservationWireBudget.fit(reduced, value -> value.toString().length() <= 510).observation();
		JsonObject twiceSection = twice.getAsJsonObject("coverage").getAsJsonObject("sections").getAsJsonObject("blocks");
		check(twiceSection.get("returned").getAsInt() + twiceSection.get("omittedByWire").getAsInt() == 8, "repeated envelope fitting preserves omission total");
		return 35;
	}

	private static void verifyOversizedEntries() {
		String supplementary = new String(Character.toChars(0x1f600)).repeat(2048);
		JsonObject oversized = bookEntry(0, supplementary);
		JsonObject first = ObservationPage.collect(2, 0, 1, index -> index == 0 ? oversized : bookEntry(index, "later"), "held_book", 8_000);
		JsonObject coverage = first.getAsJsonObject("coverage");
		check(first.getAsJsonArray("entries").isEmpty(), "oversized content is not misrepresented as a delivered entry");
		check(coverage.get("nextOffset").getAsInt() == 1, "oversized first entry advances the cursor");
		check(coverage.get("hasMore").getAsBoolean(), "later entries remain reachable");
		check(coverage.get("byteLimited").getAsBoolean() && !coverage.get("complete").getAsBoolean(), "oversized omission is incomplete and byte limited");
		JsonObject omission = coverage.getAsJsonObject("omittedEntry");
		check(omission.get("offset").getAsInt() == 0 && omission.get("utf8Bytes").getAsInt() == bytes(oversized), "omission identifies the entry and its serialized UTF-8 size");
		check(omission.get("reason").getAsString().equals("entry_exceeds_page_budget"), "omission explains why content remains unknown");
		check(bytes(first) <= 8_000, "omission and coverage fit inside the page budget");
		check(first.equals(ObservationPage.collect(2, 0, 1, index -> oversized, "held_book", 8_000)), "same offset gives a stable omission and cursor");
		JsonObject later = ObservationPage.collect(2, coverage.get("nextOffset").getAsInt(), 1, index -> bookEntry(index, "later"), "held_book", 8_000);
		check(later.getAsJsonArray("entries").get(0).getAsJsonObject().get("page").getAsInt() == 1, "following cursor delivers the later book page");
		check(!later.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "later page ends traversal");
		check(oversized.get("text").getAsString().equals(supplementary), "source Unicode content is never mutated");
		AtomicInteger reads = new AtomicInteger();
		JsonObject base = ObservationPage.collect(90, 64, 16, index -> { reads.incrementAndGet(); return oversized; }, "open_menu", 8_000);
		check(reads.get() == 1, "oversized page reads only its blocking entry");
		check(base.getAsJsonObject("coverage").get("nextOffset").getAsInt() == 65, "cursor respects nonzero base offset");
		check(base.getAsJsonObject("coverage").get("limit").getAsInt() == 16 && base.getAsJsonObject("coverage").getAsJsonObject("omittedEntry").get("offset").getAsInt() == 64, "requested limit and omission offset survive");
		JsonObject normal = ObservationPage.collect(3, 0, 3, index -> bookEntry(index, "short"), "held_book", 8_000);
		check(normal.getAsJsonObject("coverage").get("complete").getAsBoolean() && !normal.getAsJsonObject("coverage").has("omittedEntry"), "normal complete pages retain their contract");
		int onePageBytes = bytes(ObservationPage.collect(2, 0, 1, index -> bookEntry(index, "short"), "held_book", 8_000));
		JsonObject bounded = ObservationPage.collect(2, 0, 1, index -> bookEntry(index, "short"), "held_book", onePageBytes);
		check(bytes(bounded) == onePageBytes && bounded.getAsJsonArray("entries").size() == 1, "exact full-page byte boundary accepts the entry");
		JsonObject deferred = ObservationPage.collect(2, 0, 2, index -> bookEntry(index, "short"), "held_book", onePageBytes);
		check(deferred.getAsJsonArray("entries").size() == 1 && deferred.getAsJsonObject("coverage").get("nextOffset").getAsInt() == 1, "full page defers the next entry without skipping it");
		check(bytes(deferred) <= onePageBytes && !deferred.getAsJsonObject("coverage").has("omittedEntry"), "deferred entry is not reported as an oversized omission");
		JsonObject terminal = ObservationPage.collect(1, 0, 1, index -> oversized, "held_book", 8_000);
		check(!terminal.getAsJsonObject("coverage").get("hasMore").getAsBoolean() && !terminal.getAsJsonObject("coverage").get("complete").getAsBoolean(), "omitted terminal entry exhausts traversal without claiming full coverage");
		JsonObject beyond = ObservationPage.collect(2, Integer.MAX_VALUE, 32, index -> { throw new AssertionError("read beyond end"); }, "held_book", 8_000);
		check(beyond.getAsJsonObject("coverage").get("nextOffset").getAsInt() == 2 && !beyond.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "out-of-range offset safely reports exhaustion");
		JsonObject escaped = ObservationPage.collect(1, 0, 1, index -> bookEntry(index, "\u0001".repeat(2048)), "held_book", 8_000);
		check(escaped.getAsJsonObject("coverage").has("omittedEntry") && bytes(escaped) <= 8_000, "JSON escaping counts toward the UTF-8 envelope budget");
		JsonObject htmlEscaped = ObservationPage.collect(1, 0, 1, index -> bookEntry(index, "<".repeat(2048)), "held_book", 8_000);
		check(htmlEscaped.getAsJsonObject("coverage").has("omittedEntry") && bytes(htmlEscaped) <= 8_000, "bridge HTML escaping counts toward the byte budget");
		boolean rejected = false;
		try { ObservationPage.collect(1, 0, 1, index -> oversized, "held_book", 1); }
		catch (IllegalArgumentException expected) { rejected = true; }
		check(rejected, "impossibly small metadata budget fails explicitly instead of returning an over-budget or stuck page");
	}

	private static JsonObject bookEntry(int index, String text) {
		JsonObject entry = new JsonObject();
		entry.addProperty("page", index);
		entry.addProperty("text", text);
		entry.addProperty("truncated", false);
		return entry;
	}

	private static int bytes(JsonObject value) {
		return new GsonBuilder().serializeNulls().create().toJson(value).getBytes(StandardCharsets.UTF_8).length;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
