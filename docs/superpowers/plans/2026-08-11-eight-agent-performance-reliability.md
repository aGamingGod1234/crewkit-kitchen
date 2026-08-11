# Eight-Agent Performance and Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make summonable NPC control responsive and reliable for eight simultaneous agents without sacrificing Minecraft tick health or protocol safety.

**Architecture:** Extend the existing bounded coordinator scheduler and protocol-v2 bridge with rolling control-latency measurements, duplicate-plan suppression, tick-budgeted observation delivery, and throttled action-progress events. Keep macro planning event-driven and provider-isolated while Minecraft's deterministic controllers remain responsible for tick-level movement, combat, survival, stalls, and terminal outcomes.

**Tech Stack:** Java 25, Fabric/Carpet for Minecraft 26.1.2, Gson, Node.js ESM, Node built-in test runner, Gradle 9.5.1.

## Global Constraints

- Optimize the summonable NPC mode for up to eight simultaneous agents.
- Preserve the existing protocol security boundaries, provider isolation, and legacy two-client compatibility.
- Use automated and headless tooling only; no computer-use or interactive GUI automation.
- Keep planning concurrency at four by default and never exceed the configured maximum or global sixteen-turn cap.
- Never discard lifecycle controls, terminal action results, damage or threat events, or goal revisions.
- Keep queues, retries, telemetry windows, processes, threads, and payloads bounded.
- Do not add third-party runtime dependencies or expose prompts, model output, observations, credentials, or secrets in status telemetry.

---

### Task 1: Bounded control-latency telemetry

**Files:**
- Create: `coordinator/src/control-latency-registry.mjs`
- Create: `coordinator/test/control-latency-registry.test.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/CoordinatorStatusSnapshot.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/bridge/CoordinatorStatusVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioRecoveryVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioPreflightVerification.java`

**Interfaces:**
- Produces: `ControlLatencyRegistry.record(operation, durationMs)` and `ControlLatencyRegistry.snapshot()`.
- Produces: optional protocol-v2 `coordinator_status.latencies` entries shaped as `{ operation, count, p50Ms, p95Ms }`.
- Produces: Java `CoordinatorStatusSnapshot.LatencyHealth` with the same fields.

- [ ] **Step 1: Write the failing Node registry tests**

```js
test('latency registry bounds samples and reports nearest-rank percentiles', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 3, operationCap: 2 });
	for (const value of [10, 20, 30, 40]) registry.record('observation_to_plan', value);
	assert.deepEqual(registry.snapshot(), [
		{ operation: 'observation_to_plan', count: 3, p50Ms: 30, p95Ms: 40 },
	]);
});

test('latency registry rejects unbounded identities and durations', () => {
	const registry = new ControlLatencyRegistry({ operationCap: 1 });
	registry.record('action_completion', 1);
	assert.throws(() => registry.record('second_operation', 1), /capacity/);
	assert.throws(() => registry.record('action_completion', -1), /duration/);
});
```

- [ ] **Step 2: Run the registry tests and verify RED**

Run: `node --test test/control-latency-registry.test.mjs` from `coordinator`.

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `control-latency-registry.mjs`.

- [ ] **Step 3: Implement the bounded registry**

```js
const MAX_OPERATION_LENGTH = 128;

export class ControlLatencyRegistry {
	#windowSize;
	#operationCap;
	#samples = new Map();

	constructor({ windowSize = 50, operationCap = 16 } = {}) {
		if (!Number.isSafeInteger(windowSize) || windowSize < 1) throw new TypeError('windowSize must be a positive safe integer');
		if (!Number.isSafeInteger(operationCap) || operationCap < 1 || operationCap > 16) throw new TypeError('operationCap must be in [1, 16]');
		this.#windowSize = windowSize;
		this.#operationCap = operationCap;
	}

	record(operationValue, durationValue) {
		const operation = String(operationValue ?? '').trim();
		if (operation.length === 0 || operation.length > MAX_OPERATION_LENGTH) throw new TypeError('operation must be nonblank and at most 128 characters');
		if (!Number.isFinite(durationValue) || durationValue < 0) throw new TypeError('duration must be non-negative and finite');
		let samples = this.#samples.get(operation);
		if (samples === undefined) {
			if (this.#samples.size >= this.#operationCap) throw new RangeError('latency operation capacity is full');
			samples = [];
			this.#samples.set(operation, samples);
		}
		const durationMs = Math.round(durationValue);
		samples.push(durationMs);
		if (samples.length > this.#windowSize) samples.splice(0, samples.length - this.#windowSize);
		return Object.freeze({ operation, durationMs });
	}

	snapshot() {
		return [...this.#samples].sort(([left], [right]) => left.localeCompare(right)).map(([operation, values]) => {
			const sorted = [...values].sort((left, right) => left - right);
			const percentile = (fraction) => sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
			return Object.freeze({ operation, count: sorted.length, p50Ms: percentile(0.5), p95Ms: percentile(0.95) });
		});
	}
}
```

Use nearest-rank percentiles: `sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]`. Limit operation names to 128 nonblank characters and durations to non-negative finite safe integers after rounding.

- [ ] **Step 4: Run the registry tests and verify GREEN**

Run: `node --test test/control-latency-registry.test.mjs` from `coordinator`.

Expected: 2 passed, 0 failed.

- [ ] **Step 5: Write failing coordinator-status compatibility tests**

Add a Node protocol test proving `latencies` is optional for old senders, normalized to `[]`, bounded to 16 entries, strict, and contains no arbitrary fields. Add a dynamic coordinator test expecting published status to include `latencies: []` with an empty injected registry. Add Java verification that decodes both the old field set and the new field set and rejects malformed latency rows.

```js
const latency = { operation: 'observation_to_plan', count: 8, p50Ms: 25, p95Ms: 80 };
assert.deepEqual(validateProtocolV2Payload('coordinator_status', { ...payload, latencies: [latency] }).latencies, [latency]);
assert.deepEqual(validateProtocolV2Payload('coordinator_status', payload).latencies, []);
```

- [ ] **Step 6: Run focused status tests and verify RED**

Run: `node --test test/protocol-v2.test.mjs test/dynamic-main.test.mjs` from `coordinator`, then `..\gradlew.bat compileTestJava --no-daemon --console=plain` with the project Java 25 environment.

Expected: Node rejects/omits the new field and Java cannot construct or decode `LatencyHealth`.

- [ ] **Step 7: Wire optional status telemetry end to end**

Update protocol validation so the old seven fields remain required and `latencies` is optional. Add `latencyRegistry` to `DynamicCoordinator` and `createDynamicCoordinator`, publish `latencyRegistry.snapshot()`, and decode optional latencies in Java. Preserve the old Java constructor as a delegating overload with `List.of()` so existing consumers remain source-compatible.

```java
public record LatencyHealth(String operation, int count, int p50Ms, int p95Ms) {
	public LatencyHealth {
		operation = nonblank(operation, "operation");
		if (count < 0 || p50Ms < 0 || p95Ms < 0) throw new IllegalArgumentException("invalid latency health");
	}
}
```

- [ ] **Step 8: Run focused status tests and verify GREEN**

Run both focused commands from Step 6.

Expected: all focused Node tests pass and Java test sources compile.

- [ ] **Step 9: Commit the telemetry slice**

```powershell
git add coordinator/src/control-latency-registry.mjs coordinator/test/control-latency-registry.test.mjs coordinator/src/protocol-v2.mjs coordinator/test/protocol-v2.test.mjs coordinator/src/dynamic-main.mjs coordinator/test/dynamic-main.test.mjs src/main/java/dev/agaminggod/arenaagents/server/bridge/CoordinatorStatusSnapshot.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/server/bridge/CoordinatorStatusVerification.java src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioRecoveryVerification.java src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioPreflightVerification.java
git commit -m "feat: expose bounded agent control latency"
```

### Task 2: Duplicate-plan suppression and coordinator timing

**Files:**
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `coordinator/test/eight-agent-soak.test.mjs`

**Interfaces:**
- Consumes: `ControlLatencyRegistry.record(operation, durationMs)` from Task 1.
- Produces: per-agent last-planned observation fingerprints and outstanding-action timestamps owned by `DynamicCoordinator`.
- Produces: latency operations `observation_to_plan`, `command_to_first_progress`, `action_completion`, and `reconnect_reconciliation`.

- [ ] **Step 1: Write failing duplicate and timing tests**

Add a deferred planner fixture and prove that three observations arriving while its first plan is pending produce exactly one plan and one action command. Prove an observation received while an action is outstanding is ingested into the fact ledger but does not issue a second action. Inject a monotonic `now` function and assert that first progress and terminal result record exactly one bounded sample each.

```js
bridge.emit('observation', observationMessage('obs-1'));
bridge.emit('observation', observationMessage('obs-2'));
bridge.emit('observation', observationMessage('obs-3'));
await eventually(() => planner.requests.length === 1);
assert.equal(bridge.sent.filter((row) => row.type === 'action_command').length, 1);
```

- [ ] **Step 2: Run focused coordinator tests and verify RED**

Run: `node --test test/dynamic-main.test.mjs test/eight-agent-soak.test.mjs` from `coordinator`.

Expected: duplicate observations create additional queued planner work or timing samples are absent.

- [ ] **Step 3: Implement observation gating and timing**

Add `#lastPlannedObservations`, `#latencyRegistry`, `#now`, and `#disconnectedAt` fields. Capture observation receipt time before enqueue. After reconciliation, record reconnect duration. Before calling the planner, reject a duplicate `(goalRevision, observationHash)` and return when `#outstandingActions.has(agentId)`. Store `dispatchedAt` and `firstProgressRecorded` in each outstanding action. Clear per-agent fingerprints and timing state on goal replacement, removal, disconnect, and shutdown.

```js
const fingerprint = `${record.goalRevision}:${observationHash(observation)}`;
if (this.#outstandingActions.has(record.agentId) || this.#lastPlannedObservations.get(record.agentId) === fingerprint) return;
this.#lastPlannedObservations.set(record.agentId, fingerprint);
```

Record `observation_to_plan` after an accepted decision, `command_to_first_progress` only for the first matching progress frame, and `action_completion` only for the matching terminal frame. Timing failure must be caught so metrics cannot change authoritative control behavior.

- [ ] **Step 4: Run focused coordinator tests and verify GREEN**

Run the command from Step 2 and repeat the soak test 25 times.

Expected: all runs pass, each agent retains isolation, and no run issues overlapping action commands.

- [ ] **Step 5: Commit the coordinator slice**

```powershell
git add coordinator/src/dynamic-main.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/eight-agent-soak.test.mjs
git commit -m "perf: coalesce redundant agent planning"
```

### Task 3: Tick-budgeted and cached observations

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/perception/ObservationDispatchQueue.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/perception/ObservationSectionCache.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Produces: `ObservationDispatchQueue.offer(AgentId)` and `drain(Consumer<AgentId>)`, bounded to 16 pending identities and two observations per drain.
- Produces: `ObservationSectionCache.getOrCompute(K key, long tick, Supplier<V> loader)` and `invalidate(K key)` with 16-entry LRU capacity and 10-tick freshness.
- Consumes: the existing complete `ServerObservationCollector.collect(AgentId)` protocol payload.

- [ ] **Step 1: Write failing pure-Java budget tests**

```java
ObservationDispatchQueue<String> queue = new ObservationDispatchQueue<>(16, 2);
queue.offer("agent-a"); queue.offer("agent-a"); queue.offer("agent-b"); queue.offer("agent-c");
List<String> first = new ArrayList<>();
queue.drain(first::add);
assertEquals(List.of("agent-a", "agent-b"), first, "drain is FIFO, coalesced, and tick bounded");

ObservationSectionCache<String, String> cache = new ObservationSectionCache<>(16, 10L);
AtomicInteger loads = new AtomicInteger();
assertEquals("v1", cache.getOrCompute("agent-a", 100L, () -> "v" + loads.incrementAndGet()), "initial load");
assertEquals("v1", cache.getOrCompute("agent-a", 110L, () -> "v" + loads.incrementAndGet()), "fresh reuse");
assertEquals("v2", cache.getOrCompute("agent-a", 111L, () -> "v" + loads.incrementAndGet()), "expired reload");
```

- [ ] **Step 2: Run core verification and verify RED**

Run: `.\gradlew.bat compileTestJava --no-daemon --console=plain`.

Expected: compilation fails because the two budget classes do not exist.

- [ ] **Step 3: Implement bounded queue and cache utilities**

Use `LinkedHashSet<K>` for FIFO coalescing and an access-ordered `LinkedHashMap<K, Entry<V>>` for LRU caching. Reject non-positive capacities and negative/backward ticks. The queue throws a bounded `IllegalStateException` instead of silently dropping a distinct agent.

- [ ] **Step 4: Run core verification and verify GREEN for utilities**

Run: `.\gradlew.bat verifyCore --no-daemon --console=plain`.

Expected: `ObservationBudgetVerification` passes.

- [ ] **Step 5: Write failing collector/bridge integration assertions**

Extend the pure-Java verification to require deep-copy isolation for cached `JsonObject` values, explicit invalidation, a maximum of two observation callbacks per drain, FIFO order across distinct agents, and idempotent coalescing for repeated offers of one agent. The bridge integration must route both `agent_ready` and post-result refreshes through this verified queue and invoke its drain immediately after `actionExecutor.tick()`.

- [ ] **Step 6: Integrate the budget into collection and delivery**

Cache only the expensive `blocks` and `nearbyContainers` sections using a key of agent ID, dimension, and block position. Keep health, hunger, velocity, entities, inventory, action, result, and world values fresh. Deep-copy cached JSON before adding it to an observation. Invalidate the agent's expensive section after every terminal action result.

Replace direct observation sends in `plannerReady` and `sendActionResult` with `queueObservation(agentId)`. Drain at most two identities after `actionExecutor.tick()` and catch collection failure per identity so one unloaded agent cannot break the server tick.

- [ ] **Step 7: Run Java verification and verify GREEN**

Run: `.\gradlew.bat verifyCore --no-daemon --console=plain`.

Expected: all Java verification passes with bounded, coalesced observation delivery.

- [ ] **Step 8: Commit the observation slice**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/perception/ObservationDispatchQueue.java src/main/java/dev/agaminggod/arenaagents/server/perception/ObservationSectionCache.java src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java
git commit -m "perf: budget server observation work"
```

### Task 4: Throttled action progress and stall evidence

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressEmissionPolicy.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionProgress.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressTracker.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressTrackerVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`

**Interfaces:**
- Produces: `ActionProgressEmissionPolicy.shouldEmit(double progress, long nowEpochMs)` with a 0.05 material delta and 1,000 ms heartbeat.
- Produces: immutable `ServerActionProgress` carrying agent ID, revision, action ID/type, bounded progress, elapsed time, and observation time.
- Consumes: protocol-v2's existing `action_progress` payload; no new message type is introduced.

- [ ] **Step 1: Write failing progress policy and tracker tests**

```java
ActionProgressEmissionPolicy policy = new ActionProgressEmissionPolicy(0.05D, 1_000L);
assertTrue(policy.shouldEmit(0.0D, 1_000L), "first progress emits");
assertFalse(policy.shouldEmit(0.01D, 1_100L), "jitter is suppressed");
assertTrue(policy.shouldEmit(0.06D, 1_200L), "material progress emits");
assertTrue(policy.shouldEmit(0.06D, 2_200L), "heartbeat emits without progress");

ActionProgressTracker tracker = new ActionProgressTracker(10.0D, 1_000L, 3_000L);
assertEquals(0.5D, tracker.progress(5.0D), "distance progress is normalized");
```

- [ ] **Step 2: Run Java test compilation and verify RED**

Run: `.\gradlew.bat compileTestJava --no-daemon --console=plain`.

Expected: missing policy/progress record and missing `ActionProgressTracker.progress` fail compilation.

- [ ] **Step 3: Implement bounded progress types and throttling**

Validate every duration and progress value. Keep first emission immediate, emit later only for material forward progress or the heartbeat deadline, and never regress the last emitted progress. Add a result-only `ServerActionExecutor` constructor that delegates to a new `(manager, resultSink, progressSink)` constructor so existing tests and consumers remain compatible.

- [ ] **Step 4: Publish progress from active actions**

For movement use normalized remaining distance. For controllers retain `TickResult.progress()`. For timed use/wait and postcondition actions use bounded elapsed/timeout progress capped below one until terminal completion. Publish through the injected sink only after `ActiveAction.tick()` returns nonterminal and the throttle accepts it.

```java
progressSink.accept(new ServerActionProgress(
	request.agentId(), request.goalRevision(), request.actionId(), request.type(),
	progress, Math.max(0L, now - startedAt), now
));
```

Encode this as the already-supported `action_progress` bridge message. Never publish after cancellation or a terminal result.

- [ ] **Step 5: Run focused Java verification and verify GREEN**

Run: `.\gradlew.bat verifyCore --no-daemon --console=plain`.

Expected: all action tracker/executor and protocol assertions pass.

- [ ] **Step 6: Commit the progress slice**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressEmissionPolicy.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionProgress.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressTracker.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/server/runtime/ActionProgressTrackerVerification.java src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java
git commit -m "feat: publish bounded action progress"
```

### Task 5: Repeated headless stress and release evidence

**Files:**
- Create: `scripts/run-performance-reliability-verification.ps1`
- Create: `docs/plans/2026-08-11-eight-agent-performance-reliability-report.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: repository Gradle verification, coordinator Node tests, and the eight-agent fake-provider soak.
- Produces: a headless verification script with explicit nonzero exit on any failed stage and a report that distinguishes measured evidence from unavailable live metrics.

- [ ] **Step 1: Write the verification script with bounded stages**

The script must locate the project Java 25 toolchain the same way as `scripts/run-automated-verification.ps1`, run that existing verifier once, then run the eight-agent soak 50 times in separate Node processes. It must count failures, print elapsed time and pass totals, and exit 1 when any run fails.

```powershell
for ($iteration = 1; $iteration -le 50; $iteration++) {
	& node --test test/eight-agent-soak.test.mjs
	if ($LASTEXITCODE -ne 0) { throw "Eight-agent soak failed on iteration $iteration" }
}
```

- [ ] **Step 2: Run the headless verification**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1`.

Expected: clean Gradle/Fabric build, all Java assertions, all coordinator tests, and 50/50 repeated eight-agent soak passes.

- [ ] **Step 3: Run an isolated headless server smoke**

Queue a `stop` command on standard input and launch only the offline isolated smoke server:

```powershell
@('stop') | powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1 -OfflineSmoke
```

Capture the command output, require the dedicated-server startup banner and a zero exit code, and never open the normal Minecraft world or a client GUI. If a required isolated runtime artifact is absent, preserve the automated evidence and record the exact missing path in the report rather than substituting simulated evidence.

- [ ] **Step 4: Write the evidence report and README guidance**

Record actual assertion/test counts, soak pass count, wall-clock duration, server startup result, warnings, and any metrics that remained unverified. Document the new `coordinator_status.latencies`, four-turn default, two-observation-per-tick budget, 10-tick expensive-section cache, progress throttle, and verification command.

- [ ] **Step 5: Run final repository checks**

Run:

```powershell
git diff --check
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1
git status --short --branch
```

Expected: no diff errors, full verifier success, 50/50 soak success, and only intentional project files modified.

- [ ] **Step 6: Commit the verification slice**

```powershell
git add scripts/run-performance-reliability-verification.ps1 docs/plans/2026-08-11-eight-agent-performance-reliability-report.md README.md
git commit -m "test: add eight-agent reliability gate"
```
