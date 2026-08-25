package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Verifies that a crashed Minecraft owner cannot leave the bundled coordinator blocking an update. */
public final class CoordinatorProcessOwnershipVerification {
	private CoordinatorProcessOwnershipVerification() {
	}

	public static int verify() throws Exception {
		Path root = Files.createTempDirectory("arena-coordinator-ownership");
		Process orphan = null;
		Process liveOwned = null;
		Process unrelated = null;
		Process tree = null;
		Process interruptedTree = null;
		try {
			Path main = root.resolve("coordinator/src/dynamic-main.mjs").toAbsolutePath().normalize();
			Files.createDirectories(main.getParent());
			Files.writeString(main, "// ownership fixture", StandardCharsets.UTF_8);
			Path unrelatedMain = root.resolve("unrelated-main.mjs").toAbsolutePath().normalize();
			Files.writeString(unrelatedMain, "// unrelated fixture", StandardCharsets.UTF_8);

			orphan = startSleeper(main);
			CoordinatorProcessOwnership.record(root, orphan, main, Long.MAX_VALUE);
			assertTrue(CoordinatorProcessOwnership.reapOrphaned(root) == 1,
					"a coordinator whose Minecraft owner is gone is reaped");
			assertTrue(orphan.waitFor(5, TimeUnit.SECONDS), "the orphaned coordinator process exits");
			assertTrue(!Files.exists(CoordinatorProcessOwnership.ownershipFile(root)),
					"the stale ownership record is removed");

			liveOwned = startSleeper(main);
			CoordinatorProcessOwnership.record(root, liveOwned, main, ProcessHandle.current().pid());
			assertTrue(CoordinatorProcessOwnership.reapOrphaned(root) == 0,
					"a coordinator with a live Minecraft owner is preserved");
			assertTrue(liveOwned.isAlive(), "the live owner's coordinator remains running");

			int clearAssertions = verifyClearPreservesNewerRecord(root, liveOwned);
			unrelated = startSleeper(unrelatedMain);
			CoordinatorProcessOwnership.record(root, unrelated, main, Long.MAX_VALUE);
			if (unrelated.toHandle().info().arguments().isEmpty() && unrelated.toHandle().info().commandLine().isEmpty()) {
				invalidateStartTimestamp(root);
			}
			assertTrue(CoordinatorProcessOwnership.reapOrphaned(root) == 0,
					"a stale PID with a different coordinator identity is not reaped");
			assertTrue(unrelated.isAlive(), "an unrelated process with the recorded PID survives");

			tree = startTreeSleeper(main);
			CoordinatorProcessOwnership.record(root, tree, main, Long.MAX_VALUE);
			var descendants = tree.toHandle().descendants().toList();
			assertTrue(!descendants.isEmpty(), "ownership fixture creates a child process");
			assertTrue(CoordinatorProcessOwnership.reapOrphaned(root) == 1,
					"an orphaned coordinator tree is reaped");
			assertTrue(tree.waitFor(5, TimeUnit.SECONDS), "the orphaned coordinator exits");
			assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive), "orphaned coordinator children exit");

			interruptedTree = startTreeSleeper(main);
			var interruptedDescendants = interruptedTree.toHandle().descendants().toList();
			Thread.currentThread().interrupt();
			CoordinatorProcessOwnership.terminateTree(interruptedTree.toHandle());
			assertTrue(Thread.interrupted(), "tree termination preserves interruption status");
			assertTrue(interruptedDescendants.stream().noneMatch(ProcessHandle::isAlive),
					"interrupted tree termination still stops every child");
			return 12 + clearAssertions + verifyStartupOwnershipRecordFailureCleanup(main);
		} finally {
			if (orphan != null && orphan.isAlive()) orphan.destroyForcibly();
			if (liveOwned != null && liveOwned.isAlive()) liveOwned.destroyForcibly();
			if (unrelated != null && unrelated.isAlive()) unrelated.destroyForcibly();
			if (tree != null && tree.isAlive()) tree.destroyForcibly();
			if (interruptedTree != null && interruptedTree.isAlive()) interruptedTree.destroyForcibly();
			deleteTree(root);
		}
	}

	private static void invalidateStartTimestamp(Path root) throws Exception {
		Path ownership = CoordinatorProcessOwnership.ownershipFile(root);
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(ownership)) {
			values.load(reader);
		}
		values.setProperty("startedAtEpochMs", "1");
		try (var writer = Files.newBufferedWriter(ownership)) {
			values.store(writer, "invalidated ownership fixture");
		}
	}

	private static int verifyStartupOwnershipRecordFailureCleanup(Path main) throws Exception {
		Process failedStart = startTreeSleeper(main);
		try {
			var descendants = failedStart.toHandle().descendants().toList();
			assertTrue(!descendants.isEmpty(), "startup failure fixture creates a child process");
			CoordinatorProcessSupervisor.terminateFailedStart(failedStart);
			assertTrue(failedStart.waitFor(5, TimeUnit.SECONDS),
					"startup ownership-record failure terminates the coordinator");
			assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive),
					"startup ownership-record failure terminates every coordinator child");
			return 3;
		} finally {
			if (failedStart.isAlive()) failedStart.destroyForcibly();
		}
	}

	private static int verifyClearPreservesNewerRecord(Path root, Process process) throws Exception {
		Path ownership = CoordinatorProcessOwnership.ownershipFile(root);
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(ownership)) {
			values.load(reader);
		}
		long processStart = process.info().startInstant()
				.orElseThrow(() -> new AssertionError("clear fixture process start time is unavailable"))
				.toEpochMilli();
		values.setProperty("startedAtEpochMs", Long.toString(processStart + 1L));
		try (var writer = Files.newBufferedWriter(ownership)) {
			values.store(writer, "newer ownership fixture");
		}

		CoordinatorProcessOwnership.clear(root, process);
		assertTrue(Files.exists(ownership), "clear preserves a newer record with the same PID");
		Files.deleteIfExists(ownership);
		return 1;
	}

	private static Process startSleeper(Path main) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
		Process process = new ProcessBuilder(
				java,
				"-cp",
				System.getProperty("java.class.path"),
				Sleeper.class.getName(),
				main.toString()
		).start();
		Thread.sleep(100L);
		assertTrue(process.isAlive(), "ownership fixture process started");
		return process;
	}

	private static Process startTreeSleeper(Path main) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
		Process process = new ProcessBuilder(
				java,
				"-cp",
				System.getProperty("java.class.path"),
				TreeSleeper.class.getName(),
				main.toString()
		).start();
		long deadline = System.currentTimeMillis() + 5_000L;
		while (process.toHandle().descendants().findAny().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25L);
		assertTrue(process.isAlive(), "ownership tree fixture process started");
		return process;
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	public static final class Sleeper {
		private Sleeper() {
		}

		public static void main(String[] args) throws Exception {
			Thread.sleep(TimeUnit.MINUTES.toMillis(5));
		}
	}

	public static final class TreeSleeper {
		private TreeSleeper() {
		}

		public static void main(String[] args) throws Exception {
			String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
			new ProcessBuilder(
					java,
					"-cp",
					System.getProperty("java.class.path"),
					Sleeper.class.getName(),
					args[0]
			).start();
			Thread.sleep(TimeUnit.MINUTES.toMillis(5));
		}
	}
}
