package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class VoiceWorkerClient {
	private static final int MAX_SAMPLES = 48_000 * 20;
	private final HttpClient client;
	private final URI endpoint;
	private final String secret;

	VoiceWorkerClient() {
		this(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
				URI.create(System.getProperty("arenaagents.voiceUrl", "http://127.0.0.1:8766/v1/tts")),
				readSecret()
		);
	}

	VoiceWorkerClient(HttpClient client, URI endpoint, String secret) {
		this.client = client;
		this.endpoint = endpoint;
		this.secret = secret;
	}

	CompletableFuture<short[]> synthesize(VoiceRequest request) {
		JsonObject payload = new JsonObject();
		payload.addProperty("agentId", request.agentId().toString());
		payload.addProperty("text", request.text());
		payload.addProperty("profileId", request.profileId());
		payload.addProperty("radius", request.radius());
		payload.addProperty("conversationSequence", request.conversationSequence());
		HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(35))
				.header("Authorization", "Bearer " + secret)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
				.build();
		return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new VoiceWorkerException("VOICE_WORKER_HTTP", "Voice worker returned HTTP " + response.statusCode());
					}
					String contentType = response.headers().firstValue("Content-Type").orElse("")
							.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
					String sampleRate = response.headers().firstValue("X-Audio-Sample-Rate").orElse("");
					String channels = response.headers().firstValue("X-Audio-Channels").orElse("");
					if (!contentType.equals("audio/l16") || !sampleRate.equals("48000") || !channels.equals("1")) {
						throw new VoiceWorkerException(
								"VOICE_WORKER_AUDIO", "Voice worker returned invalid 48 kHz mono PCM metadata"
						);
					}
					byte[] bytes = response.body();
					if (bytes.length == 0 || bytes.length % 2 != 0 || bytes.length > MAX_SAMPLES * 2) {
						throw new VoiceWorkerException("VOICE_WORKER_AUDIO", "Voice worker returned invalid 48 kHz mono PCM");
					}
					ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
					short[] samples = new short[bytes.length / 2];
					buffer.asShortBuffer().get(samples);
					return samples;
				});
	}

	static String readSecret() {
		String configured = System.getProperty(
				"arenaagents.voiceSecretFile",
				System.getProperty("arenaagents.bridgeSecretFile", "runtime/bridge-secret.txt")
		);
		try {
			String value = Files.readString(Path.of(configured), StandardCharsets.UTF_8).trim();
			if (value.length() < 16) throw new IOException("secret is too short");
			return value;
		} catch (IOException exception) {
			throw new VoiceWorkerException("VOICE_SECRET_UNAVAILABLE", "Voice worker secret is unavailable", exception);
		}
	}

	static final class VoiceWorkerException extends RuntimeException {
		private final String code;

		VoiceWorkerException(String code, String message) {
			super(message);
			this.code = code;
		}

		VoiceWorkerException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		String code() {
			return code;
		}
	}
}
