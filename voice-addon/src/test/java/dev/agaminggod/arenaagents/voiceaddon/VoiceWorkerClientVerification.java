package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Both clients must own rejected response streams without reading their bodies. */
final class VoiceWorkerClientVerification {
	static final String SECRET = "response-cleanup-fixture-secret";
	static final Duration DEADLINE = Duration.ofMillis(25);
	static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000103");
	static int cases;

	static int verify() throws Exception {
		cases = 0;
		rejected("stt-declared-9000-immediate", false, 200, "9000", true);
		rejected("stt-declared-9000-pending-exchange", false, 200, "9000", false);
		rejected("tts-success-declared-1920001", true, 200, "1920001", false);
		rejected("tts-error-declared-9000", true, 503, "9000", false);
		rejected("stt-negative-length", false, 200, "-1", false);
		rejected("stt-invalid-length", false, 200, "invalid", false);
		for (int i = 0; i < 3; i++) rejected("stt-repeat-" + i, false, 200, "9000", false);
		blockedClose(false);
		blockedClose(true);
		byte[] transcript = new byte[8192];
		Arrays.fill(transcript, (byte) ' ');
		byte[] json = "{\"transcript\":\"ok\",\"confidence\":0.5}".getBytes(StandardCharsets.UTF_8);
		System.arraycopy(json, 0, transcript, 0, json.length);
		control("stt-exact-limit-signed", false, "8192", transcript, false, true, null);
		control("tts-exact-limit-signed", true, "1920000", new byte[1920000], false, true, null);
		control("stt-no-length-streamed-overflow", false, null, new byte[9000], false, false, "VOICE_WORKER_RESPONSE");
		control("stt-bad-signature", false, "2", new byte[] {'{', '}'}, false, false, "VOICE_WORKER_AUTHENTICATION");
		control("tts-stalled-stream", true, null, new byte[0], true, false, "VOICE_WORKER_TIMEOUT");
		return cases;
	}

	static CompletableFuture<?> invoke(FakeClient http, boolean tts) {
		URI endpoint = URI.create("http://127.0.0.1:1/v1/" + (tts ? "tts" : "stt"));
		Duration deadline = http.body.stalled ? DEADLINE : Duration.ofSeconds(1);
		return tts
				? new VoiceWorkerClient(http, endpoint, SECRET, deadline).synthesize(
					new VoiceRequest(new AgentId(ID), "Probe", "voice.auto.v1", 48, 1))
				: new SpeechWorkerClient(http, endpoint, SECRET, deadline).transcribe(ID, 1, false, new short[]{1});
	}

	static void rejected(String name, boolean tts, int status, String length, boolean immediate) throws Exception {
		// This represents an open response; no bytes need arrive for a header-only rejection.
		Tracker body = new Tracker(new byte[0], true);
		FakeClient http = new FakeClient(body, status, length, tts, false, immediate);
		try {
			CompletableFuture<?> result = invoke(http, tts);
			if (!immediate) http.deliver();
			assertFailure(result, "VOICE_WORKER_RESPONSE");
			check(!result.isCancelled(), name + " should complete exceptionally, not be cancelled");
			check(!result.cancel(true), name + " already-completed result cannot be cancelled later");
			check(body.closed.await(1, TimeUnit.SECONDS), name + " rejected body closes");
			check(body.reads.get() == 0 && body.closes.get() == 1, name + " closes once without reading");
			check(http.bodyGets.get() == 1 && http.exchange.cancels.get() > 0, name + " body acquired and exchange aborted");
			cases++;
		} finally { body.close(); }
	}

	static void blockedClose(boolean tts) throws Exception {
		Tracker body = new Tracker(new byte[0], true);
		body.closeGate = new CountDownLatch(1);
		FakeClient http = new FakeClient(body, 503, "9000", tts, false, false);
		CompletableFuture<Void> delivery = null;
		try {
			CompletableFuture<?> result = invoke(http, tts);
			delivery = CompletableFuture.runAsync(http::deliver);
			check(body.closeEntered.await(1, TimeUnit.SECONDS), "rejection begins stream close");
			assertFailure(result, "VOICE_WORKER_RESPONSE");
			check(body.closed.getCount() == 1, "failure settles while stream close is blocked");
			check(body.reads.get() == 0, "rejection never reads the blocked stream");
			cases++;
		} finally {
			body.closeGate.countDown();
			if (delivery != null) delivery.get(2, TimeUnit.SECONDS);
			check(body.closed.await(2, TimeUnit.SECONDS), "owned close worker finishes");
		}
	}

	static void control(String name, boolean tts, String length, byte[] data, boolean stalled,
						boolean signed, String expectedError) throws Exception {
		Tracker body = new Tracker(data, stalled);
		FakeClient http = new FakeClient(body, 200, length, tts, signed, false);
		try {
			CompletableFuture<?> result = invoke(http, tts);
			http.deliver();
			if (expectedError == null) {
				Object value = result.get(2, TimeUnit.SECONDS);
				if (tts) check(((short[]) value).length == 960000, "exact TTS bound decoded");
				else check(((SpeechWorkerClient.Transcript) value).text().equals("ok"), "exact STT bound decoded");
			} else assertFailure(result, expectedError);
			check(body.closed.await(1, TimeUnit.SECONDS), name + " body closes");
			check(body.reads.get() > 0 && http.bodyGets.get() == 1, name + " production reader exercised");
			if (stalled || "VOICE_WORKER_RESPONSE".equals(expectedError)) {
				check(http.exchange.cancels.get() > 0, name + " abort callback invoked");
			}
			System.out.println(name + ": outcome=" + (expectedError == null ? "success" : expectedError)
					+ " bodyGets=" + http.bodyGets + " reads=" + body.reads + " closes=" + body.closes
					+ " abortCalls=" + http.exchange.cancels);
			cases++;
		} finally { body.close(); }
	}

	static void assertFailure(CompletableFuture<?> result, String code) throws Exception {
		try { result.get(2, TimeUnit.SECONDS); throw new AssertionError("expected " + code); }
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
			check(cause instanceof VoiceWorkerClient.VoiceWorkerException, "typed exception: " + cause);
			check(((VoiceWorkerClient.VoiceWorkerException) cause).code().equals(code), "error code: " + cause);
		}
	}
	static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

	static final class Tracker extends InputStream {
		final byte[] data;
		final ByteArrayInputStream bytes;
		final boolean stalled;
		final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
		final CountDownLatch closed = new CountDownLatch(1);
		final CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch closeGate;
		Tracker(byte[] data, boolean stalled) { this.data = data; this.bytes = new ByteArrayInputStream(data); this.stalled = stalled; }
		@Override public int read(byte[] buffer, int offset, int count) throws IOException {
			reads.incrementAndGet();
			if (stalled) {
				try { if (!closed.await(2, TimeUnit.SECONDS)) throw new IOException("fixture wait limit"); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
				return -1;
			}
			return bytes.read(buffer, offset, count);
		}
		@Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
		@Override public void close() {
			closes.incrementAndGet();
			closeEntered.countDown();
			if (closeGate != null) {
				try { check(closeGate.await(4, TimeUnit.SECONDS), "fixture close gate released"); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
			}
			closed.countDown();
		}
	}

	static final class CancelFuture extends CompletableFuture<HttpResponse<InputStream>> {
		final AtomicInteger cancels = new AtomicInteger();
		@Override public boolean cancel(boolean interrupt) { cancels.incrementAndGet(); return super.cancel(interrupt); }
	}

	// Standard HttpClient fake shape adapted from the repository's ImmediateResponseHttpClient.
	static final class FakeClient extends HttpClient {
		final Tracker body;
		final int status;
		final String length;
		final boolean tts, signed, immediate;
		final CancelFuture exchange = new CancelFuture();
		final AtomicInteger bodyGets = new AtomicInteger();
		HttpRequest request;
		FakeClient(Tracker body, int status, String length, boolean tts, boolean signed, boolean immediate) {
			this.body = body; this.status = status; this.length = length; this.tts = tts; this.signed = signed; this.immediate = immediate;
		}
		void deliver() {
			String type = tts ? "audio/l16" : "application/json";
			Map<String,List<String>> headers = new HashMap<>();
			headers.put("Content-Type", List.of(type));
			if (length != null) headers.put("Content-Length", List.of(length));
			if (tts) { headers.put("X-Audio-Sample-Rate", List.of("48000")); headers.put("X-Audio-Channels", List.of("1")); }
			if (signed) headers.put("X-Voice-Response-Signature", List.of(VoiceHttpAuthentication.responseSignature(
					SECRET, request.headers().firstValue("X-Voice-Nonce").orElseThrow(), status, type, body.data)));
			HttpHeaders responseHeaders = HttpHeaders.of(headers, (name, value) -> true);
			exchange.complete(new HttpResponse<InputStream>() {
				public int statusCode() { return status; }
				public HttpRequest request() { return request; }
				public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
				public HttpHeaders headers() { return responseHeaders; }
				public InputStream body() { bodyGets.incrementAndGet(); return body; }
				public Optional<SSLSession> sslSession() { return Optional.empty(); }
				public URI uri() { return request.uri(); }
				public Version version() { return Version.HTTP_1_1; }
			});
		}
		public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
		public Optional<Duration> connectTimeout() { return Optional.empty(); }
		public Redirect followRedirects() { return Redirect.NEVER; }
		public Optional<ProxySelector> proxy() { return Optional.empty(); }
		public SSLContext sslContext() { return null; }
		public SSLParameters sslParameters() { return new SSLParameters(); }
		public Optional<Authenticator> authenticator() { return Optional.empty(); }
		public Version version() { return Version.HTTP_1_1; }
		public Optional<Executor> executor() { return Optional.empty(); }
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
		@SuppressWarnings("unchecked")
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
			this.request = request;
			if (immediate) deliver();
			return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) exchange;
		}
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
				HttpResponse.PushPromiseHandler<T> push) { return sendAsync(request, handler); }
	}
}
