package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BundledCoordinatorInstaller {
	private static final String RESOURCE_ROOT = "arena-agents/coordinator/";
	private static final String MANIFEST_NAME = "coordinator-manifest.txt";
	private static final String INSTALLED_MANIFEST_NAME = ".arena-agents-bundle-manifest";
	private static final String USER_CONFIG_PATH = "config/dynamic-agents.json";
	private static final String SECRET_PATH = "runtime/bridge-secret.txt";
	private static final int SWAP_ATTEMPTS = 21;
	private static final long SWAP_RETRY_DELAY_MS = 50L;

	private BundledCoordinatorInstaller() {
	}

	static boolean installBundled(Path packageRoot) throws IOException {
		ClassLoader classLoader = BundledCoordinatorInstaller.class.getClassLoader();
		if (classLoader.getResource(RESOURCE_ROOT + MANIFEST_NAME) == null) return false;
		return install(packageRoot, path -> {
			InputStream stream = classLoader.getResourceAsStream(path);
			if (stream == null) throw new IOException("Bundled coordinator resource is missing: " + path);
			return stream;
		});
	}

	/** Installs the embedded coordinator when present, then returns one validated runtime context. */
	static RuntimePackage prepare(Path packageRoot) throws IOException {
		installBundled(packageRoot);
		return validate(packageRoot);
	}

	static RuntimePackage validate(Path packageRoot) throws IOException {
		return validatedPackage(packageRoot);
	}

	static boolean install(Path packageRoot, ResourceSource resources) throws IOException {
		Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
		recoverInterruptedSwap(normalizedRoot);
		String manifest;
		try (InputStream input = resources.open(RESOURCE_ROOT + MANIFEST_NAME)) {
			if (input == null) throw new IOException("Bundled coordinator manifest is missing");
			manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		List<Entry> entries = parseManifest(manifest);
		Path coordinator = normalizedRoot.resolve("coordinator");
		Path installedManifest = coordinator.resolve(INSTALLED_MANIFEST_NAME);
		if (Files.isRegularFile(installedManifest)
				&& Files.readString(installedManifest, StandardCharsets.UTF_8).equals(manifest)
				&& installedFilesMatch(coordinator, entries)) {
			ensureSecret(normalizedRoot.resolve(SECRET_PATH));
			return false;
		}

		Files.createDirectories(normalizedRoot);
		String installId = UUID.randomUUID().toString().replace("-", "");
		Path staging = normalizedRoot.resolve("coordinator.staging-" + installId);
		Path previous = normalizedRoot.resolve("coordinator.previous-" + installId);
		try {
			extract(staging, entries, resources);
			preserveUserConfig(coordinator, staging);
			Files.writeString(staging.resolve(INSTALLED_MANIFEST_NAME), manifest, StandardCharsets.UTF_8);
			boolean hadExisting = Files.exists(coordinator);
			if (hadExisting) moveDirectoryWithRetry(coordinator, previous);
			try {
				moveDirectoryWithRetry(staging, coordinator);
			} catch (IOException exception) {
				if (hadExisting && Files.exists(previous) && !Files.exists(coordinator)) {
					moveDirectoryWithRetry(previous, coordinator);
				}
				throw exception;
			}
			if (hadExisting) deleteTree(previous);
			ensureSecret(normalizedRoot.resolve(SECRET_PATH));
			return true;
		} finally {
			deleteTree(staging);
		}
	}

	private static List<Entry> parseManifest(String manifest) throws IOException {
		List<Entry> entries = new ArrayList<>();
		Set<Path> paths = new HashSet<>();
		for (String line : manifest.lines().toList()) {
			if (line.isBlank()) continue;
			int separator = line.indexOf(' ');
			if (separator != 64 || line.length() <= 65) throw new IOException("Invalid bundled coordinator manifest");
			String hash = line.substring(0, separator);
			if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid bundled coordinator hash");
			String rawPath = line.substring(separator + 1);
			if (rawPath.contains("\\")) throw new IOException("Invalid bundled coordinator path");
			Path path = Path.of(rawPath).normalize();
			if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")
					|| !rawPath.equals(path.toString().replace('\\', '/')) || !paths.add(path)) {
				throw new IOException("Invalid bundled coordinator path");
			}
			entries.add(new Entry(path, hash));
		}
		if (entries.isEmpty()) throw new IOException("Bundled coordinator manifest is empty");
		return List.copyOf(entries);
	}

	private static boolean installedFilesMatch(Path coordinator, List<Entry> entries) throws IOException {
		for (Entry entry : entries) {
			if (entry.path.toString().replace('\\', '/').equals(USER_CONFIG_PATH)) {
				Path config = coordinator.resolve(entry.path).normalize();
				if (!Files.isRegularFile(config)) return false;
				continue;
			}
			Path file = coordinator.resolve(entry.path).normalize();
			if (!file.startsWith(coordinator) || !Files.isRegularFile(file)
					|| !sha256(file).equals(entry.sha256)) return false;
		}
		return true;
	}

	private static void preserveUserConfig(Path coordinator, Path staging) throws IOException {
		Path existing = coordinator.resolve(USER_CONFIG_PATH);
		if (!Files.isRegularFile(existing)) return;
		Path replacement = staging.resolve(USER_CONFIG_PATH);
		Files.createDirectories(replacement.getParent());
		Files.copy(existing, replacement, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void ensureSecret(Path secret) throws IOException {
		if (Files.isRegularFile(secret)) {
			String value = Files.readString(secret, StandardCharsets.UTF_8).trim();
			if (value.length() >= 32) return;
			throw new IOException("Bridge secret is invalid");
		}
		Files.createDirectories(secret.getParent());
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		String value = HexFormat.of().formatHex(bytes);
		try {
			Files.writeString(secret, value, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
		} catch (java.nio.file.FileAlreadyExistsException ignored) {
			String existing = Files.readString(secret, StandardCharsets.UTF_8).trim();
			if (existing.length() < 32) throw new IOException("Bridge secret is invalid", ignored);
		}
	}

	private static RuntimePackage validatedPackage(Path packageRoot) throws IOException {
		Path root = packageRoot.toAbsolutePath().normalize();
		Path coordinator = root.resolve("coordinator").normalize();
		Path main = coordinator.resolve("src/dynamic-main.mjs").normalize();
		Path config = coordinator.resolve(USER_CONFIG_PATH).normalize();
		Path secret = root.resolve(SECRET_PATH).normalize();
		if (!Files.isRegularFile(main) || !Files.isRegularFile(config)) {
			throw new IOException("Coordinator package is incomplete; install the bundled coordinator runtime");
		}
		if (!Files.isRegularFile(secret)) throw new IOException("Bridge secret file is missing");
		String value = Files.readString(secret, StandardCharsets.UTF_8).trim();
		if (value.length() < 32) throw new IOException("Bridge secret is invalid");
		return new RuntimePackage(root, coordinator, main, config, secret);
	}

	private static void recoverInterruptedSwap(Path root) throws IOException {
		Path coordinator = root.resolve("coordinator");
		if (!Files.isDirectory(root)) return;
		if (!Files.exists(coordinator)) {
			try (var paths = Files.list(root)) {
				Path previous = paths.filter(path -> path.getFileName().toString().startsWith("coordinator.previous-"))
						.findFirst().orElse(null);
				if (previous != null) moveDirectoryWithRetry(previous, coordinator);
			}
		}
	}

	private static void moveDirectoryWithRetry(Path source, Path target) throws IOException {
		moveDirectoryWithRetry(source, target, Files::move);
	}

	static void moveDirectoryWithRetry(Path source, Path target, MoveOperation operation) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= SWAP_ATTEMPTS; attempt += 1) {
			try {
				operation.move(source, target);
				return;
			} catch (IOException busy) {
				lastFailure = busy;
				if (attempt == SWAP_ATTEMPTS) break;
				try {
					Thread.sleep(SWAP_RETRY_DELAY_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting to replace the coordinator runtime", interrupted);
				}
			}
		}
		throw lastFailure;
	}

	@FunctionalInterface
	interface MoveOperation {
		void move(Path source, Path target) throws IOException;
	}

	private static void extract(Path staging, List<Entry> entries, ResourceSource resources) throws IOException {
		Files.createDirectories(staging);
		for (Entry entry : entries) {
			Path destination = staging.resolve(entry.path).normalize();
			if (!destination.startsWith(staging)) throw new IOException("Bundled coordinator path escaped staging");
			Files.createDirectories(destination.getParent());
			try (InputStream input = resources.open(RESOURCE_ROOT + entry.path.toString().replace('\\', '/'))) {
				if (input == null) throw new IOException("Bundled coordinator resource is missing: " + entry.path);
				Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!sha256(destination).equals(entry.sha256)) {
				throw new IOException("Bundled coordinator resource hash mismatch: " + entry.path);
			}
		}
	}

	private static String sha256(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[16_384];
			for (int read; (read = input.read(buffer)) >= 0; ) {
				if (read > 0) digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	@FunctionalInterface
	interface ResourceSource {
		InputStream open(String path) throws IOException;
	}

	private record Entry(Path path, String sha256) {
	}

	record RuntimePackage(Path root, Path coordinatorRoot, Path main, Path config, Path secret) {
		RuntimePackage {
			root = root.toAbsolutePath().normalize();
			coordinatorRoot = coordinatorRoot.toAbsolutePath().normalize();
			main = main.toAbsolutePath().normalize();
			config = config.toAbsolutePath().normalize();
			secret = secret.toAbsolutePath().normalize();
		}
	}
}
