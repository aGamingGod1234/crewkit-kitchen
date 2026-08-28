package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Verifies ownership and refresh of the voice URL published by coordinator configuration. */
public final class CoordinatorVoiceEndpointRefreshVerification {
	private CoordinatorVoiceEndpointRefreshVerification() {
	}

	public static int verify() throws Exception {
		String oldAutoStart = System.getProperty("arenaagents.coordinatorAutoStart");
		String oldVoiceUrl = System.getProperty("arenaagents.voiceUrl");
		Path config = Files.createTempFile("arena-supervisor-voice-refresh-", ".json");
		CoordinatorProcessSupervisor managed = null;
		CoordinatorProcessSupervisor overridden = null;
		try {
			System.setProperty("arenaagents.coordinatorAutoStart", "true");
			System.clearProperty("arenaagents.voiceUrl");
			Files.writeString(config, "{\"voice\":{\"port\":18766}}", StandardCharsets.UTF_8);
			MutableResolver managedResolver = new MutableResolver(config, "managed-initial");
			managed = supervisor(managedResolver, "00000000-0000-0000-0000-000000000801");

			assertEquals("http://127.0.0.1:18766/v1/tts", System.getProperty("arenaagents.voiceUrl"),
					"initial dependency preparation publishes the config-derived voice endpoint");
			long initialVoiceRevision = managed.voiceConfigurationRevision();
			long initialBridgeRevision = managed.bridgeRevision();
			assertTrue(initialVoiceRevision > 0L,
					"initial managed endpoint publication advances the voice configuration revision");

			Files.writeString(config, "{\"voice\":{\"port\":18767}}", StandardCharsets.UTF_8);
			managedResolver.fingerprint = "managed-config-only-change";
			managed.publishDependencyFingerprintChange();
			managed.tick(false, null, 0L);
			assertEquals("http://127.0.0.1:18767/v1/tts", System.getProperty("arenaagents.voiceUrl"),
					"config-only dependency changes replace the supervisor-managed voice endpoint");
			assertTrue(managed.voiceConfigurationRevision() > initialVoiceRevision,
					"config-only endpoint changes advance the voice configuration revision");
			assertEquals(initialBridgeRevision, managed.bridgeRevision(),
					"voice-only changes do not restart the authenticated bridge listener");

			long refreshedRevision = managed.voiceConfigurationRevision();
			managed.publishDependencyFingerprintChange();
			managed.tick(false, null, 0L);
			assertEquals(refreshedRevision, managed.voiceConfigurationRevision(),
					"an unchanged resolved endpoint does not recreate the voice client");
			managed.close();
			managed = null;
			assertEquals(null, System.getProperty("arenaagents.voiceUrl"),
					"supervisor shutdown releases its managed endpoint before a same-JVM restart");

			String explicitOverride = "http://127.0.0.1:19999/v1/tts";
			System.setProperty("arenaagents.voiceUrl", explicitOverride);
			MutableResolver overrideResolver = new MutableResolver(config, "override-initial");
			overridden = supervisor(overrideResolver, "00000000-0000-0000-0000-000000000802");
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"initial preparation leaves an explicit user voice endpoint untouched");
			long overrideRevision = overridden.voiceConfigurationRevision();

			Files.writeString(config, "{\"voice\":{\"port\":18768}}", StandardCharsets.UTF_8);
			overrideResolver.fingerprint = "explicit-override-config-change";
			overridden.publishDependencyFingerprintChange();
			overridden.tick(false, null, 0L);
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"config changes never replace an explicit user voice endpoint override");
			assertEquals(overrideRevision, overridden.voiceConfigurationRevision(),
					"ignored config endpoints do not advance the explicit override revision");
			overridden.close();
			overridden = null;
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"supervisor shutdown leaves an explicit user voice endpoint untouched");
			return 11;
		} finally {
			if (managed != null) managed.close();
			if (overridden != null) overridden.close();
			restoreProperty("arenaagents.coordinatorAutoStart", oldAutoStart);
			restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
			Files.deleteIfExists(config);
		}
	}

	private static CoordinatorProcessSupervisor supervisor(MutableResolver resolver, String launchId) {
		return new CoordinatorProcessSupervisor(
				Path.of("build", "voice-endpoint-refresh-game"), Map.of(), () -> 100_000L, resolver,
				request -> { throw new AssertionError("voice endpoint refresh must not launch before startup grace"); },
				() -> launchId, Runnable::run, runtimeRoot -> 0, task -> { }
		);
	}

	private static final class MutableResolver implements CoordinatorProcessSupervisor.DependencyResolver {
		private final CoordinatorProcessSupervisor.PreparedRuntime runtime;
		private String fingerprint;

		private MutableResolver(Path config, String fingerprint) {
			this.runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
					Path.of("build", "voice-endpoint-runtime"),
					Path.of("build", "voice-endpoint-runtime", "coordinator"),
					Path.of("build", "voice-endpoint-runtime", "coordinator", "src", "dynamic-main.mjs"),
					config,
					Path.of("build", "voice-endpoint-runtime", "runtime", "bridge-secret.txt"),
					Path.of("build", "voice-endpoint-runtime", "runtime", "toolchains", "node", "node.exe"),
					"v".repeat(32)
			);
			this.fingerprint = fingerprint;
		}

		@Override
		public String fingerprint() {
			return fingerprint;
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			return CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
