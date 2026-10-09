package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

final class NodeVoiceWorkerIntegrationVerification {
	private static final String SECRET = "cross-runtime-voice-verification-secret";
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000001");
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");

	private NodeVoiceWorkerIntegrationVerification() {
	}

	static int verify() throws Exception {
		int cleanupAssertions = verifyReadinessCleanup();
		ProcessBuilder builder = new ProcessBuilder(
				"node", "coordinator/fixtures/voice-worker-cross-runtime-fixture.mjs"
		);
		builder.redirectErrorStream(true);
		builder.environment().put("VOICE_FIXTURE_SECRET", SECRET);
		Process process = builder.start();
		return cleanupAssertions + verify(process, Duration.ofSeconds(10));
	}

	private static int verify(Process process, Duration readinessTimeout) throws Exception {
		// The process must exit before reader/executor cleanup can wait on its stdout.
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			try (BufferedReader output = new BufferedReader(new InputStreamReader(
					process.getInputStream(), StandardCharsets.UTF_8
			))) {
				Future<String> readyLine = executor.submit(output::readLine);
				try {
					String ready = readyLine.get(readinessTimeout.toMillis(), TimeUnit.MILLISECONDS);
					if (ready == null || !ready.startsWith("READY ")) {
						throw new AssertionError("Node voice fixture did not start: " + ready);
					}
					int port = Integer.parseInt(ready.substring("READY ".length()));
					HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
					VoiceWorkerClient tts = new VoiceWorkerClient(
							client, URI.create("http://127.0.0.1:" + port + "/v1/tts"), SECRET
					);
					short[] samples = tts.synthesize(new VoiceRequest(
							AGENT, "Cross-runtime voice test.", "voice.auto.v1", 48, 21L
					)).join();
					assertEquals(480, samples.length, "Node resampling reaches 48 kHz");
					assertEquals(true, samples[100] != 0, "Node PCM survives Java decoding");

					SpeechWorkerClient stt = new SpeechWorkerClient(
							client, URI.create("http://127.0.0.1:" + port + "/v1/stt"), SECRET
					);
					SpeechWorkerClient.Transcript transcript = stt.transcribe(
							PLAYER, 22L, true, new short[] { -1, 0, 1 }
					).join();
					assertEquals("fixture heard 6 bytes", transcript.text(), "Java PCM reaches Node STT route");
					assertEquals(0.88D, transcript.confidence(), "Node confidence reaches Java client");

					process.getOutputStream().write("close\n".getBytes(StandardCharsets.UTF_8));
					process.getOutputStream().flush();
					assertEquals(true, process.waitFor(10, TimeUnit.SECONDS), "Node voice fixture exits cleanly");
					assertEquals(0, process.exitValue(), "Node voice fixture exit code");
					return 6;
				} finally {
					try {
						terminate(process);
					} finally {
						readyLine.cancel(true);
					}
				}
			}
		}
	}

	private static void terminate(Process process) throws InterruptedException {
		if (process.isAlive()) {
			process.destroy();
			if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
		}
	}

	private static int verifyReadinessCleanup() throws Exception {
		for (String mode : new String[] { "early-exit", "invalid-line", "silent" }) {
			Process child = new ProcessBuilder(
					java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-cp", System.getProperty("java.class.path"), ReadinessFixture.class.getName(), mode
			).redirectErrorStream(true).start();
			CompletableFuture<Throwable> outcome = new CompletableFuture<>();
			Thread verifier = Thread.ofVirtual().start(() -> {
				try {
					// Only "silent" must time out; the others need room for a cold child JVM under a parallel build.
					verify(child, Duration.ofSeconds(mode.equals("silent") ? 1 : 10));
					outcome.complete(null);
				} catch (Throwable failure) {
					outcome.complete(failure);
				}
			});
			try {
				Throwable failure = outcome.get(15, TimeUnit.SECONDS);
				assertEquals(true, mode.equals("silent") ? failure instanceof TimeoutException
						: failure instanceof AssertionError, mode + " preserves readiness failure");
				assertEquals(false, child.isAlive(), mode + " terminates its owned fixture");
			} finally {
				// Only this directly created process is eligible for cleanup, including on regression.
				terminate(child);
				verifier.join(3_000L);
				assertEquals(false, verifier.isAlive(), "readiness verifier cleanup finishes");
			}
		}
		return 9;
	}

	public static final class ReadinessFixture {
		public static void main(String[] args) throws Exception {
			if (args[0].equals("early-exit")) return;
			if (args[0].equals("invalid-line")) System.out.println("NOT_READY");
			Thread.sleep(10_000L);
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
