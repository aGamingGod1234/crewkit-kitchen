package dev.agaminggod.arenaagents.server.voice;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;

/** Verifies retryable voice provider startup with direct worker configuration. */
public final class VoiceSubsystemRuntimeVerification {
	private VoiceSubsystemRuntimeVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " voice runtime assertions");
	}

	public static int verify() {
		MinecraftServer server = uninitializedServer();
		VoiceSubsystemConfiguration configuration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18771/v1/tts", "m".repeat(32)
		);
		VoiceSubsystemConfiguration replacementConfiguration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18772/v1/tts", "n".repeat(32)
		);
		AtomicReference<VoiceSubsystemConfiguration> received = new AtomicReference<>();
		AtomicInteger closes = new AtomicInteger();
		AtomicInteger constructions = new AtomicInteger();
		boolean failed = VoiceSubsystemRuntime.start(server, configuration, List.of((actualServer, actualConfiguration) -> {
			received.set(actualConfiguration);
			throw new IllegalStateException("voice transport is starting");
		}));
		assertFalse(failed, "a provider construction failure remains retryable instead of caching NoVoice");
		assertEquals(configuration, received.get(), "provider receives the exact in-memory endpoint and secret");

		RecordingVoiceSubsystem first = new RecordingVoiceSubsystem(closes);
		boolean promoted = VoiceSubsystemRuntime.start(server, configuration, List.of((actualServer, actualConfiguration) -> {
			constructions.incrementAndGet();
			return first;
		}));
		assertTrue(promoted, "a later provider construction succeeds without restarting Minecraft");
		assertTrue(VoiceSubsystemRuntime.available(server), "successful retry promotes the real voice subsystem");
		assertTrue(VoiceSubsystemRuntime.start(server, configuration, List.of((actualServer, actualConfiguration) -> {
			throw new AssertionError("identical configuration must not reconstruct voice");
		})), "identical voice start is idempotent");
		assertEquals(1, constructions.get(), "identical voice start preserves one runtime");

		RecordingVoiceSubsystem reconfigured = new RecordingVoiceSubsystem(closes);
		assertTrue(VoiceSubsystemRuntime.start(server, replacementConfiguration,
				List.of((actualServer, actualConfiguration) -> {
					assertEquals(replacementConfiguration, actualConfiguration,
							"reconfigure retains exact endpoint and secret ownership");
					constructions.incrementAndGet();
					return reconfigured;
				})), "configuration change replaces the live runtime");
		assertEquals(1, closes.get(), "reconfigure closes the displaced runtime once");

		reconfigured.available = false;
		RecordingVoiceSubsystem recovered = new RecordingVoiceSubsystem(closes);
		assertTrue(VoiceSubsystemRuntime.start(server, replacementConfiguration,
				List.of((actualServer, actualConfiguration) -> {
					constructions.incrementAndGet();
					return recovered;
				})), "a failed live runtime is reconstructed without restarting Minecraft");
		assertEquals(2, closes.get(), "live failure closes the failed generation once");
		assertTrue(VoiceSubsystemRuntime.available(server), "live failure recovery promotes the replacement");
		dev.agaminggod.arenaagents.agent.AgentId agentId = dev.agaminggod.arenaagents.agent.AgentId.parse(
				"00000000-0000-4000-8000-000000000777"
		);
		assertTrue(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "first availability fallback is a diagnostic transition");
		assertFalse(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "identical availability fallback is deduplicated");
		assertEquals("VOICE_TRANSPORT_UNAVAILABLE",
				VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"successful availability clears the prior fallback once");
		assertEquals(null, VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"repeated recovery emits no duplicate transition");
		assertTrue(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "a later fallback is visible after recovery");
		VoiceRequest request = new VoiceRequest(agentId, "Recovered voice.", "voice.auto.v1", 48, 1L);
		assertEquals(VoiceReceipt.Status.ACCEPTED,
				VoiceSubsystemRuntime.speak(server, request).toCompletableFuture().join().status(),
				"healthy voice remains usable after recovery");
		assertEquals(null, VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"successful speech clears the availability fallback transition");
		recovered.speakFailure = new IllegalStateException("secret must-not-reach-control-work");
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT,
				VoiceSubsystemRuntime.speak(server, request).toCompletableFuture().join().status(),
				"synchronous voice failure degrades without rejecting control work");
		VoiceSubsystemRuntime.close(server);
		VoiceSubsystemRuntime.close(server);
		assertEquals(3, closes.get(), "current voice runtime closes exactly once");

		MinecraftServer startupRaceServer = uninitializedServer();
		AtomicInteger startupAttempts = new AtomicInteger();
		RecordingVoiceSubsystem startupRecovered = new RecordingVoiceSubsystem(new AtomicInteger());
		VoiceSubsystemProvider startupRaceProvider = (actualServer, actualConfiguration) -> {
			if (startupAttempts.getAndIncrement() == 0) {
				throw new IllegalStateException("Simple Voice Chat has not registered yet");
			}
			return startupRecovered;
		};
		assertFalse(VoiceSubsystemRuntime.start(startupRaceServer, configuration, List.of(startupRaceProvider)),
				"a provider waiting for Simple Voice Chat remains retryable");
		assertFalse(VoiceSubsystemRuntime.available(startupRaceServer),
				"a startup registration race does not install a permanent no-voice runtime");
		assertTrue(VoiceSubsystemRuntime.retryPending(startupRaceServer, Long.MAX_VALUE),
				"the pending voice provider retries after Simple Voice Chat registration");
		assertTrue(VoiceSubsystemRuntime.available(startupRaceServer),
				"the retry promotes the real voice subsystem without restarting Minecraft");
		VoiceSubsystemRuntime.close(startupRaceServer);
		return 25;
	}

	private static MinecraftServer uninitializedServer() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			return (MinecraftServer) unsafe.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate voice-runtime-only server", exception);
		}
	}

	private static final class RecordingVoiceSubsystem implements VoiceSubsystem {
		private final AtomicInteger closes;
		private boolean available = true;
		private RuntimeException speakFailure;

		private RecordingVoiceSubsystem(AtomicInteger closes) {
			this.closes = closes;
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public void registerAgent(dev.agaminggod.arenaagents.agent.AgentId agentId, java.util.UUID entityId) {
		}

		@Override
		public void unregisterAgent(dev.agaminggod.arenaagents.agent.AgentId agentId) {
		}

		@Override
		public java.util.concurrent.CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			if (speakFailure != null) throw speakFailure;
			return CompletableFuture.completedFuture(VoiceReceipt.accepted());
		}

		@Override
		public void stop(dev.agaminggod.arenaagents.agent.AgentId agentId) {
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
