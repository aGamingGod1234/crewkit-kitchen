# Task 1 implementation report: enforce the single-brain boundary

## Outcome

Task 1 removes the body-authored safety movement loop and keeps urgent hazards on the existing factual observation-attention path. Provider ownership now treats `{provider, model, reasoningEffort, serviceTier}` as immutable for an agent ID, including in-flight creation, retry, and recovery. Profile conflicts fail closed with bounded, credential-free typed errors.

## TDD evidence

Focused tests were written or changed before production edits.

### RED

Coordinator tests against the unmodified coordinator production code:

```text
node --test --test-concurrency=1 coordinator/test/provider-service.test.mjs
```

Relevant result:

```text
tests 7
pass 5
fail 2
provider router rejects every profile mutation for an existing agent ID: Missing expected rejection
provider router reserves an in-flight agent ID before recovery can mutate its profile: Missing expected rejection
```

Java focused verification initially used the wrong daemon JDK and failed setup with `release version 25 not supported`. With the project Temurin 25 JDK selected, the unchanged production code produced the intended behavioral RED:

```text
$taskJdk = (Resolve-Path 'runtime/toolchains/temurin-25/jdk-25.0.3+9').Path
$env:JAVA_HOME = $taskJdk
.\gradlew.bat verifyCore
```

Relevant result:

```text
java.lang.AssertionError: player.air remains a factual attention delta
at AttentionHazardVerification.java:31
> Task :verifyCore FAILED
```

### GREEN

Focused coordinator verification:

```text
node --test --test-concurrency=1 coordinator/test/provider-service.test.mjs coordinator/test/acp-service.test.mjs coordinator/test/codex-service.test.mjs coordinator/test/agent-planner.test.mjs
```

Result:

```text
tests 51
pass 51
fail 0
cancelled 0
```

Focused Java verification:

```text
$taskJdk = (Resolve-Path 'runtime/toolchains/temurin-25/jdk-25.0.3+9').Path
$env:JAVA_HOME = $taskJdk
.\gradlew.bat verifyCore
```

Result:

```text
PASS: 6364 protocol and bridge assertions
BUILD SUCCESSFUL
```

## Files changed

- `coordinator/src/provider-service.mjs`: reserves agent IDs during creation, stores immutable normalized profiles, routes existing IDs to their original provider, and raises typed profile conflicts.
- `coordinator/src/codex-service.mjs`: keeps profile conflict diagnostics bounded and credential-free while preserving exact profile matching.
- `coordinator/src/acp-service.mjs`: includes service tier in ACP profile identity and rejects mutations for existing or in-flight sessions.
- `coordinator/test/provider-service.test.mjs`: covers provider, model, reasoning-effort, service-tier, and in-flight profile mutations.
- `coordinator/test/codex-service.test.mjs`: covers exact profile reuse across recovery and one persistent Codex thread.
- `coordinator/test/acp-service.test.mjs`: covers exact profile reuse across recovery and one persistent ACP session.
- `coordinator/test/agent-planner.test.mjs`: asserts urgent retry preserves the complete selected profile.
- `src/main/java/dev/agaminggod/arenaagents/server/perception/AttentionFactDelta.java`: keeps air, max air, and fall distance as attention facts during an active action.
- `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java`: removes the safety-reflex tick registration.
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/AgentInputRuntime.java`: removes safety-reflex cleanup.
- Deleted `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/SafetyInputReflex.java`.
- Deleted `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/SafetyInputLease.java`.
- Deleted `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/SafetyInputRuntime.java`.
- Deleted now-unused `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/RecentDamageTracker.java`.
- Deleted `src/test/java/dev/agaminggod/arenaagents/server/runtime/input/SafetyInputReflexVerification.java`.
- Added `src/test/java/dev/agaminggod/arenaagents/server/perception/AttentionHazardVerification.java` with observable factual hazard assertions for health, fire, air, suffocation, falling, attacker, and lava.
- Updated `src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java` for factual falling attention and motion-fixture isolation.
- Updated `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` to run the factual hazard verification.

## Full verification

Required command, run once before commit:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

Result:

```text
BUILD SUCCESSFUL in 38s
PASS: 6364 protocol and bridge assertions
PASS: 77 voice-addon assertions
tests 523
pass 523
fail 0
cancelled 0
Automated Java, Fabric, coordinator, and fake-E2E verification passed.
```

The full run also completed the fake end-to-end scenarios, including pre-authored falling and lava interrupts, same-model recovery, and no replacement destination on path failure.

## Self-review

- No `SafetyInput` or `RecentDamageTracker` references remain under `src` or `coordinator`.
- The body no longer emits a replacement direction, attack, flee, jump, sprint, speech, goal completion, or provider fallback from the removed safety path.
- Hazard assertions inspect changed factual paths and reject tactical labels. They do not grep production source.
- Profile conflicts use stable typed codes and static messages. The messages are bounded and contain no profile values or credentials.
- Existing successful profile reuse returns the original provider agent/session. In-flight reservations prevent a concurrent recovery request from changing ownership.
- `git diff --check` is clean.

## Concerns

ACP transports do not expose a service-tier configuration operation. The selected service tier is still retained and compared as part of the immutable profile at the ACP and provider-router boundaries; no alternate ACP session or provider is created when it changes.
