package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns coordinator availability until explicit Minecraft shutdown. */
final class CoordinatorProcessSupervisor implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorProcessSupervisor.class);
	private static final long DEPENDENCY_RECHECK_MS = 5_000L;
	private static final long AUTHENTICATION_TIMEOUT_MS = 15_000L;
	private static final long RECONNECT_TIMEOUT_MS = 10_000L;
	private static final long STABILITY_INTERVAL_MS = 30_000L;
	private static final long TERMINATION_RETRY_MS = 1_000L;
	private static final int CANDIDATE_FAILURES_BEFORE_ROLLBACK = 3;

	private final Path gameDirectory;
	private final Map<String, String> launchEnvironmentOverrides;
	private final LongSupplier clock;
	private final DependencyResolver dependencyResolver;
	private final ProcessLauncher processLauncher;
	private final Supplier<String> launchIds;
	private final MaintenanceWorker maintenanceWorker;
	private final OrphanReaper orphanReaper;
	private final DependencyChangeMonitor dependencyChangeMonitor;
	private final GenerationController generationController;
	private final long createdAtEpochMs;
	private final CoordinatorLaunchPolicy.RestartBudget restartBudget = new CoordinatorLaunchPolicy.RestartBudget();
	private final ConcurrentLinkedQueue<MaintenanceResult> maintenanceResults = new ConcurrentLinkedQueue<>();
	private final AtomicLong dependencyWakeGeneration = new AtomicLong();
	private final AtomicBoolean dependencyWakeQueued = new AtomicBoolean();
	private final Set<Path> reapedOrphanRoots = new LinkedHashSet<>();

	private PreparedRuntime runtime;
	private ChildProcess child;
	private CoordinatorRecoveryState state;
	private volatile boolean stopped;
	private boolean maintenancePending;
	private boolean orphanCleanupComplete;
	private boolean coordinatorReconciled;
	private boolean stabilityCredited;
	private long generation;
	private long nextRetryEpochMs;
	private long nextDependencyCheckEpochMs;
	private long lastStableEpochMs;
	private long processStartedEpochMs;
	private long authenticationDeadlineEpochMs;
	private long reconnectDeadlineEpochMs;
	private long authenticatedSinceEpochMs;
	private long authenticatedSessionGeneration;
	private String launchId;
	private String dependencyFingerprint;
	private String failureCode;
	private String failureMessage;
	private String failingBoundary;
	private ChildProcess pendingTermination;
	private long bridgeRevision;
	private int candidateFailures;
	private boolean rollbackRequested;
	private long nextGenerationMutationEpochMs;
	private long nextTerminationRetryEpochMs;

	CoordinatorProcessSupervisor() {
		this(FabricLoader.getInstance().getGameDir());
	}

	CoordinatorProcessSupervisor(Path gameDirectory) {
		this(gameDirectory, Map.of());
	}

	/** Allows isolated startup fixtures to control inherited environment state without invoking a shell. */
	CoordinatorProcessSupervisor(Path gameDirectory, Map<String, String> launchEnvironmentOverrides) {
		this(
				gameDirectory,
				launchEnvironmentOverrides,
				System::currentTimeMillis,
				null,
				null,
				() -> UUID.randomUUID().toString(),
				new OwnedMaintenanceWorker(),
				CoordinatorProcessOwnership::reapOrphaned,
				new OwnedDependencyMonitorScheduler()
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds
	) {
		this(
				gameDirectory,
				launchEnvironmentOverrides,
				clock,
				dependencyResolver,
				processLauncher,
				launchIds,
				Runnable::run,
				CoordinatorProcessOwnership::reapOrphaned,
				task -> { }
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper
	) {
		this(
				gameDirectory, launchEnvironmentOverrides, clock, dependencyResolver, processLauncher, launchIds,
				maintenanceWorker, orphanReaper, task -> { }
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper,
			DependencyMonitorScheduler dependencyMonitorScheduler
	) {
		this(
				gameDirectory, launchEnvironmentOverrides, clock, dependencyResolver, processLauncher, launchIds,
				maintenanceWorker, orphanReaper, dependencyMonitorScheduler, new DefaultGenerationController()
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper,
			DependencyMonitorScheduler dependencyMonitorScheduler,
			GenerationController generationController
	) {
		this.gameDirectory = Objects.requireNonNull(gameDirectory, "game directory must not be null")
				.toAbsolutePath().normalize();
		this.launchEnvironmentOverrides = Map.copyOf(Objects.requireNonNull(
				launchEnvironmentOverrides,
				"launch environment overrides must not be null"
		));
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		this.dependencyResolver = dependencyResolver == null
				? new DefaultDependencyResolver(this.gameDirectory, this.launchEnvironmentOverrides)
				: dependencyResolver;
		this.processLauncher = processLauncher == null ? new DefaultProcessLauncher() : processLauncher;
		this.launchIds = Objects.requireNonNull(launchIds, "launch IDs must not be null");
		this.maintenanceWorker = Objects.requireNonNull(maintenanceWorker, "maintenance worker must not be null");
		this.orphanReaper = Objects.requireNonNull(orphanReaper, "orphan reaper must not be null");
		this.generationController = Objects.requireNonNull(generationController,
				"generation controller must not be null");
		DependencyMonitorScheduler monitorScheduler = Objects.requireNonNull(
				dependencyMonitorScheduler, "dependency monitor scheduler must not be null"
		);
		this.createdAtEpochMs = now();
		if (!autoStartEnabled()) {
			stopped = true;
			state = CoordinatorRecoveryState.STOPPED;
			this.maintenanceWorker.close();
			monitorScheduler.close();
			this.dependencyChangeMonitor = null;
			return;
		}
		state = CoordinatorRecoveryState.STARTING;
		nextRetryEpochMs = createdAtEpochMs + CoordinatorLaunchPolicy.STARTUP_GRACE_MS;
		this.dependencyChangeMonitor = new DependencyChangeMonitor(
				this::safeFingerprint, this::publishDependencyFingerprintChange, monitorScheduler
		);
		submitDependencyMaintenance(createdAtEpochMs, true, true);
		drainMaintenanceResults(createdAtEpochMs);
	}

	@FunctionalInterface
	interface ProcessLauncher {
		ChildProcess launch(LaunchRequest request) throws IOException;
	}

	interface ChildProcess {
		boolean isAlive();

		long pid();

		void terminate();
	}

	interface DependencyResolver {
		String fingerprint();

		DependencyResolution resolve();
	}

	interface GenerationController {
		GenerationStatus promote(Path root, String generationId) throws IOException;

		GenerationStatus rollback(Path root, String generationId) throws IOException;
	}

	record GenerationStatus(String generationId, boolean candidate, boolean lastKnownGoodAvailable) {
		GenerationStatus {
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			if (!generationId.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("coordinator generation ID is invalid");
			}
		}
	}

	@FunctionalInterface
	interface MaintenanceWorker extends AutoCloseable {
		void execute(Runnable task);

		@Override
		default void close() {
		}
	}

	@FunctionalInterface
	interface DependencyMonitorScheduler extends AutoCloseable {
		void start(Runnable task);

		@Override
		default void close() {
		}
	}

	@FunctionalInterface
	interface OrphanReaper {
		int reap(Path runtimeRoot) throws IOException;
	}

	private sealed interface MaintenanceResult permits DependencyMaintenanceResult,
			DependencyFingerprintChanged, LaunchMaintenanceResult, TerminationMaintenanceResult,
			PromotionMaintenanceResult, RollbackMaintenanceResult {
	}

	private record DependencyMaintenanceResult(
			String fingerprint,
			DependencyResolution resolution,
			boolean fingerprintChanged,
			boolean initial,
			boolean orphanCleanupSucceeded,
			List<Path> orphanRootsReaped,
			long submittedWakeGeneration
	) implements MaintenanceResult {
	}

	private record DependencyFingerprintChanged(long generation) implements MaintenanceResult {
	}

	private record LaunchMaintenanceResult(
			ChildProcess child,
			String launchId,
			long startedAtEpochMs,
			String failureMessage
	) implements MaintenanceResult {
	}

	private record TerminationMaintenanceResult(ChildProcess child, String failureMessage) implements MaintenanceResult {
	}

	private record PromotionMaintenanceResult(GenerationStatus status, String generationId, String failureMessage)
			implements MaintenanceResult {
	}

	private record RollbackMaintenanceResult(GenerationStatus status, String generationId, String failureMessage)
			implements MaintenanceResult {
	}

	record DependencyResolution(
			PreparedRuntime runtime,
			String failureCode,
			String failureMessage
	) {
		DependencyResolution {
			if (runtime == null && (failureCode == null || failureCode.isBlank())) {
				throw new IllegalArgumentException("unavailable coordinator dependencies require a failure code");
			}
		}

		static DependencyResolution ready(PreparedRuntime runtime) {
			PreparedRuntime prepared = Objects.requireNonNull(runtime, "runtime must not be null");
			if (prepared.nodeExecutable() == null) throw new IllegalArgumentException("ready runtime requires Node");
			return new DependencyResolution(prepared, null, null);
		}

		static DependencyResolution blocked(String code, String message) {
			return blocked(null, code, message);
		}

		static DependencyResolution blocked(PreparedRuntime runtime, String code, String message) {
			return new DependencyResolution(
					runtime, Objects.requireNonNull(code, "failure code must not be null"), message
			);
		}

		boolean ready() {
			return runtime != null && runtime.nodeExecutable() != null && failureCode == null;
		}
	}

	record PreparedRuntime(
			Path root,
			Path coordinatorRoot,
			Path main,
			Path config,
			Path secret,
			Path nodeExecutable,
			String bridgeSecret,
			int bridgePort,
			String generationId,
			boolean candidate,
			boolean lastKnownGoodAvailable
	) {
		PreparedRuntime(
				Path root,
				Path coordinatorRoot,
				Path main,
				Path config,
				Path secret,
				Path nodeExecutable,
				String bridgeSecret
		) {
			this(
					root, coordinatorRoot, main, config, secret, nodeExecutable, bridgeSecret, 25_570,
					"0".repeat(64), false, false
			);
		}

		PreparedRuntime(
				Path root,
				Path coordinatorRoot,
				Path main,
				Path config,
				Path secret,
				Path nodeExecutable,
				String bridgeSecret,
				String generationId,
				boolean candidate,
				boolean lastKnownGoodAvailable
		) {
			this(root, coordinatorRoot, main, config, secret, nodeExecutable, bridgeSecret, 25_570,
					generationId, candidate, lastKnownGoodAvailable);
		}

		PreparedRuntime {
			root = normalized(root, "runtime root");
			coordinatorRoot = normalized(coordinatorRoot, "coordinator root");
			main = normalized(main, "coordinator main");
			config = normalized(config, "coordinator config");
			secret = normalized(secret, "bridge secret");
			nodeExecutable = nodeExecutable == null ? null : normalized(nodeExecutable, "Node executable");
			bridgeSecret = Objects.requireNonNull(bridgeSecret, "bridge secret value must not be null").strip();
			if (bridgeSecret.length() < 32 || bridgeSecret.length() > 256) {
				throw new IllegalArgumentException("bridge secret value is invalid");
			}
			if (bridgePort < 1 || bridgePort > 65_535) {
				throw new IllegalArgumentException("bridge port must be between 1 and 65535");
			}
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			if (!generationId.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("coordinator generation ID is invalid");
			}
		}

		private static Path normalized(Path path, String label) {
			return Objects.requireNonNull(path, label + " must not be null").toAbsolutePath().normalize();
		}
	}

	record LaunchRequest(
			List<String> command,
			Path workingDirectory,
			Map<String, String> environment,
			Path standardOutput,
			Path standardError,
			Path runtimeRoot,
			Path main,
			String generationId,
			String launchId
	) {
		LaunchRequest {
			command = List.copyOf(command);
			workingDirectory = normalized(workingDirectory, "working directory");
			environment = Map.copyOf(environment);
			standardOutput = normalized(standardOutput, "standard output");
			standardError = normalized(standardError, "standard error");
			runtimeRoot = normalized(runtimeRoot, "runtime root");
			main = normalized(main, "coordinator main");
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			launchId = UUID.fromString(Objects.requireNonNull(launchId, "launch ID must not be null")).toString();
		}

		private static Path normalized(Path path, String label) {
			return Objects.requireNonNull(path, label + " must not be null").toAbsolutePath().normalize();
		}
	}

	synchronized boolean configured() {
		return runtime != null && runtime.nodeExecutable() != null;
	}

	synchronized Path secretPath() {
		return runtime == null ? null : runtime.secret();
	}

	synchronized String bridgeSecret() {
		return runtime == null ? null : runtime.bridgeSecret();
	}

	synchronized int bridgePort() {
		return runtime == null ? 25_570 : runtime.bridgePort();
	}

	synchronized String failureCode() {
		return failureCode;
	}

	synchronized String failureMessage() {
		return failureMessage;
	}

	synchronized long bridgeRevision() {
		return bridgeRevision;
	}

	synchronized String runtimeGenerationId() {
		return runtime == null ? null : runtime.generationId();
	}

	void publishDependencyFingerprintChange() {
		if (stopped) return;
		long generation = dependencyWakeGeneration.incrementAndGet();
		if (dependencyWakeQueued.compareAndSet(false, true)) {
			maintenanceResults.add(new DependencyFingerprintChanged(generation));
		}
	}

	synchronized void tick(boolean bridgeAuthenticated) {
		tick(bridgeAuthenticated, bridgeAuthenticated ? launchId : null, bridgeAuthenticated ? 1L : 0L);
	}

	synchronized void tick(boolean bridgeAuthenticated, String authenticatedLaunchId) {
		tick(bridgeAuthenticated, authenticatedLaunchId, bridgeAuthenticated ? 1L : 0L);
	}

	synchronized void tick(boolean bridgeAuthenticated, String authenticatedLaunchId, long sessionGeneration) {
		// The legacy verification overload predates the coordinator readiness gate. Keep
		// it source-compatible for isolated supervisor tests; production uses the
		// explicit readiness-aware overload below.
		tick(bridgeAuthenticated, authenticatedLaunchId, sessionGeneration, true);
	}

	/**
	 * Advances supervision with the authenticated bridge's reconciliation boundary.
	 * Authentication proves transport ownership only; candidate generations are not
	 * promoted until the coordinator has also reconciled its catalog and roster.
	 */
	synchronized void tick(
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady
	) {
		if (stopped) return;
		long now = now();
		drainMaintenanceResults(now);

		if (child != null && !child.isAlive()) {
			queueTermination(detachChild());
			recordFailure(now, "COORDINATOR_EXITED", "Coordinator process exited unexpectedly", "process");
		}

		if (child != null) {
			observeOwnedChild(now, bridgeAuthenticated, authenticatedLaunchId, sessionGeneration, coordinatorReady);
			if (child != null) submitDependencyMaintenance(now, false, false);
		}
		if (pendingTermination != null) {
			if (now >= nextTerminationRetryEpochMs) submitTerminationMaintenance();
			drainMaintenanceResults(now);
			if (pendingTermination != null || maintenancePending) return;
		}
		if (rollbackRequested && child == null) {
			if (now < nextGenerationMutationEpochMs) return;
			submitRollbackMaintenance();
			drainMaintenanceResults(now);
			if (rollbackRequested || maintenancePending) return;
		}
		if (child != null) {
			return;
		}
		if (maintenancePending) return;

		if (runtime == null || runtime.nodeExecutable() == null) {
			if (now < nextDependencyCheckEpochMs) return;
			submitDependencyMaintenance(now, false, false);
			drainMaintenanceResults(now);
			if (maintenancePending || runtime == null || runtime.nodeExecutable() == null) return;
			if (pendingTermination != null) {
				submitTerminationMaintenance();
				drainMaintenanceResults(now);
				if (maintenancePending || pendingTermination != null) return;
			}
		}
		if (now >= nextDependencyCheckEpochMs) {
			submitDependencyMaintenance(now, false, false);
			drainMaintenanceResults(now);
			if (maintenancePending || runtime == null || runtime.nodeExecutable() == null) return;
			if (pendingTermination != null) {
				submitTerminationMaintenance();
				drainMaintenanceResults(now);
				if (maintenancePending || pendingTermination != null) return;
			}
		}

		if (bridgeAuthenticated && authenticatedLaunchId == null) {
			state = CoordinatorRecoveryState.HEALTHY;
			nextRetryEpochMs = 0L;
			coordinatorReconciled = coordinatorReady;
			clearDiagnostic();
			submitDependencyMaintenance(now, false, false);
			return;
		}
		if (now >= nextRetryEpochMs) {
			submitLaunchMaintenance(now);
			drainMaintenanceResults(now);
			return;
		}
		submitDependencyMaintenance(now, false, false);
	}

	synchronized CoordinatorRecoverySnapshot snapshot() {
		return new CoordinatorRecoverySnapshot(
				state,
				generation,
				restartBudget.restartCount(),
				lastStableEpochMs,
				nextRetryEpochMs,
				failureCode,
				failureMessage,
				failingBoundary,
				child == null ? null : launchId,
				child == null ? -1L : child.pid(),
				child == null ? 0L : processStartedEpochMs,
				child == null ? 0L : authenticationDeadlineEpochMs,
				child == null ? 0L : reconnectDeadlineEpochMs
		);
	}

	private void observeOwnedChild(
			long now,
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady
	) {
		boolean matchingAuthentication = bridgeAuthenticated
				&& launchId != null
				&& launchId.equals(authenticatedLaunchId)
				&& sessionGeneration > 0L;
		if (matchingAuthentication) {
			if (state != CoordinatorRecoveryState.HEALTHY
					|| authenticatedSessionGeneration != sessionGeneration) {
				state = CoordinatorRecoveryState.HEALTHY;
				authenticatedSinceEpochMs = now;
				stabilityCredited = false;
			}
			authenticatedSessionGeneration = sessionGeneration;
			coordinatorReconciled = coordinatorReady;
			failingBoundary = null;
			clearDiagnostic();
			authenticationDeadlineEpochMs = 0L;
			reconnectDeadlineEpochMs = 0L;
			nextRetryEpochMs = 0L;
			if (!stabilityCredited && coordinatorReconciled
					&& now - authenticatedSinceEpochMs >= STABILITY_INTERVAL_MS) {
				if (runtime != null && runtime.candidate()) {
					if (now >= nextGenerationMutationEpochMs) submitPromotionMaintenance();
				} else {
					creditStability(now);
				}
			}
			return;
		}

		if (state == CoordinatorRecoveryState.HEALTHY || state == CoordinatorRecoveryState.DEGRADED) {
			if (state == CoordinatorRecoveryState.HEALTHY) {
				state = CoordinatorRecoveryState.DEGRADED;
				coordinatorReconciled = false;
				reconnectDeadlineEpochMs = now + RECONNECT_TIMEOUT_MS;
				authenticatedSinceEpochMs = 0L;
				stabilityCredited = false;
				setDiagnostic(
						"COORDINATOR_BRIDGE_DISCONNECTED",
						"Authenticated coordinator bridge disconnected; waiting for reconnection",
						"bridge_reconnect"
				);
			}
			if (now >= reconnectDeadlineEpochMs) {
				queueTermination(detachChild());
				recordFailure(now, "COORDINATOR_RECONNECT_TIMEOUT",
						"Coordinator stayed alive but did not restore its authenticated bridge", "bridge_reconnect");
			}
			return;
		}

		state = CoordinatorRecoveryState.AUTHENTICATING;
		coordinatorReconciled = false;
		if (now >= authenticationDeadlineEpochMs) {
			queueTermination(detachChild());
			recordFailure(now, "COORDINATOR_AUTHENTICATION_TIMEOUT",
					"Coordinator process did not authenticate before its deadline", "bridge_authentication");
		}
	}

	private void creditStability(long now) {
		restartBudget.resetAfterStability();
		lastStableEpochMs = now;
		stabilityCredited = true;
		candidateFailures = 0;
	}

	private boolean applyDependencyResolution(
			DependencyResolution resolution,
			long now,
			boolean initial,
			boolean fingerprintChanged
	) {
		nextDependencyCheckEpochMs = now + DEPENDENCY_RECHECK_MS;
		PreparedRuntime previous = runtime;
		runtime = resolution.runtime();
		boolean launchMaterialChanged = previous != null && runtime != null && (
				!Objects.equals(previous.root(), runtime.root())
						|| !Objects.equals(previous.coordinatorRoot(), runtime.coordinatorRoot())
						|| !Objects.equals(previous.main(), runtime.main())
						|| !Objects.equals(previous.config(), runtime.config())
						|| !Objects.equals(previous.secret(), runtime.secret())
						|| !Objects.equals(previous.nodeExecutable(), runtime.nodeExecutable())
						|| !Objects.equals(previous.bridgeSecret(), runtime.bridgeSecret())
						|| previous.bridgePort() != runtime.bridgePort()
						|| !Objects.equals(previous.generationId(), runtime.generationId())
		);
		if (runtime == null || previous == null || !previous.generationId().equals(runtime.generationId())) {
			candidateFailures = 0;
			rollbackRequested = false;
		}
		if (runtime != null && !runtime.candidate()) {
			candidateFailures = 0;
			rollbackRequested = false;
		}
		if (runtime != null) configureSharedBridgeSecretPath(runtime.secret());
		if (runtime != null) configureSharedVoiceEndpoint(runtime.config());
		if (runtime != null && (previous == null
				|| !previous.secret().equals(runtime.secret())
				|| !previous.bridgeSecret().equals(runtime.bridgeSecret())
				|| previous.bridgePort() != runtime.bridgePort())) {
			bridgeRevision++;
		}
		if (!resolution.ready()) {
			queueTermination(detachChild());
			state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
			nextRetryEpochMs = nextDependencyCheckEpochMs;
			authenticationDeadlineEpochMs = 0L;
			reconnectDeadlineEpochMs = 0L;
			authenticatedSinceEpochMs = 0L;
			stabilityCredited = false;
			setDiagnostic(resolution.failureCode(), resolution.failureMessage(), "startup_dependencies");
			return false;
		}

		if ((fingerprintChanged || launchMaterialChanged) && previous != null && child != null) {
			queueTermination(detachChild());
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = now;
		} else if (state == CoordinatorRecoveryState.BLOCKED_RETRYABLE) {
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = now;
		} else if (initial) {
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = createdAtEpochMs + CoordinatorLaunchPolicy.STARTUP_GRACE_MS;
		}
		return true;
	}

	private void submitDependencyMaintenance(long requestedAt, boolean force, boolean initial) {
		if (maintenancePending || stopped) return;
		// Honor the deadline before touching dependency files. A no-op completion keeps
		// the maintenance queue's ordering deterministic without calling safeFingerprint.
		if (!force && orphanCleanupComplete && now() < nextDependencyCheckEpochMs) {
			maintenancePending = true;
			String observedFingerprint = dependencyFingerprint;
			long submittedWakeGeneration = dependencyWakeGeneration.get();
			submitMaintenance(() -> publishMaintenanceResult(new DependencyMaintenanceResult(
					observedFingerprint, null, false, false, false, List.of(), submittedWakeGeneration
			)));
			return;
		}
		List<Path> cleanupRoots = orphanRuntimeRoots();
		Set<Path> rootsAlreadyReaped = Set.copyOf(reapedOrphanRoots);
		boolean reapRequired = !orphanCleanupComplete
				|| cleanupRoots.stream().anyMatch(root -> !rootsAlreadyReaped.contains(root));
		maintenancePending = true;
		String previousFingerprint = dependencyFingerprint;
		long scheduledCheck = nextDependencyCheckEpochMs;
		long submittedWakeGeneration = dependencyWakeGeneration.get();
		submitMaintenance(() -> {
			String currentFingerprint = safeFingerprint();
			dependencyChangeMonitor.observeSubmittedFingerprint(currentFingerprint);
			boolean changed = !Objects.equals(previousFingerprint, currentFingerprint);
			long checkedAt = now();
			if (!force && !reapRequired && !changed && checkedAt < scheduledCheck) {
				publishMaintenanceResult(new DependencyMaintenanceResult(
						currentFingerprint, null, false, initial, false, List.of(), submittedWakeGeneration
				));
				return;
			}
			boolean reaped = !reapRequired;
			if (reapRequired) {
				try {
					int count = 0;
					for (Path root : cleanupRoots) {
						if (rootsAlreadyReaped.contains(root)) continue;
						int rootCount = orphanReaper.reap(root);
						count += rootCount;
					}
					if (count > 0) {
						LOGGER.warn("Stopped {} orphaned Arena Agents coordinator process(es) before updating the runtime", count);
					}
					reaped = true;
				} catch (IOException | RuntimeException failure) {
					publishMaintenanceResult(new DependencyMaintenanceResult(
							currentFingerprint,
							DependencyResolution.blocked(
									"COORDINATOR_ORPHAN_CLEANUP_FAILED",
									failure.getMessage() == null ? "Owned coordinator cleanup is temporarily unavailable" : failure.getMessage()
								),
								changed,
								initial,
								false,
								List.of(),
								submittedWakeGeneration
							));
					return;
				}
			}
			publishMaintenanceResult(new DependencyMaintenanceResult(
				currentFingerprint, resolveDependencies(), changed, initial, reaped,
				reaped ? List.copyOf(cleanupRoots) : List.of(), submittedWakeGeneration
			));
		});
	}

	private void submitLaunchMaintenance(long requestedAt) {
		if (maintenancePending || stopped) return;
		generation++;
		state = CoordinatorRecoveryState.STARTING;
		String ownedLaunchId;
		try {
			ownedLaunchId = UUID.fromString(Objects.requireNonNull(launchIds.get(), "launch ID must not be null")).toString();
		} catch (RuntimeException failure) {
			recordFailure(requestedAt, "COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator", "process_start");
			return;
		}
		PreparedRuntime prepared = Objects.requireNonNull(runtime, "coordinator runtime is not prepared");
		launchId = ownedLaunchId;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				LaunchRequest request = launchRequest(prepared, ownedLaunchId);
				ChildProcess started = Objects.requireNonNull(processLauncher.launch(request), "process launcher returned no child");
				publishMaintenanceResult(new LaunchMaintenanceResult(started, ownedLaunchId, now(), null));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new LaunchMaintenanceResult(
						null, ownedLaunchId, now(), failure.getMessage()
				));
			}
		});
	}

	private void submitTerminationMaintenance() {
		if (maintenancePending || pendingTermination == null || stopped) return;
		ChildProcess terminating = pendingTermination;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				terminating.terminate();
				publishMaintenanceResult(new TerminationMaintenanceResult(terminating, null));
			} catch (RuntimeException failure) {
				publishMaintenanceResult(new TerminationMaintenanceResult(
						terminating, Objects.toString(failure.getMessage(), "Owned process-tree termination failed")
				));
			}
		});
	}

	private void submitPromotionMaintenance() {
		if (maintenancePending || stopped || runtime == null || !runtime.candidate()) return;
		PreparedRuntime promoting = runtime;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				GenerationStatus status = generationController.promote(promoting.root(), promoting.generationId());
				publishMaintenanceResult(new PromotionMaintenanceResult(status, promoting.generationId(), null));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new PromotionMaintenanceResult(
						null, promoting.generationId(), Objects.toString(failure.getMessage(), "Candidate promotion failed")
				));
			}
		});
	}

	private void submitRollbackMaintenance() {
		if (maintenancePending || stopped || runtime == null || !runtime.candidate()
				|| !runtime.lastKnownGoodAvailable()) {
			rollbackRequested = false;
			return;
		}
		PreparedRuntime failed = runtime;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				GenerationStatus status = generationController.rollback(failed.root(), failed.generationId());
				publishMaintenanceResult(new RollbackMaintenanceResult(status, failed.generationId(), null));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new RollbackMaintenanceResult(
						null, failed.generationId(), Objects.toString(failure.getMessage(), "Candidate rollback failed")
				));
			}
		});
	}

	private void submitMaintenance(Runnable task) {
		try {
			maintenanceWorker.execute(task);
		} catch (RuntimeException failure) {
			maintenancePending = false;
			state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
			nextRetryEpochMs = now() + DEPENDENCY_RECHECK_MS;
			setDiagnostic("COORDINATOR_MAINTENANCE_UNAVAILABLE", failure.getMessage(), "maintenance_worker");
		}
	}

	private void publishMaintenanceResult(MaintenanceResult result) {
		ChildProcess cleanup = null;
		synchronized (this) {
			if (stopped && result instanceof LaunchMaintenanceResult launch && launch.child() != null) {
				cleanup = launch.child();
			} else if (!stopped) {
				maintenanceResults.add(result);
			}
		}
		if (cleanup != null) cleanup.terminate();
	}

	private void drainMaintenanceResults(long now) {
		MaintenanceResult result;
		while ((result = maintenanceResults.poll()) != null) {
			if (result instanceof DependencyFingerprintChanged) {
				dependencyWakeQueued.set(false);
				nextDependencyCheckEpochMs = 0L;
				continue;
			}
			maintenancePending = false;
			if (result instanceof DependencyMaintenanceResult dependency) {
				dependencyFingerprint = dependency.fingerprint();
				if (dependency.orphanCleanupSucceeded()) {
					reapedOrphanRoots.addAll(dependency.orphanRootsReaped());
					orphanCleanupComplete = orphanRuntimeRoots().stream().allMatch(reapedOrphanRoots::contains);
				}
				if (dependency.resolution() != null) {
					applyDependencyResolution(
							dependency.resolution(), now, dependency.initial(), dependency.fingerprintChanged()
					);
				}
				if (dependencyWakeGeneration.get() > dependency.submittedWakeGeneration()) {
					nextDependencyCheckEpochMs = 0L;
				}
			} else if (result instanceof LaunchMaintenanceResult launch) {
				if (launch.child() == null) {
					launchId = null;
					recordFailure(now, "COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator", "process_start");
					continue;
				}
				if (!Objects.equals(launchId, launch.launchId())) {
					queueTermination(launch.child());
					continue;
				}
				child = launch.child();
				processStartedEpochMs = launch.startedAtEpochMs();
				authenticationDeadlineEpochMs = launch.startedAtEpochMs() + AUTHENTICATION_TIMEOUT_MS;
				reconnectDeadlineEpochMs = 0L;
				authenticatedSinceEpochMs = 0L;
				coordinatorReconciled = false;
				stabilityCredited = false;
				nextRetryEpochMs = 0L;
				state = CoordinatorRecoveryState.AUTHENTICATING;
				failingBoundary = "bridge_authentication";
				LOGGER.info("Started the Arena Agents coordinator (pid {}, generation {})", child.pid(), generation);
			} else if (result instanceof TerminationMaintenanceResult termination) {
				if (pendingTermination != termination.child()) continue;
				if (termination.failureMessage() != null) {
					nextTerminationRetryEpochMs = now + TERMINATION_RETRY_MS;
					state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
					setDiagnostic("COORDINATOR_TERMINATION_FAILED", termination.failureMessage(), "process_termination");
					continue;
				}
				pendingTermination = null;
				nextTerminationRetryEpochMs = 0L;
			} else if (result instanceof PromotionMaintenanceResult promotion) {
				if (runtime == null || !promotion.generationId().equals(runtime.generationId())) continue;
				if (promotion.status() == null) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_PROMOTION_FAILED", promotion.failureMessage(), "runtime_promotion");
					continue;
				}
				if (!promotion.generationId().equals(promotion.status().generationId())) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_PROMOTION_FAILED",
							"Candidate promotion returned a different generation", "runtime_promotion");
					continue;
				}
				runtime = withGeneration(runtime, promotion.status());
				nextGenerationMutationEpochMs = 0L;
				creditStability(now);
			} else if (result instanceof RollbackMaintenanceResult rollback) {
				if (runtime == null || !rollback.generationId().equals(runtime.generationId())) continue;
				if (rollback.status() == null) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_ROLLBACK_FAILED", rollback.failureMessage(), "runtime_rollback");
					continue;
				}
				runtime = withGeneration(runtime, rollback.status());
				candidateFailures = 0;
				rollbackRequested = false;
				nextGenerationMutationEpochMs = 0L;
				nextDependencyCheckEpochMs = now + DEPENDENCY_RECHECK_MS;
				nextRetryEpochMs = now;
				LOGGER.warn("Rolled back failed coordinator generation {} to verified generation {}",
						rollback.generationId(), rollback.status().generationId());
			}
		}
	}

	private static PreparedRuntime withGeneration(PreparedRuntime runtime, GenerationStatus status) {
		return new PreparedRuntime(
				runtime.root(), runtime.coordinatorRoot(), runtime.main(), runtime.config(), runtime.secret(),
				runtime.nodeExecutable(), runtime.bridgeSecret(), runtime.bridgePort(), status.generationId(), status.candidate(),
				status.lastKnownGoodAvailable()
		);
	}

	private DependencyResolution resolveDependencies() {
		try {
			DependencyResolution resolution = dependencyResolver.resolve();
			return Objects.requireNonNull(resolution, "dependency resolution must not be null");
		} catch (RuntimeException exception) {
			return DependencyResolution.blocked(
					"COORDINATOR_STARTUP_INVALID",
					exception.getMessage() == null ? "Coordinator startup dependencies are unavailable" : exception.getMessage()
			);
		}
	}

	/**
	 * Returns every runtime root that may contain an ownership record for this
	 * server. The installed game-local root is retained for upgrades, while the
	 * selected external package root covers packageRoot migrations without ever
	 * broadening cleanup beyond known roots.
	 */
	private synchronized List<Path> orphanRuntimeRoots() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured != null && !configured.isBlank()) {
			try {
				roots.add(Path.of(configured).toAbsolutePath().normalize());
			} catch (RuntimeException ignored) {
				// Dependency resolution reports the malformed path; cleanup remains bounded.
			}
		}
		Path discovered = findPackageRoot(gameDirectory);
		if (discovered != null) roots.add(discovered);
		roots.add(gameDirectory.resolve("arena-agents-runtime").toAbsolutePath().normalize());
		return List.copyOf(roots);
	}

	private String safeFingerprint() {
		try {
			return Objects.toString(dependencyResolver.fingerprint(), "");
		} catch (RuntimeException exception) {
			return "fingerprint-unavailable:" + exception.getClass().getName();
		}
	}

	private LaunchRequest launchRequest(PreparedRuntime prepared, String ownedLaunchId) {
		Map<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		environment.putAll(System.getenv());
		for (Map.Entry<String, String> override : launchEnvironmentOverrides.entrySet()) {
			environment.put(override.getKey(), override.getValue());
		}
		environment.put("ARENA_AGENT_BRIDGE_SECRET", prepared.bridgeSecret());
		environment.put("ARENA_AGENT_COORDINATOR_LAUNCH_ID", ownedLaunchId);
		environment.put("ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION", prepared.generationId());
		Path logs = gameDirectory.resolve("logs");
		return new LaunchRequest(
				List.of(
						prepared.nodeExecutable().toString(),
						prepared.main().toString(),
						"--config",
						prepared.config().toString()
				),
				prepared.coordinatorRoot(),
				environment,
				logs.resolve("arena-agents-coordinator.log"),
				logs.resolve("arena-agents-coordinator-error.log"),
				prepared.root(),
				prepared.main(),
				prepared.generationId(),
				ownedLaunchId
		);
	}

	private void recordFailure(long now, String code, String message, String boundary) {
		restartBudget.recordUnexpectedExit();
		if (runtime != null && runtime.candidate() && candidateFailure(code)) {
			candidateFailures++;
			if (candidateFailures >= CANDIDATE_FAILURES_BEFORE_ROLLBACK
					&& runtime.lastKnownGoodAvailable()) {
				rollbackRequested = true;
				nextGenerationMutationEpochMs = 0L;
			}
		}
		state = CoordinatorRecoveryState.BACKOFF;
		nextRetryEpochMs = now + restartBudget.nextDelayMs();
		processStartedEpochMs = 0L;
		authenticationDeadlineEpochMs = 0L;
		reconnectDeadlineEpochMs = 0L;
		authenticatedSinceEpochMs = 0L;
		coordinatorReconciled = false;
		stabilityCredited = false;
		setDiagnostic(code, message, boundary);
	}

	private static boolean candidateFailure(String code) {
		return "COORDINATOR_START_FAILED".equals(code)
				|| "COORDINATOR_EXITED".equals(code)
				|| "COORDINATOR_AUTHENTICATION_TIMEOUT".equals(code)
				|| "COORDINATOR_RECONNECT_TIMEOUT".equals(code);
	}

	private void setDiagnostic(String code, String message, String boundary) {
		boolean changed = !Objects.equals(failureCode, code)
				|| !Objects.equals(failureMessage, message)
				|| !Objects.equals(failingBoundary, boundary);
		failureCode = code;
		failureMessage = message;
		failingBoundary = boundary;
		if (changed && code != null) {
			LOGGER.warn("Arena Agents coordinator recovering [{}] at {}: {}", code, boundary,
					message == null ? "retry scheduled" : message);
		}
	}

	private void clearDiagnostic() {
		failureCode = null;
		failureMessage = null;
		failingBoundary = null;
	}

	private ChildProcess detachChild() {
		ChildProcess owned = child;
		if (owned == null) return null;
		child = null;
		launchId = null;
		processStartedEpochMs = 0L;
		authenticationDeadlineEpochMs = 0L;
		reconnectDeadlineEpochMs = 0L;
		return owned;
	}

	private void queueTermination(ChildProcess owned) {
		if (owned == null) return;
		if (pendingTermination != null && pendingTermination != owned) {
			throw new IllegalStateException("coordinator termination is already pending");
		}
		pendingTermination = owned;
	}

	static void terminateFailedStart(Process started) {
		CoordinatorProcessOwnership.terminateTree(started.toHandle());
	}

	@Override
	public synchronized void close() {
		if (stopped) return;
		stopped = true;
		state = CoordinatorRecoveryState.STOPPED;
		nextRetryEpochMs = 0L;
		nextDependencyCheckEpochMs = 0L;
		ArrayList<ChildProcess> cleanup = new ArrayList<>();
		ChildProcess active = detachChild();
		if (active != null) cleanup.add(active);
		if (pendingTermination != null && !cleanup.contains(pendingTermination)) cleanup.add(pendingTermination);
		pendingTermination = null;
		MaintenanceResult result;
		while ((result = maintenanceResults.poll()) != null) {
			if (result instanceof LaunchMaintenanceResult launch && launch.child() != null
					&& !cleanup.contains(launch.child())) {
				cleanup.add(launch.child());
			}
		}
		for (ChildProcess process : cleanup) {
			try {
				process.terminate();
			} catch (RuntimeException failure) {
				LOGGER.warn("Could not complete coordinator shutdown cleanup", failure);
			}
		}
		maintenanceWorker.close();
		if (dependencyChangeMonitor != null) dependencyChangeMonitor.close();
	}

	private long now() {
		long value = clock.getAsLong();
		if (value < 0L) throw new IllegalStateException("coordinator clock must not be negative");
		return value;
	}

	private static boolean autoStartEnabled() {
		return !"false".equalsIgnoreCase(System.getProperty("arenaagents.coordinatorAutoStart"));
	}

	static void configureSharedBridgeSecretPath(Path secretPath) {
		Path canonical = secretPath.toAbsolutePath().normalize();
		System.setProperty("arenaagents.bridgeSecretFile", canonical.toString());
		System.setProperty("arenaagents.voiceSecretFile", canonical.toString());
	}

	private void configureSharedVoiceEndpoint(Path configPath) {
		String configured = System.getProperty("arenaagents.voiceUrl");
		if ((configured != null && !configured.isBlank()) || !Files.isRegularFile(configPath)) return;
		try {
			CoordinatorVoiceEndpoint.resolve(configPath, System.getenv(), launchEnvironmentOverrides)
					.ifPresent(endpoint -> System.setProperty("arenaagents.voiceUrl", endpoint));
		} catch (IOException | RuntimeException invalidVoiceConfiguration) {
			LOGGER.warn("Ignoring unavailable optional voice endpoint", invalidVoiceConfiguration);
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
				&& Files.isRegularFile(candidate.resolve("runtime/dynamic-agents.json"));
	}

	private static String dependencyFailureCode(IOException failure) {
		String message = Objects.toString(failure.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
		if (message.contains("secret")) return "BRIDGE_SECRET_INVALID";
		if (message.contains("config")) return "COORDINATOR_CONFIG_INVALID";
		if (message.contains("manifest") || message.contains("package") || message.contains("runtime")) {
			return "COORDINATOR_RUNTIME_INVALID";
		}
		return "COORDINATOR_STARTUP_INVALID";
	}

	private static final class OwnedMaintenanceWorker implements MaintenanceWorker {
		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "arenaagents-coordinator-maintenance");
			thread.setDaemon(true);
			return thread;
		});

		@Override
		public void execute(Runnable task) {
			executor.execute(Objects.requireNonNull(task, "maintenance task must not be null"));
		}

		@Override
		public void close() {
			executor.shutdown();
		}
	}

	private static final class DependencyChangeMonitor implements AutoCloseable {
		private final Supplier<String> fingerprint;
		private final Runnable changed;
		private final DependencyMonitorScheduler scheduler;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final Object stateLock = new Object();
		private String previous;
		private boolean initialized;

		private DependencyChangeMonitor(
				Supplier<String> fingerprint,
				Runnable changed,
				DependencyMonitorScheduler scheduler
		) {
			this.fingerprint = Objects.requireNonNull(fingerprint, "dependency fingerprint must not be null");
			this.changed = Objects.requireNonNull(changed, "dependency wake callback must not be null");
			this.scheduler = Objects.requireNonNull(scheduler, "dependency monitor scheduler must not be null");
			this.scheduler.start(this::poll);
		}

		private void poll() {
			if (closed.get()) return;
			String current = fingerprint.get();
			if (closed.get()) return;
			if (observe(current)) changed.run();
		}

		private void observeSubmittedFingerprint(String submitted) {
			if (observe(submitted)) changed.run();
		}

		private boolean observe(String current) {
			synchronized (stateLock) {
				if (closed.get()) return false;
				if (!initialized) {
					previous = current;
					initialized = true;
					return false;
				}
				if (Objects.equals(previous, current)) return false;
				previous = current;
				return true;
			}
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) return;
			scheduler.close();
		}
	}

	private static final class OwnedDependencyMonitorScheduler implements DependencyMonitorScheduler {
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "arenaagents-coordinator-dependency-monitor");
			thread.setDaemon(true);
			return thread;
		});
		private final AtomicBoolean started = new AtomicBoolean();

		@Override
		public void start(Runnable task) {
			if (!started.compareAndSet(false, true)) return;
			executor.scheduleWithFixedDelay(
					Objects.requireNonNull(task, "dependency monitor task must not be null"),
					0L,
					250L,
					TimeUnit.MILLISECONDS
			);
		}

		@Override
		public void close() {
			executor.shutdownNow();
		}
	}

	private static final class DefaultGenerationController implements GenerationController {
		@Override
		public GenerationStatus promote(Path root, String generationId) throws IOException {
			BundledCoordinatorInstaller.promote(root, generationId);
			return status(BundledCoordinatorInstaller.validate(root));
		}

		@Override
		public GenerationStatus rollback(Path root, String generationId) throws IOException {
			BundledCoordinatorInstaller.rollback(root, generationId);
			return status(BundledCoordinatorInstaller.validate(root));
		}

		private static GenerationStatus status(BundledCoordinatorInstaller.RuntimePackage runtime) {
			return new GenerationStatus(
					runtime.generationId(), runtime.candidate(), runtime.lastKnownGoodAvailable()
			);
		}
	}

	private static final class DefaultProcessLauncher implements ProcessLauncher {
		@Override
		public ChildProcess launch(LaunchRequest request) throws IOException {
			Files.createDirectories(request.standardOutput().getParent());
			CoordinatorLogRotation.rotate(request.standardOutput().getParent());
			ProcessBuilder builder = new ProcessBuilder(request.command());
			builder.directory(request.workingDirectory().toFile());
			builder.environment().clear();
			builder.environment().putAll(request.environment());
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(request.standardOutput().toFile()));
			builder.redirectError(ProcessBuilder.Redirect.appendTo(request.standardError().toFile()));
			Process process = builder.start();
			try {
				CoordinatorProcessOwnership.record(
						request.runtimeRoot(), process, request.main(), request.generationId(), request.launchId()
				);
			} catch (IOException ownershipFailure) {
				terminateFailedStart(process);
				throw ownershipFailure;
			}
			return new OwnedProcessChild(
					request.runtimeRoot(), process, request.generationId(), request.launchId()
			);
		}
	}

	private static final class OwnedProcessChild implements ChildProcess {
		private final Path runtimeRoot;
		private final Process process;
		private final String generationId;
		private final String launchId;
		private final AtomicBoolean terminated = new AtomicBoolean();

		private OwnedProcessChild(Path runtimeRoot, Process process, String generationId, String launchId) {
			this.runtimeRoot = runtimeRoot;
			this.process = process;
			this.generationId = generationId;
			this.launchId = launchId;
		}

		@Override
		public boolean isAlive() {
			return process.isAlive();
		}

		@Override
		public long pid() {
			return process.pid();
		}

		@Override
		public synchronized void terminate() {
			if (terminated.get()) return;
			CoordinatorProcessOwnership.terminateTree(process.toHandle());
			try {
				CoordinatorProcessOwnership.clear(runtimeRoot, process, generationId, launchId);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not safely clear coordinator ownership", exception);
			}
			terminated.set(true);
		}
	}

	private static final class DefaultDependencyResolver implements DependencyResolver {
		private final Path gameDirectory;
		private final Map<String, String> environmentOverrides;

		private DefaultDependencyResolver(Path gameDirectory, Map<String, String> environmentOverrides) {
			this.gameDirectory = gameDirectory;
			this.environmentOverrides = environmentOverrides;
		}

		@Override
		public String fingerprint() {
			Path installedRoot = gameDirectory.resolve("arena-agents-runtime");
			Path packageRoot = findPackageRoot(gameDirectory);
			Path root = packageRoot == null ? installedRoot : packageRoot;
			ArrayList<String> values = new ArrayList<>();
			values.add(Objects.toString(System.getProperty("arenaagents.packageRoot"), ""));
			values.add(Objects.toString(System.getProperty(NodeRuntimeLocator.PROPERTY), ""));
			String path = effectiveEnvironmentValue("PATH");
			values.add(Objects.toString(path, ""));
			values.add(fileStamp(root.resolve("coordinator/.arena-agents-bundle-manifest")));
			values.add(fileStamp(root.resolve("coordinator.last-known-good/.arena-agents-bundle-manifest")));
			values.add(fileStamp(root.resolve("coordinator/src/dynamic-main.mjs")));
			values.add(fileStamp(root.resolve("runtime/coordinator-generation.properties")));
			values.add(fileStamp(root.resolve("runtime/dynamic-agents.json")));
			values.add(fileStamp(root.resolve("runtime/bridge-secret.txt")));
			values.add(fileStamp(bundledNode(root)));
			String explicit = System.getProperty(NodeRuntimeLocator.PROPERTY);
			if (explicit != null && !explicit.isBlank()) {
				try {
					values.add(fileStamp(Path.of(explicit)));
				} catch (RuntimeException invalid) {
					values.add("invalid-explicit-node");
				}
			}
			String executableName = System.getProperty("os.name", "")
					.toLowerCase(java.util.Locale.ROOT).contains("win") ? "node.exe" : "node";
			if (path != null && !path.isBlank()) {
				for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
					if (entry.isBlank()) continue;
					try {
						values.add(fileStamp(Path.of(entry).resolve(executableName)));
					} catch (RuntimeException invalid) {
						values.add("invalid-path-entry");
					}
				}
			}
			for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
				if (override.getKey().equalsIgnoreCase("PATH") || override.getKey().equalsIgnoreCase("APPDATA")) {
					values.add(override.getKey().toUpperCase(java.util.Locale.ROOT) + '=' + override.getValue());
				}
			}
			return String.join("|", values);
		}

		@Override
		public DependencyResolution resolve() {
			BundledCoordinatorInstaller.RuntimePackage prepared = null;
			try {
				Path installedRoot = gameDirectory.resolve("arena-agents-runtime");
				IOException installFailure = null;
				try {
					if (BundledCoordinatorInstaller.installBundled(installedRoot)) {
						LOGGER.info("Installed the bundled Arena Agents coordinator runtime");
					}
				} catch (IOException failure) {
					installFailure = failure;
					LOGGER.warn("Could not refresh the bundled Arena Agents runtime; attempting the last complete installed version", failure);
				}
				Path discoveredRoot = findPackageRoot(gameDirectory);
				if (discoveredRoot == null && installFailure != null) {
					try {
						if (BundledCoordinatorInstaller.restoreLastKnownGoodIfActiveInvalid(installedRoot)) {
							LOGGER.warn("Restored the verified Arena Agents coordinator runtime after the active installation became invalid");
							discoveredRoot = findPackageRoot(gameDirectory);
						}
					} catch (IOException restoreFailure) {
						installFailure.addSuppressed(restoreFailure);
					}
				}
				if (discoveredRoot == null) {
					if (installFailure != null) throw installFailure;
					throw new IOException("Coordinator runtime package is unavailable");
				}
				try {
					prepared = BundledCoordinatorInstaller.validate(discoveredRoot);
				} catch (IOException invalidRuntime) {
					try {
						if (!BundledCoordinatorInstaller.restoreLastKnownGoodIfActiveInvalid(discoveredRoot)) {
							throw invalidRuntime;
						}
						LOGGER.warn("Restored the verified Arena Agents coordinator runtime after active-generation corruption");
						prepared = BundledCoordinatorInstaller.validate(discoveredRoot);
					} catch (IOException restoreFailure) {
						if (restoreFailure != invalidRuntime) invalidRuntime.addSuppressed(restoreFailure);
						if (installFailure != null) invalidRuntime.addSuppressed(installFailure);
						throw invalidRuntime;
					}
				}
				String secret = Files.readString(prepared.secret(), StandardCharsets.UTF_8).trim();
				validateConfig(prepared.config());
				PreparedRuntime partial = prepared(prepared, null, secret);
				try {
					NodeRuntimeLocator.LocatedNode node = NodeRuntimeLocator.locate(prepared.root());
					return DependencyResolution.ready(prepared(prepared, node.executable(), secret));
				} catch (NodeRuntimeLocator.NodeRuntimeFailure failure) {
					return DependencyResolution.blocked(partial, failure.code(), failure.getMessage());
				}
			} catch (IOException failure) {
				return DependencyResolution.blocked(
						prepared == null ? null : safePartial(prepared),
						dependencyFailureCode(failure),
						failure.getMessage() == null ? "Coordinator startup dependencies are unavailable" : failure.getMessage()
				);
			} catch (RuntimeException failure) {
				return DependencyResolution.blocked(
						"COORDINATOR_STARTUP_INVALID",
						failure.getMessage() == null ? "Coordinator startup dependencies are unavailable" : failure.getMessage()
				);
			}
		}

		private String effectiveEnvironmentValue(String name) {
			for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
				if (override.getKey().equalsIgnoreCase(name)) return override.getValue();
			}
			return System.getenv(name);
		}

		private static PreparedRuntime prepared(
				BundledCoordinatorInstaller.RuntimePackage runtime,
				Path node,
				String secret
		) {
			return new PreparedRuntime(
					runtime.root(), runtime.coordinatorRoot(), runtime.main(), runtime.config(), runtime.secret(), node, secret,
					bridgePort(runtime.config()), runtime.generationId(), runtime.candidate(), runtime.lastKnownGoodAvailable()
			);
		}

		private static PreparedRuntime safePartial(BundledCoordinatorInstaller.RuntimePackage runtime) {
			try {
				String secret = Files.readString(runtime.secret(), StandardCharsets.UTF_8).trim();
				return prepared(runtime, null, secret);
			} catch (IOException | RuntimeException ignored) {
				return null;
			}
		}

		private static void validateConfig(Path config) throws IOException {
			try {
				if (!JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).isJsonObject()) {
					throw new IOException("Coordinator config must be a JSON object");
				}
			} catch (com.google.gson.JsonParseException invalid) {
				throw new IOException("Coordinator config is invalid JSON", invalid);
			}
		}

		private static int bridgePort(Path config) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
				if (!root.has("bridge")) return 25_570;
				if (!root.get("bridge").isJsonObject()) throw new IllegalArgumentException("Coordinator config bridge section is invalid");
				JsonObject bridge = root.getAsJsonObject("bridge");
				if (!bridge.has("port") || !bridge.get("port").isJsonPrimitive()
						|| !bridge.getAsJsonPrimitive("port").isNumber()) {
					throw new IllegalArgumentException("Coordinator config bridge.port is missing");
				}
				int port = bridge.get("port").getAsInt();
				if (port < 1 || port > 65_535) throw new IllegalArgumentException("Coordinator config bridge.port is invalid");
				return port;
			} catch (IOException | com.google.gson.JsonParseException failure) {
				throw new IllegalArgumentException("Coordinator config bridge.port is invalid", failure);
			}
		}

		private static String fileStamp(Path path) {
			try {
				Path normalized = path.toAbsolutePath().normalize();
				if (!Files.exists(normalized)) return normalized + ":missing";
				return normalized + ":" + Files.size(normalized) + ":" + Files.getLastModifiedTime(normalized).toMillis();
			} catch (IOException | RuntimeException failure) {
				return Objects.toString(path) + ":unreadable";
			}
		}

		private static Path bundledNode(Path root) {
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
			return windows
					? root.resolve("runtime/toolchains/node/node.exe")
					: root.resolve("runtime/toolchains/node/bin/node");
		}
	}
}
