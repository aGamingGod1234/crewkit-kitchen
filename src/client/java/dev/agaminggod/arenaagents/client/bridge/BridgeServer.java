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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeServer implements AutoCloseable {
	public static final String THREAD_NAME_PREFIX = "arenaagents-bridge-";

	private static final int ACCEPT_BACKLOG = 1;
	private static final long THREAD_JOIN_MS = 2_000L;

	private final AgentConfig config;
	private final ProtocolCodec codec;
	private final SessionFactory sessionFactory;
	private final Object lifecycleLock = new Object();
	private final CountDownLatch closeCompleted = new CountDownLatch(1);

	private ServerState state = ServerState.NEW;
	private ServerSocket serverSocket;
	private Thread acceptThread;
	private BridgeSession activeSession;
	private Thread closingAcceptThread;
	private BridgeSession closingSession;

	public BridgeServer(
			AgentConfig config,
			ProtocolCodec codec,
			Executor callbackExecutor,
			BridgeEventSink eventSink
	) {
		this(
				config,
				codec,
				callbackExecutor,
				eventSink,
				(socket, closedCallback) -> new BridgeSession(
						config,
						socket,
						codec,
						callbackExecutor,
						eventSink,
						closedCallback
				)
		);
	}

	BridgeServer(
			AgentConfig config,
			ProtocolCodec codec,
			Executor callbackExecutor,
			BridgeEventSink eventSink,
			SessionFactory sessionFactory
	) {
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.codec = Objects.requireNonNull(codec, "codec must not be null");
		Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
		Objects.requireNonNull(eventSink, "eventSink must not be null");
		this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
	}

	public void start() {
		if (!config.enabled()) {
			return;
		}
		synchronized (lifecycleLock) {
			if (state == ServerState.CLOSED) {
				throw new IllegalStateException("Cannot start a closed bridge server");
			}
			if (state != ServerState.NEW) {
				return;
			}
			state = ServerState.STARTING;
		}

		ServerSocket socket = null;
		try {
			socket = new ServerSocket();
			socket.setReuseAddress(true);
			InetAddress loopback = InetAddress.getByName(AgentConfigLoader.LOOPBACK_HOST);
			socket.bind(new InetSocketAddress(loopback, config.bridgePort()), ACCEPT_BACKLOG);
			if (publishListener(socket)) {
				socket = null;
			}
		} catch (IOException exception) {
			boolean closedDuringStart;
			synchronized (lifecycleLock) {
				closedDuringStart = state == ServerState.CLOSING || state == ServerState.CLOSED;
				if (!closedDuringStart) {
					state = ServerState.NEW;
				}
			}
			closeServerSocket(socket);
			if (closedDuringStart) {
				return;
			}
			throw new ProtocolException(
					"BRIDGE_BIND_FAILED",
					"Could not bind bridge to " + AgentConfigLoader.LOOPBACK_HOST + ':' + config.bridgePort(),
					exception
			);
		} finally {
			closeServerSocket(socket);
		}
	}

	private boolean publishListener(ServerSocket socket) {
		synchronized (lifecycleLock) {
			if (state != ServerState.STARTING) {
				return false;
			}
			serverSocket = socket;
			acceptThread = Thread.ofPlatform()
					.name(THREAD_NAME_PREFIX + "accept-" + config.agentId())
					.daemon(true)
					.unstarted(() -> acceptLoop(socket));
			state = ServerState.RUNNING;
			acceptThread.start();
			return true;
		}
	}

	public String sendEvent(String type, JsonObject payload) {
		BridgeSession session;
		synchronized (lifecycleLock) {
			session = activeSession;
		}
		if (session == null || !session.isAuthenticated()) {
			throw new ProtocolException("NO_AUTHENTICATED_SESSION", "No authenticated coordinator session is active");
		}
		return session.sendEvent(type, payload);
	}

	public boolean isRunning() {
		synchronized (lifecycleLock) {
			return state == ServerState.RUNNING && serverSocket != null && !serverSocket.isClosed();
		}
	}

	public int boundPort() {
		synchronized (lifecycleLock) {
			if (state != ServerState.RUNNING || serverSocket == null || serverSocket.isClosed()) {
				throw new IllegalStateException("Bridge server is not bound");
			}
			return serverSocket.getLocalPort();
		}
	}

	private void acceptLoop(ServerSocket listener) {
		while (isCurrentListenerRunning(listener)) {
			try {
				Socket accepted = listener.accept();
				accept(accepted);
			} catch (IOException exception) {
				if (isCurrentListenerRunning(listener)) {
					close();
				}
			}
		}
	}

	private boolean isCurrentListenerRunning(ServerSocket listener) {
		synchronized (lifecycleLock) {
			return state == ServerState.RUNNING && serverSocket == listener;
		}
	}

	private void accept(Socket socket) {
		AdmissionDecision initialDecision = prepareAdmission();
		if (initialDecision == AdmissionDecision.CLOSE) {
			closeSocket(socket);
			return;
		}
		if (initialDecision == AdmissionDecision.REJECT) {
			BridgeSession.rejectAdditionalSession(socket, config, codec);
			return;
		}

		AtomicReference<BridgeSession> candidateReference = new AtomicReference<>();
		BridgeSession candidate = null;
		try {
			candidate = sessionFactory.create(
					socket,
					() -> clearSession(candidateReference.get())
			);
			candidateReference.set(candidate);
			admit(candidate);
		} catch (IOException | RuntimeException exception) {
			if (candidate != null) {
				candidate.close();
			} else {
				closeSocket(socket);
			}
		}
	}

	private AdmissionDecision prepareAdmission() {
		synchronized (lifecycleLock) {
			if (state != ServerState.RUNNING) {
				return AdmissionDecision.CLOSE;
			}
			if (activeSession == null) {
				return AdmissionDecision.ADMIT;
			}
			if (activeSession.isOpen()) {
				return AdmissionDecision.REJECT;
			}
			activeSession = null;
			return AdmissionDecision.ADMIT;
		}
	}

	private void admit(BridgeSession candidate) {
		boolean admitted = false;
		synchronized (lifecycleLock) {
			if (state == ServerState.RUNNING && activeSession == null) {
				activeSession = candidate;
				try {
					candidate.start();
					admitted = true;
				} catch (RuntimeException exception) {
					activeSession = null;
					throw exception;
				}
			}
		}
		if (!admitted) {
			candidate.close();
		}
	}

	private void clearSession(BridgeSession candidate) {
		synchronized (lifecycleLock) {
			if (activeSession == candidate) {
				activeSession = null;
			}
		}
	}

	@Override
	public void close() {
		ServerSocket listener;
		BridgeSession session;
		Thread thread;
		boolean ownsClose;
		synchronized (lifecycleLock) {
			if (state == ServerState.CLOSED) {
				return;
			}
			ownsClose = state != ServerState.CLOSING;
			if (ownsClose) {
				state = ServerState.CLOSING;
				listener = serverSocket;
				serverSocket = null;
				session = activeSession;
				activeSession = null;
				thread = acceptThread;
				acceptThread = null;
				closingSession = session;
				closingAcceptThread = thread;
			} else {
				listener = null;
				session = closingSession;
				thread = closingAcceptThread;
			}
		}
		if (!ownsClose) {
			awaitCloseCompletionWhenSafe(session, thread);
			return;
		}
		try {
			closeServerSocket(listener);
			if (session != null) {
				session.close();
			}
			if (thread != null && thread != Thread.currentThread()) {
				thread.interrupt();
				try {
					thread.join(THREAD_JOIN_MS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		} finally {
			synchronized (lifecycleLock) {
				state = ServerState.CLOSED;
				closingSession = null;
				closingAcceptThread = null;
			}
			closeCompleted.countDown();
		}
	}

	private void awaitCloseCompletionWhenSafe(BridgeSession session, Thread thread) {
		Thread current = Thread.currentThread();
		if (current == thread || (session != null && session.isDispatchingCallbackOnCurrentThread())) {
			return;
		}
		boolean interrupted = false;
		while (closeCompleted.getCount() != 0L) {
			try {
				closeCompleted.await();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			current.interrupt();
		}
	}

	private static void closeServerSocket(ServerSocket socket) {
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

	@FunctionalInterface
	interface SessionFactory {
		BridgeSession create(Socket socket, Runnable closedCallback) throws IOException;
	}

	private enum AdmissionDecision {
		ADMIT,
		REJECT,
		CLOSE
	}

	private enum ServerState {
		NEW,
		STARTING,
		RUNNING,
		CLOSING,
		CLOSED
	}
}
