package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the one Node executable owned by a coordinator launch. */
final class NodeRuntimeLocator {
	static final String PROPERTY = "arenaagents.nodePath";
	private static final int MIN_MAJOR_VERSION = 22;
	private static final int MAX_VERSION_OUTPUT_BYTES = 512;
	private static final Duration VERSION_PROBE_TIMEOUT = Duration.ofSeconds(2);
	private static final Pattern VERSION = Pattern.compile("^v?(\\d+)(?:\\..*)?\\s*$");

	private NodeRuntimeLocator() {
	}

	static LocatedNode locate(Path packageRoot) {
		return locate(
				packageRoot,
				System.getProperty(PROPERTY),
				NodeRuntimeLocator::probeVersion
		);
	}

	static LocatedNode locate(Path packageRoot, String explicitPath, Probe probe) {
		Path root = requireRoot(packageRoot);
		String configured = explicitPath == null ? "" : explicitPath.trim();
		if (!configured.isEmpty()) {
			Path candidate;
			try {
				candidate = Path.of(configured);
			} catch (RuntimeException exception) {
				throw failure("NODE_RUNTIME_EXPLICIT_INVALID",
						"arenaagents.nodePath must name an absolute Node 22+ executable", exception);
			}
			if (!candidate.isAbsolute()) {
				throw failure("NODE_RUNTIME_EXPLICIT_INVALID",
						"arenaagents.nodePath must name an absolute Node 22+ executable");
			}
			return inspect(candidate.toAbsolutePath().normalize(), Source.EXPLICIT_PROPERTY, probe);
		}

		Path bundled = bundledCandidate(root);
		if (Files.exists(bundled)) {
			return inspect(bundled, Source.BUNDLED_PROFILE, probe);
		}
		throw failure("NODE_RUNTIME_NOT_FOUND",
				"Node.js 22+ was not found; set -Darenaagents.nodePath to an absolute executable "
						+ "or install/ship the bundled profile runtime");
	}

	private static Path requireRoot(Path packageRoot) {
		if (packageRoot == null) throw new IllegalArgumentException("package root must not be null");
		return packageRoot.toAbsolutePath().normalize();
	}

	private static Path bundledCandidate(Path packageRoot) {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		return os.contains("win")
				? packageRoot.resolve("runtime/toolchains/node/node.exe")
				: packageRoot.resolve("runtime/toolchains/node/bin/node");
	}

	private static LocatedNode inspect(Path candidate, Source source, Probe probe) {
		if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
			String code = source == Source.EXPLICIT_PROPERTY
					? "NODE_RUNTIME_EXPLICIT_INVALID"
					: "NODE_RUNTIME_BUNDLED_INVALID";
			throw failure(code, remediation(source) + ": " + candidate);
		}
		String version;
		try {
			version = probe.version(candidate);
		} catch (Exception exception) {
			String code = source == Source.EXPLICIT_PROPERTY
					? "NODE_RUNTIME_EXPLICIT_INVALID"
					: "NODE_RUNTIME_BUNDLED_INVALID";
			throw failure(code, "Could not run Node.js --version for " + candidate, exception);
		}
		Matcher matcher = VERSION.matcher(version == null ? "" : version.trim());
		if (!matcher.matches()) {
			throw failure("NODE_RUNTIME_VERSION_INVALID", "Node.js --version output was not recognized for " + candidate);
		}
		int major;
		try {
			major = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException exception) {
			throw failure("NODE_RUNTIME_VERSION_INVALID", "Node.js --version output was not recognized for " + candidate, exception);
		}
		if (major < MIN_MAJOR_VERSION) {
			throw failure("NODE_RUNTIME_VERSION_UNSUPPORTED",
					"Node.js 22+ is required; " + candidate + " reports major version " + major);
		}
		return new LocatedNode(candidate, source, major);
	}

	private static String remediation(Source source) {
		return switch (source) {
			case EXPLICIT_PROPERTY -> "Configured Node executable is missing or not executable";
			case BUNDLED_PROFILE -> "Bundled profile Node executable is missing or not executable";
		};
	}

	private static String probeVersion(Path executable) throws IOException {
		Process process = new ProcessBuilder(executable.toString(), "--version")
				.redirectErrorStream(true)
				.start();
		ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "arenaagents-node-version-probe");
			thread.setDaemon(true);
			return thread;
		});
		Future<byte[]> output = readerExecutor.submit(() -> process.getInputStream().readNBytes(MAX_VERSION_OUTPUT_BYTES));
		try {
			if (!process.waitFor(VERSION_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new IOException("Node.js --version probe timed out for " + executable);
			}
			byte[] bytes = output.get(250, TimeUnit.MILLISECONDS);
			if (process.exitValue() != 0) throw new IOException("Node.js --version exited unsuccessfully for " + executable);
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Node.js --version probe was interrupted for " + executable, exception);
		} catch (java.util.concurrent.TimeoutException exception) {
			process.destroyForcibly();
			throw new IOException("Node.js --version output exceeded the probe limit for " + executable, exception);
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new IOException("Node.js --version probe failed for " + executable, exception.getCause());
		} finally {
			process.destroy();
			readerExecutor.shutdownNow();
		}
	}

	private static RuntimeException failure(String code, String message) {
		return new NodeRuntimeFailure(code, message);
	}

	private static RuntimeException failure(String code, String message, Throwable cause) {
		return new NodeRuntimeFailure(code, message, cause);
	}

	enum Source {
		EXPLICIT_PROPERTY,
		BUNDLED_PROFILE
	}

	record LocatedNode(Path executable, Source source, int majorVersion) {
		LocatedNode {
			executable = executable.toAbsolutePath().normalize();
			if (majorVersion < MIN_MAJOR_VERSION) throw new IllegalArgumentException("Node version is unsupported");
		}
	}

	@FunctionalInterface
	interface Probe {
		String version(Path executable) throws Exception;
	}

	static final class NodeRuntimeFailure extends IllegalStateException {
		private final String code;

		NodeRuntimeFailure(String code, String message) {
			super(message);
			this.code = code;
		}

		NodeRuntimeFailure(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
