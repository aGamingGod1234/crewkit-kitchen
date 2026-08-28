package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodec;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Fault-injection verification for coordinator recovery ownership and deadlines. */
public final class CoordinatorProcessSupervisorVerification {
	private static final long STARTUP_GRACE_MS = 3_000L;
	private static final long AUTHENTICATION_TIMEOUT_MS = 15_000L;
	private static final long RECONNECT_TIMEOUT_MS = 10_000L;
	private static final long STABILITY_INTERVAL_MS = 30_000L;
	private static final String GENERATION_A = "a".repeat(64);
	private static final String GENERATION_B = "b".repeat(64);

	private CoordinatorProcessSupervisorVerification() {
	}

	public static int verify() {
		return verifyFaultMatrix();
	}

	/** Replays every injected Java supervisor failure as one deterministic recovery matrix. */
	public static int verifyFaultMatrix() {
		verifyRecoveryContract();
		verifyBridgeBindFailureRecovery();
		verifyOccupiedBridgePortRecoversAndAuthenticates();
		verifyEightCrashesStillRecover();
		verifyHungAuthenticationIsReplaced();
		verifyStaleLaunchAuthenticationIsRejected();
		verifyStaleLaunchCannotSuppressReplacement();
		verifyReconnectRecoveryAndExpiry();
		verifyContinuousStabilityResetsFailures();
		verifyCandidatePromotionUsesMaintenanceWorker();
		verifyRepeatedCandidateAuthenticationFailureRollsBackAfterTermination();
		verifyCandidateRollbackWaitsForConfirmedTermination();
		verifySoleCandidateFailureKeepsRetrying();
		verifyConnectionGenerationResetsUnsampledStability();
		verifyMissingThenRestoredDependency();
		verifyPeriodicDependencyRevalidation();
		verifyHealthyDependencyRevalidation();
		verifyLaunchMaterialChangeFencesOwnedChild();
		verifyBlockingMaintenanceNeverBlocksTicks();
		verifyBlockedMaintenanceWaitsForRetryDeadline();
		verifyProductionDependencyMonitorWakesOnRelevantFileChange();
		verifyWorkerFingerprintObservationAdvancesMonitorBaseline();
		verifyDependencyWakeSurvivesInflightFailure();
		verifyDependencyMonitorCloseDoesNotWaitForPoll();
		verifyOrphanReapRetriesBeforeLaunch();
		verifyBridgeWaitsForWorkerPreparedSecret();
		verifyDeferredVoiceInitialization();
		verifyCandidateReadinessRequiresFreshReconciledStatus();
		verifyAutoStartDisabledStillBindsExplicitBridge();
		verifyPortOnlyChangeAdvancesBridgeRevision();
		verifySecretRepairRebindsBridgeAndAuthenticatesReplacement();
		verifyLaunchFailureRecovers();
		verifyProductionChildPreservesDescendantsAcrossRootExit();
		verifyProductionBridgePortValidation();
		verifyCloseWaitsForInflightOwnedLaunchCleanup();
		verifyCloseTerminatesChildBehindBlockedMaintenance();
		verifyCloseIsIdempotent();
		return 221;
	}

	private static void verifyProductionChildPreservesDescendantsAcrossRootExit() {
		Path root = null;
		CoordinatorProcessSupervisor.ChildProcess owned = null;
		ProcessHandle provider = null;
		try {
			root = Files.createTempDirectory("arena-supervisor-descendants-");
			Path runtimeRoot = root;
			Path main = root.resolve("coordinator/src/dynamic-main.mjs").toAbsolutePath().normalize();
			Path providerPid = root.resolve("runtime/provider.pid");
			Path releaseRoot = root.resolve("runtime/release-root");
			Files.createDirectories(main.getParent());
			Files.createDirectories(providerPid.getParent());
			Files.writeString(main, "// process ownership fixture", StandardCharsets.UTF_8);
			String java = Path.of(
					System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
			).toString();
			CoordinatorProcessSupervisor.LaunchRequest request = new CoordinatorProcessSupervisor.LaunchRequest(
					List.of(
							java, "-cp", System.getProperty("java.class.path"),
							RootExitFixture.class.getName(), providerPid.toString(), releaseRoot.toString()
					),
					root,
					Map.of(),
					root.resolve("logs/coordinator.log"),
					root.resolve("logs/coordinator-error.log"),
					root,
					main,
					GENERATION_A,
					"00000000-0000-0000-0000-000000000990"
			);
			owned = new CoordinatorProcessSupervisor.DefaultProcessLauncher().launch(request);
			long rootPid = owned.pid();
			awaitCondition(() -> readablePid(providerPid), "production child launches its provider descendant");
			provider = ProcessHandle.of(Long.parseLong(Files.readString(providerPid).trim())).orElseThrow();
			assertTrue(owned.isAlive(), "production coordinator root is alive while descendants are captured");
			ProcessHandle exactProvider = provider;
			awaitCondition(
					() -> ownershipTracks(runtimeRoot, exactProvider),
					"production ownership persists the provider PID and start identity"
			);

			Files.writeString(releaseRoot, "exit", StandardCharsets.UTF_8);
			awaitCondition(
					() -> ProcessHandle.of(rootPid).map(handle -> !handle.isAlive()).orElse(true),
					"coordinator root exits before provider cleanup"
			);
			assertTrue(provider.isAlive(), "provider remains alive after coordinator root exit");
			owned.terminate();
			awaitCondition(() -> !exactProvider.isAlive(), "captured provider is terminated after root reparenting");
			assertFalse(Files.exists(CoordinatorProcessOwnership.ownershipFile(root)),
					"ownership clears only after the captured provider exits");
		} catch (Exception exception) {
			throw new AssertionError("production descendant preservation verification failed", exception);
		} finally {
			if (owned != null) {
				try { owned.terminate(); } catch (RuntimeException ignored) { }
			}
			if (provider != null && provider.isAlive()) provider.destroyForcibly();
			if (root != null) deleteTree(root);
		}
	}

	private static void verifyProductionBridgePortValidation() {
		Path config = null;
		try {
			config = Files.createTempFile("arena-bridge-port-", ".json");
			assertEquals(1_024, productionBridgePort(config, "1024.0"),
					"production bridge parser accepts an exact JSON integer at the lower bound");
			assertEquals(65_535, productionBridgePort(config, "65535"),
					"production bridge parser accepts the upper bound");
			assertInvalidBridgePort(config, "1023", "production bridge parser rejects privileged ports");
			assertInvalidBridgePort(config, "65536", "production bridge parser rejects ports above 65535");
			assertInvalidBridgePort(config, "1024.5", "production bridge parser rejects fractional JSON numbers");
			assertInvalidBridgePort(config, "\"25570\"", "production bridge parser rejects numeric strings");
		} catch (IOException exception) {
			throw new AssertionError("production bridge port verification failed", exception);
		} finally {
			if (config != null) {
				try { Files.deleteIfExists(config); } catch (IOException ignored) { }
			}
		}
	}

	private static void verifyLaunchMaterialChangeFencesOwnedChild() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		String oldLaunchId = fixture.supervisor.snapshot().launchId();
		fixture.supervisor.tick(true, oldLaunchId, 1L);
		FakeChild oldChild = fixture.launcher.latest();
		CoordinatorProcessSupervisor.PreparedRuntime oldRuntime = fixture.dependencies.runtime;
		fixture.dependencies.runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
				oldRuntime.root(), oldRuntime.coordinatorRoot(), oldRuntime.main(), oldRuntime.config(),
				oldRuntime.secret(), oldRuntime.nodeExecutable(), oldRuntime.bridgeSecret(),
				GENERATION_B, false, true
		);
		fixture.dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.ready(fixture.dependencies.runtime);

		fixture.clock.advance(5_000L);
		fixture.supervisor.tick(true, oldLaunchId, 1L);
		for (int tick = 0; tick < 4; tick++) fixture.supervisor.tick(true, oldLaunchId, 1L);

		assertEquals(1, oldChild.terminations,
				"runtime generation replacement terminates the exact owned child despite an unchanged fingerprint");
		assertEquals(2, fixture.launcher.launches.size(),
				"runtime generation replacement launches exactly one successor");
		assertEquals(GENERATION_B, fixture.launcher.launches.getLast().generationId(),
				"successor launches only from the newly resolved generation");
		assertTrue(!fixture.launcher.children.getLast().equals(oldChild) && fixture.launcher.children.getLast().isAlive(),
				"one distinct successor remains alive after generation fencing");
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"new generation waits for its own authenticated bridge");

		fixture.supervisor.tick(true, oldLaunchId, 2L);
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"late authentication from the terminated generation cannot become healthy");
		String replacementLaunchId = fixture.supervisor.snapshot().launchId();
		assertTrue(!oldLaunchId.equals(replacementLaunchId),
				"replacement generation receives a fresh launch identity");
		fixture.supervisor.tick(true, replacementLaunchId, 2L);
		assertEquals(CoordinatorRecoveryState.HEALTHY, fixture.supervisor.snapshot().state(),
				"exact replacement identity automatically returns the supervisor to healthy");
		assertEquals(1L, fixture.launcher.children.stream().filter(FakeChild::isAlive).count(),
				"generation replacement retains exactly one live owned child");
		fixture.supervisor.close();
	}

	private static void verifyOccupiedBridgePortRecoversAndAuthenticates() {
		Fixture fixture = Fixture.ready();
		CodexAgentServerRuntime.BridgeSlot slot = null;
		ServerSocket conflict = null;
		Socket authenticated = null;
		try {
			fixture.startFirstProcess();
			String secret = "p".repeat(32);
			conflict = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			int port = conflict.getLocalPort();
			AtomicInteger constructions = new AtomicInteger();
			CodexAgentManager manager = uninitializedManager();
			slot = new CodexAgentServerRuntime.BridgeSlot(fixture.clock);
			CodexAgentServerRuntime.BridgeSlot ownedSlot = slot;
			java.util.function.Function<String, MultiplexedServerBridge> factory = preparedSecret -> {
				constructions.incrementAndGet();
				return MultiplexedServerBridge.withPreparedSecret(manager, port, preparedSecret);
			};

			CodexAgentServerRuntime.reconcilePreparedBridge(slot, 1L, secret, factory);
			assertEquals(null, slot.bridge(), "occupied configured port leaves no partially active bridge");
			assertEquals("BRIDGE_BIND_FAILED", slot.retry().failureCode(),
					"BridgeSlot reports the real bind boundary");
			assertEquals(fixture.clock.now + 1_000L, slot.retry().nextRetryEpochMs(),
					"BridgeSlot schedules the first bind retry deadline");
			assertEquals(1, constructions.get(), "occupied port creates one failed bridge candidate");

			long started = System.nanoTime();
			for (int tick = 0; tick < 50; tick++) {
				fixture.supervisor.tick(false, null, 0L);
				CodexAgentServerRuntime.reconcilePreparedBridge(ownedSlot, 1L, secret, factory);
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			assertTrue(elapsedMs < 250L, "bridge recovery ticks remain nonblocking: elapsed=" + elapsedMs + "ms");
			assertEquals(1, constructions.get(), "BridgeSlot does not spin before its retry deadline");

			conflict.close();
			conflict = null;
			fixture.clock.advance(1_000L);
			fixture.supervisor.tick(false, null, 0L);
			CodexAgentServerRuntime.reconcilePreparedBridge(slot, 1L, secret, factory);
			MultiplexedServerBridge recovered = slot.bridge();
			assertEquals(2, constructions.get(), "released port creates exactly one recovery bridge");
			assertTrue(recovered != null, "BridgeSlot automatically binds after the retry deadline");
			assertEquals(null, slot.retry().failureCode(), "successful bind clears the BridgeSlot failure");
			assertEquals(0L, slot.retry().nextRetryEpochMs(), "successful bind clears the BridgeSlot deadline");

			authenticated = authenticate(
					port,
					secret,
					fixture.supervisor.snapshot().launchId(),
					"bridge-slot-bind-recovery"
			);
			fixture.supervisor.tick(
					recovered.authenticated(),
					recovered.authenticatedLaunchId(),
					recovered.authenticatedSessionGeneration()
			);
			assertTrue(recovered.authenticated(), "recovered bridge authenticates its coordinator");
			assertEquals(CoordinatorRecoveryState.HEALTHY, fixture.supervisor.snapshot().state(),
					"recovered BridgeSlot promotes the supervisor to healthy");
			assertEquals(fixture.supervisor.snapshot().launchId(), recovered.authenticatedLaunchId(),
					"recovered bridge retains the exact launch identity");
			assertTrue(recovered.authenticatedSessionGeneration() > 0L,
					"recovered bridge publishes one positive session generation");

			CodexAgentServerRuntime.reconcilePreparedBridge(slot, 1L, secret, ignored -> {
				throw new AssertionError("unchanged BridgeSlot revision must not construct another bridge");
			});
			assertEquals(recovered, slot.bridge(), "unchanged reconciliation retains exactly one active bridge");
			closeSocket(authenticated);
			authenticated = null;
			slot.close();
			slot = null;
			try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
				assertEquals(port, rebound.getLocalPort(), "closing recovered BridgeSlot releases its listener once");
			}
		} catch (Exception exception) {
			throw new AssertionError("occupied BridgeSlot recovery failed", exception);
		} finally {
			closeSocket(authenticated);
			if (conflict != null) {
				try {
					conflict.close();
				} catch (IOException ignored) {
				}
			}
			if (slot != null) slot.close();
			fixture.supervisor.close();
		}
	}

	private static void verifyCandidatePromotionUsesMaintenanceWorker() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.candidate(true);
		FakeLauncher launcher = new FakeLauncher();
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		FakeGenerationController generations = new FakeGenerationController();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "candidate-promotion-game"), Map.of(), clock, dependencies, launcher,
				() -> "00000000-0000-0000-0000-000000000201", worker, runtimeRoot -> 0, task -> { }, generations
		);
		worker.runNext();
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		worker.runNext();
		supervisor.tick(false, null, 0L);
		String launchId = supervisor.snapshot().launchId();
		assertEquals(GENERATION_B, launcher.launches.getFirst().generationId(),
				"launch request carries the prepared manifest generation");
		supervisor.tick(true, launchId, 1L);
		// The dependency deadline guard intentionally keeps the maintenance queue idle
		// between checks; the next tick below still observes the authenticated child.
		supervisor.tick(true, launchId, 1L);
		clock.advance(STABILITY_INTERVAL_MS);
		supervisor.tick(true, launchId, 1L);
		assertEquals(0, generations.promotions, "server tick only queues candidate promotion");
		assertEquals(0L, supervisor.snapshot().lastStableEpochMs(),
				"candidate is not credited stable before promotion finishes");
		worker.runNext();
		supervisor.tick(true, launchId, 1L);
		if (generations.promotions == 0) worker.runNext();
		assertEquals(1, generations.promotions, "maintenance worker promotes the stable candidate once");
		supervisor.tick(true, launchId, 1L);
		assertEquals(clock.now, supervisor.snapshot().lastStableEpochMs(),
				"promotion result credits the matching candidate stability interval");
		assertEquals(0, supervisor.snapshot().consecutiveFailures(),
				"successful candidate promotion resets crash-loop history");
		supervisor.close();
	}

	private static void verifyRepeatedCandidateAuthenticationFailureRollsBackAfterTermination() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.candidate(true);
		FakeLauncher launcher = new FakeLauncher();
		FakeGenerationController generations = new FakeGenerationController();
		generations.beforeRollback = () -> assertEquals(1, launcher.latest().terminations,
				"candidate rollback begins only after the failed owned child is terminated and cleared");
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "candidate-rollback-game"), Map.of(), clock, dependencies, launcher,
				new SequentialLaunchIds(300), Runnable::run, runtimeRoot -> 0, task -> { }, generations
		);
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		long[] retryDelays = {1_000L, 2_000L};
		for (int failure = 0; failure < 3; failure++) {
			clock.advance(AUTHENTICATION_TIMEOUT_MS);
			supervisor.tick(false, null, 0L);
			if (failure < retryDelays.length) {
				assertEquals(0, generations.rollbacks, "one candidate authentication failure does not roll back early");
				clock.advance(retryDelays[failure]);
				supervisor.tick(false, null, 0L);
			}
		}
		assertEquals(1, generations.rollbacks, "three candidate authentication failures roll back once");
		assertEquals(GENERATION_A, supervisor.runtimeGenerationId(),
				"rollback result publishes the verified last-known-good generation");
		supervisor.tick(false, null, 0L);
		assertEquals(1, generations.rollbacks, "repeated ticks do not repeat a completed rollback");
		supervisor.close();
	}

	private static void verifyCandidateRollbackWaitsForConfirmedTermination() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.candidate(true);
		FakeLauncher launcher = new FakeLauncher();
		FakeGenerationController generations = new FakeGenerationController();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "candidate-termination-retry-game"), Map.of(), clock, dependencies, launcher,
				new SequentialLaunchIds(325), Runnable::run, runtimeRoot -> 0, task -> { }, generations
		);
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		for (long retryDelay : new long[]{1_000L, 2_000L}) {
			clock.advance(AUTHENTICATION_TIMEOUT_MS);
			supervisor.tick(false, null, 0L);
			clock.advance(retryDelay);
			supervisor.tick(false, null, 0L);
		}
		FakeChild failedCandidate = launcher.latest();
		failedCandidate.terminationFailuresRemaining = 1;
		clock.advance(AUTHENTICATION_TIMEOUT_MS);
		supervisor.tick(false, null, 0L);
		assertEquals(1, failedCandidate.terminationAttempts,
				"failed candidate termination is attempted before rollback");
		assertEquals(0, failedCandidate.terminations,
				"refused termination does not report the owned child dead");
		assertEquals(true, failedCandidate.ownershipPresent,
				"refused termination preserves the owned child record");
		assertEquals(0, generations.rollbacks,
				"candidate rollback stays blocked while the exact child may live");
		supervisor.tick(false, null, 0L);
		assertEquals(1, failedCandidate.terminationAttempts,
				"termination retry waits for its bounded retry deadline");
		clock.advance(1_000L);
		supervisor.tick(false, null, 0L);
		assertEquals(2, failedCandidate.terminationAttempts,
				"failed termination is retried through the maintenance boundary");
		assertEquals(false, failedCandidate.ownershipPresent,
				"ownership clears only after confirmed process-tree termination");
		assertEquals(1, generations.rollbacks,
				"rollback proceeds after the exact owned child is confirmed dead");
		supervisor.close();
	}

	private static void verifySoleCandidateFailureKeepsRetrying() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.candidate(false);
		FakeLauncher launcher = new FakeLauncher();
		FakeGenerationController generations = new FakeGenerationController();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "sole-candidate-game"), Map.of(), clock, dependencies, launcher,
				new SequentialLaunchIds(350), Runnable::run, runtimeRoot -> 0, task -> { }, generations
		);
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		long[] retryDelays = {1_000L, 2_000L, 5_000L};
		for (long delay : retryDelays) {
			clock.advance(AUTHENTICATION_TIMEOUT_MS);
			supervisor.tick(false, null, 0L);
			clock.advance(delay);
			supervisor.tick(false, null, 0L);
		}
		assertEquals(0, generations.rollbacks, "a sole runnable candidate is never rolled back or deleted");
		assertEquals(4, launcher.launches.size(), "a sole candidate continues retrying after repeated failures");
		assertEquals(GENERATION_B, supervisor.runtimeGenerationId(), "sole candidate generation remains active");
		supervisor.close();
	}

	public static void main(String[] arguments) {
		verifyCandidatePromotionUsesMaintenanceWorker();
		verifyRepeatedCandidateAuthenticationFailureRollsBackAfterTermination();
		verifyCandidateRollbackWaitsForConfirmedTermination();
		verifySoleCandidateFailureKeepsRetrying();
		System.out.println("PASS: coordinator generation supervisor assertions");
	}

	private static void verifyRecoveryContract() {
		List<String> states = Arrays.stream(CoordinatorRecoveryState.values()).map(Object::toString).toList();
		assertEquals(
				List.of("STARTING", "AUTHENTICATING", "HEALTHY", "DEGRADED", "BACKOFF", "BLOCKED_RETRYABLE", "STOPPED"),
				states,
				"coordinator recovery exposes every non-terminal recovery state"
		);
	}

	private static void verifyBridgeBindFailureRecovery() {
		try {
			FakeClock clock = new FakeClock();
			Class<?> retryType = Class.forName("dev.agaminggod.arenaagents.server.CodexAgentServerRuntime$BridgeRetry");
			var constructor = retryType.getDeclaredConstructor(java.util.function.LongSupplier.class);
			constructor.setAccessible(true);
			Object retry = constructor.newInstance(clock);
			var canAttempt = retryType.getDeclaredMethod("canAttempt");
			var recordFailure = retryType.getDeclaredMethod("recordFailure", String.class, String.class);
			var recordSuccess = retryType.getDeclaredMethod("recordSuccess");
			var nextRetry = retryType.getDeclaredMethod("nextRetryEpochMs");
			var failureCode = retryType.getDeclaredMethod("failureCode");
			for (var method : List.of(canAttempt, recordFailure, recordSuccess, nextRetry, failureCode)) method.setAccessible(true);
			assertEquals(true, canAttempt.invoke(retry), "Java bridge can make its first bind attempt");
			long[] delays = {1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 30_000L};
			for (long delay : delays) {
				recordFailure.invoke(retry, "BRIDGE_BIND_FAILED", "Loopback port is temporarily occupied");
				assertEquals(clock.now + delay, nextRetry.invoke(retry), "Java bridge bind failure schedules capped retry");
				assertEquals(false, canAttempt.invoke(retry), "Java bridge does not spin before its retry deadline");
				clock.advance(delay);
				assertEquals(true, canAttempt.invoke(retry), "Java bridge retries after its bind deadline");
			}
			recordSuccess.invoke(retry);
			assertEquals(0L, nextRetry.invoke(retry), "successful Java bridge bind clears retry deadline");
			assertEquals(null, failureCode.invoke(retry), "successful Java bridge bind clears failure status");
		} catch (ReflectiveOperationException missingRetry) {
			throw new AssertionError("Java bridge retry contract is missing", missingRetry);
		}
	}

	private static void verifyEightCrashesStillRecover() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		long[] delays = {1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L, 30_000L};
		for (int index = 0; index < delays.length; index++) {
			FakeChild crashed = fixture.launcher.latest();
			crashed.crash();
			fixture.supervisor.tick(false, null);
			CoordinatorRecoverySnapshot backoff = fixture.supervisor.snapshot();
			assertEquals(CoordinatorRecoveryState.BACKOFF, backoff.state(), "crash enters backoff");
			assertEquals(index + 1, backoff.consecutiveFailures(), "crash increments consecutive failure count once");
			assertEquals(fixture.clock.now + delays[index], backoff.nextRetryEpochMs(), "crash schedules capped retry");
			fixture.supervisor.tick(false, null);
			assertEquals(index + 1, fixture.launcher.launches.size(), "repeated tick does not duplicate a pending retry");
			fixture.clock.advance(delays[index]);
			fixture.supervisor.tick(false, null);
			assertEquals(index + 2, fixture.launcher.launches.size(), "retry launches after crash " + (index + 1));
		}
		String launchId = fixture.supervisor.snapshot().launchId();
		fixture.supervisor.tick(true, launchId);
		assertEquals(CoordinatorRecoveryState.HEALTHY, fixture.supervisor.snapshot().state(),
				"eighth replacement can still become healthy");
		fixture.supervisor.close();
	}

	private static void verifyHungAuthenticationIsReplaced() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		FakeChild hung = fixture.launcher.latest();
		fixture.clock.advance(AUTHENTICATION_TIMEOUT_MS);
		fixture.supervisor.tick(false, null);
		CoordinatorRecoverySnapshot backoff = fixture.supervisor.snapshot();
		assertEquals(CoordinatorRecoveryState.BACKOFF, backoff.state(), "hung authentication enters backoff");
		assertEquals("COORDINATOR_AUTHENTICATION_TIMEOUT", backoff.failureCode(), "hung authentication reports its boundary");
		assertEquals(1, hung.terminations, "hung child is terminated exactly once");
		fixture.supervisor.tick(false, null);
		assertEquals(1, hung.terminations, "repeated hung tick is idempotent");
		fixture.supervisor.close();
	}

	private static void verifyStaleLaunchAuthenticationIsRejected() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		String staleLaunchId = fixture.supervisor.snapshot().launchId();
		fixture.launcher.latest().crash();
		fixture.supervisor.tick(false, null);
		fixture.clock.advance(1_000L);
		fixture.supervisor.tick(false, null);
		String currentLaunchId = fixture.supervisor.snapshot().launchId();
		assertFalse(staleLaunchId.equals(currentLaunchId), "replacement owns a distinct launch UUID");
		fixture.supervisor.tick(true, staleLaunchId);
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"stale launch authentication cannot promote the current child");
		fixture.clock.advance(AUTHENTICATION_TIMEOUT_MS);
		FakeChild current = fixture.launcher.latest();
		fixture.supervisor.tick(true, staleLaunchId);
		assertEquals(1, current.terminations, "stale launch authentication cannot save a hung replacement");
		fixture.supervisor.close();
	}

	private static void verifyStaleLaunchCannotSuppressReplacement() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		String staleLaunchId = fixture.supervisor.snapshot().launchId();
		fixture.launcher.latest().crash();
		fixture.supervisor.tick(true, staleLaunchId);
		assertEquals(CoordinatorRecoveryState.BACKOFF, fixture.supervisor.snapshot().state(),
				"crashed child enters backoff even while its stale socket remains authenticated");
		fixture.clock.advance(1_000L);
		fixture.supervisor.tick(true, staleLaunchId);
		assertEquals(2, fixture.launcher.launches.size(),
				"stale launch authentication cannot suppress the scheduled replacement");
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"replacement still requires its own matching launch authentication");
		fixture.supervisor.close();
	}

	private static void verifyReconnectRecoveryAndExpiry() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		String launchId = fixture.supervisor.snapshot().launchId();
		FakeChild child = fixture.launcher.latest();
		fixture.supervisor.tick(true, launchId);
		fixture.supervisor.tick(false, null);
		CoordinatorRecoverySnapshot degraded = fixture.supervisor.snapshot();
		assertEquals(CoordinatorRecoveryState.DEGRADED, degraded.state(), "authenticated disconnect enters degraded state");
		assertEquals(fixture.clock.now + RECONNECT_TIMEOUT_MS, degraded.reconnectDeadlineEpochMs(),
				"disconnect receives a bounded reconnect window");
		fixture.clock.advance(RECONNECT_TIMEOUT_MS - 1L);
		fixture.supervisor.tick(true, launchId);
		assertEquals(CoordinatorRecoveryState.HEALTHY, fixture.supervisor.snapshot().state(),
				"matching reconnect recovers the existing child");
		assertEquals(0, child.terminations, "successful reconnect retains the owned child");
		fixture.supervisor.tick(false, null);
		fixture.clock.advance(RECONNECT_TIMEOUT_MS);
		fixture.supervisor.tick(false, null);
		assertEquals("COORDINATOR_RECONNECT_TIMEOUT", fixture.supervisor.failureCode(),
				"expired reconnect reports its boundary");
		assertEquals(1, child.terminations, "expired reconnect replaces the owned child");
		fixture.supervisor.close();
	}

	private static void verifyContinuousStabilityResetsFailures() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		fixture.launcher.latest().crash();
		fixture.supervisor.tick(false, null);
		fixture.clock.advance(1_000L);
		fixture.supervisor.tick(false, null);
		String launchId = fixture.supervisor.snapshot().launchId();
		fixture.supervisor.tick(true, launchId);
		fixture.clock.advance(STABILITY_INTERVAL_MS - 1L);
		fixture.supervisor.tick(true, launchId);
		assertEquals(1, fixture.supervisor.snapshot().consecutiveFailures(),
				"one healthy tick does not reset crash-loop history");
		fixture.clock.advance(1L);
		fixture.supervisor.tick(true, launchId);
		assertEquals(0, fixture.supervisor.snapshot().consecutiveFailures(),
				"continuous authenticated stability resets crash-loop history");
		assertEquals(fixture.clock.now, fixture.supervisor.snapshot().lastStableEpochMs(),
				"stable reset records its promotion time");
		fixture.supervisor.close();
	}

	private static void verifyConnectionGenerationResetsUnsampledStability() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		fixture.launcher.latest().crash();
		fixture.supervisor.tick(false, null, 0L);
		fixture.clock.advance(1_000L);
		fixture.supervisor.tick(false, null, 0L);
		String launchId = fixture.supervisor.snapshot().launchId();
		fixture.supervisor.tick(true, launchId, 1L);
		fixture.clock.advance(STABILITY_INTERVAL_MS);
		fixture.supervisor.tick(true, launchId, 2L);
		assertEquals(1, fixture.supervisor.snapshot().consecutiveFailures(),
				"a reconnect between sampled ticks resets stability when its session generation changes");
		fixture.clock.advance(STABILITY_INTERVAL_MS - 1L);
		fixture.supervisor.tick(true, launchId, 2L);
		assertEquals(1, fixture.supervisor.snapshot().consecutiveFailures(),
				"replacement session must remain continuously authenticated for the full interval");
		fixture.clock.advance(1L);
		fixture.supervisor.tick(true, launchId, 2L);
		assertEquals(0, fixture.supervisor.snapshot().consecutiveFailures(),
				"one continuous matching session generation eventually clears crash-loop history");
		fixture.supervisor.close();
	}

	private static void verifyMissingThenRestoredDependency() {
		Fixture fixture = Fixture.blocked("NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable");
		assertEquals(CoordinatorRecoveryState.BLOCKED_RETRYABLE, fixture.supervisor.snapshot().state(),
				"missing Node is retryable instead of latched");
		fixture.dependencies.restore("node-restored");
		fixture.supervisor.publishDependencyFingerprintChange();
		fixture.supervisor.tick(false, null);
		assertEquals(1, fixture.launcher.launches.size(), "dependency fingerprint change retries immediately");
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"restored Node resumes without restarting Minecraft");
		fixture.supervisor.close();
	}

	private static void verifyPeriodicDependencyRevalidation() {
		Fixture fixture = Fixture.blocked("BRIDGE_SECRET_INVALID", "Bridge secret is invalid");
		fixture.dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.ready(fixture.dependencies.runtime);
		fixture.clock.advance(5_000L);
		fixture.supervisor.tick(false, null);
		assertEquals(1, fixture.launcher.launches.size(), "blocked dependency is periodically revalidated without a fingerprint change");
		fixture.supervisor.close();
	}

	private static void verifyHealthyDependencyRevalidation() {
		Fixture fixture = Fixture.ready();
		assertEquals(1, fixture.dependencies.resolveCalls, "startup validates healthy dependencies once");
		fixture.clock.advance(5_000L);
		fixture.supervisor.tick(false, null);
		assertEquals(2, fixture.dependencies.resolveCalls,
				"healthy Node, runtime, config, and secret dependencies are periodically revalidated");
		fixture.supervisor.close();
	}

	private static void verifyBlockingMaintenanceNeverBlocksTicks() {
		FakeClock clock = new FakeClock();
		BlockingDependencies dependencies = new BlockingDependencies();
		BlockingLauncher launcher = new BlockingLauncher();
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "blocking-supervisor-game"),
				Map.of(),
				clock,
				dependencies,
				launcher,
				() -> "00000000-0000-0000-0000-000000000101",
				worker,
				runtimeRoot -> 0
		);
		assertEquals(1, worker.submissions, "construction schedules one dependency maintenance task");
		Thread resolver = worker.startNext("blocking-dependency-resolution");
		await(dependencies.started, "blocking dependency resolver started on maintenance worker");
		assertTicksPrompt(supervisor, 20, "ticks stay prompt while dependency resolution blocks");
		assertEquals(1, worker.submissions, "ticks coalesce while dependency maintenance is in flight");
		dependencies.release.countDown();
		join(resolver, "dependency maintenance completes after release");
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		assertEquals(2, worker.submissions, "completed dependency maintenance schedules one launch task");
		worker.runNext();
		supervisor.tick(false, null, 0L);
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, supervisor.snapshot().state(),
				"off-thread launch result is promoted by a later tick");

		clock.advance(AUTHENTICATION_TIMEOUT_MS);
		supervisor.tick(false, null, 0L);
		assertEquals(CoordinatorRecoveryState.BACKOFF, supervisor.snapshot().state(),
				"authentication timeout enters backoff before slow termination finishes");
		worker.runNext();
		supervisor.tick(false, null, 0L);
		Thread terminator = worker.startNext("blocking-child-termination");
		await(launcher.child.terminationStarted, "blocking child termination started on maintenance worker");
		int submissionsBeforeTicks = worker.submissions;
		assertTicksPrompt(supervisor, 20, "ticks stay prompt while process-tree termination blocks");
		assertEquals(submissionsBeforeTicks, worker.submissions,
				"ticks do not duplicate maintenance while termination is in flight");
		launcher.child.releaseTermination.countDown();
		join(terminator, "child termination completes after release");
		supervisor.close();
	}

	private static void verifyBlockedMaintenanceWaitsForRetryDeadline() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.blocked(
				"NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable"
		);
		FakeLauncher launcher = new FakeLauncher();
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "blocked-maintenance-game"), Map.of(), clock, dependencies, launcher,
				() -> "00000000-0000-0000-0000-000000000151", worker, runtimeRoot -> 0
		);
		assertEquals(1, worker.submissions, "blocked startup schedules one dependency task");
		worker.runNext();
		supervisor.tick(false, null, 0L);
		for (int index = 0; index < 50; index++) supervisor.tick(false, null, 0L);
		assertEquals(1, worker.submissions,
				"persistent blocked dependencies do not resubmit maintenance before their deadline");
		assertEquals(0, launcher.launches.size(), "blocked dependency churn never spawns a process");
		clock.advance(4_999L);
		supervisor.tick(false, null, 0L);
		assertEquals(1, worker.submissions, "blocked maintenance stays idle one millisecond before retry");
		clock.advance(1L);
		supervisor.tick(false, null, 0L);
		assertEquals(2, worker.submissions, "blocked maintenance submits exactly one task at the retry deadline");
		for (int index = 0; index < 50; index++) supervisor.tick(false, null, 0L);
		assertEquals(2, worker.submissions, "ticks coalesce the deadline retry while it remains queued");
		assertEquals(1, dependencies.resolveCalls, "only the completed blocked attempt resolved dependencies");
		supervisor.close();
	}

	private static void verifyProductionDependencyMonitorWakesOnRelevantFileChange() {
		String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
		Path fixtureRoot = null;
		CoordinatorProcessSupervisor supervisor = null;
		try {
			fixtureRoot = Files.createTempDirectory("arena-dependency-monitor-");
			Path packageRoot = fixtureRoot.resolve("package");
			Path config = packageRoot.resolve("runtime/dynamic-agents.json");
			Files.createDirectories(config.getParent());
			Files.writeString(config, "{}", StandardCharsets.UTF_8);
			System.setProperty("arenaagents.packageRoot", packageRoot.toString());
			FakeClock clock = new FakeClock();
			QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
			ManualDependencyMonitorScheduler monitor = new ManualDependencyMonitorScheduler();
			supervisor = new CoordinatorProcessSupervisor(
					fixtureRoot.resolve("game"), Map.of(), clock, null, new FakeLauncher(),
					() -> "00000000-0000-0000-0000-000000000454", worker, runtimeRoot -> 0, monitor
			);
			monitor.poll();
			worker.runNext();
			supervisor.tick(false, null, 0L);
			assertEquals(1, worker.submissions, "initial production dependency resolution completes once");
			for (int index = 0; index < 50; index++) {
				monitor.poll();
				supervisor.tick(false, null, 0L);
			}
			assertEquals(1, worker.submissions, "unchanged production monitor polls never duplicate resolution");

			Files.writeString(config, "{\"voice\":{\"port\":18766}}", StandardCharsets.UTF_8);
			monitor.poll();
			supervisor.tick(false, null, 0L);
			assertEquals(2, worker.submissions,
					"a real coordinator config stamp change wakes blocked production resolution before five seconds");
			long closeStarted = System.nanoTime();
			supervisor.close();
			supervisor = null;
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted) < 250L,
					"dependency monitor close never joins or blocks the server thread");
			assertTrue(monitor.closed, "supervisor close idempotently stops its production dependency monitor");
			monitor.poll();
			assertEquals(2, worker.submissions, "a closed dependency monitor cannot publish later work");
		} catch (IOException exception) {
			throw new AssertionError("production dependency monitor verification failed", exception);
		} finally {
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			if (fixtureRoot != null) deleteTree(fixtureRoot);
		}
	}

	private static void verifyWorkerFingerprintObservationAdvancesMonitorBaseline() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.blocked(
				"NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable"
		);
		dependencies.fingerprint = "fingerprint-a";
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		ManualDependencyMonitorScheduler monitor = new ManualDependencyMonitorScheduler();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "monotonic-dependency-observation-game"), Map.of(), clock, dependencies,
				new FakeLauncher(), () -> "00000000-0000-0000-0000-000000000457",
				worker, runtimeRoot -> 0, monitor
		);

		worker.runNext();
		supervisor.tick(false, null, 0L);
		dependencies.fingerprint = "fingerprint-b";
		clock.advance(5_000L);
		supervisor.tick(false, null, 0L);
		worker.runNext();
		supervisor.tick(false, null, 0L);
		assertEquals(3, worker.submissions,
				"the first worker observation of fingerprint B queues one replacement resolution");

		worker.runNext();
		supervisor.tick(false, null, 0L);
		assertEquals(3, worker.submissions,
				"a second worker observation of fingerprint B cannot publish a duplicate wake");
		assertEquals(3, dependencies.resolveCalls,
				"fingerprint B performs only its deadline resolution and one wake-fenced replacement");

		dependencies.fingerprint = "fingerprint-c";
		monitor.poll();
		monitor.poll();
		supervisor.tick(false, null, 0L);
		assertEquals(4, worker.submissions, "fingerprint C publishes exactly one later wake");
		worker.runNext();
		supervisor.tick(false, null, 0L);
		assertEquals(4, worker.submissions, "the C resolution cannot wake itself again");
		assertEquals(4, dependencies.resolveCalls, "fingerprint C performs exactly one resolution");
		supervisor.close();
	}

	private static void verifyDependencyWakeSurvivesInflightFailure() {
		FakeClock clock = new FakeClock();
		BlockingFailureDependencies dependencies = new BlockingFailureDependencies();
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		ManualDependencyMonitorScheduler monitor = new ManualDependencyMonitorScheduler();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "inflight-dependency-wake-game"), Map.of(), clock, dependencies, new FakeLauncher(),
				() -> "00000000-0000-0000-0000-000000000455", worker, runtimeRoot -> 0, monitor
		);
		Thread resolver = worker.startNext("blocking-failed-dependency-resolution");
		await(dependencies.started, "in-flight failing dependency resolution started");
		dependencies.fingerprint = "blocked-inflight-changed";
		monitor.poll();
		dependencies.release.countDown();
		join(resolver, "in-flight failing dependency resolution completes");
		supervisor.tick(false, null, 0L);
		assertEquals(2, worker.submissions,
				"a dependency wake during in-flight failure remains immediately eligible after stale failure publication");
		assertEquals(1, dependencies.resolveCalls.get(), "the stale blocked result is applied only once before its replacement queues");
		supervisor.close();
	}

	private static void verifyDependencyMonitorCloseDoesNotWaitForPoll() {
		BlockingFingerprintDependencies dependencies = new BlockingFingerprintDependencies();
		ManualDependencyMonitorScheduler monitor = new ManualDependencyMonitorScheduler();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "blocking-dependency-monitor-close"), Map.of(), new FakeClock(), dependencies,
				new FakeLauncher(), () -> "00000000-0000-0000-0000-000000000456",
				new QueuedMaintenanceWorker(), runtimeRoot -> 0, monitor
		);
		Thread poller = monitor.startPoll("blocking-dependency-monitor-poll");
		await(dependencies.fingerprintStarted, "dependency monitor fingerprint poll started");
		CountDownLatch closeReturned = new CountDownLatch(1);
		Thread closer = Thread.ofPlatform().daemon().name("nonblocking-dependency-monitor-close").start(() -> {
			supervisor.close();
			closeReturned.countDown();
		});
		boolean prompt;
		try {
			prompt = closeReturned.await(250L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("dependency monitor close wait was interrupted", interrupted);
		} finally {
			dependencies.releaseFingerprint.countDown();
		}
		join(poller, "blocked dependency fingerprint poll exits after release");
		join(closer, "dependency monitor close returns after release");
		assertTrue(prompt, "supervisor close never waits for an in-flight dependency fingerprint poll");
	}

	private static void verifyOrphanReapRetriesBeforeLaunch() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.ready();
		FakeLauncher launcher = new FakeLauncher();
		AtomicInteger reapAttempts = new AtomicInteger();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "orphan-retry-game"),
				Map.of(),
				clock,
				dependencies,
				launcher,
				() -> "00000000-0000-0000-0000-000000000202",
				Runnable::run,
				runtimeRoot -> {
					if (reapAttempts.incrementAndGet() == 1) throw new IOException("ownership file is temporarily locked");
					return 1;
				}
		);
		assertEquals(CoordinatorRecoveryState.BLOCKED_RETRYABLE, supervisor.snapshot().state(),
				"failed orphan cleanup is retryable");
		assertEquals(0, launcher.launches.size(), "no replacement launches before orphan cleanup succeeds");
		clock.advance(5_000L);
		supervisor.tick(false, null, 0L);
		assertEquals(2, reapAttempts.get(), "orphan cleanup retries after its first failure");
		assertEquals(1, launcher.launches.size(), "replacement launches only after successful orphan cleanup");
		supervisor.close();
	}

	private static void verifySecretRepairRebindsBridgeAndAuthenticatesReplacement() {
		Path fixtureRoot = null;
		CoordinatorProcessSupervisor supervisor = null;
		CodexAgentServerRuntime.BridgeSlot slot = null;
		Socket originalConnection = null;
		Socket repairedConnection = null;
		try {
			fixtureRoot = Files.createTempDirectory("arena-secret-repair-");
			Path secretFile = fixtureRoot.resolve("bridge-secret.txt");
			String originalSecret = "a".repeat(32);
			String repairedSecret = "b".repeat(32);
			Files.writeString(secretFile, originalSecret, StandardCharsets.UTF_8);
			FakeClock clock = new FakeClock();
			MutableDependencies dependencies = MutableDependencies.ready(secretFile, originalSecret);
			FakeLauncher launcher = new FakeLauncher();
			AtomicInteger launchIds = new AtomicInteger();
			supervisor = new CoordinatorProcessSupervisor(
					fixtureRoot.resolve("game"), Map.of(), clock, dependencies, launcher,
					() -> "00000000-0000-0000-0000-%012d".formatted(400 + launchIds.incrementAndGet()),
					Runnable::run, runtimeRoot -> 0
			);
			clock.advance(STARTUP_GRACE_MS);
			supervisor.tick(false, null, 0L);

			CodexAgentManager manager = uninitializedManager();
			AgentRecord active = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Keeper"), 1_000L);
			manager.registry().setAutomaticProgress(active.agentId(), false, 1_000L);
			manager.registry().start(active.agentId(), "keep gathering stone", 1_001L);
			var originalProfile = manager.registry().require(active.agentId()).profile();
			var originalGoal = manager.registry().require(active.agentId()).currentGoal();
			int port = unusedLoopbackPort();
			slot = new CodexAgentServerRuntime.BridgeSlot(clock);
			CoordinatorProcessSupervisor ownedSupervisor = supervisor;
			CodexAgentServerRuntime.BridgeSlot ownedSlot = slot;
			slot.reconcile(supervisor.bridgeRevision(), () ->
					MultiplexedServerBridge.withPreparedSecret(manager, port, ownedSupervisor.bridgeSecret()));
			MultiplexedServerBridge originalBridge = slot.bridge();
			originalConnection = authenticate(port, originalSecret, supervisor.snapshot().launchId(), "original-secret");
			supervisor.tick(true, originalBridge.authenticatedLaunchId(), originalBridge.authenticatedSessionGeneration());
			assertEquals(CoordinatorRecoveryState.HEALTHY, supervisor.snapshot().state(),
					"original coordinator child authenticates through the initial Java bridge");

			Files.writeString(secretFile, repairedSecret, StandardCharsets.UTF_8);
			dependencies.rotate("repaired-secret", secretFile, repairedSecret);
			clock.advance(5_000L);
			supervisor.tick(true, originalBridge.authenticatedLaunchId(), originalBridge.authenticatedSessionGeneration());
			supervisor.tick(true, originalBridge.authenticatedLaunchId(), originalBridge.authenticatedSessionGeneration());
			assertEquals(2, launcher.launches.size(), "secret repair relaunches one matching coordinator child");
			long repairedRevision = supervisor.bridgeRevision();
			slot.reconcile(repairedRevision, () ->
					MultiplexedServerBridge.withPreparedSecret(manager, port, ownedSupervisor.bridgeSecret()));
			MultiplexedServerBridge repairedBridge = slot.bridge();
			assertFalse(originalBridge == repairedBridge, "secret repair replaces the cached Java bridge instance");
			assertEquals(dev.agaminggod.arenaagents.agent.AgentLifecycleState.DISCONNECTED,
					manager.registry().require(active.agentId()).state(),
					"bridge replacement drains the authenticated session disconnect before dropping the old bridge");
			repairedConnection = authenticate(port, repairedSecret, supervisor.snapshot().launchId(), "repaired-secret");
			supervisor.tick(true, repairedBridge.authenticatedLaunchId(), repairedBridge.authenticatedSessionGeneration());
			assertEquals(CoordinatorRecoveryState.HEALTHY, supervisor.snapshot().state(),
					"matching repaired child authenticates automatically through the rebound bridge");
			assertEquals(originalProfile, manager.registry().require(active.agentId()).profile(),
					"bridge replacement preserves the active agent profile");
			assertEquals(originalGoal, manager.registry().require(active.agentId()).currentGoal(),
					"bridge replacement preserves the active goal");
			slot.reconcile(repairedRevision, () -> {
				throw new AssertionError("unchanged bridge revision must not create another listener");
			});
			assertEquals(repairedBridge, ownedSlot.bridge(), "repeated reconciliation is idempotent");
		} catch (Exception exception) {
			throw new AssertionError("bridge secret repair recovery failed", exception);
		} finally {
			closeSocket(repairedConnection);
			closeSocket(originalConnection);
			if (slot != null) slot.close();
			if (supervisor != null) supervisor.close();
			if (fixtureRoot != null) deleteTree(fixtureRoot);
		}
	}

	private static void verifyBridgeWaitsForWorkerPreparedSecret() {
		CoordinatorProcessSupervisor supervisor = null;
		CodexAgentServerRuntime.BridgeSlot slot = null;
		try {
			FakeClock clock = new FakeClock();
			MutableDependencies dependencies = MutableDependencies.ready();
			QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
			supervisor = new CoordinatorProcessSupervisor(
					Path.of("build", "deferred-bridge-game"), Map.of(), clock, dependencies, new FakeLauncher(),
					() -> "00000000-0000-0000-0000-000000000252", worker, runtimeRoot -> 0
			);
			slot = new CodexAgentServerRuntime.BridgeSlot(clock);
			AtomicInteger constructions = new AtomicInteger();
			AtomicReference<String> suppliedSecret = new AtomicReference<>();
			CodexAgentManager manager = uninitializedManager();
			int port = unusedLoopbackPort();
			long started = System.nanoTime();
			for (int index = 0; index < 50; index++) {
				supervisor.tick(false, null, 0L);
				CodexAgentServerRuntime.reconcilePreparedBridge(
						slot, supervisor.bridgeRevision(), supervisor.bridgeSecret(), secret -> {
							constructions.incrementAndGet();
							suppliedSecret.set(secret);
							return MultiplexedServerBridge.withPreparedSecret(manager, port, secret);
						}
				);
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			assertTrue(elapsedMs < 250L, "server ticks stay prompt before a worker-prepared bridge secret exists");
			assertEquals(0, constructions.get(), "no path-based bridge construction or secret read occurs before preparation");
			assertEquals(null, slot.bridge(), "Java bridge does not bind before preparation publishes its secret");
			assertEquals(1, worker.submissions, "preparation remains one coalesced worker task");

			worker.runNext();
			supervisor.tick(false, null, 0L);
			CodexAgentServerRuntime.reconcilePreparedBridge(
					slot, supervisor.bridgeRevision(), supervisor.bridgeSecret(), secret -> {
						constructions.incrementAndGet();
						suppliedSecret.set(secret);
						return MultiplexedServerBridge.withPreparedSecret(manager, port, secret);
					}
			);
			assertEquals(1, constructions.get(), "published in-memory secret enables exactly one Java bridge construction");
			assertEquals("s".repeat(32), suppliedSecret.get(), "bridge receives only the worker-prevalidated in-memory secret");
			assertTrue(slot.bridge() != null, "prepared bridge binds after worker result publication");
		} catch (Exception exception) {
			throw new AssertionError("deferred prepared bridge verification failed", exception);
		} finally {
			if (slot != null) slot.close();
			if (supervisor != null) supervisor.close();
		}
	}

	private static void verifyLaunchFailureRecovers() {
		Fixture fixture = Fixture.ready();
		fixture.launcher.failuresRemaining = 1;
		fixture.clock.advance(STARTUP_GRACE_MS);
		fixture.supervisor.tick(false, null);
		CoordinatorRecoverySnapshot failed = fixture.supervisor.snapshot();
		assertEquals(CoordinatorRecoveryState.BACKOFF, failed.state(), "process start failure enters backoff");
		assertEquals("COORDINATOR_START_FAILED", failed.failureCode(), "process start failure reports the launch boundary");
		fixture.clock.advance(1_000L);
		fixture.supervisor.tick(false, null);
		assertEquals(1, fixture.launcher.launches.size(), "process start retries after its first backoff");
		assertEquals(CoordinatorRecoveryState.AUTHENTICATING, fixture.supervisor.snapshot().state(),
				"process start retry can recover");
		fixture.supervisor.close();
	}

	private static void verifyCloseIsIdempotent() {
		Fixture fixture = Fixture.ready();
		fixture.startFirstProcess();
		FakeChild child = fixture.launcher.latest();
		fixture.supervisor.close();
		fixture.supervisor.close();
		fixture.clock.advance(60_000L);
		fixture.supervisor.tick(false, null);
		assertEquals(CoordinatorRecoveryState.STOPPED, fixture.supervisor.snapshot().state(), "close is terminal by explicit shutdown only");
		assertEquals(1, child.terminations, "repeated close terminates the child once");
		assertEquals(1, fixture.launcher.launches.size(), "ticks after close never restart the coordinator");
	}

	private static void verifyDeferredVoiceInitialization() {
		AtomicInteger starts = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		CodexAgentServerRuntime.VoiceInitializationGate gate =
				new CodexAgentServerRuntime.VoiceInitializationGate(starts::incrementAndGet, closes::incrementAndGet);

		assertFalse(gate.reconcile(false, 1L),
				"voice remains deferred while the coordinator has not published its prepared paths");
		assertEquals(0, starts.get(), "unprepared auto-start does not permanently select the NoVoice fallback");
		assertEquals(0, closes.get(), "unprepared auto-start does not close an uninitialized voice runtime");
		assertTrue(gate.reconcile(true, 1L),
				"voice initializes after dependency preparation publishes the secret and endpoint properties");
		assertEquals(1, starts.get(), "prepared voice runtime is created exactly once");
		assertFalse(gate.reconcile(true, 1L), "stable prepared voice configuration is idempotent");
		assertTrue(gate.reconcile(true, 2L), "changed prepared voice configuration recreates the runtime");
		assertEquals(2, starts.get(), "voice runtime is recreated for a changed prepared revision");
		assertEquals(1, closes.get(), "the prior voice runtime is closed exactly once before recreation");
	}

	private static void verifyCandidateReadinessRequiresFreshReconciledStatus() {
		long now = 10_000L;
		CoordinatorStatusSnapshot fresh = coordinatorStatus(true, now - 2_500L);
		CoordinatorStatusSnapshot stale = coordinatorStatus(true, now - 2_501L);
		assertTrue(CodexAgentServerRuntime.coordinatorStatusReady(fresh, now),
				"candidate promotion accepts a reconciled status at the bounded freshness edge");
		assertFalse(CodexAgentServerRuntime.coordinatorStatusReady(stale, now),
				"candidate promotion rejects a status that stopped refreshing");
		assertFalse(CodexAgentServerRuntime.coordinatorStatusReady(coordinatorStatus(false, now), now),
				"candidate promotion still requires coordinator reconciliation");
	}

	private static CoordinatorStatusSnapshot coordinatorStatus(boolean reconciled, long receivedAtEpochMs) {
		return new CoordinatorStatusSnapshot(
				reconciled, List.of(), 0, 0, 0,
				new CoordinatorStatusSnapshot.SchedulerStatus(0, 0, 1, 0, false),
				List.of(), receivedAtEpochMs
		);
	}

	private static void verifyPortOnlyChangeAdvancesBridgeRevision() {
		CodexAgentServerRuntime.BridgeSlot slot = null;
		Socket reboundConnection = null;
		Fixture fixture = null;
		try {
			int originalPort = unusedLoopbackPort();
			int changedPort = unusedLoopbackPort();
			while (changedPort == originalPort) changedPort = unusedLoopbackPort();
			MutableDependencies dependencies = MutableDependencies.ready();
			dependencies.setPort(originalPort);
			fixture = new Fixture(dependencies);
			CodexAgentManager manager = uninitializedManager();
			slot = new CodexAgentServerRuntime.BridgeSlot(fixture.clock);
			CodexAgentServerRuntime.reconcileBridgeConfiguration(slot, manager, fixture.supervisor);
			long originalRevision = fixture.supervisor.bridgeRevision();
			MultiplexedServerBridge originalBridge = slot.bridge();
			assertTrue(originalBridge != null, "production bridge binds its initial configured port");

			fixture.dependencies.setPort(changedPort);
			fixture.dependencies.fingerprint = "port-only-change";
			fixture.supervisor.publishDependencyFingerprintChange();
			fixture.supervisor.tick(false, null, 0L);
			assertEquals(changedPort, fixture.supervisor.bridgePort(),
					"dependency publication carries the changed bridge port into Java");
			assertTrue(fixture.supervisor.bridgeRevision() > originalRevision,
					"a port-only config change advances the Java bridge rebind revision");

			CodexAgentServerRuntime.reconcileBridgeConfiguration(slot, manager, fixture.supervisor);
			MultiplexedServerBridge reboundBridge = slot.bridge();
			assertTrue(reboundBridge != null && reboundBridge != originalBridge,
					"production reconciliation replaces the listener after a port-only change");
			try (ServerSocket released = new ServerSocket(originalPort, 1, InetAddress.getLoopbackAddress())) {
				assertEquals(originalPort, released.getLocalPort(), "port-only reconciliation releases the old listener");
			}
			reboundConnection = authenticate(
					changedPort, fixture.supervisor.bridgeSecret(),
					"00000000-0000-0000-0000-000000000774", "port-only-production-rebind"
			);
			assertTrue(reboundBridge.authenticated(),
					"replacement listener accepts authentication on the changed configured port");
		} catch (Exception exception) {
			throw new AssertionError("port-only production bridge rebind failed", exception);
		} finally {
			closeSocket(reboundConnection);
			if (slot != null) slot.close();
			if (fixture != null) fixture.supervisor.close();
		}
	}

	private static void verifyAutoStartDisabledStillBindsExplicitBridge() {
		String oldAutoStart = System.getProperty("arenaagents.coordinatorAutoStart");
		String oldSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldPort = System.getProperty("arenaagents.bridgePort");
		Path secretFile = null;
		CoordinatorProcessSupervisor supervisor = null;
		CodexAgentServerRuntime.BridgeSlot slot = null;
		try {
			secretFile = Files.createTempFile("arena-explicit-bridge-", ".txt");
			Files.writeString(secretFile, "e".repeat(32), StandardCharsets.UTF_8);
			int port = unusedLoopbackPort();
			System.setProperty("arenaagents.coordinatorAutoStart", "false");
			System.setProperty("arenaagents.bridgeSecretFile", secretFile.toString());
			System.clearProperty("arenaagents.voiceSecretFile");
			System.setProperty("arenaagents.bridgePort", Integer.toString(port));
			FakeLauncher launcher = new FakeLauncher();
			supervisor = new CoordinatorProcessSupervisor(
					Path.of("build", "explicit-bridge-game"), Map.of(), new FakeClock(), MutableDependencies.ready(),
					launcher, () -> "00000000-0000-0000-0000-000000000772"
			);
			assertEquals(CoordinatorRecoveryState.STOPPED, supervisor.snapshot().state(),
					"disabled coordinator autostart stops only child-process supervision");
			assertEquals(0, launcher.launches.size(),
					"disabled coordinator autostart never launches a child coordinator");
			assertTrue(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					"bridge-secret-only explicit setup initializes voice through the worker's supported fallback");
			slot = new CodexAgentServerRuntime.BridgeSlot(System::currentTimeMillis);
			CodexAgentServerRuntime.reconcileBridgeConfiguration(slot, uninitializedManager(), supervisor);
			assertTrue(slot.bridge() != null, "explicit bridge secret still constructs the Java listener");
			try (Socket connection = authenticate(
					port, "e".repeat(32), "00000000-0000-0000-0000-000000000773", "explicit-disabled-autostart"
			)) {
				assertTrue(slot.bridge().authenticated(),
						"explicit nondefault bridge port authenticates when coordinator autostart is disabled");
			}
		} catch (Exception exception) {
			throw new AssertionError("disabled autostart explicit bridge verification failed", exception);
		} finally {
			if (slot != null) slot.close();
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.coordinatorAutoStart", oldAutoStart);
			restoreProperty("arenaagents.bridgeSecretFile", oldSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.bridgePort", oldPort);
			if (secretFile != null) {
				try { Files.deleteIfExists(secretFile); } catch (IOException ignored) { }
			}
		}
	}

	private static void verifyCloseTerminatesChildBehindBlockedMaintenance() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.ready();
		FakeLauncher launcher = new FakeLauncher();
		QueuedMaintenanceWorker worker = new QueuedMaintenanceWorker();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "blocked-close-supervisor"), Map.of(), clock, dependencies, launcher,
				() -> "00000000-0000-0000-0000-000000000771", worker, runtimeRoot -> 0
		);
		worker.runNext();
		clock.advance(STARTUP_GRACE_MS);
		supervisor.tick(false, null, 0L);
		worker.runNext();
		supervisor.tick(false, null, 0L);
		FakeChild child = launcher.latest();
		worker.runNext();

		CountDownLatch maintenanceStarted = new CountDownLatch(1);
		CountDownLatch releaseMaintenance = new CountDownLatch(1);
		worker.execute(() -> {
			maintenanceStarted.countDown();
			await(releaseMaintenance, "blocked close maintenance released");
		});
		Thread blocked = worker.startNext("blocked-close-maintenance");
		await(maintenanceStarted, "maintenance worker is blocked before close");
		long started = System.nanoTime();
		supervisor.close();
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertEquals(1, child.terminations,
				"close terminates the exact owned child without queueing behind daemon maintenance");
		assertTrue(!child.isAlive(), "close leaves no owned coordinator child alive");
		assertTrue(elapsedMs < 250L, "close is not coupled to unrelated blocked daemon maintenance");
		releaseMaintenance.countDown();
		join(blocked, "blocked close maintenance exits after release");
	}

	private static void verifyCloseWaitsForInflightOwnedLaunchCleanup() {
		FakeClock clock = new FakeClock();
		MutableDependencies dependencies = MutableDependencies.ready();
		CountDownLatch dependenciesResolved = new CountDownLatch(1);
		CoordinatorProcessSupervisor.DependencyResolver signallingDependencies =
				new CoordinatorProcessSupervisor.DependencyResolver() {
					@Override
					public String fingerprint() {
						return dependencies.fingerprint();
					}

					@Override
					public CoordinatorProcessSupervisor.DependencyResolution resolve() {
						CoordinatorProcessSupervisor.DependencyResolution resolution = dependencies.resolve();
						dependenciesResolved.countDown();
						return resolution;
					}
				};
		DelayedLauncher launcher = new DelayedLauncher();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "inflight-close-supervisor"), Map.of(), clock, signallingDependencies, launcher,
				() -> "00000000-0000-0000-0000-000000000774",
				new CoordinatorProcessSupervisor.OwnedMaintenanceWorker(), runtimeRoot -> 0, task -> { }
		);
		await(dependenciesResolved, "production maintenance worker resolves startup dependencies");
		supervisor.tick(false, null, 0L);
		assertTrue(supervisor.configured(), "startup dependencies publish before the launch race");

		clock.advance(STARTUP_GRACE_MS);
		long launchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		while (launcher.started.getCount() > 0L && System.nanoTime() < launchDeadline) {
			supervisor.tick(false, null, 0L);
			Thread.onSpinWait();
		}
		await(launcher.started, "owned launch enters the production maintenance worker");
		Thread closing = Thread.ofPlatform().daemon().name("inflight-owned-launch-close").start(supervisor::close);
		awaitState(supervisor, CoordinatorRecoveryState.STOPPED,
				"close publishes its terminal state before awaiting maintenance");
		assertTrue(closing.isAlive(), "close waits while the owned launch can still publish a child");

		launcher.release.countDown();
		join(closing, "close waits for in-flight owned launch cleanup");
		assertEquals(1, launcher.child.terminationAttempts,
				"the late owned child receives exactly one shutdown cleanup attempt");
		assertEquals(1, launcher.child.terminations,
				"close returns only after the late owned child is confirmed terminated");
		assertFalse(launcher.child.ownershipPresent,
				"close returns only after the late owned child's ownership is cleared");
	}

	private static final class Fixture {
		private final FakeClock clock = new FakeClock();
		private final MutableDependencies dependencies;
		private final FakeLauncher launcher = new FakeLauncher();
		private final CoordinatorProcessSupervisor supervisor;

		private Fixture(MutableDependencies dependencies) {
			this.dependencies = dependencies;
			AtomicInteger ids = new AtomicInteger();
			String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
			try {
				System.setProperty("arenaagents.packageRoot", Path.of("build", "missing-supervisor-fixture").toAbsolutePath().toString());
				supervisor = new CoordinatorProcessSupervisor(
						Path.of("build", "supervisor-fixture-game"),
						Map.of(),
						clock,
						dependencies,
						launcher,
						() -> "00000000-0000-0000-0000-%012d".formatted(ids.incrementAndGet())
				);
			} finally {
				restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			}
		}

		private static Fixture ready() {
			return new Fixture(MutableDependencies.ready());
		}

		private static Fixture blocked(String code, String message) {
			return new Fixture(MutableDependencies.blocked(code, message));
		}

		private void startFirstProcess() {
			assertEquals(CoordinatorRecoveryState.STARTING, supervisor.snapshot().state(), "configured supervisor begins in starting state");
			clock.advance(STARTUP_GRACE_MS);
			supervisor.tick(false, null);
			assertEquals(1, launcher.launches.size(), "startup grace launches one coordinator");
			CoordinatorRecoverySnapshot authenticating = supervisor.snapshot();
			assertEquals(CoordinatorRecoveryState.AUTHENTICATING, authenticating.state(),
					"new coordinator waits for matching bridge authentication");
			assertEquals(clock.now + AUTHENTICATION_TIMEOUT_MS, authenticating.authenticationDeadlineEpochMs(),
					"new coordinator receives an authentication deadline");
			assertEquals(authenticating.launchId(), launcher.launches.getFirst().environment().get("ARENA_AGENT_COORDINATOR_LAUNCH_ID"),
					"launch UUID is passed through the child environment");
			assertEquals(launcher.launches.getFirst().generationId(),
					launcher.launches.getFirst().environment().get("ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION"),
					"exact runtime generation is passed through the child environment");
			UUID.fromString(authenticating.launchId());
		}
	}

	private static final class FakeClock implements java.util.function.LongSupplier {
		private long now = 100_000L;

		@Override
		public long getAsLong() {
			return now;
		}

		private void advance(long millis) {
			now += millis;
		}
	}

	private static final class MutableDependencies implements CoordinatorProcessSupervisor.DependencyResolver {
		private CoordinatorProcessSupervisor.PreparedRuntime runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
				Path.of("build", "supervisor-runtime"),
				Path.of("build", "supervisor-runtime", "coordinator"),
				Path.of("build", "supervisor-runtime", "coordinator", "src", "dynamic-main.mjs"),
				Path.of("build", "supervisor-runtime", "coordinator", "config", "dynamic-agents.json"),
				Path.of("build", "supervisor-runtime", "runtime", "bridge-secret.txt"),
				Path.of("build", "supervisor-runtime", "runtime", "toolchains", "node", "node.exe"),
				"s".repeat(32)
		);
		private String fingerprint;
		private CoordinatorProcessSupervisor.DependencyResolution result;
		private int resolveCalls;

		private MutableDependencies(String fingerprint, CoordinatorProcessSupervisor.DependencyResolution result) {
			this.fingerprint = fingerprint;
			this.result = result;
		}

		private static MutableDependencies ready() {
			MutableDependencies dependencies = new MutableDependencies("ready", null);
			dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.ready(dependencies.runtime);
			return dependencies;
		}

		private static MutableDependencies candidate(boolean lastKnownGoodAvailable) {
			MutableDependencies dependencies = ready();
			CoordinatorProcessSupervisor.PreparedRuntime current = dependencies.runtime;
			dependencies.runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
					current.root(), current.coordinatorRoot(), current.main(), current.config(), current.secret(),
					current.nodeExecutable(), current.bridgeSecret(), GENERATION_B, true, lastKnownGoodAvailable
			);
			dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.ready(dependencies.runtime);
			return dependencies;
		}

		private static MutableDependencies ready(Path secretPath, String secret) {
			MutableDependencies dependencies = ready();
			dependencies.runtime = runtime(secretPath, secret);
			dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.ready(dependencies.runtime);
			return dependencies;
		}

		private static MutableDependencies blocked(String code, String message) {
			MutableDependencies dependencies = new MutableDependencies("blocked", null);
			dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.blocked(code, message);
			return dependencies;
		}

		private void restore(String fingerprint) {
			this.fingerprint = fingerprint;
			this.result = CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}

		private void rotate(String fingerprint, Path secretPath, String secret) {
			this.fingerprint = fingerprint;
			this.runtime = runtime(secretPath, secret);
			this.result = CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}

		private void setPort(int port) {
			CoordinatorProcessSupervisor.PreparedRuntime current = runtime;
			runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
					current.root(), current.coordinatorRoot(), current.main(), current.config(), current.secret(),
					current.nodeExecutable(), current.bridgeSecret(), port, current.generationId(),
					current.candidate(), current.lastKnownGoodAvailable()
			);
			result = CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}

		private static CoordinatorProcessSupervisor.PreparedRuntime runtime(Path secretPath, String secret) {
			return new CoordinatorProcessSupervisor.PreparedRuntime(
					Path.of("build", "supervisor-runtime"),
					Path.of("build", "supervisor-runtime", "coordinator"),
					Path.of("build", "supervisor-runtime", "coordinator", "src", "dynamic-main.mjs"),
					Path.of("build", "supervisor-runtime", "coordinator", "config", "dynamic-agents.json"),
					secretPath,
					Path.of("build", "supervisor-runtime", "runtime", "toolchains", "node", "node.exe"),
					secret
			);
		}

		@Override
		public String fingerprint() {
			return fingerprint;
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			resolveCalls++;
			return result;
		}
	}

	private static final class FakeLauncher implements CoordinatorProcessSupervisor.ProcessLauncher {
		private final List<CoordinatorProcessSupervisor.LaunchRequest> launches = new ArrayList<>();
		private final List<FakeChild> children = new ArrayList<>();
		private int failuresRemaining;

		@Override
		public CoordinatorProcessSupervisor.ChildProcess launch(CoordinatorProcessSupervisor.LaunchRequest request)
				throws IOException {
			if (failuresRemaining > 0) {
				failuresRemaining--;
				throw new IOException("loopback bind is temporarily unavailable");
			}
			launches.add(request);
			FakeChild child = new FakeChild(10_000L + children.size());
			children.add(child);
			return child;
		}

		private FakeChild latest() {
			return children.getLast();
		}
	}

	private static final class FakeGenerationController implements CoordinatorProcessSupervisor.GenerationController {
		private int promotions;
		private int rollbacks;
		private Runnable beforeRollback = () -> { };

		@Override
		public CoordinatorProcessSupervisor.GenerationStatus promote(Path root, String generationId) {
			promotions++;
			return new CoordinatorProcessSupervisor.GenerationStatus(generationId, false, true);
		}

		@Override
		public CoordinatorProcessSupervisor.GenerationStatus rollback(Path root, String generationId) {
			beforeRollback.run();
			rollbacks++;
			return new CoordinatorProcessSupervisor.GenerationStatus(GENERATION_A, false, false);
		}
	}

	private static final class SequentialLaunchIds implements java.util.function.Supplier<String> {
		private final AtomicInteger value;

		private SequentialLaunchIds(int initial) {
			value = new AtomicInteger(initial);
		}

		@Override
		public String get() {
			return "00000000-0000-0000-0000-%012d".formatted(value.incrementAndGet());
		}
	}

	private static final class FakeChild implements CoordinatorProcessSupervisor.ChildProcess {
		private final long pid;
		private boolean alive = true;
		private int terminations;
		private int terminationAttempts;
		private int terminationFailuresRemaining;
		private boolean ownershipPresent = true;

		private FakeChild(long pid) {
			this.pid = pid;
		}

		private void crash() {
			alive = false;
		}

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public long pid() {
			return pid;
		}

		@Override
		public void terminate() {
			terminationAttempts++;
			if (terminationFailuresRemaining > 0) {
				terminationFailuresRemaining--;
				throw new IllegalStateException("owned process refused termination");
			}
			terminations++;
			alive = false;
			ownershipPresent = false;
		}
	}

	private static final class BlockingDependencies implements CoordinatorProcessSupervisor.DependencyResolver {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CoordinatorProcessSupervisor.PreparedRuntime runtime = MutableDependencies.ready().runtime;

		@Override
		public String fingerprint() {
			return "blocking-ready";
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			started.countDown();
			await(release, "blocking dependency resolver released");
			return CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}
	}

	private static final class BlockingFailureDependencies implements CoordinatorProcessSupervisor.DependencyResolver {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger resolveCalls = new AtomicInteger();
		private volatile String fingerprint = "blocked-inflight";

		@Override
		public String fingerprint() {
			return fingerprint;
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			resolveCalls.incrementAndGet();
			started.countDown();
			await(release, "in-flight failed dependency resolver released");
			return CoordinatorProcessSupervisor.DependencyResolution.blocked(
					"NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable"
			);
		}
	}

	private static final class BlockingFingerprintDependencies implements CoordinatorProcessSupervisor.DependencyResolver {
		private final CountDownLatch fingerprintStarted = new CountDownLatch(1);
		private final CountDownLatch releaseFingerprint = new CountDownLatch(1);

		@Override
		public String fingerprint() {
			fingerprintStarted.countDown();
			await(releaseFingerprint, "blocking dependency fingerprint released");
			return "blocking-fingerprint";
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			return CoordinatorProcessSupervisor.DependencyResolution.blocked(
					"NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable"
			);
		}
	}

	private static final class BlockingLauncher implements CoordinatorProcessSupervisor.ProcessLauncher {
		private final BlockingChild child = new BlockingChild();

		@Override
		public CoordinatorProcessSupervisor.ChildProcess launch(CoordinatorProcessSupervisor.LaunchRequest request) {
			return child;
		}
	}

	private static final class DelayedLauncher implements CoordinatorProcessSupervisor.ProcessLauncher {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final FakeChild child = new FakeChild(42_425L);

		@Override
		public CoordinatorProcessSupervisor.ChildProcess launch(CoordinatorProcessSupervisor.LaunchRequest request) {
			started.countDown();
			await(release, "delayed owned launch released");
			return child;
		}
	}

	private static final class BlockingChild implements CoordinatorProcessSupervisor.ChildProcess {
		private final CountDownLatch terminationStarted = new CountDownLatch(1);
		private final CountDownLatch releaseTermination = new CountDownLatch(1);
		private volatile boolean alive = true;

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public long pid() {
			return 42_424L;
		}

		@Override
		public void terminate() {
			terminationStarted.countDown();
			await(releaseTermination, "blocking child termination released");
			alive = false;
		}
	}

	private static final class QueuedMaintenanceWorker implements CoordinatorProcessSupervisor.MaintenanceWorker {
		private final Deque<Runnable> tasks = new ArrayDeque<>();
		private int submissions;

		@Override
		public synchronized void execute(Runnable task) {
			tasks.addLast(task);
			submissions++;
		}

		private synchronized Runnable removeNext() {
			Runnable task = tasks.pollFirst();
			if (task == null) throw new AssertionError("no maintenance task is queued");
			return task;
		}

		private void runNext() {
			removeNext().run();
		}

		private Thread startNext(String name) {
			Thread thread = Thread.ofPlatform().daemon().name(name).start(removeNext());
			return thread;
		}
	}

	private static final class ManualDependencyMonitorScheduler
			implements CoordinatorProcessSupervisor.DependencyMonitorScheduler {
		private Runnable poll;
		private boolean closed;

		@Override
		public void start(Runnable task) {
			if (poll != null) throw new AssertionError("dependency monitor was started more than once");
			poll = task;
		}

		private void poll() {
			if (poll == null) throw new AssertionError("dependency monitor was not started");
			poll.run();
		}

		private Thread startPoll(String name) {
			if (poll == null) throw new AssertionError("dependency monitor was not started");
			return Thread.ofPlatform().daemon().name(name).start(poll);
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	public static final class RootExitFixture {
		private RootExitFixture() {
		}

		public static void main(String[] arguments) throws Exception {
			String java = Path.of(
					System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
			).toString();
			Process provider = new ProcessBuilder(
					java, "-cp", System.getProperty("java.class.path"), ProviderFixture.class.getName()
			).start();
			Files.writeString(Path.of(arguments[0]), Long.toString(provider.pid()), StandardCharsets.UTF_8);
			Path release = Path.of(arguments[1]);
			while (!Files.exists(release)) Thread.sleep(10L);
		}
	}

	public static final class ProviderFixture {
		private ProviderFixture() {
		}

		public static void main(String[] arguments) throws Exception {
			Thread.sleep(TimeUnit.MINUTES.toMillis(5L));
		}
	}

	private static void assertTicksPrompt(
			CoordinatorProcessSupervisor supervisor,
			int count,
			String label
	) {
		long started = System.nanoTime();
		for (int index = 0; index < count; index++) supervisor.tick(false, null, 0L);
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		if (elapsedMs >= 250L) throw new AssertionError(label + ": elapsed " + elapsedMs + "ms");
	}

	private static Socket authenticate(
			int port,
			String secret,
			String launchId,
			String messageId
	) throws Exception {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, port);
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			socket.setSoTimeout(2_000);
			JsonObject hello = new JsonObject();
			hello.addProperty("secret", secret);
			hello.addProperty("launchId", launchId);
			socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
					2, "coordinator", "server", "hello", messageId, hello
			)).getBytes(StandardCharsets.UTF_8));
			socket.getOutputStream().flush();
			assertEquals("hello_ack", codec.decode(reader.readLine()).type(), "matching child receives hello acknowledgement");
			return socket;
		} catch (Exception failure) {
			closeSocket(socket);
			throw failure;
		}
	}

	private static void closeSocket(Socket socket) {
		if (socket == null) return;
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	private static int unusedLoopbackPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static boolean readablePid(Path path) {
		try {
			return Files.isRegularFile(path) && !Files.readString(path).trim().isEmpty();
		} catch (IOException ignored) {
			return false;
		}
	}

	private static boolean ownershipTracks(Path root, ProcessHandle process) {
		try {
			Properties ownership = new Properties();
			try (var reader = Files.newBufferedReader(CoordinatorProcessOwnership.ownershipFile(root))) {
				ownership.load(reader);
			}
			long startedAt = process.info().startInstant().orElseThrow().toEpochMilli();
			String expected = process.pid() + ":" + startedAt;
			return Arrays.asList(ownership.getProperty("descendants", "").split(",")).contains(expected);
		} catch (IOException | RuntimeException ignored) {
			return false;
		}
	}

	private static int productionBridgePort(Path config, String jsonValue) throws IOException {
		Files.writeString(config, "{\"bridge\":{\"port\":" + jsonValue + "}}", StandardCharsets.UTF_8);
		return CoordinatorProcessSupervisor.DefaultDependencyResolver.bridgePort(config);
	}

	private static void assertInvalidBridgePort(Path config, String jsonValue, String label) throws IOException {
		try {
			productionBridgePort(config, jsonValue);
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(label);
	}

	private static void awaitCondition(BooleanSupplier condition, String label) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
		assertTrue(condition.getAsBoolean(), label);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), new LinkedHashSet<AgentId>());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	private static void deleteTree(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException exception) {
					throw new java.io.UncheckedIOException(exception);
				}
			});
		} catch (IOException | java.io.UncheckedIOException exception) {
			throw new AssertionError("could not clean secret repair fixture", exception);
		}
	}

	private static void await(CountDownLatch latch, String label) {
		try {
			if (!latch.await(5L, TimeUnit.SECONDS)) throw new AssertionError(label + " timed out");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " was interrupted", exception);
		}
	}

	private static void join(Thread thread, String label) {
		try {
			thread.join(5_000L);
			if (thread.isAlive()) throw new AssertionError(label + " timed out");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " was interrupted", exception);
		}
	}

	private static void awaitState(
			CoordinatorProcessSupervisor supervisor,
			CoordinatorRecoveryState expected,
			String label
	) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		while (supervisor.snapshot().state() != expected && System.nanoTime() < deadline) Thread.onSpinWait();
		assertEquals(expected, supervisor.snapshot().state(), label);
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
