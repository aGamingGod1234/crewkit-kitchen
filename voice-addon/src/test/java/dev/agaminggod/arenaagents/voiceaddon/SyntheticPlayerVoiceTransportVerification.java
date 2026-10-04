package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.ServerPlayer;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SyntheticPlayerVoiceTransportVerification {
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000101");
	private static final UUID FIRST_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000101");
	private static final UUID RESPAWNED_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000102");
	private static final UUID ORDINARY_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000103");

	private SyntheticPlayerVoiceTransportVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += ActiveRebindRegression.verify();
		assertions += verifyConnectedSpeakingAndSilentLifecycle();
		assertions += verifyRespawnRebindsExactlyOneSender();
		assertions += verifyVoiceServerRestartRebindsTheSamePlayer();
		assertions += verifyRejectedPacketFailsWithoutAFalseCompletion();
		assertions += verifyLaterPacketFailureIsReportedAsFailure();
		assertions += verifyCancellationStopsTheStreamWithoutCallbacks();
		assertions += verifyDelayedConnectionIsRetried();
		assertions += verifyRealVoiceClientIsNeverModified();
		return assertions;
	}

	private static int verifyConnectedSpeakingAndSilentLifecycle() throws Exception {
		RecordingApi api = new RecordingApi();
		RecordingConnection agent = api.connection(FIRST_PLAYER, false);
		RecordingConnection ordinary = api.connection(ORDINARY_PLAYER, true);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		transport.registerAgent(AGENT, FIRST_PLAYER);

		assertEquals(true, agent.connected, "managed fake player is presented as voice connected");
		assertEquals(false, agent.disabled, "managed fake player is not presented as voice disabled");
		assertEquals(1, api.registeredSenders, "reconciliation keeps one sender per managed player");
		assertEquals(0, ordinary.connectionWrites, "ordinary players are not modified");

		CountDownLatch stopped = new CountDownLatch(1);
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[1_920], stopped::countDown,
				() -> { throw new AssertionError("complete stream must not report failure"); }
		);
		assertEquals(48, SyntheticPlayerVoiceTransport.playbackDistance(FIRST_PLAYER),
				"create records the requested proximity radius");
		RecordingDistanceEvent distanceEvent = new RecordingDistanceEvent(FIRST_PLAYER, 16.0F);
		SyntheticPlayerVoiceTransport.applyPlaybackDistance(distanceEvent.event());
		assertEquals(48.0F, distanceEvent.distance,
				"VoiceDistanceEvent uses the requested proximity radius instead of the server-wide default");
		playback.start();
		assertEquals(true, stopped.await(2, TimeUnit.SECONDS), "speech stream reaches its natural stop callback");
		RecordingSender sender = api.onlySender();
		assertEquals(2, sender.frames.size(), "48 kHz speech is sent as two paced 20 ms microphone packets");
		assertEquals(960, sender.encodedSampleCounts.get(0), "first Opus frame has the required sample count");
		assertEquals(960, sender.encodedSampleCounts.get(1), "second Opus frame has the required sample count");
		assertEquals(true, sender.resetCalls >= 2, "sender marks both stream start and stream end");
		assertEquals(true, sender.encoderClosed, "per-utterance Opus encoder is closed");
		assertEquals(true, agent.connected, "silent managed player remains voice connected");
		assertEquals(1, api.registeredSenders, "silent managed player retains its sender for later speech");
		assertEquals(null, SyntheticPlayerVoiceTransport.playbackDistance(FIRST_PLAYER),
				"completed playback clears the requested proximity radius");

		transport.unregisterAgent(AGENT);
		assertEquals(false, agent.connected, "removed agent is no longer presented as voice connected");
		assertEquals(0, api.registeredSenders, "removed agent releases its audio sender");
		assertEquals(0, ordinary.connectionWrites, "cleanup still does not touch ordinary players");
		transport.close();
		return 17;
	}

	private static int verifyRespawnRebindsExactlyOneSender() {
		RecordingApi api = new RecordingApi();
		RecordingConnection first = api.connection(FIRST_PLAYER, false);
		RecordingConnection respawned = api.connection(RESPAWNED_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		transport.registerAgent(AGENT, RESPAWNED_PLAYER);
		assertEquals(false, first.connected, "old fake player connection is cleared during identity replacement");
		assertEquals(true, respawned.connected, "replacement fake player is connected automatically");
		assertEquals(1, api.registeredSenders, "respawn replacement leaves exactly one registered sender");
		assertEquals(2, api.senderRegistrations, "respawn creates one new sender after releasing the old one");
		assertEquals(1, api.senderUnregistrations, "respawn releases the old sender once");
		transport.close();
		assertEquals(false, respawned.connected, "transport shutdown clears replacement connection state");
		assertEquals(0, api.registeredSenders, "transport shutdown releases every sender");
		return 7;
	}

	private static int verifyRejectedPacketFailsWithoutAFalseCompletion() {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		RecordingSender sender = api.onlySender();
		sender.acceptPackets = false;
		AtomicInteger stopped = new AtomicInteger();
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[960], stopped::incrementAndGet, stopped::incrementAndGet
		);
		assertThrows(VoicePlaybackCoordinator.UnavailableException.class, playback::start,
				"a rejected first microphone packet fails playback synchronously");
		playback.stop();
		assertEquals(0, stopped.get(), "rejected playback does not report a false successful completion");
		assertEquals(true, sender.encoderClosed, "rejected playback closes its Opus encoder");
		transport.close();
		return 3;
	}

	private static int verifyLaterPacketFailureIsReportedAsFailure() throws Exception {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		api.onlySender().acceptedPacketLimit = 1;
		AtomicInteger stopped = new AtomicInteger();
		CountDownLatch failed = new CountDownLatch(1);
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[1_920], stopped::incrementAndGet, failed::countDown
		);
		playback.start();
		assertEquals(true, failed.await(2, TimeUnit.SECONDS), "later packet rejection reports stream failure");
		assertEquals(0, stopped.get(), "truncated speech is never reported as played");
		assertEquals(true, api.onlySender().encoderClosed, "failed stream closes its Opus encoder");
		transport.close();
		return 3;
	}

	private static int verifyCancellationStopsTheStreamWithoutCallbacks() throws Exception {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		AtomicInteger callbacks = new AtomicInteger();
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[96_000], callbacks::incrementAndGet, callbacks::incrementAndGet
		);
		playback.start();
		playback.stop();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!api.onlySender().encoderClosed && System.nanoTime() < deadline) Thread.sleep(5L);
		int framesAfterStop = api.onlySender().frames.size();
		Thread.sleep(60L);
		assertEquals(true, api.onlySender().encoderClosed, "cancelled stream closes its Opus encoder promptly");
		assertEquals(framesAfterStop, api.onlySender().frames.size(), "cancelled stream sends no later packets");
		assertEquals(0, callbacks.get(), "cancellation reports neither played nor failed");
		transport.close();
		return 3;
	}

	private static int verifyDelayedConnectionIsRetried() {
		RecordingApi api = new RecordingApi();
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(0, api.senderRegistrations, "missing initial voice connection does not create a broken sender");
		RecordingConnection connected = api.connection(FIRST_PLAYER, false);
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(true, connected.connected, "unchanged agent identity reconnects when voice registration appears");
		assertEquals(1, api.senderRegistrations, "delayed voice registration creates exactly one sender");
		transport.close();
		return 3;
	}

	private static int verifyVoiceServerRestartRebindsTheSamePlayer() {
		RecordingApi beforeRestart = new RecordingApi();
		RecordingConnection oldConnection = beforeRestart.connection(FIRST_PLAYER, false);
		RecordingApi afterRestart = new RecordingApi();
		RecordingConnection newConnection = afterRestart.connection(FIRST_PLAYER, false);
		AtomicReference<VoicechatServerApi> currentApi = new AtomicReference<>(beforeRestart.proxy());
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(currentApi::get);
		transport.registerAgent(AGENT, FIRST_PLAYER);
		currentApi.set(afterRestart.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(false, oldConnection.connected, "voice-server restart clears the old connection override");
		assertEquals(0, beforeRestart.registeredSenders, "voice-server restart releases the old API sender");
		assertEquals(true, newConnection.connected, "voice-server restart reconnects the same fake player");
		assertEquals(1, afterRestart.registeredSenders, "voice-server restart registers one sender on the new API");
		transport.close();
		return 4;
	}

	private static int verifyRealVoiceClientIsNeverModified() {
		RecordingApi api = new RecordingApi();
		RecordingConnection installed = api.connection(FIRST_PLAYER, true);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(0, installed.connectionWrites, "a player with a real voice client is never impersonated");
		assertEquals(0, api.senderRegistrations, "a real voice client never receives a synthetic sender");
		assertThrows(VoicePlaybackCoordinator.UnavailableException.class,
				() -> transport.create(AGENT, FIRST_PLAYER, 48, new short[960], () -> { }, () -> { }),
				"speech rejects rather than overriding a real voice client");
		transport.close();
		return 3;
	}

	private static final class RecordingApi implements InvocationHandler {
		private final Map<UUID, RecordingConnection> connections = new LinkedHashMap<>();
		private final Map<AudioSender, RecordingSender> senders = new IdentityHashMap<>();
		private final VoicechatServerApi proxy = SyntheticPlayerVoiceTransportVerification.proxy(
				VoicechatServerApi.class, this
		);
		private int registeredSenders;
		private int senderRegistrations;
		private int senderUnregistrations;

		private VoicechatServerApi proxy() {
			return proxy;
		}

		private RecordingConnection connection(UUID playerId, boolean installed) {
			RecordingConnection connection = new RecordingConnection(installed);
			connections.put(playerId, connection);
			return connection;
		}

		private RecordingSender onlySender() {
			return senders.values().stream().reduce((first, second) -> second).orElseThrow();
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "getConnectionOf" -> {
					RecordingConnection connection = connections.get((UUID) arguments[0]);
					yield connection == null ? null : connection.proxy;
				}
				case "createAudioSender" -> {
					RecordingSender sender = new RecordingSender();
					senders.put(sender.proxy, sender);
					yield sender.proxy;
				}
				case "registerAudioSender" -> {
					RecordingSender sender = senders.get((AudioSender) arguments[0]);
					sender.registered = true;
					registeredSenders++;
					senderRegistrations++;
					yield true;
				}
				case "unregisterAudioSender" -> {
					RecordingSender sender = senders.get((AudioSender) arguments[0]);
					if (sender.registered) {
						sender.registered = false;
						registeredSenders--;
						senderUnregistrations++;
						yield true;
					}
					yield false;
				}
				case "createEncoder" -> {
					RecordingSender sender = onlySender();
					yield sender.encoder;
				}
				case "toString" -> "recording-voicechat-api";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingConnection implements InvocationHandler {
		private final boolean installed;
		private final VoicechatConnection proxy = proxy(VoicechatConnection.class, this);
		private boolean connected;
		private boolean disabled = true;
		private int connectionWrites;

		private RecordingConnection(boolean installed) {
			this.installed = installed;
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "isInstalled" -> installed;
				case "isConnected" -> connected;
				case "isDisabled" -> disabled;
				case "setConnected" -> {
					connected = (boolean) arguments[0];
					connectionWrites++;
					yield null;
				}
				case "setDisabled" -> {
					disabled = (boolean) arguments[0];
					connectionWrites++;
					yield null;
				}
				case "toString" -> "recording-voicechat-connection";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingSender implements InvocationHandler {
		private boolean registered;
		private boolean acceptPackets = true;
		private int acceptedPacketLimit = Integer.MAX_VALUE;
		private boolean encoderClosed;
		private int resetCalls;
		private final AudioSender proxy = proxy(AudioSender.class, this);
		private final List<byte[]> frames = new ArrayList<>();
		private final List<Integer> encodedSampleCounts = new ArrayList<>();
		private final OpusEncoder encoder = proxy(OpusEncoder.class, (ignored, method, arguments) -> {
			return switch (method.getName()) {
					case "encode" -> {
						short[] samples = (short[]) arguments[0];
						encodedSampleCounts.add(samples.length);
						yield new byte[] { (byte) encodedSampleCounts.size() };
					}
					case "isClosed" -> encoderClosed;
					case "close" -> {
						encoderClosed = true;
						yield null;
					}
					default -> defaultValue(method.getReturnType());
				};
		});
		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "canSend" -> registered;
				case "send" -> {
					if (!registered || !acceptPackets || frames.size() >= acceptedPacketLimit) yield false;
					frames.add(((byte[]) arguments[0]).clone());
					yield true;
				}
				case "reset" -> {
					resetCalls++;
					yield registered;
				}
				case "whispering", "sequenceNumber" -> proxy;
				case "isWhispering" -> false;
				case "toString" -> "recording-audio-sender";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingDistanceEvent implements InvocationHandler {
		private final VoicechatConnection connection;
		private final VoiceDistanceEvent event = SyntheticPlayerVoiceTransportVerification.proxy(
				VoiceDistanceEvent.class, this);
		private float distance;

		private RecordingDistanceEvent(UUID playerId, float distance) {
			this.distance = distance;
			ServerPlayer player = SyntheticPlayerVoiceTransportVerification.proxy(
					ServerPlayer.class, (ignored, method, arguments) -> switch (method.getName()) {
						case "getUuid" -> playerId;
						default -> defaultValue(method.getReturnType());
					});
			this.connection = SyntheticPlayerVoiceTransportVerification.proxy(
					VoicechatConnection.class, (ignored, method, arguments) -> switch (method.getName()) {
						case "getPlayer" -> player;
						default -> defaultValue(method.getReturnType());
					});
		}

		private VoiceDistanceEvent event() {
			return event;
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "getSenderConnection" -> connection;
				case "getDistance" -> distance;
				case "setDistance" -> {
					distance = ((Number) arguments[0]).floatValue();
					yield null;
				}
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		return 0;
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable failure) {
			if (type.isInstance(failure)) return;
			throw new AssertionError(label + ": wrong failure " + failure, failure);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
		}
	}

	// Gate the second encode so rebind wins before a worker can report failure.
	private static final class ActiveRebindRegression {
		static final AgentId AGENT = new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000102"));
		static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000001102");

		static int verify() throws Exception {
			run("api-swap", true);
			run("sender-invalid", true);
			run("unchanged-refresh", false);
			run("packet-rejection", false);
			run("intentional-stop", false);
			run("identity-change", false);
			return 6;
		}

		static void run(String scenario, boolean expectDegraded) throws Exception {
			FakeApi first = new FakeApi();
			FakeApi replacement = new FakeApi();
			AtomicReference<VoicechatServerApi> current = new AtomicReference<>(first.api);
			SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(current::get);
			VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(
					ignored -> CompletableFuture.completedFuture(new short[2880]), Runnable::run,
					transport, ignored -> {}, ignored -> {});
			ReceiptWatch watch = new ReceiptWatch();
			Thread worker = null;
			try {
				coordinator.registerAgent(AGENT, ENTITY);
				transport.registerAgent(AGENT, ENTITY);
				CompletableFuture<VoiceReceipt> result = coordinator.speak(request(1)).toCompletableFuture();
				watch.observe(result);
				Encoder encoder = first.encoders.getFirst();
				require(encoder.secondEncode.await(2, TimeUnit.SECONDS), scenario + ": second frame reached");
				require(first.senders.getFirst().packets.get() == 1, "one frame already accepted");
				Object playback = map(coordinator, "players").get(AGENT);
				worker = (Thread) field(playback, "worker");

				switch (scenario) {
					case "api-swap" -> {
						current.set(replacement.api);
						transport.registerAgent(AGENT, ENTITY); // SimpleVoiceChatSubsystem.refreshAgent body
					}
					case "sender-invalid" -> {
						first.senders.getFirst().canSend = false;
						transport.registerAgent(AGENT, ENTITY);
					}
					case "unchanged-refresh" -> transport.registerAgent(AGENT, ENTITY);
					case "packet-rejection" -> first.senders.getFirst().acceptPackets = false;
					case "intentional-stop" -> coordinator.stop(AGENT);
					case "identity-change" -> {
						UUID changed = UUID.fromString("00000000-0000-0000-0000-000000001103");
						coordinator.registerAgent(AGENT, changed);
						transport.registerAgent(AGENT, changed);
					}
					default -> throw new AssertionError(scenario);
				}
				encoder.release.countDown();
				worker.join(2000);
				require(!worker.isAlive(), scenario + ": worker ended");
				require(encoder.closed, scenario + ": encoder closed");

				if (expectDegraded) {
					VoiceReceipt receipt = result.get(2, TimeUnit.SECONDS);
					require(receipt.status() == VoiceReceipt.Status.DEGRADED_TO_TEXT, scenario + ": interrupted receipt degrades");
					require(watch.completed.get() == 1 && watch.fallback.get() == 1, "one fallback callback");
					require(map(coordinator, "pending").isEmpty() && map(coordinator, "players").isEmpty(), "failed playback releases ownership");
					require(first.senders.getFirst().packets.get() == 1, "old utterance truncated after first frame");
					require(!first.senders.getFirst().registered, "old sender unregistered");
					FakeApi active = scenario.equals("api-swap") ? replacement : first;
					active.gateNewEncoders = false;
					VoiceReceipt next = coordinator.speak(request(2)).toCompletableFuture().get(2, TimeUnit.SECONDS);
					require(next.status() == VoiceReceipt.Status.PLAYED, "subsequent speech works");
					require(result.join() == receipt, "later speech preserves settled failure");
					require(watch.completed.get() == 1 && watch.fallback.get() == 1, "fallback remains exactly once");
					require(map(coordinator, "pending").isEmpty() && map(coordinator, "players").isEmpty(), "success releases ownership");
				} else {
					VoiceReceipt receipt = result.get(2, TimeUnit.SECONDS);
					VoiceReceipt.Status expected = switch (scenario) {
						case "unchanged-refresh" -> VoiceReceipt.Status.PLAYED;
						case "packet-rejection" -> VoiceReceipt.Status.DEGRADED_TO_TEXT;
						default -> VoiceReceipt.Status.CANCELLED;
					};
					require(receipt.status() == expected, scenario + ": expected " + expected);
					require(watch.completed.get() == 1, "control settles exactly once");
					int fallbackExpected = scenario.equals("packet-rejection") ? 1 : 0;
					require(watch.fallback.get() == fallbackExpected, "control fallback contract");
					require(map(coordinator, "pending").isEmpty() && map(coordinator, "players").isEmpty(), "control clears ownership");
					System.out.println(scenario + ": receipt=" + receipt.status() + "; callbacks=1; fallback=" + watch.fallback.get() + "; pending=0; players=0");
				}
			} finally {
				first.encoders.forEach(e -> e.release.countDown());
				replacement.encoders.forEach(e -> e.release.countDown());
				coordinator.close();
				transport.close();
				if (worker != null) { worker.join(2000); require(!worker.isAlive(), "cleanup worker ended"); }
				for (Sender sender : first.senders) require(!sender.registered, "first API sender released");
				for (Sender sender : replacement.senders) require(!sender.registered, "replacement API sender released");
			}
		}

		static VoiceRequest request(long sequence) { return new VoiceRequest(AGENT, "fixture", "voice.auto.v1", 48, sequence); }
		static class ReceiptWatch {
			final AtomicInteger completed = new AtomicInteger();
			final AtomicInteger fallback = new AtomicInteger();
			void observe(CompletableFuture<VoiceReceipt> future) {
				future.whenComplete((receipt, failure) -> {
					completed.incrementAndGet();
					// Same predicate as ServerActionExecutor, using the real receipt method.
					// This observes eligibility; no Minecraft player delivery is executed.
					if (failure != null || receipt == null || receipt.requiresTextFallback()) fallback.incrementAndGet();
				});
			}
		}
		static class FakeApi {
			final List<Sender> senders = new ArrayList<>();
			final List<Encoder> encoders = new ArrayList<>();
			boolean gateNewEncoders = true;
			final VoicechatConnection connection = proxy(VoicechatConnection.class, (self, method, args) -> switch (method.getName()) {
				case "isInstalled" -> false;
				case "setDisabled", "setConnected" -> null;
				default -> defaults(self, method.getName(), method.getReturnType(), args);
			});
			final VoicechatServerApi api = proxy(VoicechatServerApi.class, (self, method, args) -> switch (method.getName()) {
				case "getConnectionOf" -> connection;
				case "createAudioSender" -> { Sender sender = new Sender(); senders.add(sender); yield sender.api; }
				case "registerAudioSender" -> { find(args[0]).registered = true; yield true; }
				case "unregisterAudioSender" -> { find(args[0]).registered = false; yield true; }
				case "createEncoder" -> { Encoder encoder = new Encoder(gateNewEncoders); encoders.add(encoder); yield encoder.api; }
				default -> defaults(self, method.getName(), method.getReturnType(), args);
			});
			Sender find(Object api) { return senders.stream().filter(s -> s.api == api).findFirst().orElseThrow(); }
		}
		static class Sender {
			volatile boolean registered;
			volatile boolean canSend = true;
			volatile boolean acceptPackets = true;
			final AtomicInteger packets = new AtomicInteger();
			final AudioSender api = proxy(AudioSender.class, (self, method, args) -> switch (method.getName()) {
				case "canSend" -> registered && canSend;
				case "send" -> { if (!registered || !canSend || !acceptPackets) yield false; packets.incrementAndGet(); yield true; }
				case "reset" -> registered;
				case "whispering", "sequenceNumber" -> self;
				default -> defaults(self, method.getName(), method.getReturnType(), args);
			});
		}
		static class Encoder {
			final CountDownLatch secondEncode = new CountDownLatch(1);
			final CountDownLatch release = new CountDownLatch(1);
			boolean gate;
			volatile boolean closed;
			int calls;
			Encoder(boolean gate) { this.gate = gate; }
			final OpusEncoder api = proxy(OpusEncoder.class, (self, method, args) -> switch (method.getName()) {
				case "encode" -> {
					if (++calls == 2 && gate) {
						secondEncode.countDown();
						long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
						boolean released = false;
						while (!released && System.nanoTime() < deadline) {
							try { released = release.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
							catch (InterruptedException ignored) { /* retain the gate through cancellation */ }
						}
						require(released, "fixture gate timed out");
					}
					yield new byte[] { (byte) calls };
				}
				case "isClosed" -> closed;
				case "close" -> { closed = true; yield null; }
				default -> defaults(self, method.getName(), method.getReturnType(), args);
			});
		}
		static <T> T proxy(Class<T> type, InvocationHandler handler) {
			return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
		}
		static Object defaults(Object self, String name, Class<?> type, Object[] args) {
			if (name.equals("hashCode")) return System.identityHashCode(self);
			if (name.equals("equals")) return self == args[0];
			if (name.equals("toString")) return "active-rebind recording proxy";
			if (type == boolean.class) return false;
			if (type == void.class || !type.isPrimitive()) return null;
			throw new AssertionError("Unexpected primitive boundary: " + name);
		}
		static Object field(Object target, String name) throws Exception {
			Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
		}
		static Map<?,?> map(Object target, String name) throws Exception { return (Map<?,?>) field(target, name); }
		static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	}

}
