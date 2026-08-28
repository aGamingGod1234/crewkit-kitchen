package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Starts one staged coordinator with an isolated, empty PATH and validates its first catalog. */
public final class CoordinatorStartupSmokeVerification {
	private static final long STARTUP_TIMEOUT_MS = 15_000L;

	private CoordinatorStartupSmokeVerification() {
	}

	public static int verify() throws Exception {
		Path sourceCoordinator = Path.of("coordinator").toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceCoordinator.resolve("src/dynamic-main.mjs"))) {
			throw new AssertionError("startup smoke requires the coordinator source package");
		}
		Path packageRoot = Files.createTempDirectory("arena-startup-smoke");
		String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
		String oldNodePath = System.getProperty(NodeRuntimeLocator.PROPERTY);
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldVoiceUrl = System.getProperty("arenaagents.voiceUrl");
		CoordinatorProcessSupervisor supervisor = null;
		CoordinatorProcessSupervisor invalidVoiceSupervisor = null;
		try {
			stageCoordinator(sourceCoordinator, packageRoot);
			int credentialAssertions = verifyOptionalVoiceCredential(packageRoot.resolve("credential-test"));
			Path node = stageBundledNode(packageRoot);
			Path fakeAppData = stageFakeCodex(packageRoot);
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			Files.createDirectories(secret.getParent());
			Files.writeString(secret, "s".repeat(32), StandardCharsets.UTF_8);
			Files.writeString(
					packageRoot.resolve("runtime/fish-api-key.txt"),
					"test-fish-api-key",
					StandardCharsets.UTF_8
			);
			System.setProperty("arenaagents.packageRoot", packageRoot.toString());
			System.clearProperty(NodeRuntimeLocator.PROPERTY);
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.clearProperty("arenaagents.voiceSecretFile");
			System.clearProperty("arenaagents.voiceUrl");

			try (ServerSocket bridge = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				int voicePort = unusedLoopbackPort();
				writeSmokeConfig(packageRoot, bridge.getLocalPort(), voicePort);
				Map<String, String> emptyPath = new HashMap<>();
				emptyPath.put("PATH", "");
				emptyPath.put("APPDATA", fakeAppData.toString());
				emptyPath.put("FISH_AUDIO_API_KEY", "");
				emptyPath.put("FISH_API_KEY", "");
				Map<String, String> invalidVoice = new HashMap<>(emptyPath);
				invalidVoice.put("ARENA_AGENT_VOICE_PORT", "invalid");
				invalidVoiceSupervisor = new CoordinatorProcessSupervisor(
						packageRoot.resolve("invalid-voice-game"),
						invalidVoice,
						System::currentTimeMillis,
						null,
						null,
						() -> "00000000-0000-0000-0000-000000000303",
						Runnable::run,
						runtimeRoot -> 0
				);
				assertTrue(invalidVoiceSupervisor.configured(),
						"invalid optional voice endpoint preserves the prepared coordinator runtime");
				assertEquals(secret.toAbsolutePath().normalize(), invalidVoiceSupervisor.secretPath(),
						"invalid optional voice endpoint preserves the validated bridge secret");
				invalidVoiceSupervisor.close();
				invalidVoiceSupervisor = null;
				System.clearProperty("arenaagents.voiceUrl");
				supervisor = new CoordinatorProcessSupervisor(packageRoot.resolve("game"), emptyPath);
				long configurationDeadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (!supervisor.configured() && System.currentTimeMillis() < configurationDeadline) {
					supervisor.tick(false);
					Thread.sleep(10L);
				}
				assertTrue(supervisor.configured(), "staged package is configured");
				assertEquals("http://127.0.0.1:" + voicePort + "/v1/tts",
						supervisor.voiceConfiguration().endpoint(),
						"coordinator publishes the prepared voice endpoint directly before voice startup");
				assertEquals("s".repeat(32), supervisor.voiceConfiguration().secret(),
						"coordinator publishes the validated in-memory voice secret directly");
				assertEquals(node.toAbsolutePath().normalize(), NodeRuntimeLocator.locate(packageRoot).executable(),
						"bundled runtime is selected before the empty PATH");

				long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (System.currentTimeMillis() < deadline && supervisor.failureCode() == null) {
					supervisor.tick(false);
					if (bridge.getSoTimeout() == 0) bridge.setSoTimeout(250);
					try (Socket socket = bridge.accept()) {
						socket.setSoTimeout(15_000);
						if (completeHandshakeAndCatalog(socket)) {
							assertTrue(awaitLoopbackListener(voicePort, 5_000L),
									"runtime Fish credential starts the loopback voice worker");
							return 10 + credentialAssertions;
						}
					} catch (java.net.SocketTimeoutException ignored) {
						// The supervisor's startup grace is intentionally polled without shell state.
					}
					Thread.sleep(100L);
				}
				throw new AssertionError("staged coordinator did not reach the catalog-ready boundary: " + supervisor.failureCode());
			}
		} finally {
			AssertionError ownershipFailure = null;
			try {
				if (invalidVoiceSupervisor != null) invalidVoiceSupervisor.close();
				if (supervisor != null) {
					supervisor.close();
					try {
						long ownershipDeadline = System.currentTimeMillis() + 5_000L;
						while (Files.exists(CoordinatorProcessOwnership.ownershipFile(packageRoot))
								&& System.currentTimeMillis() < ownershipDeadline) {
							Thread.sleep(25L);
						}
						assertTrue(!Files.exists(CoordinatorProcessOwnership.ownershipFile(packageRoot)),
								"coordinator close clears the ownership record");
					} catch (AssertionError failure) {
						ownershipFailure = failure;
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						ownershipFailure = new AssertionError("ownership cleanup wait was interrupted", interrupted);
					}
				}
			} finally {
				restoreProperty("arenaagents.packageRoot", oldPackageRoot);
				restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
				restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
				restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
				restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
				deleteTree(packageRoot);
			}
			if (ownershipFailure != null) throw ownershipFailure;
		}
	}

	private static boolean completeHandshakeAndCatalog(Socket socket) throws Exception {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			String helloLine = reader.readLine();
			JsonObject hello = JsonParser.parseString(helloLine).getAsJsonObject();
			assertEquals("hello", hello.get("type").getAsString(), "coordinator starts with hello");
			String launchId = hello.getAsJsonObject("payload").get("launchId").getAsString();
			java.util.UUID.fromString(launchId);
			JsonObject payload = new JsonObject();
			payload.addProperty("replyTo", hello.get("messageId").getAsString());
			payload.addProperty("authenticated", true);
			payload.add("registry", new JsonArray());
			payload.addProperty("launchId", launchId);
			writer.write(codec.encode(new BridgeEnvelope(
					2,
					"startup-smoke-server",
					"server",
					"hello_ack",
					"startup-smoke-ack",
					payload
			)));
			writer.flush();
			String catalogLine = reader.readLine();
			JsonObject catalog = JsonParser.parseString(catalogLine).getAsJsonObject();
			assertEquals("catalog_snapshot", catalog.get("type").getAsString(), "coordinator publishes catalog after handshake");
			assertTrue(catalog.getAsJsonObject("payload").getAsJsonArray("models").toString().contains("gpt-5.6-luna"),
				"catalog-ready boundary contains the staged Codex model");
			return true;
		}
	}

	private static void stageCoordinator(Path source, Path root) throws IOException {
		String manifest = coordinatorManifest(source);
		BundledCoordinatorInstaller.install(root, resourcePath -> {
			String prefix = "arena-agents/coordinator/";
			if (!resourcePath.startsWith(prefix)) throw new IOException("Unexpected coordinator resource: " + resourcePath);
			String relative = resourcePath.substring(prefix.length());
			if (relative.equals("coordinator-manifest.txt")) {
				return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
			}
			Path file = source.resolve(relative).normalize();
			if (!file.startsWith(source) || !Files.isRegularFile(file)) {
				throw new IOException("Missing coordinator fixture resource: " + relative);
			}
			return Files.newInputStream(file);
		});
	}

	private static String coordinatorManifest(Path source) throws IOException {
		List<Path> files = new ArrayList<>();
		for (String fixed : List.of("package.json", "package-lock.json")) {
			Path file = source.resolve(fixed);
			if (Files.isRegularFile(file)) files.add(file);
		}
		for (String directory : List.of("config", "src", "node_modules/acorn")) {
			try (var paths = Files.walk(source.resolve(directory))) {
				paths.filter(Files::isRegularFile).forEach(files::add);
			}
		}
		files.sort(Comparator.comparing(file -> source.relativize(file).toString().replace('\\', '/')));
		StringBuilder manifest = new StringBuilder();
		for (Path file : files) {
			manifest.append(sha256(Files.readAllBytes(file))).append(' ')
					.append(source.relativize(file).toString().replace('\\', '/')).append('\n');
		}
		return manifest.toString();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static Path stageBundledNode(Path root) throws IOException {
		Path hostNode = findHostNode();
		Path bundled = isWindows()
				? root.resolve("runtime/toolchains/node/node.exe")
				: root.resolve("runtime/toolchains/node/bin/node");
		Files.createDirectories(bundled.getParent());
		try {
			Files.createLink(bundled, hostNode);
		} catch (IOException linkUnavailable) {
			// The fallback is temporary test state only; no runtime binary is stored in git or the release archive.
			Files.copy(hostNode, bundled, StandardCopyOption.REPLACE_EXISTING);
		}
		return bundled;
	}

	private static Path stageFakeCodex(Path root) throws IOException {
		Path entrypoint = root.resolve("fake-appdata/npm/node_modules/@openai/codex/bin/codex.js");
		Files.createDirectories(entrypoint.getParent());
		Files.writeString(entrypoint, """
				import readline from 'node:readline';
				const input = readline.createInterface({ input: process.stdin });
				input.on('line', (line) => {
					const request = JSON.parse(line);
					if (!Object.hasOwn(request, 'id')) return;
					const result = request.method === 'model/list'
						? { data: [{ id: 'gpt-5.6-luna', model: 'gpt-5.6-luna', displayName: 'Smoke model', supportedReasoningEfforts: ['xhigh'], serviceTiers: ['fast'] }] }
						: {};
					process.stdout.write(JSON.stringify({ id: request.id, result }) + '\\n');
				});
				""", StandardCharsets.UTF_8);
		return root.resolve("fake-appdata");
	}

	private static void writeSmokeConfig(Path root, int port, int voicePort) throws IOException {
		String config = """
				{
				  "bridge": { "host": "127.0.0.1", "port": %d, "secretEnvironmentVariable": "ARENA_AGENT_BRIDGE_SECRET", "reconnectDelayMs": 50, "maxReconnectDelayMs": 100 },
				  "codex": { "cwd": "%s", "planningTimeoutMs": 1000, "catalogTtlMs": 60000, "serviceTier": "fast", "launchProfile": { "model": "gpt-5.6-luna", "reasoningEffort": "xhigh", "serviceTier": "fast" } },
				  "voice": { "port": %d, "maxConcurrent": 1 },
				  "limits": { "agentCap": 1, "goalQueueCap": 1, "planningConcurrency": 1, "planningMode": "fixed", "urgentReserve": 0, "invalidDecisionRetries": 0 }
				}
				""".formatted(port, root.toString().replace("\\", "\\\\"), voicePort);
		Files.createDirectories(root.resolve("runtime"));
		Files.writeString(root.resolve("runtime/dynamic-agents.json"), config, StandardCharsets.UTF_8);
	}

	private static int verifyOptionalVoiceCredential(Path runtimeRoot) throws IOException {
		Path credential = runtimeRoot.resolve("runtime/fish-api-key.txt");
		Files.createDirectories(credential.getParent());
		Files.writeString(credential, "bad", StandardCharsets.UTF_8);
		Map<String, String> environment = new HashMap<>();
		CoordinatorProcessSupervisor.configureVoiceProviderCredential(runtimeRoot, environment);
		assertTrue(!environment.containsKey("FISH_AUDIO_API_KEY"),
				"malformed optional TTS credential is ignored");
		Files.writeString(credential, "valid-test-fish-key", StandardCharsets.UTF_8);
		CoordinatorProcessSupervisor.configureVoiceProviderCredential(runtimeRoot, environment);
		assertEquals("valid-test-fish-key", environment.get("FISH_AUDIO_API_KEY"),
				"valid optional TTS credential is injected");
		return 2;
	}

	private static int unusedLoopbackPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static boolean awaitLoopbackListener(int port, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
				return true;
			} catch (IOException unavailable) {
				Thread.sleep(25L);
			}
		}
		return false;
	}

	private static Path findHostNode() throws IOException {
		String path = System.getenv("PATH");
		String executableName = isWindows() ? "node.exe" : "node";
		if (path != null) {
			for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
				if (entry.isBlank()) continue;
				Path candidate = Path.of(entry).resolve(executableName).toAbsolutePath().normalize();
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
			}
		}
		throw new IOException("STARTUP_SMOKE_NODE_MISSING: Node 22+ is required to create the temporary bundled-runtime hardlink");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static void copyTree(Path source, Path target) throws IOException {
		if (!Files.isDirectory(source)) throw new IOException("Startup smoke source tree is missing: " + source);
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path destination = target.resolve(source.relativize(path));
				if (Files.isDirectory(path)) Files.createDirectories(destination);
				else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		for (int attempt = 0; attempt < 20; attempt += 1) {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
				return;
			} catch (java.nio.file.AccessDeniedException busyExecutable) {
				if (attempt == 19) throw busyExecutable;
				try {
					Thread.sleep(50L);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while cleaning the startup fixture", interrupted);
				}
			}
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
