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
		String oldVoiceRequestTimeout = System.getProperty("arenaagents.voiceRequestTimeoutMs");
		CoordinatorProcessSupervisor supervisor = null;
		try {
			stageCoordinator(sourceCoordinator, packageRoot);
			Path node = stageBundledNode(packageRoot);
			Path fakeAppData = stageFakeCodex(packageRoot);
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			Files.createDirectories(secret.getParent());
			Files.writeString(secret, "s".repeat(32), StandardCharsets.UTF_8);
			System.setProperty("arenaagents.packageRoot", packageRoot.toString());
			System.clearProperty(NodeRuntimeLocator.PROPERTY);
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.clearProperty("arenaagents.voiceSecretFile");
			System.clearProperty("arenaagents.voiceUrl");
			System.clearProperty("arenaagents.voiceRequestTimeoutMs");

			try (ServerSocket bridge = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				int voicePort = unusedLoopbackPort();
				writeSmokeConfig(packageRoot, bridge.getLocalPort(), voicePort);
				Map<String, String> emptyPath = new HashMap<>();
				emptyPath.put("PATH", "");
				emptyPath.put("APPDATA", fakeAppData.toString());
				emptyPath.put("FISH_AUDIO_API_KEY", "");
				emptyPath.put("FISH_API_KEY", "");
				supervisor = new CoordinatorProcessSupervisor(packageRoot.resolve("game"), emptyPath);
				long configurationDeadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (!supervisor.configured() && System.currentTimeMillis() < configurationDeadline) {
					supervisor.tick(false);
					Thread.sleep(10L);
				}
				assertTrue(supervisor.configured(), "staged package is configured");
<<<<<<< HEAD
				assertEquals(bridge.getLocalPort(), supervisor.bridgePort(),
						"production supervisor publishes the nondefault coordinator bridge port");
				assertEquals("http://127.0.0.1:" + voicePort + "/v1/tts", System.getProperty("arenaagents.voiceUrl"),
						"coordinator shares the existing optional voice endpoint before startup");
=======
				assertEquals("http://127.0.0.1:9123/v1/tts", System.getProperty("arenaagents.voiceUrl"),
						"coordinator voice endpoint is shared with the addon before voice startup");
				assertEquals("91234", System.getProperty("arenaagents.voiceRequestTimeoutMs"),
						"coordinator local inference deadline is shared with the addon before voice startup");
>>>>>>> origin/main
				assertEquals(node.toAbsolutePath().normalize(), NodeRuntimeLocator.locate(packageRoot).executable(),
						"bundled runtime is selected before the empty PATH");

				long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (System.currentTimeMillis() < deadline && supervisor.failureCode() == null) {
					supervisor.tick(false);
					if (bridge.getSoTimeout() == 0) bridge.setSoTimeout(250);
					try (Socket socket = bridge.accept()) {
						socket.setSoTimeout(15_000);
<<<<<<< HEAD
						if (completeHandshakeAndCatalog(socket)) return 10;
=======
						if (completeHandshakeAndCatalog(socket)) return 8;
>>>>>>> origin/main
					} catch (java.net.SocketTimeoutException ignored) {
						// The supervisor's startup grace is intentionally polled without shell state.
					}
					Thread.sleep(100L);
				}
				throw new AssertionError("staged coordinator did not reach the catalog-ready boundary: " + supervisor.failureCode());
			}
		} finally {
<<<<<<< HEAD
			AssertionError ownershipFailure = null;
			try {
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
=======
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
			restoreProperty("arenaagents.voiceRequestTimeoutMs", oldVoiceRequestTimeout);
			deleteTree(packageRoot);
>>>>>>> origin/main
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
			boolean discoveryRequested = false;
			boolean stagedModelReady = false;
			long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
			while (System.currentTimeMillis() < deadline) {
				String line = reader.readLine();
				if (line == null) break;
				JsonObject message = JsonParser.parseString(line).getAsJsonObject();
				if (!"catalog_snapshot".equals(message.get("type").getAsString())) continue;
				JsonArray models = message.getAsJsonObject("payload").getAsJsonArray("models");
				if (models.toString().contains("gpt-5.6-luna")) {
					stagedModelReady = true;
					break;
				}
				if (discoveryRequested) continue;
				discoveryRequested = true;
				writer.write(codec.encode(new BridgeEnvelope(
						2,
						"startup-smoke-server",
						"server",
						"catalog_request",
						"startup-smoke-catalog-request",
						new JsonObject()
				)));
				writer.flush();
			}
			assertTrue(stagedModelReady, "catalog-ready boundary contains the staged Codex model");
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
<<<<<<< HEAD
				  "voice": { "port": %d, "maxConcurrent": 1 },
=======
				  "voice": { "port": 9123, "maxConcurrent": 1, "localSpeechTimeoutMs": 91234 },
>>>>>>> origin/main
				  "limits": { "agentCap": 1, "goalQueueCap": 1, "planningConcurrency": 1, "planningMode": "fixed", "urgentReserve": 0, "invalidDecisionRetries": 0 }
				}
				""".formatted(port, root.toString().replace("\\", "\\\\"), voicePort);
		Files.createDirectories(root.resolve("runtime"));
		Files.writeString(root.resolve("runtime/dynamic-agents.json"), config, StandardCharsets.UTF_8);
	}

	private static int unusedLoopbackPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
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
<<<<<<< HEAD
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
=======
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) deleteEventually(path);
		}
	}

	private static void deleteEventually(Path path) throws IOException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);
		while (true) {
			try {
				Files.deleteIfExists(path);
				return;
			} catch (java.nio.file.AccessDeniedException exception) {
				if (!isWindows() || System.nanoTime() >= deadline) throw exception;
				try {
					Thread.sleep(25L);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for Windows to release " + path, interrupted);
>>>>>>> origin/main
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
