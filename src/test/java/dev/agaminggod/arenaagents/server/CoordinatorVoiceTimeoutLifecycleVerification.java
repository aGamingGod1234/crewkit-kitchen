package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Verifies that coordinator-derived voice deadlines follow one server lifecycle. */
public final class CoordinatorVoiceTimeoutLifecycleVerification {
	private static final String AUTO_START_PROPERTY = "arenaagents.coordinatorAutoStart";
	private static final String VOICE_URL_PROPERTY = "arenaagents.voiceUrl";
	private static final String TIMEOUT_PROPERTY = "arenaagents.voiceRequestTimeoutMs";

	private CoordinatorVoiceTimeoutLifecycleVerification() {
	}

	public static int verify() throws Exception {
		String oldAutoStart = System.getProperty(AUTO_START_PROPERTY);
		String oldVoiceUrl = System.getProperty(VOICE_URL_PROPERTY);
		String oldTimeout = System.getProperty(TIMEOUT_PROPERTY);
		Path directory = Files.createTempDirectory("arena-voice-timeout-lifecycle");
		try {
			System.setProperty(AUTO_START_PROPERTY, "false");
			System.setProperty(VOICE_URL_PROPERTY, "http://127.0.0.1:9123/v1/tts");
			Path firstConfig = writeConfig(directory.resolve("first.json"), 12_345);
			Path secondConfig = writeConfig(directory.resolve("second.json"), 54_321);

			System.clearProperty(TIMEOUT_PROPERTY);
			try (CoordinatorProcessSupervisor first = supervisor(directory.resolve("first-game"))) {
				first.configureSharedVoiceEndpoint(firstConfig);
				assertEquals("12345", System.getProperty(TIMEOUT_PROPERTY),
						"first server derives its configured timeout");
			}
			assertEquals(null, System.getProperty(TIMEOUT_PROPERTY),
					"first server releases its derived timeout");

			try (CoordinatorProcessSupervisor second = supervisor(directory.resolve("second-game"))) {
				second.configureSharedVoiceEndpoint(secondConfig);
				assertEquals("54321", System.getProperty(TIMEOUT_PROPERTY),
						"second server derives the updated timeout");
			}
			assertEquals(null, System.getProperty(TIMEOUT_PROPERTY),
					"second server releases its derived timeout");

			System.setProperty(TIMEOUT_PROPERTY, "77777");
			try (CoordinatorProcessSupervisor explicit = supervisor(directory.resolve("explicit-game"))) {
				explicit.configureSharedVoiceEndpoint(firstConfig);
				assertEquals("77777", System.getProperty(TIMEOUT_PROPERTY),
						"an explicit timeout overrides the derived configuration");
			}
			assertEquals("77777", System.getProperty(TIMEOUT_PROPERTY),
					"closing a supervisor preserves an explicit timeout");

			System.setProperty(TIMEOUT_PROPERTY, " ");
			try (CoordinatorProcessSupervisor blank = supervisor(directory.resolve("blank-game"))) {
				blank.configureSharedVoiceEndpoint(firstConfig);
				assertEquals("12345", System.getProperty(TIMEOUT_PROPERTY),
						"a blank inherited timeout is replaced for the active server");
			}
			assertEquals(" ", System.getProperty(TIMEOUT_PROPERTY),
					"closing restores the inherited blank value");

			System.clearProperty(TIMEOUT_PROPERTY);
			try (CoordinatorProcessSupervisor changed = supervisor(directory.resolve("changed-game"))) {
				changed.configureSharedVoiceEndpoint(firstConfig);
				assertEquals("12345", System.getProperty(TIMEOUT_PROPERTY),
						"the supervisor owns the value it derived");
				System.setProperty(TIMEOUT_PROPERTY, "88888");
			}
			assertEquals("88888", System.getProperty(TIMEOUT_PROPERTY),
					"closing does not clobber an override installed during the server session");
			return 10;
		} finally {
			restoreProperty(AUTO_START_PROPERTY, oldAutoStart);
			restoreProperty(VOICE_URL_PROPERTY, oldVoiceUrl);
			restoreProperty(TIMEOUT_PROPERTY, oldTimeout);
			deleteTree(directory);
		}
	}

	private static CoordinatorProcessSupervisor supervisor(Path gameDirectory) {
		return new CoordinatorProcessSupervisor(gameDirectory, Map.of());
	}

	private static Path writeConfig(Path path, int timeoutMs) throws Exception {
		Files.writeString(path, "{\"voice\":{\"localSpeechTimeoutMs\":" + timeoutMs + "}}", StandardCharsets.UTF_8);
		return path;
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
