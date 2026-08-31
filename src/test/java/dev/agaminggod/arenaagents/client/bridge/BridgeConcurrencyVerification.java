package dev.agaminggod.arenaagents.client.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeConcurrencyVerification {
	private static final int SOCKET_TIMEOUT_MS = 2_000;
	private static final long ASYNC_TIMEOUT_MS = 3_000L;
	private static final long SESSION_CONSTRUCTION_TIMEOUT_MS = 5_000L;

	private BridgeConcurrencyVerification() {
	}

	public static void verifyHelloAcknowledgementPrecedesEvents(AgentConfig config, ProtocolCodec codec)
			throws Exception {
		BlockingFirstOfferQueue outbound = new BlockingFirstOfferQueue();
		try (ServerSocket listener = new ServerSocket()) {
			listener.bind(new InetSocketAddress("127.0.0.1", config.bridgePort()));
			try (Socket client = openClient(config.bridgePort()); Socket accepted = listener.accept()) {
				BridgeSession session = new BridgeSession(
						config,
						accepted,
						codec,
						Runnable::run,
						command -> { },
						() -> { },
						outbound
				);
				try {
					session.start();
					AuthenticationAttempt attempt = respondToChallenge(client, config, codec, "ordered-response");
					outbound.awaitFirstOffer();

					AtomicReference<Throwable> prematureSend = new AtomicReference<>();
					Thread sender = Thread.ofPlatform()
							.name("arenaagents-verification-premature-event")
							.daemon(true)
							.start(() -> {
								try {
									session.sendEvent("significant_event", null);
								} catch (Throwable exception) {
									prematureSend.set(exception);
								}
							});
					sender.join(SOCKET_TIMEOUT_MS);
					if (sender.isAlive()) {
						throw new AssertionError("event send blocked while hello acknowledgement was pending");
					}
					if (!(prematureSend.get() instanceof ProtocolException exception)
							|| !"NO_AUTHENTICATED_SESSION".equals(exception.code())) {
						throw new AssertionError("session became authenticated before hello acknowledgement was queued");
					}
					client.setSoTimeout(100);
					try {
						readMessage(client, codec);
						throw new AssertionError("hello acknowledgement escaped before authentication was published");
					} catch (SocketTimeoutException expected) {
						// The writer must wait for the handshake state transition to finish.
					} finally {
						client.setSoTimeout(SOCKET_TIMEOUT_MS);
					}

					outbound.releaseFirstOffer();
					JsonObject acknowledgement = readMessage(client, codec);
					verifyAcknowledgement(acknowledgement, config, attempt);
					if (!"hello_ack".equals(acknowledgement.get("type").getAsString())) {
						throw new AssertionError("mutual authentication acknowledgement was not received");
					}
					String eventId = session.sendEvent("significant_event", null);
					JsonObject event = readMessage(client, codec);
					if (!eventId.equals(event.get("messageId").getAsString())) {
						throw new AssertionError("generated event ID did not match the ordered event frame");
					}
				} finally {
					outbound.releaseFirstOffer();
					session.close();
				}
			}
		}
	}

	public static void verifyCallbackFailureShutdownDoesNotWaitForBlockedOutput(
			AgentConfig config,
			ProtocolCodec codec
	) throws Exception {
		CountDownLatch callbackStarted = new CountDownLatch(1);
		CountDownLatch callbackCompleted = new CountDownLatch(1);
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		AtomicReference<Throwable> callbackEscape = new AtomicReference<>();
		try (ServerSocket listener = new ServerSocket()) {
			listener.bind(new InetSocketAddress("127.0.0.1", config.bridgePort()));
			try (Socket client = openClient(config.bridgePort()); Socket accepted = listener.accept()) {
				BridgeSession session = new BridgeSession(
						config,
						accepted,
						codec,
						task -> callbackThread.set(Thread.ofPlatform()
						.name("arenaagents-verification-blocked-output-callback")
						.daemon(true)
						.start(() -> {
							try {
								task.run();
							} catch (Throwable exception) {
								callbackEscape.set(exception);
							} finally {
								callbackCompleted.countDown();
							}
						})),
						command -> {
					callbackStarted.countDown();
					throw new IllegalStateException("callback failed");
						},
						() -> { }
				);
				try {
					session.start();
					authenticate(client, config, codec, "callback-response");
					codec.writeLine(
							client.getOutputStream(),
							"{\"protocolVersion\":1,\"agentId\":\"" + config.agentId()
									+ "\",\"type\":\"action_command\",\"messageId\":\"blocked-output-action\","
									+ "\"command\":{\"protocolVersion\":1,\"commandId\":\"blocked-output-command\","
									+ "\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}}"
					);
					awaitLatch(callbackStarted, "failing callback did not begin");
					awaitLatch(callbackCompleted, "callback failure did not complete");
					if (!accepted.isClosed()) {
						throw new AssertionError("callback failure did not close the socket");
					}
					if (callbackEscape.get() != null) {
						throw new AssertionError("callback failure escaped its executor", callbackEscape.get());
					}
				} finally {
					session.close();
					Thread thread = callbackThread.get();
					if (thread != null) {
						thread.join(SOCKET_TIMEOUT_MS);
					}
				}
			}
		}
	}

	public static void verifyQueueOverflowDoesNotInvertCallbackLock(AgentConfig config, ProtocolCodec codec)
			throws Exception {
		OverflowGateQueue outbound = new OverflowGateQueue();
		AtomicReference<BridgeSession> sessionReference = new AtomicReference<>();
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch callbackCompleted = new CountDownLatch(1);
		try (ServerSocket listener = new ServerSocket()) {
			listener.bind(new InetSocketAddress("127.0.0.1", config.bridgePort()));
			try (Socket client = openClient(config.bridgePort()); Socket accepted = listener.accept()) {
				BridgeSession session = new BridgeSession(
						config,
						accepted,
						codec,
						Runnable::run,
						command -> {
							callbackEntered.countDown();
							try {
								sessionReference.get().sendEvent("significant_event", null);
							} finally {
								callbackCompleted.countDown();
							}
						},
						() -> { },
						outbound
				);
				sessionReference.set(session);
				try {
					session.start();
					authenticate(client, config, codec, "overflow-response");

					AtomicReference<Throwable> overflowFailure = new AtomicReference<>();
					Thread overflowingSender = Thread.ofPlatform()
							.name("arenaagents-verification-overflow-sender")
							.daemon(true)
							.start(() -> {
								try {
									session.sendEvent("significant_event", null);
								} catch (Throwable exception) {
									overflowFailure.set(exception);
								}
							});
					outbound.awaitOverflowOffer();
					codec.writeLine(
							client.getOutputStream(),
							"{\"protocolVersion\":1,\"agentId\":\"" + config.agentId()
									+ "\",\"type\":\"action_command\",\"messageId\":\"overflow-action\","
									+ "\"command\":{\"protocolVersion\":1,\"commandId\":\"overflow-command\","
									+ "\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}}"
					);
					awaitLatch(callbackEntered, "reentrant callback did not begin");
					outbound.releaseOverflowOffer();
					overflowingSender.join(SOCKET_TIMEOUT_MS);
					if (overflowingSender.isAlive()) {
						throw new AssertionError("queue overflow deadlocked lifecycle and callback locks");
					}
					awaitLatch(callbackCompleted, "reentrant callback did not finish after overflow");
					if (!(overflowFailure.get() instanceof ProtocolException exception)
							|| !"OUTBOUND_QUEUE_FULL".equals(exception.code())) {
						throw new AssertionError("overflow sender did not receive OUTBOUND_QUEUE_FULL");
					}
				} finally {
					outbound.releaseOverflowOffer();
					session.close();
				}
			}
		}
	}

	public static void verifyCloseLinearizesPendingAdmission(AgentConfig config, ProtocolCodec codec)
			throws Exception {
		CountDownLatch admissionEntered = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		AtomicReference<BridgeSession> createdSession = new AtomicReference<>();
		BridgeServer server = new BridgeServer(
				config,
				codec,
				Runnable::run,
				command -> { },
				(socket, closedCallback) -> createDelayedSession(
						config,
						codec,
						socket,
						closedCallback,
						admissionEntered,
						releaseAdmission,
						createdSession
				)
		);
		Thread closeThread = null;
		Socket client = null;
		try {
			server.start();
			client = openClient(config.bridgePort());
			awaitLatch(admissionEntered, "server did not begin session construction");

			closeThread = Thread.ofPlatform()
					.name("arenaagents-verification-close")
					.daemon(true)
					.start(server::close);
			awaitCondition(() -> !server.isRunning(), "server did not publish its closed state");
			closeThread.join(ASYNC_TIMEOUT_MS);
			if (closeThread.isAlive()) {
				throw new AssertionError("server close did not finish while session construction was pending");
			}
			releaseAdmission.countDown();

			if (receivesHelloAcknowledgement(client, codec)) {
				throw new AssertionError("session admitted and acknowledged hello after server close");
			}
			awaitCondition(
					() -> bridgeThreads().stream().noneMatch(Thread::isAlive),
					"bridge thread remained alive after close raced with admission"
			);
		} finally {
			releaseAdmission.countDown();
			if (client != null) {
				client.close();
			}
			BridgeSession session = createdSession.get();
			if (session != null) {
				session.close();
			}
			server.close();
			if (closeThread != null) {
				closeThread.join(SOCKET_TIMEOUT_MS);
			}
		}
	}

	public static void verifyMutualAuthenticationRejectsReplayAndImpostor(AgentConfig config, ProtocolCodec codec)
			throws Exception {
		String replay;
		int replaySourcePort = config.bridgePort();
		try (BridgeServer server = new BridgeServer(config, codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = openClient(replaySourcePort)) {
				JsonObject challenge = readMessage(client, codec);
				replay = responseForChallenge(challenge, config, "replay-response", BridgeAuthentication.newNonce());
				codec.writeLine(client.getOutputStream(), replay);
				verifyAcknowledgement(readMessage(client, codec), config, attemptFromChallenge(challenge, "replay-response", replay));
			}
		}

		int replayTargetPort;
		try (ServerSocket reservation = new ServerSocket()) {
			reservation.bind(new InetSocketAddress("127.0.0.1", 0));
			replayTargetPort = reservation.getLocalPort();
		}
		AgentConfig replayConfig = new AgentConfig(
				config.agentId(), replayTargetPort, config.observationRadius(), true, config.bridgeSecret()
		);
		try (BridgeServer server = new BridgeServer(replayConfig, codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = openClient(replayTargetPort)) {
				readMessage(client, codec);
				codec.writeLine(client.getOutputStream(), replay);
				assertErrorCode(readMessage(client, codec), "AUTHENTICATION_FAILED", "replayed response is rejected");
			}
		}

		int impostorPort;
		try (ServerSocket reservation = new ServerSocket()) {
			reservation.bind(new InetSocketAddress("127.0.0.1", 0));
			impostorPort = reservation.getLocalPort();
		}
		AgentConfig impostorConfig = new AgentConfig(
				config.agentId(), impostorPort, config.observationRadius(), true, config.bridgeSecret()
		);
		try (BridgeServer server = new BridgeServer(impostorConfig, codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = openClient(impostorPort)) {
				JsonObject challenge = readMessage(client, codec);
				String impostorResponse = responseForChallenge(
						challenge,
						new AgentConfig(config.agentId(), impostorPort, config.observationRadius(), true,
								"impostor-bridge-secret-0123456789"),
						"impostor-response",
						BridgeAuthentication.newNonce()
				);
				codec.writeLine(client.getOutputStream(), impostorResponse);
				assertErrorCode(readMessage(client, codec), "AUTHENTICATION_FAILED", "impostor proof is rejected");
			}
		}
	}

	private static BridgeSession createDelayedSession(
			AgentConfig config,
			ProtocolCodec codec,
			Socket socket,
			Runnable closedCallback,
			CountDownLatch admissionEntered,
			CountDownLatch releaseAdmission,
			AtomicReference<BridgeSession> createdSession
	) throws IOException {
		admissionEntered.countDown();
		awaitReleaseUninterruptibly(releaseAdmission);
		BridgeSession session = new BridgeSession(
				config,
				socket,
				codec,
				Runnable::run,
				command -> { },
				closedCallback
		);
		createdSession.set(session);
		return session;
	}

	private static void awaitReleaseUninterruptibly(CountDownLatch releaseAdmission) throws IOException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SESSION_CONSTRUCTION_TIMEOUT_MS);
		boolean interrupted = false;
		try {
			while (releaseAdmission.getCount() != 0L) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0L) {
					throw new IOException("Timed out waiting to release session construction");
				}
				try {
					releaseAdmission.await(remainingNanos, TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static Socket openClient(int port) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		return socket;
	}

	private static JsonObject readMessage(Socket socket, ProtocolCodec codec) throws IOException {
		String line = codec.readLine(socket.getInputStream());
		if (line == null) {
			throw new AssertionError("bridge closed before sending an expected frame");
		}
		return JsonParser.parseString(line).getAsJsonObject();
	}

	private static JsonObject authenticate(Socket client, AgentConfig config, ProtocolCodec codec, String responseId)
			throws IOException {
		AuthenticationAttempt attempt = respondToChallenge(client, config, codec, responseId);
		JsonObject acknowledgement = readMessage(client, codec);
		verifyAcknowledgement(acknowledgement, config, attempt);
		return acknowledgement;
	}

	private static AuthenticationAttempt respondToChallenge(
			Socket client,
			AgentConfig config,
			ProtocolCodec codec,
			String responseId
	) throws IOException {
		JsonObject challenge = readMessage(client, codec);
		String coordinatorNonce = BridgeAuthentication.newNonce();
		String response = responseForChallenge(challenge, config, responseId, coordinatorNonce);
		codec.writeLine(client.getOutputStream(), response);
		return attemptFromChallenge(challenge, responseId, response);
	}

	private static String responseForChallenge(
			JsonObject challenge,
			AgentConfig config,
			String responseId,
			String coordinatorNonce
	) {
		if (!"hello_challenge".equals(challenge.get("type").getAsString())) {
			throw new AssertionError("bridge did not issue an authentication challenge");
		}
		String challengeId = challenge.get("messageId").getAsString();
		String bridgeNonce = challenge.get("nonce").getAsString();
		return "{\"protocolVersion\":1,\"agentId\":\"" + config.agentId()
				+ "\",\"type\":\"hello_response\",\"messageId\":\"" + responseId
				+ "\",\"challenge\":\"" + bridgeNonce + "\",\"nonce\":\"" + coordinatorNonce
				+ "\",\"proof\":\"" + BridgeAuthentication.coordinatorProof(
						config.bridgeSecret(), config.agentId(), challengeId, responseId, bridgeNonce, coordinatorNonce
				) + "\"}";
	}

	private static AuthenticationAttempt attemptFromChallenge(JsonObject challenge, String responseId, String response) {
		String bridgeNonce = challenge.get("nonce").getAsString();
		JsonObject responseObject = JsonParser.parseString(response).getAsJsonObject();
		return new AuthenticationAttempt(
				challenge.get("messageId").getAsString(), responseId, bridgeNonce, responseObject.get("nonce").getAsString()
		);
	}

	private static void verifyAcknowledgement(JsonObject acknowledgement, AgentConfig config, AuthenticationAttempt attempt) {
		if (!"hello_ack".equals(acknowledgement.get("type").getAsString())) {
			throw new AssertionError("bridge did not complete mutual authentication");
		}
		if (!attempt.responseMessageId().equals(acknowledgement.get("replyTo").getAsString())) {
			throw new AssertionError("bridge acknowledgement did not bind the coordinator response");
		}
		String expected = BridgeAuthentication.bridgeProof(
				config.bridgeSecret(),
				config.agentId(),
				attempt.challengeMessageId(),
				attempt.responseMessageId(),
				attempt.bridgeNonce(),
				attempt.coordinatorNonce()
		);
		if (!BridgeAuthentication.proofsMatch(expected, acknowledgement.get("proof").getAsString())) {
			throw new AssertionError("bridge acknowledgement proof did not authenticate the bridge");
		}
	}

	private static void assertErrorCode(JsonObject response, String expectedCode, String label) {
		if (!"error".equals(response.get("type").getAsString())
				|| !expectedCode.equals(response.get("code").getAsString())) {
			throw new AssertionError(label + ": expected " + expectedCode + " error");
		}
	}

	private static boolean receivesHelloAcknowledgement(
			Socket client,
			ProtocolCodec codec
	) {
		try {
			String line = codec.readLine(client.getInputStream());
			if (line == null) {
				return false;
			}
			JsonObject response = JsonParser.parseString(line).getAsJsonObject();
			return "hello_ack".equals(response.get("type").getAsString());
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private static List<Thread> bridgeThreads() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> thread.getName().startsWith(BridgeServer.THREAD_NAME_PREFIX))
				.toList();
	}

	private static void awaitLatch(CountDownLatch latch, String failureMessage) throws InterruptedException {
		if (!latch.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
			throw new AssertionError(failureMessage);
		}
	}

	private static void awaitCondition(BooleanSupplier condition, String failureMessage) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_TIMEOUT_MS);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(1L);
		}
		if (!condition.getAsBoolean()) {
			throw new AssertionError(failureMessage);
		}
	}

	@FunctionalInterface
	private interface BooleanSupplier {
		boolean getAsBoolean();
	}

	private record AuthenticationAttempt(
			String challengeMessageId,
			String responseMessageId,
			String bridgeNonce,
			String coordinatorNonce
	) {
	}

	private static final class BlockingFirstOfferQueue extends LinkedBlockingQueue<String> {
		private static final long serialVersionUID = 1L;

		private final CountDownLatch firstOfferEntered = new CountDownLatch(1);
		private final CountDownLatch releaseFirstOffer = new CountDownLatch(1);
		private final AtomicInteger offerCount = new AtomicInteger();

		@Override
		public boolean offer(String message) {
			if (offerCount.incrementAndGet() == 2) {
				boolean offered = super.offer(message);
				firstOfferEntered.countDown();
				awaitFirstOfferRelease();
				return offered;
			}
			return super.offer(message);
		}

		private void awaitFirstOffer() throws InterruptedException {
			if (!firstOfferEntered.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				throw new AssertionError("hello acknowledgement never reached the outbound queue");
			}
		}

		private void releaseFirstOffer() {
			releaseFirstOffer.countDown();
		}

		private void awaitFirstOfferRelease() {
			boolean interrupted = false;
			try {
				while (releaseFirstOffer.getCount() != 0L) {
					try {
						if (!releaseFirstOffer.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
							throw new AssertionError("timed out waiting to release the first outbound offer");
						}
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
			} finally {
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static final class OverflowGateQueue extends LinkedBlockingQueue<String> {
		private static final long serialVersionUID = 1L;

		private final AtomicInteger offerCount = new AtomicInteger();
		private final CountDownLatch overflowOfferEntered = new CountDownLatch(1);
		private final CountDownLatch releaseOverflowOffer = new CountDownLatch(1);

		@Override
		public boolean offer(String message) {
			if (offerCount.incrementAndGet() == 3) {
				overflowOfferEntered.countDown();
				awaitUninterruptibly(releaseOverflowOffer);
				return false;
			}
			return super.offer(message);
		}

		private void awaitOverflowOffer() throws InterruptedException {
			awaitLatch(overflowOfferEntered, "overflow offer did not reach the queue gate");
		}

		private void releaseOverflowOffer() {
			releaseOverflowOffer.countDown();
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (latch.getCount() != 0L) {
			try {
				latch.await();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
