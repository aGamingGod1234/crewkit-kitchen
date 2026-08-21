package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class VoiceWorkerClientsVerification {
	private static final String SECRET = "voice-addon-verification-secret";
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000001");
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");

	private VoiceWorkerClientsVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyTtsClientSendsContractAndDecodesPcm();
		assertions += verifyTtsClientRejectsMalformedAudioAndHttpFailure();
		assertions += verifySttClientSendsPcmMetadataAndBoundsTranscript();
		assertions += verifySttClientRejectsMalformedInputAndResponse();
		assertions += verifyClientsRejectWrongResponseMediaTypes();
		return assertions;
	}

	private static int verifyTtsClientSendsContractAndDecodesPcm() throws Exception {
		try (WorkerServer server = new WorkerServer(exchange -> {
			assertEquals("Bearer " + SECRET, exchange.getRequestHeaders().getFirst("Authorization"), "TTS authorization");
			assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"), "TTS content type");
			JsonObject payload = JsonParser.parseString(new String(
					exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8
			)).getAsJsonObject();
			assertEquals(AGENT.toString(), payload.get("agentId").getAsString(), "TTS agent id");
			assertEquals("Testing voice.", payload.get("text").getAsString(), "TTS text");
			assertEquals("voice.auto.v1", payload.get("profileId").getAsString(), "TTS profile id");
			assertEquals(48, payload.get("radius").getAsInt(), "TTS radius");
			assertEquals(12L, payload.get("conversationSequence").getAsLong(), "TTS sequence");
			respondPcm(exchange, pcm(-32_768, -1, 0, 1, 32_767));
		})) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), server.uri("/v1/tts"), SECRET);
			short[] actual = client.synthesize(new VoiceRequest(
					AGENT, "Testing voice.", "voice.auto.v1", 48, 12L
			)).join();
			server.assertHealthy();
			assertEquals(true, Arrays.equals(new short[] { -32_768, -1, 0, 1, 32_767 }, actual), "TTS PCM decode");
		}
		return 8;
	}

	private static int verifyTtsClientRejectsMalformedAudioAndHttpFailure() throws Exception {
		try (WorkerServer malformed = new WorkerServer(exchange -> respondPcm(exchange, new byte[] { 1, 2, 3 }))) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), malformed.uri("/v1/tts"), SECRET);
			assertWorkerFailure("VOICE_WORKER_AUDIO", () -> client.synthesize(request()).join(), "odd PCM response");
			malformed.assertHealthy();
		}
		try (WorkerServer unavailable = new WorkerServer(exchange -> respond(
				exchange, 503, "offline".getBytes(StandardCharsets.UTF_8), "text/plain"
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), unavailable.uri("/v1/tts"), SECRET);
			assertWorkerFailure("VOICE_WORKER_HTTP", () -> client.synthesize(request()).join(), "TTS HTTP failure");
			unavailable.assertHealthy();
		}
		return 2;
	}

	private static int verifyClientsRejectWrongResponseMediaTypes() throws Exception {
		try (WorkerServer wrongTtsType = new WorkerServer(exchange -> respond(
				exchange, 200, pcm(1, 2), "text/plain"
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(
					HttpClient.newHttpClient(), wrongTtsType.uri("/v1/tts"), SECRET
			);
			assertWorkerFailure("VOICE_WORKER_AUDIO", () -> client.synthesize(request()).join(),
					"TTS response media type");
			wrongTtsType.assertHealthy();
		}
		try (WorkerServer wrongSttType = new WorkerServer(exchange -> respond(
				exchange, 200, "{\"transcript\":\"hello\",\"confidence\":0.8}".getBytes(StandardCharsets.UTF_8),
				"text/plain"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), wrongSttType.uri("/v1/stt"), SECRET
			);
			assertWorkerFailure("STT_WORKER_RESPONSE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "STT response media type");
			wrongSttType.assertHealthy();
		}
		return 2;
	}

	private static int verifySttClientSendsPcmMetadataAndBoundsTranscript() throws Exception {
		String transcript = "a".repeat(513);
		try (WorkerServer server = new WorkerServer(exchange -> {
			assertEquals("Bearer " + SECRET, exchange.getRequestHeaders().getFirst("Authorization"), "STT authorization");
			assertEquals("audio/l16;rate=48000;channels=1", exchange.getRequestHeaders().getFirst("Content-Type"),
					"STT content type");
			assertEquals(PLAYER.toString(), exchange.getRequestHeaders().getFirst("X-Player-Id"), "STT player id");
			assertEquals("17", exchange.getRequestHeaders().getFirst("X-Utterance-Sequence"), "STT sequence");
			assertEquals("true", exchange.getRequestHeaders().getFirst("X-Whispering"), "STT whisper state");
			assertEquals(true, Arrays.equals(pcm(-32_768, 0, 32_767), exchange.getRequestBody().readAllBytes()),
					"STT PCM encoding");
			respond(exchange, 200, ("{\"transcript\":\"" + transcript + "\",\"confidence\":0.75}")
					.getBytes(StandardCharsets.UTF_8), "application/json");
		})) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), server.uri("/v1/stt"), SECRET);
			SpeechWorkerClient.Transcript actual = client.transcribe(
					PLAYER, 17L, true, new short[] { -32_768, 0, 32_767 }
			).join();
			server.assertHealthy();
			assertEquals(512, actual.text().codePointCount(0, actual.text().length()), "STT transcript bound");
			assertEquals(0.75D, actual.confidence(), "STT confidence");
		}
		return 8;
	}

	private static int verifySttClientRejectsMalformedInputAndResponse() throws Exception {
		try (WorkerServer unused = new WorkerServer(exchange -> respond(
				exchange, 500, new byte[0], "application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), unused.uri("/v1/stt"), SECRET);
			assertWorkerFailure("STT_WORKER_AUDIO", () -> client.transcribe(PLAYER, 1L, false, new short[0]).join(),
					"empty STT samples");
		}
		try (WorkerServer malformed = new WorkerServer(exchange -> respond(
				exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8), "application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), malformed.uri("/v1/stt"), SECRET);
			assertWorkerFailure("STT_WORKER_RESPONSE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "malformed STT response");
			malformed.assertHealthy();
		}
		return 2;
	}

	private static VoiceRequest request() {
		return new VoiceRequest(AGENT, "Testing voice.", "voice.auto.v1", 48, 1L);
	}

	private static byte[] pcm(int... samples) {
		ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int sample : samples) buffer.putShort((short) sample);
		return buffer.array();
	}

	private static void respond(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void respondPcm(HttpExchange exchange, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("X-Audio-Sample-Rate", "48000");
		exchange.getResponseHeaders().set("X-Audio-Channels", "1");
		respond(exchange, 200, body, "audio/L16");
	}

	private static void assertWorkerFailure(String expectedCode, Runnable action, String message) {
		try {
			action.run();
			throw new AssertionError(message + ": expected failure " + expectedCode);
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (!(cause instanceof VoiceWorkerClient.VoiceWorkerException workerFailure)) {
				throw new AssertionError(message + ": unexpected failure " + cause, cause);
			}
			assertEquals(expectedCode, workerFailure.code(), message);
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws Exception;
	}

	private static final class WorkerServer implements AutoCloseable {
		private final HttpServer server;
		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private WorkerServer(Handler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> {
				try {
					handler.handle(exchange);
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
					if (exchange.getResponseCode() < 0) respond(
							exchange, 500, "fixture failure".getBytes(StandardCharsets.UTF_8), "text/plain"
					);
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
		}

		private URI uri(String path) {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
		}

		private void assertHealthy() {
			Throwable throwable = failure.get();
			if (throwable != null) throw new AssertionError("worker fixture failed", throwable);
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
