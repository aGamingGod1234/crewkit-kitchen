package dev.agaminggod.arenaagents.client;

import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ArenaAgentsClientBootstrapVerification {
	private ArenaAgentsClientBootstrapVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyConfigurationFailureFallsBackToDisabledDefaults();
		assertions += verifySuccessfulConfigurationIsPreserved();
		assertions += verifyBridgeStartFailureCleansUpWithoutEscaping();
		assertions += verifyBridgeStartSuccessSkipsCleanup();
		return assertions;
	}

	private static int verifyConfigurationFailureFallsBackToDisabledDefaults() {
		AtomicReference<RuntimeException> reported = new AtomicReference<>();
		ProtocolException failure = new ProtocolException("INVALID_CONFIG", "malformed");
		AgentConfig config = ArenaAgentsClient.loadConfigOrDisabled(
				() -> {
					throw failure;
				},
				reported::set
		);
		assertEquals(AgentConfig.defaults(), config, "invalid configuration falls back to disabled defaults");
		assertSame(failure, reported.get(), "invalid configuration failure is reported");

		AtomicReference<RuntimeException> ioReported = new AtomicReference<>();
		AgentConfig ioFallback = ArenaAgentsClient.loadConfigOrDisabled(
				() -> {
					throw new IOException("unreadable");
				},
				ioReported::set
		);
		assertEquals(AgentConfig.defaults(), ioFallback, "configuration I/O failure falls back to disabled defaults");
		assertTrue(ioReported.get() != null, "configuration I/O failure is reported");
		return 4;
	}

	private static int verifySuccessfulConfigurationIsPreserved() {
		AgentConfig expected = new AgentConfig("bootstrap-test", 25_571, 8, true);
		AtomicReference<RuntimeException> reported = new AtomicReference<>();
		AgentConfig actual = ArenaAgentsClient.loadConfigOrDisabled(() -> expected, reported::set);
		assertSame(expected, actual, "valid configuration is preserved");
		assertEquals(null, reported.get(), "valid configuration reports no failure");
		return 2;
	}

	private static int verifyBridgeStartFailureCleansUpWithoutEscaping() {
		List<String> order = new ArrayList<>();
		AtomicReference<RuntimeException> reported = new AtomicReference<>();
		RuntimeException startFailure = new IllegalStateException("bind failed");
		boolean started = ArenaAgentsClient.startBridgeOrCleanup(
				() -> {
					order.add("start");
					throw startFailure;
				},
				() -> order.add("stop"),
				() -> order.add("close"),
				reported::set
		);
		assertEquals(false, started, "bridge start failure is contained");
		assertEquals(List.of("start", "stop", "close"), order, "failed bridge start releases runtime resources");
		assertSame(startFailure, reported.get(), "bridge start failure is reported");
		return 3;
	}

	private static int verifyBridgeStartSuccessSkipsCleanup() {
		List<String> order = new ArrayList<>();
		AtomicReference<RuntimeException> reported = new AtomicReference<>();
		boolean started = ArenaAgentsClient.startBridgeOrCleanup(
				() -> order.add("start"),
				() -> order.add("stop"),
				() -> order.add("close"),
				reported::set
		);
		assertEquals(true, started, "successful bridge start is preserved");
		assertEquals(List.of("start"), order, "successful bridge start skips cleanup");
		assertEquals(null, reported.get(), "successful bridge start reports no failure");
		return 3;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected same instance");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
