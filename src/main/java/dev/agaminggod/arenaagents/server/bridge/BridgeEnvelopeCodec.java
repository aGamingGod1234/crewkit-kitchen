package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Base64;
import java.util.function.Supplier;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class BridgeEnvelopeCodec {
	public static final int MAX_LINE_BYTES = 65_536;
	private static final int SMALL_FRAME_HINT = 512;
	// 256 queued specs: escaped 4096-character requests, 64 identifiers (256 chars),
	// 16 predicate leaves with 16 properties of 64+128 chars, plus current goal and metadata fit below this bound.
	static final int MAX_REGISTRY_ENTRY_BYTES = 128 * 1024 * 1024;
	static final int REGISTRY_FRAGMENT_BYTES = 24 * 1024;
	static final int MAX_REGISTRY_AGENTS = 1024;
	private static final Set<String> FRAGMENTED_TYPES = Set.of("registry_entry", "agent_registered", "goal_control", "conversation_wake");
	private static final Set<String> FIELDS = Set.of(
			"protocolVersion", "serverInstanceId", "agentId", "type", "messageId", "payload"
	);
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();
	/** Size of the last observation frame plus slack, so the next one is written without regrowing its buffer. */
	private volatile int observationFrameHint = SMALL_FRAME_HINT;

	public BridgeEnvelope decode(String line) {
		if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Protocol line exceeds " + MAX_LINE_BYTES + " bytes");
		}
		JsonElement parsed;
		try {
			parsed = decodeUniqueJson(line);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (RuntimeException | IOException exception) {
			throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line is not valid JSON", exception);
		}
		if (!parsed.isJsonObject()) {
			throw new BridgeProtocolException("INVALID_ENVELOPE", "Protocol envelope must be an object");
		}
		JsonObject object = parsed.getAsJsonObject();
		for (String field : FIELDS) {
			if (!object.has(field)) {
				throw new BridgeProtocolException("MISSING_FIELD", "Missing envelope field: " + field);
			}
		}
		for (String field : object.keySet()) {
			if (!FIELDS.contains(field)) {
				throw new BridgeProtocolException("INVALID_FIELD", "Unknown envelope field: " + field);
			}
		}
		if (!object.get("payload").isJsonObject()) {
			throw new BridgeProtocolException("INVALID_FIELD", "payload must be an object");
		}
		return BridgeEnvelope.fromDecoded(
				requiredInteger(object, "protocolVersion"),
				requiredString(object, "serverInstanceId"),
				requiredString(object, "agentId"),
				requiredString(object, "type"),
				requiredString(object, "messageId"),
				object.getAsJsonObject("payload")
		);
	}

	private static JsonElement decodeUniqueJson(String line) throws IOException {
		try (JsonReader reader = new JsonReader(new StringReader(line))) {
			reader.setLenient(false);
			JsonElement value = readElement(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line has trailing content");
			return value;
		}
	}

	private static JsonElement readElement(JsonReader reader) throws IOException {
		return switch (reader.peek()) {
			case BEGIN_OBJECT -> readObject(reader);
			case BEGIN_ARRAY -> readArray(reader);
			case STRING -> new JsonPrimitive(reader.nextString());
			case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> {
				reader.nextNull();
				yield JsonNull.INSTANCE;
			}
			default -> throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line has an invalid JSON token");
		};
	}

	private static JsonObject readObject(JsonReader reader) throws IOException {
		JsonObject object = new JsonObject();
		Set<String> names = new HashSet<>();
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			if (!names.add(name)) throw new BridgeProtocolException("DUPLICATE_FIELD", "Duplicate JSON field: " + name);
			object.add(name, readElement(reader));
		}
		reader.endObject();
		return object;
	}

	private static JsonArray readArray(JsonReader reader) throws IOException {
		JsonArray array = new JsonArray();
		reader.beginArray();
		while (reader.hasNext()) array.add(readElement(reader));
		reader.endArray();
		return array;
	}

	private static String requiredString(JsonObject object, String field) {
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON string");
		}
		return object.get(field).getAsString();
	}

	private static int requiredInteger(JsonObject object, String field) {
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON number");
		}
		try {
			return object.get(field).getAsBigDecimal().intValueExact();
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be an integer", exception);
		}
	}

	public String encode(BridgeEnvelope envelope) {
		return encodeFrame(envelope).utf8();
	}

	/** Returns the exact UTF-8 wire size, including the newline frame delimiter. */
	public int encodedBytes(BridgeEnvelope envelope) {
		return encodedFrameUnchecked(envelope).byteLength();
	}

	/** Returns the exact UTF-8 JSON line size, excluding the newline frame delimiter. */
	public int encodedLineBytes(BridgeEnvelope envelope) {
		return encodedFrameUnchecked(envelope).byteLength() - 1;
	}

	/**
	 * Returns an exact UTF-8 JSON line size for a transient payload probe without allocating an
	 * envelope, defensive payload copy, or disposable newline frame. The metadata values are owned
	 * by the caller for the duration of this call; callers that need envelope validation should use
	 * {@link #encodedLineBytes(BridgeEnvelope)} instead.
	 */
	int encodedLineBytesForPayload(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload
	) {
		Objects.requireNonNull(serverInstanceId, "serverInstanceId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(messageId, "messageId must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
		Utf8Sink sink = new Utf8Sink(false, 0);
		writeEnvelope(protocolVersion, serverInstanceId, agentId, type, messageId, payload, sink);
		return sink.finish();
	}

	/** Preflight logical publication before lifecycle commit; fragments consume one queue slot. */
	long publicationBytes(BridgeEnvelope envelope, boolean registryFragments) {
		if ("hello_ack".equals(envelope.type())) {
			JsonArray registry = envelope.payloadView().getAsJsonArray("registry");
			if (registry.size() > MAX_REGISTRY_AGENTS) throw new BridgeProtocolException("REGISTRY_CAP_EXCEEDED", "Registry exceeds configured protocol capacity");
			JsonObject header = registryHeader(envelope);
			long bytes = encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), "server", "hello_ack", envelope.messageId(), header)).byteLength();
			for (JsonElement entry : registry) {
				// Validate generated entry metadata before any snapshot frame becomes visible.
				JsonObject record = entry.getAsJsonObject();
				new BridgeEnvelope(2, envelope.serverInstanceId(), record.get("agentId").getAsString(), "registry_entry", "server-entry", new JsonObject());
				bytes += registryEntryBytes(entry).length;
			}
			bytes += Math.max(0, registry.size() - 1);
			if (!registryFragments && bytes - 1 > MAX_LINE_BYTES) throw capabilityRequired();
			if (registryFragments && bytes - 1 > MAX_LINE_BYTES) {
				header.addProperty("registryCount", registry.size());
				encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), "server", "hello_ack", envelope.messageId(), header));
			}
			return bytes;
		}
		if (FRAGMENTED_TYPES.contains(envelope.type())) {
			long bytes = registryEntryBytes(envelope.payloadView()).length
					+ encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), envelope.agentId(), envelope.type(), envelope.messageId(), new JsonObject())).byteLength() - 2L;
			if (!registryFragments && bytes - 1 > MAX_LINE_BYTES) throw capabilityRequired();
			return bytes;
		}
		return encodeFrame(envelope).byteLength();
	}

	void preflightRegistryEntry(String serverInstanceId, JsonObject payload) {
		new BridgeEnvelope(2, serverInstanceId, payload.get("agentId").getAsString(), "registry_entry", "server-entry", new JsonObject());
		registryEntryBytes(payload);
	}

	/** Keeps only a small legacy-hello candidate and one current registry entry in serialized form. */
	void writeRegistrySnapshot(BridgeEnvelope header, int count, Iterable<JsonObject> records,
			Supplier<String> nextId, OutputStream output) throws IOException {
		if (count < 0 || count > MAX_REGISTRY_AGENTS) throw new BridgeProtocolException("REGISTRY_CAP_EXCEEDED", "Registry exceeds configured protocol capacity");
		JsonArray candidate = new JsonArray();
		long bytes = encodeFrame(header).byteLength();
		var remaining = records.iterator();
		while (remaining.hasNext()) {
			JsonObject entry = remaining.next();
			bytes += registryEntryBytes(entry).length + (candidate.isEmpty() ? 0 : 1);
			candidate.add(entry);
			if (bytes - 1 > MAX_LINE_BYTES) break;
		}
		if (bytes - 1 <= MAX_LINE_BYTES) {
			JsonObject small = header.payload(); small.add("registry", candidate);
			output.write(encodeFrame(new BridgeEnvelope(2, header.serverInstanceId(), "server", "hello_ack", header.messageId(), small)).bytesView());
			return;
		}
		JsonObject begin = header.payload(); begin.addProperty("registryCount", count);
		output.write(encodeFrame(new BridgeEnvelope(2, header.serverInstanceId(), "server", "hello_ack", header.messageId(), begin)).bytesView());
		for (JsonElement entry : candidate) writeRegistryEntry(header.serverInstanceId(), entry.getAsJsonObject(), nextId, output);
		candidate = null;
		while (remaining.hasNext()) writeRegistryEntry(header.serverInstanceId(), remaining.next(), nextId, output);
		JsonObject complete = new JsonObject(); complete.addProperty("replyTo", header.messageId()); complete.addProperty("count", count);
		output.write(encodeFrame(new BridgeEnvelope(2, header.serverInstanceId(), "server", "registry_complete", nextId.get(), complete)).bytesView());
	}

	private void writeRegistryEntry(String serverInstanceId, JsonObject payload, Supplier<String> nextId, OutputStream output) throws IOException {
		writePublication(new BridgeEnvelope(2, serverInstanceId, payload.get("agentId").getAsString(), "registry_entry", nextId.get(), payload), true, nextId, output);
	}

	private static BridgeProtocolException capabilityRequired() {
		return new BridgeProtocolException("REGISTRY_FRAGMENT_CAPABILITY_REQUIRED", "Coordinator must negotiate registryFragments to receive this registry or goal queue");
	}

	private static JsonObject registryHeader(BridgeEnvelope envelope) {
		JsonObject header = new JsonObject();
		for (var entry : envelope.payloadView().entrySet()) {
			if (!"registry".equals(entry.getKey())) header.add(entry.getKey(), entry.getValue());
		}
		header.add("registry", new JsonArray());
		return header;
	}

	private static byte[] registryEntryBytes(JsonElement payload) {
		byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_REGISTRY_ENTRY_BYTES) throw new BridgeProtocolException("REGISTRY_ENTRY_TOO_LARGE", "Registry entry exceeds the schema-derived byte bound");
		return bytes;
	}

	/** Stream one logical message completely before the writer takes the next publication. */
	void writePublication(BridgeEnvelope envelope, boolean registryFragments, Supplier<String> nextId, OutputStream output) throws IOException {
		long publicationBytes = publicationBytes(envelope, registryFragments);
		if ("hello_ack".equals(envelope.type()) && registryFragments && publicationBytes - 1 > MAX_LINE_BYTES) {
			JsonObject begin = registryHeader(envelope);
			JsonArray registry = envelope.payloadView().getAsJsonArray("registry");
			begin.addProperty("registryCount", registry.size());
			output.write(encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), "server", "hello_ack", envelope.messageId(), begin)).bytesView());
			for (JsonElement entry : registry) {
				JsonObject payload = entry.getAsJsonObject();
				BridgeEnvelope record = new BridgeEnvelope(2, envelope.serverInstanceId(), payload.get("agentId").getAsString(), "registry_entry", nextId.get(), payload);
				writePublication(record, true, nextId, output);
			}
			JsonObject complete = new JsonObject();
			complete.addProperty("replyTo", envelope.messageId());
			complete.addProperty("count", registry.size());
			output.write(encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), "server", "registry_complete", nextId.get(), complete)).bytesView());
			return;
		}
		if (publicationBytes - 1 <= MAX_LINE_BYTES) {
			output.write(encodeFrame(envelope).bytesView());
			return;
		}
		byte[] bytes = registryEntryBytes(envelope.payloadView());
		for (int offset = 0, index = 0; offset < bytes.length; offset += REGISTRY_FRAGMENT_BYTES, index++) {
			JsonObject part = new JsonObject();
			part.addProperty("messageId", envelope.messageId());
			part.addProperty("type", envelope.type());
			part.addProperty("index", index);
			part.addProperty("totalBytes", bytes.length);
			part.addProperty("data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + REGISTRY_FRAGMENT_BYTES))));
			output.write(encodeFrame(new BridgeEnvelope(2, envelope.serverInstanceId(), envelope.agentId(), "registry_fragment", nextId.get(), part)).bytesView());
		}
	}

	public EncodedFrame encodeFrame(BridgeEnvelope envelope) {
		EncodedFrame encoded = encodedFrameUnchecked(envelope);
		if (encoded.byteLength() - 1 > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Encoded protocol line exceeds " + MAX_LINE_BYTES + " UTF-8 bytes");
		}
		return encoded;
	}

	private EncodedFrame encodedFrameUnchecked(BridgeEnvelope envelope) {
		BridgeEnvelope checked = java.util.Objects.requireNonNull(envelope, "envelope must not be null");
		EncodedFrame cached = checked.encodedFrame();
		if (cached != null) return cached;
		// Written straight to UTF-8 with the newline delimiter; large observations skip the String and its two byte copies.
		boolean observation = "observation".equals(checked.type());
		Utf8Sink sink = new Utf8Sink(true, observation ? observationFrameHint : SMALL_FRAME_HINT);
		writeEnvelope(checked.protocolVersion(), checked.serverInstanceId(), checked.agentId(), checked.type(),
				checked.messageId(), checked.payloadView(), sink);
		sink.write('\n');
		byte[] wireBytes = sink.toByteArray();
		if (observation) observationFrameHint = wireBytes.length + (wireBytes.length >> 3);
		EncodedFrame encoded = new EncodedFrame(wireBytes);
		checked.cacheEncodedFrame(encoded);
		return checked.encodedFrame();
	}

	private static void writeEnvelope(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload,
			Writer output
	) {
		JsonObject object = new JsonObject();
		object.addProperty("protocolVersion", protocolVersion);
		object.addProperty("serverInstanceId", serverInstanceId);
		object.addProperty("agentId", agentId);
		object.addProperty("type", type);
		object.addProperty("messageId", messageId);
		object.add("payload", payload);
		GSON.toJson(object, output);
	}

	/**
	 * Gson output as UTF-8, byte for byte what {@code String.getBytes(UTF_8)} returns (a lone surrogate becomes '?'),
	 * either only counted or also kept. Counting needs no buffer at all.
	 */
	private static final class Utf8Sink extends Writer {
		private final boolean keep;
		private byte[] bytes;
		private int length;
		private char pendingHigh;

		Utf8Sink(boolean keep, int capacityHint) {
			this.keep = keep;
			this.bytes = keep ? new byte[Math.max(256, capacityHint)] : null;
		}

		@Override public void write(int value) { put((char) value); }

		@Override public void write(char[] buffer, int offset, int count) {
			for (int index = offset, end = offset + count; index < end; index++) put(buffer[index]);
		}

		@Override public void write(String text, int offset, int count) {
			for (int index = offset, end = offset + count; index < end; index++) put(text.charAt(index));
		}

		@Override public void flush() { }

		@Override public void close() { }

		/** Completes a trailing lone high surrogate and returns the byte length. */
		int finish() {
			if (pendingHigh != 0) {
				pendingHigh = 0;
				emit('?');
			}
			return length;
		}

		byte[] toByteArray() {
			finish();
			return Arrays.copyOf(bytes, length);
		}

		private void put(char value) {
			if (value < 0x80 && pendingHigh == 0) {
				emit(value);
				return;
			}
			if (pendingHigh != 0) {
				char high = pendingHigh;
				pendingHigh = 0;
				if (Character.isLowSurrogate(value)) {
					int codePoint = Character.toCodePoint(high, value);
					emit(0xF0 | (codePoint >> 18));
					emit(0x80 | ((codePoint >> 12) & 0x3F));
					emit(0x80 | ((codePoint >> 6) & 0x3F));
					emit(0x80 | (codePoint & 0x3F));
					return;
				}
				emit('?');
			}
			if (value < 0x80) {
				emit(value);
			} else if (value < 0x800) {
				emit(0xC0 | (value >> 6));
				emit(0x80 | (value & 0x3F));
			} else if (Character.isHighSurrogate(value)) {
				pendingHigh = value;
			} else if (Character.isLowSurrogate(value)) {
				emit('?');
			} else {
				emit(0xE0 | (value >> 12));
				emit(0x80 | ((value >> 6) & 0x3F));
				emit(0x80 | (value & 0x3F));
			}
		}

		private void emit(int value) {
			if (keep) {
				if (length == bytes.length) bytes = Arrays.copyOf(bytes, bytes.length * 2);
				bytes[length] = (byte) value;
			}
			length++;
		}
	}

	public static final class EncodedFrame {
		private final byte[] bytes;
		private volatile String utf8;

		private EncodedFrame(byte[] bytes) {
			this.bytes = bytes;
		}

		public int byteLength() { return bytes.length; }
		public byte[] bytes() { return bytes.clone(); }
		byte[] bytesView() { return bytes; }
		public String utf8() {
			String value = utf8;
			if (value != null) return value;
			value = new String(bytes, StandardCharsets.UTF_8);
			utf8 = value;
			return value;
		}
	}
}
