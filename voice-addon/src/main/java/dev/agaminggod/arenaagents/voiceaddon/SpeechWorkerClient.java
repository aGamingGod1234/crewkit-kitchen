package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class SpeechWorkerClient {
	private static final int MAX_SAMPLES = 48_000 * 20;
	private final HttpClient client;
	private final URI endpoint;
	private final String secret;
	private final Duration requestTimeout;

	SpeechWorkerClient(VoiceSubsystemConfiguration configuration) {
		this(
				defaultClient(),
				speechEndpoint(configuration.endpoint()),
				configuration.secret(),
				Duration.ofMillis(configuration.requestTimeoutMs())
		);
	}

	SpeechWorkerClient(HttpClient client, URI endpoint, String secret) {
		this(client, endpoint, secret, Duration.ofMillis(VoiceSubsystemConfiguration.DEFAULT_REQUEST_TIMEOUT_MS));
	}

	SpeechWorkerClient(HttpClient client, URI endpoint, String secret, Duration requestTimeout) {
		this.client = java.util.Objects.requireNonNull(client, "client must not be null");
		this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint must not be null");
		if (secret == null || secret.length() < 16) throw new IllegalArgumentException("secret is too short");
		this.secret = secret;
		this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
	}

	CompletableFuture<Transcript> transcribe(
			UUID playerId,
			long utteranceSequence,
			boolean whispering,
			short[] samples
	) {
		if (playerId == null || utteranceSequence < 1L || samples == null
				|| samples.length == 0 || samples.length > MAX_SAMPLES) {
			return CompletableFuture.failedFuture(new VoiceWorkerClient.VoiceWorkerException(
					"STT_WORKER_AUDIO", "Speech input must be at most 20 seconds of 48 kHz mono PCM"
			));
		}
		ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		pcm.asShortBuffer().put(samples);
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(requestTimeout)
				.header("Authorization", "Bearer " + secret)
				.header("Content-Type", "audio/l16;rate=48000;channels=1")
				.header("X-Player-Id", playerId.toString())
				.header("X-Utterance-Sequence", Long.toString(utteranceSequence))
				.header("X-Whispering", Boolean.toString(whispering))
				.POST(HttpRequest.BodyPublishers.ofByteArray(pcm.array()))
				.build();
		CompletableFuture<HttpResponse<String>> exchange = client.sendAsync(
				request, HttpResponse.BodyHandlers.ofString()
		);
		CompletableFuture<Transcript> result = exchange.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw workerHttpFailure(response);
					}
					try {
						String contentType = response.headers().firstValue("Content-Type").orElse("")
								.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
						if (!contentType.equals("application/json")) {
							throw new VoiceWorkerClient.VoiceWorkerException(
									"STT_WORKER_RESPONSE", "Speech worker returned a non-JSON transcript"
							);
						}
						JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
						if (!payload.has("transcript") || !payload.get("transcript").isJsonPrimitive()
								|| !payload.has("confidence") || !payload.get("confidence").isJsonPrimitive()) {
							throw new IllegalArgumentException("missing transcript fields");
						}
						String transcript = payload.get("transcript").getAsString().strip();
						if (transcript.codePointCount(0, transcript.length()) > 512) {
							transcript = transcript.substring(0, transcript.offsetByCodePoints(0, 512));
						}
						double confidence = payload.get("confidence").getAsDouble();
						if (!Double.isFinite(confidence)) throw new IllegalArgumentException("confidence is not finite");
						return new Transcript(transcript, Math.max(0.0D, Math.min(1.0D, confidence)));
					} catch (RuntimeException exception) {
						if (exception instanceof VoiceWorkerClient.VoiceWorkerException workerException) {
							throw workerException;
						}
						throw new VoiceWorkerClient.VoiceWorkerException(
								"STT_WORKER_RESPONSE", "Speech worker returned an invalid transcript", exception
						);
					}
				});
		result.whenComplete((transcript, failure) -> {
			if (result.isCancelled()) exchange.cancel(true);
		});
		return result;
	}

	private static VoiceWorkerClient.VoiceWorkerException workerHttpFailure(HttpResponse<String> response) {
		String code = "STT_WORKER_HTTP";
		String contentType = response.headers().firstValue("Content-Type").orElse("")
				.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
		if (contentType.equals("application/json")) {
			try {
				JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
				if (payload.has("code") && payload.get("code").isJsonPrimitive()
						&& payload.get("code").getAsString().equals("STT_UNAVAILABLE")) {
					code = "STT_UNAVAILABLE";
				}
			} catch (RuntimeException ignored) {
				// Invalid error bodies remain a generic bounded HTTP failure.
			}
		}
		return new VoiceWorkerClient.VoiceWorkerException(
				code, "Speech worker returned HTTP " + response.statusCode()
		);
	}

	private static HttpClient defaultClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	private static URI speechEndpoint(String voiceEndpoint) {
		return URI.create(voiceEndpoint.replace("/v1/tts", "/v1/stt"));
	}

	record Transcript(String text, double confidence) {
	}
}
