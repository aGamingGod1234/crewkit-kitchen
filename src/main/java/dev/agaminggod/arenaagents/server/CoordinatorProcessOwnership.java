package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Records the coordinator's Minecraft owner and removes only proven orphaned coordinator processes. */
final class CoordinatorProcessOwnership {
	private static final String OWNERSHIP_PATH = "runtime/coordinator-process.properties";
	private static final long EXIT_TIMEOUT_MS = 2_000L;

	private CoordinatorProcessOwnership() {
	}

	static Path ownershipFile(Path runtimeRoot) {
		return normalizeRoot(runtimeRoot).resolve(OWNERSHIP_PATH).normalize();
	}

	static void record(Path runtimeRoot, Process process, Path main) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		ProcessHandle owner = ProcessHandle.current();
		record(root, process, main, BundledCoordinatorInstaller.validate(root).generationId(),
				UUID.randomUUID().toString(), owner.pid(), ownerStartEpochMs(owner));
	}

	static void record(Path runtimeRoot, Process process, Path main, long ownerPid) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		ProcessHandle owner = ProcessHandle.of(ownerPid)
				.orElseThrow(() -> new IOException("coordinator owner process is unavailable"));
		record(root, process, main, BundledCoordinatorInstaller.validate(root).generationId(),
				UUID.randomUUID().toString(), ownerPid, ownerStartEpochMs(owner));
	}

	static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId
	) throws IOException {
		ProcessHandle owner = ProcessHandle.current();
		record(runtimeRoot, process, main, generationId, launchId, owner.pid(), ownerStartEpochMs(owner));
	}

	static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId,
			long ownerPid
	) throws IOException {
		ProcessHandle owner = ProcessHandle.of(ownerPid)
				.orElseThrow(() -> new IOException("coordinator owner process is unavailable"));
		record(runtimeRoot, process, main, generationId, launchId, ownerPid, ownerStartEpochMs(owner));
	}

	private static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId,
			long ownerPid,
			long ownerStartedAtEpochMs
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path expectedMain = normalizeMain(root, main);
		String expectedGeneration = normalizeGeneration(generationId);
		String expectedLaunch = normalizeLaunchId(launchId);
		if (ownerPid <= 0L) throw new IllegalArgumentException("coordinator owner pid must be positive");
		if (ownerStartedAtEpochMs <= 0L) throw new IllegalArgumentException("coordinator owner start time must be positive");
		Properties values = new Properties();
		values.setProperty("pid", Long.toString(process.pid()));
		values.setProperty("ownerPid", Long.toString(ownerPid));
		values.setProperty("ownerStartedAtEpochMs", Long.toString(ownerStartedAtEpochMs));
		long startedAtEpochMs = process.info().startInstant()
				.orElseThrow(() -> new IOException("coordinator process start time is unavailable"))
				.toEpochMilli();
		values.setProperty("startedAtEpochMs", Long.toString(startedAtEpochMs));
		values.setProperty("main", expectedMain.toString());
		values.setProperty("generationId", expectedGeneration);
		values.setProperty("launchId", expectedLaunch);
		Path ownership = ownershipFile(root);
		Files.createDirectories(ownership.getParent());
		Path staging = ownership.resolveSibling(ownership.getFileName() + ".staging-" + UUID.randomUUID());
		try {
			try (Writer writer = Files.newBufferedWriter(staging)) {
				values.store(writer, "Arena Agents coordinator ownership");
			}
			try {
				Files.move(staging, ownership, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				Files.move(staging, ownership, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(staging);
		}
	}

	static int reapOrphaned(Path runtimeRoot) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path ownershipFile = ownershipFile(root);
		if (!Files.isRegularFile(ownershipFile)) return 0;
		Ownership ownership = read(root);
		if (ownership == null) {
			Files.deleteIfExists(ownershipFile);
			return 0;
		}
		if (ownerAlive(ownership)) return 0;
		// The recorded process identity is sufficient to prove ownership. Requiring the
		// active bundle to validate here prevents orphan cleanup precisely when a broken
		// active generation must be moved out of the way for rollback.
		return reapOrphaned(root, ownership.generationId());
	}

	static int reapOrphaned(Path runtimeRoot, String activeGenerationId) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		normalizeGeneration(activeGenerationId);
		Path expectedMain = root.resolve("coordinator/src/dynamic-main.mjs").normalize();
		Ownership ownership = read(root);
		if (ownership != null && ownerAlive(ownership)) return 0;
		if (ownership == null) return 0;

		int reaped = 0;
		Optional<ProcessHandle> recorded = ProcessHandle.of(ownership.pid());
		if (recorded.isPresent() && matchesIdentity(recorded.get(), expectedMain, ownership.startedAtEpochMs())) {
			terminateTree(recorded.get());
			reaped += 1;
		}
		clearIfMatching(root, ownership);
		return reaped;
	}

	static void clear(Path runtimeRoot, long processPid) throws IOException {
		Optional<ProcessHandle> process = ProcessHandle.of(processPid);
		long startedAtEpochMs = process.flatMap(handle -> handle.info().startInstant())
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, processPid, startedAtEpochMs);
	}

	static void clear(Path runtimeRoot, Process process) throws IOException {
		long startedAtEpochMs = process.info().startInstant()
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, process.pid(), startedAtEpochMs);
	}

	static void clear(Path runtimeRoot, Process process, String generationId, String launchId) throws IOException {
		long startedAtEpochMs = process.info().startInstant()
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, process.pid(), startedAtEpochMs, normalizeGeneration(generationId), normalizeLaunchId(launchId));
	}

	private static void clear(Path runtimeRoot, long processPid, long startedAtEpochMs) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Ownership ownership = read(root);
		if (ownership == null || (ownership.pid() == processPid && ownership.startedAtEpochMs() == startedAtEpochMs)) {
			Files.deleteIfExists(ownershipFile(root));
		}
	}

	private static void clear(
			Path runtimeRoot,
			long processPid,
			long startedAtEpochMs,
			String generationId,
			String launchId
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Ownership ownership = read(root);
		if (ownership != null && ownership.pid() == processPid
				&& ownership.startedAtEpochMs() == startedAtEpochMs
				&& ownership.generationId().equals(generationId)
				&& ownership.launchId().equals(launchId)) {
			clearIfMatching(root, ownership);
		}
	}

	private static Ownership read(Path runtimeRoot) throws IOException {
		return read(runtimeRoot, ownershipFile(runtimeRoot));
	}

	private static Ownership read(Path runtimeRoot, Path file) throws IOException {
		if (!Files.isRegularFile(file)) return null;
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			values.load(reader);
		}
		try {
			long pid = Long.parseLong(values.getProperty("pid", ""));
			long ownerPid = Long.parseLong(values.getProperty("ownerPid", ""));
			String ownerStartedAtValue = values.getProperty("ownerStartedAtEpochMs");
			long ownerStartedAtEpochMs = ownerStartedAtValue == null
					? -1L : Long.parseLong(ownerStartedAtValue);
			long startedAtEpochMs = Long.parseLong(values.getProperty("startedAtEpochMs", ""));
			String generationId = normalizeGeneration(values.getProperty("generationId", ""));
			String launchId = normalizeLaunchId(values.getProperty("launchId", ""));
			Path main = Path.of(values.getProperty("main", "")).toAbsolutePath().normalize();
			Path expectedMain = normalizeRoot(runtimeRoot).resolve("coordinator/src/dynamic-main.mjs").normalize();
			if (pid <= 0L || ownerPid <= 0L || startedAtEpochMs <= 0L
					|| (ownerStartedAtValue != null && ownerStartedAtEpochMs <= 0L)
					|| !samePath(main, expectedMain)) return null;
			return new Ownership(pid, ownerPid, ownerStartedAtEpochMs, startedAtEpochMs, generationId, launchId);
		} catch (RuntimeException invalid) {
			return null;
		}
	}

	private static boolean ownerAlive(Ownership ownership) {
		if (ownership.ownerStartedAtEpochMs() <= 0L) {
			// Legacy records predate the owner start identity. Only the process running
			// this cleanup may conservatively keep such a record alive; another process
			// with the old PID cannot suppress orphan cleanup without proof of identity.
			return ownership.ownerPid() == ProcessHandle.current().pid();
		}
		return ProcessHandle.of(ownership.ownerPid())
				.map(handle -> handle.isAlive() && sameStart(handle, ownership.ownerStartedAtEpochMs()))
				.orElse(false);
	}

	private static long ownerStartEpochMs(ProcessHandle owner) throws IOException {
		return owner.info().startInstant()
				.orElseThrow(() -> new IOException("coordinator owner process start time is unavailable"))
				.toEpochMilli();
	}

	private static boolean sameStart(ProcessHandle handle, long expectedEpochMs) {
		return handle.info().startInstant().map(start -> start.toEpochMilli() == expectedEpochMs).orElse(false);
	}

	private static boolean matchesIdentity(ProcessHandle handle, Path expectedMain, long expectedEpochMs) {
		if (!handle.isAlive() || !sameStart(handle, expectedEpochMs)) return false;
		ProcessHandle.Info info = handle.info();
		if (info.arguments().isEmpty() && info.commandLine().isEmpty()) return true;
		return ownsMain(handle, expectedMain);
	}

	private static boolean ownsMain(ProcessHandle handle, Path expectedMain) {
		String[] arguments = handle.info().arguments().orElse(null);
		if (arguments != null && java.util.Arrays.stream(arguments).anyMatch(argument -> argumentMatchesMain(argument, expectedMain))) {
			return true;
		}
		String commandLine = handle.info().commandLine().orElse("");
		return containsPathAsArgument(commandLine, expectedMain);
	}

	private static boolean argumentMatchesMain(String argument, Path expectedMain) {
		try {
			return samePath(Path.of(argument).toAbsolutePath().normalize(), expectedMain);
		} catch (RuntimeException invalidPath) {
			return false;
		}
	}

	private static boolean containsPathAsArgument(String commandLine, Path expectedMain) {
		String command = normalizedText(commandLine);
		String expected = normalizedText(expectedMain.toString());
		int offset = command.indexOf(expected);
		while (offset >= 0) {
			int end = offset + expected.length();
			boolean startsArgument = offset == 0 || isCommandLineBoundary(command.charAt(offset - 1));
			boolean endsArgument = end == command.length() || isCommandLineBoundary(command.charAt(end));
			if (startsArgument && endsArgument) return true;
			offset = command.indexOf(expected, offset + 1);
		}
		return false;
	}

	private static boolean isCommandLineBoundary(char value) {
		return Character.isWhitespace(value) || value == '"' || value == '\'';
	}

	private static String normalizedText(String value) {
		String normalized = value.replace('/', '\\');
		return isWindows() ? normalized.toLowerCase(Locale.ROOT) : normalized;
	}

	private static boolean samePath(Path left, Path right) {
		if (isWindows()) return left.toString().equalsIgnoreCase(right.toString());
		return left.equals(right);
	}

	private static Path normalizeMain(Path root, Path main) {
		Path normalized = main.toAbsolutePath().normalize();
		Path expected = root.resolve("coordinator/src/dynamic-main.mjs").normalize();
		if (!samePath(normalized, expected)) throw new IllegalArgumentException("coordinator main must belong to its runtime root");
		return normalized;
	}

	private static Path normalizeRoot(Path runtimeRoot) {
		return runtimeRoot.toAbsolutePath().normalize();
	}

	private static boolean clearIfMatching(Path root, Ownership expected) throws IOException {
		Path ownership = ownershipFile(root);
		Path claimed = ownership.resolveSibling(ownership.getFileName() + ".clear-" + UUID.randomUUID());
		try {
			moveWithoutReplace(ownership, claimed);
		} catch (NoSuchFileException missing) {
			return false;
		}
		Ownership claimedOwnership = read(root, claimed);
		if (expected.equals(claimedOwnership)) {
			Files.deleteIfExists(claimed);
			return true;
		}
		try {
			Files.move(claimed, ownership);
		} catch (FileAlreadyExistsException newerOwnershipPublished) {
			Files.deleteIfExists(claimed);
		}
		return false;
	}

	private static void moveWithoutReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, target);
		}
	}

	private static String normalizeGeneration(String generationId) {
		String normalized = java.util.Objects.requireNonNull(generationId, "generation ID must not be null");
		if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("coordinator generation ID is invalid");
		return normalized;
	}

	private static String normalizeLaunchId(String launchId) {
		return UUID.fromString(java.util.Objects.requireNonNull(launchId, "launch ID must not be null")).toString();
	}

	static void terminateTree(ProcessHandle process) {
		terminateTree(process, EXIT_TIMEOUT_MS);
	}

	static void terminateTree(ProcessHandle process, long exitTimeoutMs) {
		if (exitTimeoutMs < 0L) throw new IllegalArgumentException("process exit timeout must not be negative");
		boolean interrupted = Thread.interrupted();
		try {
			LinkedHashSet<ProcessHandle> descendants = new LinkedHashSet<>(process.descendants().toList());
			descendants.forEach(ProcessHandle::destroy);
			process.destroy();
			interrupted |= awaitExit(process, List.copyOf(descendants), exitTimeoutMs);
			descendants.addAll(process.descendants().toList());
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			if (process.isAlive()) process.destroyForcibly();
			interrupted |= awaitExit(process, List.copyOf(descendants), exitTimeoutMs);
			if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
				throw new IllegalStateException("Owned coordinator process tree is still alive after termination");
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static boolean awaitExit(ProcessHandle process, List<ProcessHandle> descendants, long timeoutMs) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		return interrupted;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private record Ownership(
			long pid,
			long ownerPid,
			long ownerStartedAtEpochMs,
			long startedAtEpochMs,
			String generationId,
			String launchId
	) {
	}
}
