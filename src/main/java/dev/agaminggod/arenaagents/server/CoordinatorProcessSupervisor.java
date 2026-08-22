package dev.agaminggod.arenaagents.server;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts the bundled localhost coordinator with one prepared runtime context. */
final class CoordinatorProcessSupervisor implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorProcessSupervisor.class);

	private final Path gameDirectory;
	private final BundledCoordinatorInstaller.RuntimePackage runtimePackage;
	private final NodeRuntimeLocator.LocatedNode node;
	private final Map<String, String> launchEnvironmentOverrides;
	private final long createdAtEpochMs;
	private final CoordinatorLaunchPolicy.RestartBudget restartBudget = new CoordinatorLaunchPolicy.RestartBudget();
	private Process process;
	private Process observedExit;
	private long nextStartEpochMs;
	private String failureCode;
	private String failureMessage;

	CoordinatorProcessSupervisor() {
		this(FabricLoader.getInstance().getGameDir());
	}

	CoordinatorProcessSupervisor(Path gameDirectory) {
		this(gameDirectory, Map.of());
	}

	/** Allows isolated startup fixtures to control inherited environment state without invoking a shell. */
	CoordinatorProcessSupervisor(Path gameDirectory, Map<String, String> launchEnvironmentOverrides) {
		this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
		this.launchEnvironmentOverrides = Map.copyOf(Objects.requireNonNull(launchEnvironmentOverrides, "launch environment overrides must not be null"));
		this.createdAtEpochMs = System.currentTimeMillis();
		BundledCoordinatorInstaller.RuntimePackage prepared = null;
		NodeRuntimeLocator.LocatedNode located = null;
		try {
			Path installedRoot = this.gameDirectory.resolve("arena-agents-runtime");
			if (BundledCoordinatorInstaller.installBundled(installedRoot)) {
				LOGGER.info("Installed the bundled Arena Agents coordinator runtime");
			}
			Path discoveredRoot = findPackageRoot(this.gameDirectory);
			if (discoveredRoot != null) {
				prepared = discoveredRoot.equals(installedRoot)
						? BundledCoordinatorInstaller.prepare(discoveredRoot)
						: BundledCoordinatorInstaller.validate(discoveredRoot);
				configureSharedBridgeSecretPath(prepared.secret());
				located = NodeRuntimeLocator.locate(prepared.root());
			}
		} catch (NodeRuntimeLocator.NodeRuntimeFailure exception) {
			failureCode = exception.code();
			failureMessage = exception.getMessage();
			logFailure(failureCode, failureMessage);
		} catch (IOException | RuntimeException exception) {
			failureCode = exception instanceof StartupFailure startup ? startup.code() : "COORDINATOR_STARTUP_INVALID";
			failureMessage = exception.getMessage() == null ? "Coordinator startup dependencies are unavailable" : exception.getMessage();
			logFailure(failureCode, failureMessage);
		}
		this.runtimePackage = prepared;
		this.node = located;
	}

	synchronized boolean configured() {
		return runtimePackage != null;
	}

	synchronized Path secretPath() {
		return runtimePackage == null ? null : runtimePackage.secret();
	}

	synchronized String failureCode() {
		return failureCode;
	}

	synchronized void tick(boolean bridgeAuthenticated) {
		if (runtimePackage == null) return;
		long now = System.currentTimeMillis();
		if (bridgeAuthenticated) {
			restartBudget.resetAfterAuthentication();
			observedExit = null;
		}
		if (process != null && !process.isAlive() && process != observedExit) {
			observedExit = process;
			if (!restartBudget.recordUnexpectedExit()) {
				latchFailure("COORDINATOR_RESTART_EXHAUSTED",
						"Coordinator exited three times; restart Minecraft after checking the coordinator log");
				return;
			}
			nextStartEpochMs = now + restartBudget.nextDelayMs();
		}
		if (failureCode != null || node == null) return;
		if (!CoordinatorLaunchPolicy.shouldStart(
				bridgeAuthenticated,
				process != null && process.isAlive(),
				createdAtEpochMs,
				now
		)) return;
		if (now < nextStartEpochMs) return;
		start(now);
	}

	private void start(long now) {
		try {
			String secret = Files.readString(runtimePackage.secret(), StandardCharsets.UTF_8).trim();
			if (secret.length() < 32 || secret.length() > 256) {
				throw new StartupFailure("BRIDGE_SECRET_INVALID", "Bridge secret is missing or invalid");
			}
			Files.createDirectories(gameDirectory.resolve("logs"));
			ProcessBuilder builder = new ProcessBuilder(List.of(
					node.executable().toString(),
				runtimePackage.main().toString(),
				"--config",
				runtimePackage.config().toString()
			));
			builder.directory(runtimePackage.coordinatorRoot().toFile());
			Map<String, String> environment = builder.environment();
			// ProcessBuilder inherits the caller's provider credentials and environment by default.
			// Only the validated bridge secret is added for the coordinator child.
			for (Map.Entry<String, String> override : launchEnvironmentOverrides.entrySet()) {
				for (String existing : List.copyOf(environment.keySet())) {
					if (existing.equalsIgnoreCase(override.getKey())) environment.remove(existing);
				}
				environment.put(override.getKey(), override.getValue());
			}
			environment.put("ARENA_AGENT_BRIDGE_SECRET", secret);
			Path logDirectory = gameDirectory.resolve("logs");
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator.log").toFile()));
			builder.redirectError(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator-error.log").toFile()));
			process = builder.start();
			observedExit = null;
			nextStartEpochMs = now;
			LOGGER.info("Started the Arena Agents coordinator (pid {})", process.pid());
		} catch (StartupFailure exception) {
			latchFailure(exception.code(), exception.getMessage());
		} catch (IOException | RuntimeException exception) {
			latchFailure("COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator");
		}
	}

	private void latchFailure(String code, String message) {
		if (Objects.equals(failureCode, code) && Objects.equals(failureMessage, message)) return;
		failureCode = code;
		failureMessage = message;
		logFailure(code, message);
	}

	private static void logFailure(String code, String message) {
		LOGGER.error("Arena Agents coordinator unavailable [{}]: {}", code, message == null ? "check startup configuration" : message);
	}

	@Override
	public synchronized void close() {
		Process owned = process;
		if (owned == null) return;
		ProcessHandle handle = owned.toHandle();
		handle.descendants().forEach(ProcessHandle::destroy);
		if (owned.isAlive()) owned.destroy();
		try {
			if (!owned.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				handle.descendants().forEach(ProcessHandle::destroyForcibly);
				owned.destroyForcibly();
				owned.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			handle.descendants().forEach(ProcessHandle::destroyForcibly);
			owned.destroyForcibly();
		}
		process = null;
	}

	private static void configureSharedBridgeSecretPath(Path secretPath) {
		Path canonical = secretPath.toAbsolutePath().normalize();
		String configuredBridge = firstNonblank(System.getProperty("arenaagents.bridgeSecretFile"),
				System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE"));
		String configuredVoice = System.getProperty("arenaagents.voiceSecretFile");
		if (configuredBridge != null && !samePath(configuredBridge, canonical)) {
			throw new StartupFailure("BRIDGE_SECRET_PATH_CONFLICT", "Configured bridge secret path does not match the prepared runtime");
		}
		if (configuredVoice != null && !configuredVoice.isBlank() && !samePath(configuredVoice, canonical)) {
			throw new StartupFailure("BRIDGE_SECRET_PATH_CONFLICT", "Configured voice secret path does not match the prepared runtime");
		}
		System.setProperty("arenaagents.bridgeSecretFile", canonical.toString());
		System.setProperty("arenaagents.voiceSecretFile", canonical.toString());
	}

	private static String firstNonblank(String first, String second) {
		if (first != null && !first.isBlank()) return first;
		return second == null || second.isBlank() ? null : second;
	}

	private static boolean samePath(String configured, Path canonical) {
		try {
			return Path.of(configured).toAbsolutePath().normalize().equals(canonical);
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static Path findPackageRoot(Path gameDirectory) {
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured != null && !configured.isBlank()) {
			try {
				return Path.of(configured).toAbsolutePath().normalize();
			} catch (RuntimeException exception) {
				return null;
			}
		}
		Path installedRuntime = gameDirectory.resolve("arena-agents-runtime");
		if (isPackageRoot(installedRuntime)) return installedRuntime;
		Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
			if (isPackageRoot(cursor)) return cursor;
		}
		Path sibling = gameDirectory.getParent() == null ? null : gameDirectory.getParent().resolve("agent arena");
		return sibling != null && isPackageRoot(sibling) ? sibling : null;
	}

	private static boolean isPackageRoot(Path candidate) {
		return Files.isRegularFile(candidate.resolve("runtime/bridge-secret.txt"))
				&& Files.isRegularFile(candidate.resolve("coordinator/src/dynamic-main.mjs"))
				&& Files.isRegularFile(candidate.resolve("coordinator/config/dynamic-agents.json"));
	}

	private static final class StartupFailure extends RuntimeException {
		private final String code;

		private StartupFailure(String code, String message) {
			super(message);
			this.code = code;
		}

		private String code() {
			return code;
		}
	}
}
