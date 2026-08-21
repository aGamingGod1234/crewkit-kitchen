package dev.agaminggod.arenaagents.server;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Starts the bundled localhost coordinator for normal single-player launches and restarts it after crashes. */
final class CoordinatorProcessSupervisor implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorProcessSupervisor.class);
	private static final long RESTART_DELAY_MS = Duration.ofSeconds(2).toMillis();

	private final Path gameDirectory;
	private final Path packageRoot;
	private Process process;
	private long nextStartEpochMs;
	private final long createdAtEpochMs;

	CoordinatorProcessSupervisor() {
		this(FabricLoader.getInstance().getGameDir());
	}

	CoordinatorProcessSupervisor(Path gameDirectory) {
		this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
		try {
			if (BundledCoordinatorInstaller.installBundled(this.gameDirectory.resolve("arena-agents-runtime"))) {
				LOGGER.info("Installed the bundled Arena Agents coordinator runtime");
			}
		} catch (IOException exception) {
			LOGGER.error("Could not install the bundled Arena Agents coordinator runtime", exception);
		}
		this.packageRoot = findPackageRoot(this.gameDirectory);
		configureSharedBridgeSecretPath(this.packageRoot);
		this.createdAtEpochMs = System.currentTimeMillis();
	}

	private static void configureSharedBridgeSecretPath(Path packageRoot) {
		if (packageRoot == null) return;
		String configured = System.getProperty("arenaagents.bridgeSecretFile");
		String environment = System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE");
		if ((configured == null || configured.isBlank()) && (environment == null || environment.isBlank())) {
			System.setProperty("arenaagents.bridgeSecretFile", packageRoot.resolve("runtime/bridge-secret.txt").toString());
		}
	}

	synchronized boolean configured() {
		return packageRoot != null;
	}

	synchronized void tick(boolean bridgeAuthenticated) {
		if (packageRoot == null) return;
		long now = System.currentTimeMillis();
		if (!CoordinatorLaunchPolicy.shouldStart(bridgeAuthenticated, process != null && process.isAlive(), createdAtEpochMs, now)) return;
		if (now < nextStartEpochMs) return;
		start(now);
	}

	private void start(long now) {
		Path secretPath = packageRoot.resolve("runtime/bridge-secret.txt");
		Path coordinatorMain = packageRoot.resolve("coordinator/src/dynamic-main.mjs");
		Path coordinatorConfig = packageRoot.resolve("coordinator/config/dynamic-agents.json");
		Path logDirectory = gameDirectory.resolve("logs");
		try {
			String secret = Files.readString(secretPath).trim();
			if (secret.length() < 32) throw new IOException("bridge secret is missing or invalid");
			Files.createDirectories(logDirectory);
			ProcessBuilder builder = new ProcessBuilder(List.of(
					"node", coordinatorMain.toString(), "--config", coordinatorConfig.toString()));
			builder.directory(packageRoot.resolve("coordinator").toFile());
			Map<String, String> environment = builder.environment();
			environment.put("ARENA_AGENT_BRIDGE_SECRET", secret);
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator.log").toFile()));
			builder.redirectError(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("arena-agents-coordinator-error.log").toFile()));
			process = builder.start();
			nextStartEpochMs = now + RESTART_DELAY_MS;
			LOGGER.info("Started the Arena Agents coordinator (pid {})", process.pid());
		} catch (IOException | RuntimeException exception) {
			nextStartEpochMs = now + RESTART_DELAY_MS;
			LOGGER.error("Could not start the bundled Arena Agents coordinator", exception);
		}
	}

	@Override
	public synchronized void close() {
		if (process == null || !process.isAlive()) return;
		process.destroy();
		try {
			if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private static Path findPackageRoot(Path gameDirectory) {
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured != null && !configured.isBlank()) {
			Path candidate = Path.of(configured).toAbsolutePath().normalize();
			return isPackageRoot(candidate) ? candidate : null;
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
}
