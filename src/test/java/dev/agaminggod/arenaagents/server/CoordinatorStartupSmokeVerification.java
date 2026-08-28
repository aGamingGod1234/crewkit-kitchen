package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.Comparator;
import java.util.HashMap;
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
				writeSmokeConfig(packageRoot, bridge.getLocalPort());
				Map<String, String> emptyPath = new HashMap<>();
				emptyPath.put("PATH", "");
				emptyPath.put("APPDATA", fakeAppData.toString());
				emptyPath.put("FISH_AUDIO_API_KEY", "");
				emptyPath.put("FISH_API_KEY", "");
				supervisor = new CoordinatorProcessSupervisor(packageRoot.resolve("game"), emptyPath);
				assertTrue(supervisor.configured(), "staged package is configured");
				assertEquals("http://127.0.0.1:9123/v1/tts", System.getProperty("arenaagents.voiceUrl"),
						"coordinator voice endpoint is shared with the addon before voice startup");
				assertEquals("91234", System.getProperty("arenaagents.voiceRequestTimeoutMs"),
						"coordinator local inference deadline is shared with the addon before voice startup");
				assertEquals(node.toAbsolutePath().normalize(), NodeRuntimeLocator.locate(packageRoot).executable(),
						"bundled runtime is selected before the empty PATH");

				long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (System.currentTimeMillis() < deadline && supervisor.failureCode() == null) {
					supervisor.tick(false);
					if (bridge.getSoTimeout() == 0) bridge.setSoTimeout(250);
					try (Socket socket = bridge.accept()) {
						socket.setSoTimeout(15_000);
						if (completeHandshakeAndCatalog(socket)) return 8;
					} catch (java.net.SocketTimeoutException ignored) {
						// The supervisor's startup grace is intentionally polled without shell state.
					}
					Thread.sleep(100L);
				}
				throw new AssertionError("staged coordinator did not reach the catalog-ready boundary: " + supervisor.failureCode());
			}
		} finally {
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
			restoreProperty("arenaagents.voiceRequestTimeoutMs", oldVoiceRequestTimeout);
			deleteTree(packageRoot);
		}
	}

	private static boolean completeHandshakeAndCatalog(Socket socket) throws Exception {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			String helloLine = reader.readLine();
			JsonObject hello = JsonParser.parseString(helloLine).getAsJsonObject();
			assertEquals("hello", hello.get("type").getAsString(), "coordinator starts with hello");
			JsonObject payload = new JsonObject();
			payload.addProperty("replyTo", hello.get("messageId").getAsString());
			payload.addProperty("authenticated", true);
			payload.add("registry", new JsonArray());
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
		copyTree(source.resolve("src"), root.resolve("coordinator/src"));
		copyTree(source.resolve("node_modules/acorn"), root.resolve("coordinator/node_modules/acorn"));
		Files.createDirectories(root.resolve("coordinator/config"));
		Files.copy(source.resolve("package.json"), root.resolve("coordinator/package.json"), StandardCopyOption.REPLACE_EXISTING);
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

	private static void writeSmokeConfig(Path root, int port) throws IOException {
		String config = """
				{
				  "bridge": { "host": "127.0.0.1", "port": %d, "secretEnvironmentVariable": "ARENA_AGENT_BRIDGE_SECRET", "reconnectDelayMs": 50, "maxReconnectDelayMs": 100 },
				  "codex": { "cwd": "%s", "planningTimeoutMs": 1000, "catalogTtlMs": 60000, "serviceTier": "fast", "launchProfile": { "model": "gpt-5.6-luna", "reasoningEffort": "xhigh", "serviceTier": "fast" } },
				  "voice": { "port": 9123, "maxConcurrent": 1, "localSpeechTimeoutMs": 91234 },
				  "limits": { "agentCap": 1, "goalQueueCap": 1, "planningConcurrency": 1, "planningMode": "fixed", "urgentReserve": 0, "invalidDecisionRetries": 0 }
				}
				""".formatted(port, root.toString().replace("\\", "\\\\"));
		Files.createDirectories(root.resolve("coordinator/config"));
		Files.writeString(root.resolve("coordinator/config/dynamic-agents.json"), config, StandardCharsets.UTF_8);
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
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
