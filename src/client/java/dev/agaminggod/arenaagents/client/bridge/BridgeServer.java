package dev.agaminggod.arenaagents.client.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeServer implements AutoCloseable {
	public static final String THREAD_NAME_PREFIX = "arenaagents-bridge-";

	private static final int ACCEPT_BACKLOG = 1;
	private static final long THREAD_JOIN_MS = 2_000L;

	private final AgentConfig config;
	private final ProtocolCodec codec;
	private final Executor callbackExecutor;
	private final BridgeEventSink eventSink;
	private final AtomicReference<BridgeSession> activeSession = new AtomicReference<>();
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();

	private volatile ServerSocket serverSocket;
	private volatile Thread acceptThread;

	public BridgeServer(
			AgentConfig config,
			ProtocolCodec codec,
			Executor callbackExecutor,
			BridgeEventSink eventSink
	) {
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.codec = Objects.requireNonNull(codec, "codec must not be null");
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
	}

	public void start() {
		if (!config.enabled()) {
			return;
		}
		if (closed.get()) {
			throw new IllegalStateException("Cannot start a closed bridge server");
		}
		if (!started.compareAndSet(false, true)) {
			return;
		}

		try {
			ServerSocket socket = new ServerSocket();
			socket.setReuseAddress(true);
			InetAddress loopback = InetAddress.getByName(AgentConfigLoader.LOOPBACK_HOST);
			socket.bind(new InetSocketAddress(loopback, config.bridgePort()), ACCEPT_BACKLOG);
			serverSocket = socket;
			acceptThread = Thread.ofPlatform()
					.name(THREAD_NAME_PREFIX + "accept-" + config.agentId())
					.daemon(true)
					.start(this::acceptLoop);
		} catch (IOException exception) {
			started.set(false);
			closeServerSocket();
			throw new ProtocolException(
					"BRIDGE_BIND_FAILED",
					"Could not bind bridge to " + AgentConfigLoader.LOOPBACK_HOST + ':' + config.bridgePort(),
					exception
			);
		}
	}

	public void sendEvent(String type, String messageId, JsonObject payload) {
		BridgeSession session = activeSession.get();
		if (session == null || !session.isAuthenticated()) {
			throw new ProtocolException("NO_AUTHENTICATED_SESSION", "No authenticated coordinator session is active");
		}
		session.sendEvent(type, messageId, payload);
	}

	public boolean isRunning() {
		ServerSocket socket = serverSocket;
		return started.get() && !closed.get() && socket != null && !socket.isClosed();
	}

	public int boundPort() {
		ServerSocket socket = serverSocket;
		if (socket == null || socket.isClosed()) {
			throw new IllegalStateException("Bridge server is not bound");
		}
		return socket.getLocalPort();
	}

	private void acceptLoop() {
		while (!closed.get()) {
			try {
				Socket accepted = serverSocket.accept();
				accept(accepted);
			} catch (IOException exception) {
				if (!closed.get()) {
					close();
				}
			}
		}
	}

	private void accept(Socket socket) {
		BridgeSession current = activeSession.get();
		if (current != null && current.isOpen()) {
			BridgeSession.rejectAdditionalSession(socket, config, codec);
			return;
		}
		if (current != null) {
			activeSession.compareAndSet(current, null);
		}

		AtomicReference<BridgeSession> candidateReference = new AtomicReference<>();
		try {
			BridgeSession candidate = new BridgeSession(
					config,
					socket,
					codec,
					callbackExecutor,
					eventSink,
					() -> activeSession.compareAndSet(candidateReference.get(), null)
			);
			candidateReference.set(candidate);
			if (!activeSession.compareAndSet(null, candidate)) {
				candidate.close();
				BridgeSession.rejectAdditionalSession(socket, config, codec);
				return;
			}
			candidate.start();
		} catch (IOException | RuntimeException exception) {
			closeSocket(socket);
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		closeServerSocket();
		BridgeSession session = activeSession.getAndSet(null);
		if (session != null) {
			session.close();
		}
		Thread thread = acceptThread;
		if (thread != null && thread != Thread.currentThread()) {
			thread.interrupt();
			try {
				thread.join(THREAD_JOIN_MS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void closeServerSocket() {
		ServerSocket socket = serverSocket;
		if (socket == null) {
			return;
		}
		try {
			socket.close();
		} catch (IOException ignored) {
			// Closing remains idempotent; there is no additional resource to recover.
		}
	}

	private static void closeSocket(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
			// The rejected socket has no remaining owner.
		}
	}
}
