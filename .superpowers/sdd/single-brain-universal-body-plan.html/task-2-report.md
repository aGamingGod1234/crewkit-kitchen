# Task 2 report: bounded latency traces

## Outcome

Task 2 is implemented. One bounded opaque `traceId` now follows a selected-model planning task through admission, provider response, parsing, program dispatch, first physical-world action, and completion-verification status. The existing benchmark recorder receives phase rows, while the existing control-latency registry owns bounded trace summaries. No provider profile, session, or action authority changed.

## TDD evidence

The first concrete checkpoint was a RED run in a detached worktree at the Task 2 starting commit, with the new registry tests applied before the implementation. The focused command was:

```text
npm test -- --test-name-pattern="latency registry"
```

Expected RED evidence: the new complete-trace, invalid-phase, provider-isolation, and retry-reason cases failed with `TypeError: registry.recordTracePhase is not a function` because the starting registry had no trace API. The detached fixture also reported its unrelated missing `coordinator/node_modules/acorn` dependency while loading the full suite; the isolated registry failures were the intended RED checkpoint.

Focused GREEN coverage then passed for:

- exact seven-phase ordering, exact durations, skipped verification, and fail-closed incomplete traces;
- unknown, duplicate, overlapping, and backward phases;
- provider phases excluded from the five local aggregates;
- normalized, bounded, credential-safe retry reasons;
- malformed-decision retry sharing one trace and recording queue/provider/parse once;
- strict action command/progress/result trace round trips, missing/blank/overlong/mismatched rejection;
- planner-to-runtime dispatch and first-world-action separation;
- Java request/progress/result trace retention and typed invalid-ID checks;
- deterministic trace loads 1, 4, 8, and 16.

## Phase schema

Each raw phase row contains `traceId`, `phase`, monotonic `startMonotonicMs`, monotonic `endMonotonicMs`, exact `durationMs`, and `outcome` (`completed`, `failed`, or `skipped`). Optional `retryReason` is normalized to at most 64 ASCII characters. The fixed phase registry is:

```text
queue_wait
provider_first_byte
provider_final_byte
parse
first_command_dispatch
first_world_action
completion_verification
```

Required successful phases are the first six. Completion verification is emitted as `skipped` with `VERIFIER_NOT_INSTALLED` until Task 5. Missing, duplicate, unknown, overlapping, or out-of-order phases remain incomplete and have no published total. Trace storage is capped at 4,096 identities. Trace IDs are bounded to 128 UTF-8 bytes and reject control characters.

Provider duration remains in provider-turn telemetry and is not added to local control-operation aggregates. Java epoch timestamps remain wire facts; coordinator trace spans use the monotonic coordinator clock.

## Files changed

Coordinator production:

- `coordinator/src/control-latency-registry.mjs`
- `coordinator/src/provider-turn-telemetry.mjs`
- `coordinator/src/agent-planner.mjs`
- `coordinator/src/program-runtime-manager.mjs`
- `coordinator/src/dynamic-main.mjs`
- `coordinator/src/benchmark/latency-runner.mjs`
- `coordinator/src/protocol-v2.mjs`
- `coordinator/src/schema.mjs`
- `coordinator/src/simulator/virtual-minecraft-bridge.mjs`

Coordinator tests/fixtures:

- `coordinator/test/control-latency-registry.test.mjs`
- `coordinator/test/agent-planner.test.mjs`
- `coordinator/test/program-runtime-manager.test.mjs`
- `coordinator/test/dynamic-main.test.mjs`
- `coordinator/test/latency-runner.test.mjs`
- `coordinator/test/protocol-v2.test.mjs`
- `coordinator/test/scenario-program.test.mjs`
- `coordinator/test/model-authored-programs-e2e.test.mjs`
- `coordinator/test/fixtures/fake-minecraft-bridge.mjs`
- `coordinator/test/fixtures/two-agent-fixture.mjs`

Java bridge/executor and verification:

- `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProvenance.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionRequest.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionProgress.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionResult.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- `src/test/java/dev/agaminggod/arenaagents/server/bridge/BridgeEnvelopeCodecVerification.java`
- `src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java`

## Verification

Required final verification:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

Result: passed. Gradle reported 6,370 protocol/bridge assertions and 77 voice-addon assertions. Coordinator reported 533 passed, 0 failed. The expected deprecated Java API and intentional telemetry-unavailable warning remained non-fatal.

## Self-review and concerns

- Action traces are required on the v2 coordinator action wire and on Java bridge requests. Legacy lifecycle/control messages remain unchanged.
- Java record compatibility constructors still permit null traces for isolated legacy unit callers, but the v2 action bridge requires a trace before accepting an action command.
- Reactive planning can overlap an older physical action. Runtime tracks the action's trace separately so a late progress/result is validated against the trace that authorized that action, while the next planning task receives a new trace.
- Completion verification is deliberately not reported as successful. It remains explicitly skipped until Task 5 installs factual verification.
- No second telemetry sink, provider fallback, profile mutation, prompt text, provider output, credentials, or exception dump is placed in trace rows.

## Fix round 1/5 evidence

The independent review identified seven Important findings and one Minor. TDD RED coverage was added before the fix-round production edits and run with:

```text
node --test coordinator/test/control-latency-registry.test.mjs coordinator/test/agent-planner.test.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/latency-runner.test.mjs coordinator/test/virtual-minecraft-bridge.test.mjs
```

RED result: 82 passed, 5 failed. The five failures were the new assertions for registry poisoning, unsafe retry-reason rejection, planner clock regression, real matrix trace capture, and bridge pre-execution rejection attribution.

Fix-round GREEN focused commands and results:

```text
node --test coordinator/test/agent-planner.test.mjs coordinator/test/control-latency-registry.test.mjs coordinator/test/latency-runner.test.mjs
# 49 passed, 0 failed

node --test coordinator/test/dynamic-main.test.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/virtual-minecraft-bridge.test.mjs coordinator/test/protocol-v2.test.mjs
# 98 passed, 0 failed

node --test coordinator/test/scenario-program.test.mjs
# 5 passed, 0 failed

$env:JAVA_HOME=(Resolve-Path 'runtime/toolchains/temurin-25/jdk-25.0.3+9').Path; .\gradlew.bat verifyCore --no-daemon --console=plain
# BUILD SUCCESSFUL, 6,370 protocol and bridge assertions
```

The fix closes the review findings by poisoning traces on every invalid phase attempt, retaining only stable bounded retry codes or `ERROR`, rejecting credential/provider/exception-shaped text, requiring Java top-level and provenance trace equality, failing closed for null traces on model-action submission, carrying authoritative `executionStarted`/`physicalAttempted` result markers, and suppressing `first_world_action` on pre-execution rejection. `runLatencyMatrix` now injects the real registry into the production coordinator path, uses a shared deterministic clock, exposes captured traces per trial, and uses a minimum 64-trace cap. Producer phase rows are validated before the recorder receives them, with clock regression invalidating the trace. Virtual and fake bridges no longer synthesize action traces; isolated scenario fixtures now supply explicit traces. Dynamic-main passes the work trace explicitly and rejects a mismatched planner echo.

The final coordinator run passed 538 tests with 0 failures. The required full automated verification script then passed in one run: Gradle 6,370 protocol/bridge assertions, 77 voice-addon assertions, and coordinator 538 tests, 0 failures. The earlier direct Gradle invocation without the pinned project JDK was intentionally not counted because it failed during Java toolchain initialization (`release version 25 not supported`).

Fix-round concerns: legacy Java record constructors remain available for explicitly out-of-band callers, but `submitProgramPrimitive` rejects their null trace. Older direct coordinator unit calls that omit execution markers retain a compatibility fallback; all protocol bridge/simulator results now carry explicit authoritative markers, and a marker pair of `false, false` is fail-closed.
