package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Fault-injection verification for coordinator recovery ownership and deadlines. */
public final class CoordinatorProcessSupervisorVerification {
	private static final long STARTUP_GRACE_MS = 3_000L;
	private static final long AUTHENTICATION_TIMEOUT_MS = 15_000L;
	private static final long RECONNECT_TIMEOUT_MS = 10_000L;
	private static final long STABILITY_INTERVAL_MS = 30_000L;

	private CoordinatorProcessSupervisorVerification() {
	}

	public static int verify() {
		verifyRecoveryContract();
		verifyBridgeBindFailureRecovery();
		verifyEightCrashesStillRecover();
		verifyHungAuthenticationIsReplaced();
		verifyStaleLaunchAuthenticationIsRejected();
		verifyStaleLaunchCannotSuppressReplacement();
		verifyReconnectRecoveryAndExpiry();
		verifyContinuousStabilityResetsFailures();
		verifyMissingThenRestoredDependency();
		verifyPeriodicDependencyRevalidation();
		verifyHealthyDependencyRevalidation();
		verifyLaunchFailureRecovers();
		verifyCloseIsIdempotent();
		return 78;
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

	private static void verifyMissingThenRestoredDependency() {
		Fixture fixture = Fixture.blocked("NODE_RUNTIME_NOT_FOUND", "Node.js 22+ is unavailable");
		assertEquals(CoordinatorRecoveryState.BLOCKED_RETRYABLE, fixture.supervisor.snapshot().state(),
				"missing Node is retryable instead of latched");
		fixture.dependencies.restore("node-restored");
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
		private final CoordinatorProcessSupervisor.PreparedRuntime runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
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

		private static MutableDependencies blocked(String code, String message) {
			MutableDependencies dependencies = new MutableDependencies("blocked", null);
			dependencies.result = CoordinatorProcessSupervisor.DependencyResolution.blocked(code, message);
			return dependencies;
		}

		private void restore(String fingerprint) {
			this.fingerprint = fingerprint;
			this.result = CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
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

	private static final class FakeChild implements CoordinatorProcessSupervisor.ChildProcess {
		private final long pid;
		private boolean alive = true;
		private int terminations;

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
			terminations++;
			alive = false;
		}
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
}
