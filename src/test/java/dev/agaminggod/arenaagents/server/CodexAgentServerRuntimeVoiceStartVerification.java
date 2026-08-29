package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies voice starts from the supported default secret when coordinator autostart is disabled. */
public final class CodexAgentServerRuntimeVoiceStartVerification {
	private CodexAgentServerRuntimeVoiceStartVerification() {
	}

	public static int verify() throws Exception {
		String oldAutoStart = System.getProperty("arenaagents.coordinatorAutoStart");
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldBridgePort = System.getProperty("arenaagents.bridgePort");
		Path defaultSecret = Path.of("runtime", "bridge-secret.txt");
		boolean hadDefaultSecret = Files.exists(defaultSecret);
		byte[] previousSecret = hadDefaultSecret ? Files.readAllBytes(defaultSecret) : null;
		CoordinatorProcessSupervisor supervisor = null;
		CodexAgentServerRuntime.BridgeSlot slot = null;
		try {
			Files.writeString(defaultSecret, "d".repeat(32), StandardCharsets.UTF_8);
			System.setProperty("arenaagents.coordinatorAutoStart", "false");
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.clearProperty("arenaagents.voiceSecretFile");
			System.setProperty("arenaagents.bridgePort", Integer.toString(unusedLoopbackPort()));

			supervisor = new CoordinatorProcessSupervisor(
					Path.of("build", "default-secret-voice-game"), Map.of(), () -> 0L,
					null, null, () -> "00000000-0000-0000-0000-000000000901"
			);
			assertFalse(supervisor.configured(), "disabled autostart skips coordinator dependency preparation");
			assertTrue(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					"readable default runtime secret prepares voice without an explicit secret property");

			slot = new CodexAgentServerRuntime.BridgeSlot(System::currentTimeMillis);
			CodexAgentServerRuntime.reconcileBridgeConfiguration(slot, uninitializedManager(), supervisor);
			assertTrue(slot.bridge() != null, "the Java bridge starts from the same default runtime secret");

			AtomicInteger voiceStarts = new AtomicInteger();
			CodexAgentServerRuntime.VoiceInitializationGate gate =
					new CodexAgentServerRuntime.VoiceInitializationGate(voiceStarts::incrementAndGet, () -> { });
			assertTrue(gate.reconcile(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"prepared default runtime secret starts the voice subsystem");
			assertEquals(1, voiceStarts.get(), "voice starts once for the prepared default runtime secret");

			Files.writeString(defaultSecret, "invalid", StandardCharsets.UTF_8);
			assertFalse(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					"invalid default runtime secret keeps voice fail-closed");
			Files.delete(defaultSecret);
			assertFalse(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					"missing default runtime secret keeps voice fail-closed");
			return 6;
		} finally {
			if (slot != null) slot.close();
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.coordinatorAutoStart", oldAutoStart);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.bridgePort", oldBridgePort);
			if (hadDefaultSecret) Files.write(defaultSecret, previousSecret);
			else Files.deleteIfExists(defaultSecret);
		}
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), new java.util.LinkedHashSet<>());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	private static int unusedLoopbackPort() throws IOException {
		try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
