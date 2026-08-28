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

	public static int verify() {
		MinecraftServer server = uninitializedServer();
		VoiceSubsystemConfiguration configuration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18771/v1/tts", "m".repeat(32)
		);
		AtomicReference<VoiceSubsystemConfiguration> received = new AtomicReference<>();
		AtomicInteger closes = new AtomicInteger();
		boolean failed = VoiceSubsystemRuntime.start(server, configuration, List.of((actualServer, actualConfiguration) -> {
			received.set(actualConfiguration);
			throw new IllegalStateException("voice transport is starting");
		}));
		assertFalse(failed, "a provider construction failure remains retryable instead of caching NoVoice");
		assertEquals(configuration, received.get(), "provider receives the exact in-memory endpoint and secret");

		boolean promoted = VoiceSubsystemRuntime.start(server, configuration, List.of((actualServer, actualConfiguration) ->
				new RecordingVoiceSubsystem(closes)));
		assertTrue(promoted, "a later provider construction succeeds without restarting Minecraft");
		assertTrue(VoiceSubsystemRuntime.available(server), "successful retry promotes the real voice subsystem");
		VoiceSubsystemRuntime.close(server);
		VoiceSubsystemRuntime.close(server);
		assertEquals(1, closes.get(), "promoted voice runtime closes exactly once");
		return 5;
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

		private RecordingVoiceSubsystem(AtomicInteger closes) {
			this.closes = closes;
		}

		@Override
		public boolean available() {
			return true;
		}

		@Override
		public void registerAgent(dev.agaminggod.arenaagents.agent.AgentId agentId, java.util.UUID entityId) {
		}

		@Override
		public void unregisterAgent(dev.agaminggod.arenaagents.agent.AgentId agentId) {
		}

		@Override
		public java.util.concurrent.CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
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
