package dev.agaminggod.arenaagents.server;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts the bundled localhost coordinator with one prepared runtime context. */
final class CoordinatorProcessSupervisor implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorProcessSupervisor.class);
	private static final String FISH_API_KEY_FILE = "runtime/fish-api-key.txt";

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
		if (!autoStartEnabled()) {
			this.runtimePackage = null;
			this.node = null;
			return;
		}
		BundledCoordinatorInstaller.RuntimePackage prepared = null;
		NodeRuntimeLocator.LocatedNode located = null;
		try {
			Path installedRoot = this.gameDirectory.resolve("arena-agents-runtime");
			int reaped = 0;
			for (Path ownershipRoot : ownershipRoots(this.gameDirectory)) {
				reaped += CoordinatorProcessOwnership.reapOrphaned(ownershipRoot);
			}
			if (reaped > 0) LOGGER.warn("Stopped {} orphaned Arena Agents coordinator process(es) before updating the runtime", reaped);
			IOException installFailure = null;
			try {
				if (BundledCoordinatorInstaller.installBundled(installedRoot)) {
					LOGGER.info("Installed the bundled Arena Agents coordinator runtime");
				}
			} catch (IOException failure) {
				installFailure = failure;
				LOGGER.warn("Could not refresh the bundled Arena Agents runtime; attempting the last complete installed version", failure);
			}
			Path discoveredRoot = findPackageRoot(this.gameDirectory);
			if (discoveredRoot != null) {
				try {
					prepared = BundledCoordinatorInstaller.validate(discoveredRoot);
				} catch (IOException invalidRuntime) {
					if (installFailure != null) invalidRuntime.addSuppressed(installFailure);
					throw invalidRuntime;
				}
				configureSharedBridgeSecretPath(prepared.secret());
				configureSharedVoiceEndpoint(prepared.config());
				located = NodeRuntimeLocator.locate(prepared.root());
			} else if (installFailure != null) {
				throw installFailure;
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

	private static boolean autoStartEnabled() {
		return !"false".equalsIgnoreCase(System.getProperty("arenaagents.coordinatorAutoStart"));
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

	synchronized String failureMessage() {
		return failureMessage;
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
			for (Map.Entry<String, String> override : launchEnvironmentOverrides.entrySet()) {
				for (String existing : List.copyOf(environment.keySet())) {
					if (existing.equalsIgnoreCase(override.getKey())) environment.remove(existing);
				}
				environment.put(override.getKey(), override.getValue());
			}
			configureVoiceProviderCredential(environment);
			environment.put("ARENA_AGENT_BRIDGE_SECRET", secret);
			Path logDirectory = gameDirectory.resolve("logs");
			CoordinatorLogRotation.rotate(logDirectory);
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator.log").toFile()));
			builder.redirectError(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator-error.log").toFile()));
			Process started = builder.start();
			try {
				CoordinatorProcessOwnership.record(runtimePackage.root(), started, runtimePackage.main());
			} catch (IOException ownershipFailure) {
				terminateFailedStart(started);
				throw ownershipFailure;
			}
			process = started;
			observedExit = null;
			nextStartEpochMs = now;
			LOGGER.info("Started the Arena Agents coordinator (pid {})", process.pid());
		} catch (StartupFailure exception) {
			latchFailure(exception.code(), exception.getMessage());
		} catch (IOException | RuntimeException exception) {
			latchFailure("COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator");
		}
	}

	static void terminateFailedStart(Process started) {
		CoordinatorProcessOwnership.terminateTree(started.toHandle());
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
		CoordinatorProcessOwnership.terminateTree(owned.toHandle());
		try {
			CoordinatorProcessOwnership.clear(runtimePackage.root(), owned);
		} catch (IOException exception) {
			LOGGER.warn("Could not clear the Arena Agents coordinator ownership record", exception);
		}
		process = null;
	}

	private void configureVoiceProviderCredential(Map<String, String> environment) {
		configureVoiceProviderCredential(runtimePackage.root(), environment);
	}

	static void configureVoiceProviderCredential(Path runtimeRoot, Map<String, String> environment) {
		if (nonBlankEnvironmentValue(environment, "FISH_AUDIO_API_KEY")
				|| nonBlankEnvironmentValue(environment, "FISH_API_KEY")) return;
		Path credentialFile = Objects.requireNonNull(runtimeRoot, "runtime root must not be null")
				.resolve(FISH_API_KEY_FILE).normalize();
		if (!Files.isRegularFile(credentialFile)) return;
		String credential;
		try {
			credential = Files.readString(credentialFile, StandardCharsets.UTF_8).trim();
		} catch (IOException exception) {
			LOGGER.warn("Ignoring unreadable optional Fish TTS credential; proximity speech will use its fallback");
			return;
		}
		if (credential.length() < 8 || credential.length() > 512) {
			LOGGER.warn("Ignoring malformed optional Fish TTS credential; proximity speech will use its fallback");
			return;
		}
		for (String existing : List.copyOf(environment.keySet())) {
			if (existing.equalsIgnoreCase("FISH_AUDIO_API_KEY")) environment.remove(existing);
		}
		environment.put("FISH_AUDIO_API_KEY", credential);
		LOGGER.info("Configured the Arena Agents TTS provider from the runtime credential file");
	}

	private static boolean nonBlankEnvironmentValue(Map<String, String> environment, String name) {
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isBlank()) {
				return true;
			}
		}
		return false;
	}

	static void configureSharedBridgeSecretPath(Path secretPath) {
		Path canonical = secretPath.toAbsolutePath().normalize();
		System.setProperty("arenaagents.bridgeSecretFile", canonical.toString());
		System.setProperty("arenaagents.voiceSecretFile", canonical.toString());
	}

	private void configureSharedVoiceEndpoint(Path configPath) throws IOException {
		String configured = System.getProperty("arenaagents.voiceUrl");
		if (configured != null && !configured.isBlank()) return;
		CoordinatorVoiceEndpoint.resolve(configPath, System.getenv(), launchEnvironmentOverrides)
				.ifPresent(endpoint -> System.setProperty("arenaagents.voiceUrl", endpoint));
	}

	private static Path findPackageRoot(Path gameDirectory) {
		Path configured = configuredPackageRoot();
		if (configured != null) return configured;
		Path installedRuntime = gameDirectory.resolve("arena-agents-runtime");
		if (isPackageRoot(installedRuntime)) return installedRuntime;
		Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
			if (isPackageRoot(cursor)) return cursor;
		}
		Path sibling = gameDirectory.getParent() == null ? null : gameDirectory.getParent().resolve("agent arena");
		return sibling != null && isPackageRoot(sibling) ? sibling : null;
	}

	static List<Path> ownershipRoots(Path gameDirectory) {
		Path installed = gameDirectory.toAbsolutePath().normalize().resolve("arena-agents-runtime").normalize();
		ArrayList<Path> roots = new ArrayList<>();
		roots.add(installed);
		Path configured = configuredPackageRoot();
		if (configured != null && !configured.equals(installed)) roots.add(configured);
		return List.copyOf(roots);
	}

	private static Path configuredPackageRoot() {
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured == null || configured.isBlank()) return null;
		try {
			return Path.of(configured).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			return null;
		}
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
