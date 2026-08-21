package dev.agaminggod.arenaagents.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

public final class BundledCoordinatorInstallerVerification {
	private BundledCoordinatorInstallerVerification() {
	}

	public static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyStaleCoordinatorIsReplacedWithoutTouchingRuntimeState();
		assertions += verifyIncompleteBundleLeavesExistingCoordinatorIntact();
		assertions += verifyFreshInstallCreatesSecretAndPreservesProviderConfig();
		assertions += verifyInterruptedSwapRecoversPreviousCoordinator();
		return assertions;
	}

	private static int verifyStaleCoordinatorIsReplacedWithoutTouchingRuntimeState() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install");
		try {
			Path coordinator = packageRoot.resolve("coordinator");
			Files.createDirectories(coordinator.resolve("src"));
			Files.writeString(coordinator.resolve("src/dynamic-main.mjs"), "old main", StandardCharsets.UTF_8);
			Files.writeString(coordinator.resolve("stale-file.mjs"), "stale", StandardCharsets.UTF_8);
			Files.createDirectories(packageRoot.resolve("runtime"));
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			byte[] existingSecret = new byte[64];
			for (int index = 0; index < existingSecret.length; index++) existingSecret[index] = (byte) ('a' + index % 6);
			Files.write(secret, existingSecret);
			String secretFingerprint = sha256(existingSecret);

			String manifest = """
					c3030194b6df5f53b10753e8a19de3af866b5e95c6454d0888be297c91277667 src/dynamic-main.mjs
					fdd8894ff997b79b72253382c82f641840e76b7d5f14fd0ad775cd8cf5d7bae5 package.json
					""";
			Map<String, byte[]> resources = Map.of(
					"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/src/dynamic-main.mjs", "new main".getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/package.json", "{\"name\":\"arena\"}".getBytes(StandardCharsets.UTF_8)
			);

			boolean installed = BundledCoordinatorInstaller.install(packageRoot, resource(resources));
			assertTrue(installed, "stale coordinator triggers a bundled install");
			assertEquals("new main", Files.readString(coordinator.resolve("src/dynamic-main.mjs")),
					"bundled coordinator replaces stale source");
			assertTrue(Files.isRegularFile(coordinator.resolve("package.json")),
					"bundled coordinator installs every manifest entry");
			assertFalse(Files.exists(coordinator.resolve("stale-file.mjs")),
					"directory swap removes files absent from the new bundle");
			assertEquals(secretFingerprint, sha256(Files.readAllBytes(secret)),
					"coordinator refresh preserves runtime state outside its directory");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources)),
					"matching content manifest skips a redundant install");
			return 6;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyIncompleteBundleLeavesExistingCoordinatorIntact() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-failure");
		try {
			Path existingMain = packageRoot.resolve("coordinator/src/dynamic-main.mjs");
			Files.createDirectories(existingMain.getParent());
			Files.writeString(existingMain, "working old main", StandardCharsets.UTF_8);
			String manifest = """
					c3030194b6df5f53b10753e8a19de3af866b5e95c6454d0888be297c91277667 src/dynamic-main.mjs
					fdd8894ff997b79b72253382c82f641840e76b7d5f14fd0ad775cd8cf5d7bae5 package.json
					""";
			Map<String, byte[]> incomplete = Map.of(
					"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/src/dynamic-main.mjs", "new main".getBytes(StandardCharsets.UTF_8)
			);
			try {
				BundledCoordinatorInstaller.install(packageRoot, resource(incomplete));
				throw new AssertionError("incomplete coordinator bundle must fail installation");
			} catch (IOException expected) {
				assertEquals("working old main", Files.readString(existingMain),
						"failed extraction preserves the complete existing coordinator");
			}
			return 1;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyFreshInstallCreatesSecretAndPreservesProviderConfig() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-fresh");
		try {
			byte[] initialMain = "main v1".getBytes(StandardCharsets.UTF_8);
			byte[] initialConfig = "{\"codex\":{\"model\":\"user-model\"}}".getBytes(StandardCharsets.UTF_8);
			String initialManifest = manifest(
					entry("src/dynamic-main.mjs", initialMain),
					entry("config/dynamic-agents.json", initialConfig));
			Map<String, byte[]> initialResources = resources(initialManifest, initialMain, initialConfig);

			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(initialResources)),
					"fresh JAR install extracts the bundled coordinator");
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			assertTrue(Files.isRegularFile(secret), "fresh JAR install creates the shared bridge secret");
			String secretFingerprint = sha256(Files.readAllBytes(secret));
			assertTrue(Files.readString(secret, StandardCharsets.UTF_8).trim().length() >= 32,
					"fresh shared bridge secret is bounded and usable");
			Path config = packageRoot.resolve("coordinator/config/dynamic-agents.json");
			Files.writeString(config, "{\"codex\":{\"model\":\"my-custom-model\"}}", StandardCharsets.UTF_8);

			byte[] upgradedMain = "main v2".getBytes(StandardCharsets.UTF_8);
			byte[] upgradedConfig = "{\"codex\":{\"model\":\"new-default\"}}".getBytes(StandardCharsets.UTF_8);
			String upgradedManifest = manifest(
					entry("src/dynamic-main.mjs", upgradedMain),
					entry("config/dynamic-agents.json", upgradedConfig));
			Map<String, byte[]> upgradedResources = resources(upgradedManifest, upgradedMain, upgradedConfig);
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(upgradedResources)),
					"changed bundled code triggers a coordinator upgrade");
			assertEquals("{\"codex\":{\"model\":\"my-custom-model\"}}",
					Files.readString(config, StandardCharsets.UTF_8),
					"user provider config survives a bundled coordinator upgrade");
			assertEquals(secretFingerprint, sha256(Files.readAllBytes(secret)),
					"coordinator upgrade keeps the one shared bridge secret");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(upgradedResources)),
					"matching coordinator upgrade is idempotent after preserving custom config");
			return 6;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyInterruptedSwapRecoversPreviousCoordinator() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-recovery");
		try {
			Path previous = packageRoot.resolve("coordinator.previous-crash");
			Path previousConfig = previous.resolve("config/dynamic-agents.json");
			Files.createDirectories(previous.resolve("src"));
			Files.createDirectories(previousConfig.getParent());
			Files.writeString(previous.resolve("src/dynamic-main.mjs"), "working old main", StandardCharsets.UTF_8);
			Files.writeString(previousConfig, "{\"codex\":{\"model\":\"crash-safe-custom\"}}", StandardCharsets.UTF_8);
			String manifest = manifest(
					entry("src/dynamic-main.mjs", "new main"),
					entry("config/dynamic-agents.json", "new default"));
			Map<String, byte[]> resources = resources(manifest,
					"new main".getBytes(StandardCharsets.UTF_8),
					"new default".getBytes(StandardCharsets.UTF_8));

			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources)),
					"an interrupted swap is recovered before installing the new coordinator");
			assertEquals("new main", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"recovered swap installs the new coordinator code");
			assertEquals("{\"codex\":{\"model\":\"crash-safe-custom\"}}",
					Files.readString(packageRoot.resolve("coordinator/config/dynamic-agents.json")),
					"recovered swap retains the user provider config");
			assertFalse(Files.exists(previous), "completed recovery cleans the old previous directory");
			return 4;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static String manifest(EntryData... entries) {
		return java.util.Arrays.stream(entries)
				.sorted(java.util.Comparator.comparing(EntryData::path))
				.map(entry -> entry.hash() + " " + entry.path() + "\n")
				.collect(java.util.stream.Collectors.joining());
	}

	private static EntryData entry(String path, byte[] content) {
		return new EntryData(path, sha256(content));
	}

	private static EntryData entry(String path, String content) {
		return entry(path, content.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, byte[]> resources(String manifest, byte[] main, byte[] config) {
		return Map.of(
				"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
				"arena-agents/coordinator/src/dynamic-main.mjs", main,
				"arena-agents/coordinator/config/dynamic-agents.json", config);
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private static BundledCoordinatorInstaller.ResourceSource resource(Map<String, byte[]> resources) {
		return path -> {
			byte[] value = resources.get(path);
			if (value == null) throw new IOException("missing test resource " + path);
			return new ByteArrayInputStream(value);
		};
	}

	private record EntryData(String path, String hash) {
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
