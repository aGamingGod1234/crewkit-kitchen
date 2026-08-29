# Coordinator Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Minecraft coordinator, exact selected agent profiles, and active goals recoverable through process crashes, provider failures, bridge stalls, runtime corruption, and optional-component failures without requiring Minecraft or the player to restart or resume work.

**Architecture:** Java owns a non-terminal coordinator recovery state machine and a verified active/last-known-good runtime pair. The Node coordinator starts its Minecraft bridge before providers, isolates each provider behind settled catalog and session recovery boundaries, and fences all asynchronous work by bridge session and goal lease. Active goals remain active until a verified domain outcome or explicit player pause. Voice and diagnostics fail independently and retry in the background.

**Tech Stack:** Java 21/Fabric, Node.js ES modules, PowerShell deployment scripts, Gradle verification harness, Node `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-27-coordinator-recovery-design.md`

## Global Constraints

- Never substitute a provider, model, reasoning effort, or service tier. Recovery must preserve the exact five-field profile identity: agent ID, provider, model, reasoning effort, and service tier.
- No runtime failure may create a permanent failure latch. Only explicit shutdown or disabled autostart may enter `STOPPED`.
- `PAUSED` is player intent only. Coordinator, provider, bridge, scheduler, reload, and program-replan failures remain recoverable active work.
- Every retry owns a deadline and a dedupe identity. Repeated ticks and reconnect events must converge to one process, one provider session, one turn, and one physical action.
- Start the Minecraft bridge before providers and before optional voice services.
- Keep credentials and user configuration outside runtime generations. Never log or package secrets.
- Use injected clocks, fake processes, and fake sockets for deterministic fault tests. Run live/headless smoke only after deterministic gates pass.
- Use `apply_patch` for edits. Preserve existing dirty work. One implementation owner at a time; reviews use fresh read-only agents.

---

## Task 1: Make active-goal recovery an explicit domain state

**Files:**

- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentLifecycleReducer.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistry.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `coordinator/src/agent-registry.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java`
- Test: `coordinator/test/agent-registry.test.mjs`
- Test: `coordinator/test/dynamic-main.test.mjs`
- Test: `coordinator/test/authoritative-goal-lifecycle.test.mjs`

- [ ] Replace reload/disconnect reconciliation that manufactures `PAUSED` with a server-authoritative rearm transition. Preserve the current goal, revision, exact profile, and explicit player-pause state.
- [ ] Add the smallest API needed on both registries:

```java
AgentRecord rearmAfterCoordinatorRecovery(UUID agentId, long expectedGoalRevision, long nowEpochMs);
```

```js
registry.reconcile(records, { recovery: true })
// recoverable STARTING/PLANNING/ACTING/DISCONNECTED -> STARTING
// explicit PAUSED stays PAUSED
```

- [ ] Make `agent_ready` rearm a matching `DISCONNECTED` record and request fresh Minecraft facts. Duplicate ready/recovery observations must schedule one plan.
- [ ] Add red tests for server reload, coordinator disconnect/reconnect, duplicate ready, and stale goal revision. Assert exact profile equality and no player resume command.
- [ ] Run:

```powershell
.\gradlew.bat verifyCore --no-daemon --console=plain
Push-Location coordinator
node --test test/agent-registry.test.mjs test/dynamic-main.test.mjs test/authoritative-goal-lifecycle.test.mjs
Pop-Location
```

- [ ] Commit: `fix keep active goals recoverable across coordinator restarts`

## Task 2: Fence bridge sessions and recover dead connections

**Files:**

- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/ProgramActionLedger.java`
- Test: `coordinator/test/protocol-v2.test.mjs`
- Test: `coordinator/test/dynamic-main.test.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/ProgramActionLedgerVerification.java`

- [ ] Add a monotonically increasing connection epoch to Node `ready` and `disconnected` lifecycle events. Capture it in reconciliation, planning, program callbacks, catalog/status publication, and action/completion sends; discard callbacks from older epochs.
- [ ] Add injected handshake and authenticated-heartbeat deadlines. A connected peer that never acknowledges, or an authenticated peer that becomes silent, must be destroyed and reconnected with capped backoff.
- [ ] Extend action dedupe for the full active goal/session generation so eviction of the bounded diagnostics cache cannot make an old command executable again.
- [ ] Add red tests for no `hello_ack`, missed heartbeat, old reconciliation completion after reconnect, late provider/action completion, duplicate terminal results, and replay after more than 4,096 commands.
- [ ] Run:

```powershell
Push-Location coordinator
node --test test/protocol-v2.test.mjs test/dynamic-main.test.mjs
Pop-Location
.\gradlew.bat verifyCore --no-daemon --console=plain
```

- [ ] Commit: `fix fence coordinator work by live bridge session`

## Task 3: Replace the Java process failure latch with a recovery state machine

**Files:**

- Create: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorRecoveryState.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorRecoverySnapshot.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorLaunchPolicy.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorProcessSupervisor.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorProcessSupervisorVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorLaunchPolicyVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorStartupSmokeVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

- [ ] Implement states `STARTING`, `AUTHENTICATING`, `HEALTHY`, `DEGRADED`, `BACKOFF`, `BLOCKED_RETRYABLE`, and `STOPPED`. Remove `failureCode` as a tick gate; it remains diagnostic snapshot data.
- [ ] Use indefinite capped retry delays `1s, 2s, 5s, 15s, 30s, 30s...`. Reset only after a continuous authenticated stability interval.
- [ ] Inject a clock and process-launch seam. Revalidate Node, runtime, config, and secret periodically and immediately when their fingerprint changes.
- [ ] Generate a launch UUID, pass it as `ARENA_AGENT_COORDINATOR_LAUNCH_ID`, include it in the optional protocol hello payload, and only credit matching bridge authentication to the owned process.
- [ ] Terminate and replace live processes that miss authentication or reconnect deadlines. Make repeated ticks and `close()` idempotent.
- [ ] Start/retry the Java bridge even when Node/runtime/voice is unavailable. Replace restart-Minecraft guidance with current state, failing boundary, and next retry time.
- [ ] Add fake-clock/fake-child red tests for eight crashes, capped delay, hung authentication, stale launch ID, reconnect recovery/expiry, missing-then-restored Node, bind failure recovery, and close.
- [ ] Run:

```powershell
.\gradlew.bat verifyCore --no-daemon --console=plain
```

- [ ] Commit: `fix supervise the coordinator until it recovers`

## Task 4: Retain and roll back a verified runtime generation

**Files:**

- Modify: `src/main/java/dev/agaminggod/arenaagents/server/BundledCoordinatorInstaller.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorProcessOwnership.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CoordinatorProcessSupervisor.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/BundledCoordinatorInstallerVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorProcessOwnershipVerification.java`
- Modify: `scripts/distribution-runtime.ps1`
- Modify: `scripts/verify-distribution-runtime.ps1`
- Modify: `scripts/install-normal-profile-update.ps1`
- Modify: `scripts/test-install-normal-profile-update.ps1`
- Modify: `runtime/README.md`

- [ ] Keep the active path `coordinator/`, a single `coordinator.last-known-good/`, disposable `coordinator.staging-*`, and an atomic generation-state record under `runtime/`.
- [ ] Move canonical user configuration to `runtime/dynamic-agents.json`; migrate the existing active config once. Keep the bridge secret, Fish key, provider credentials, logs, and traces outside generations.
- [ ] Add idempotent prepare/promote/rollback operations. A candidate becomes last-known-good only after the supervisor’s stability interval. Repeated startup/auth failures roll back only after the owned process is dead and ownership is cleared. Never delete the only runnable generation.
- [ ] Journal swaps deterministically, validate hashes before activation and after interrupted recovery, reject linked/reparse-point mutation targets, and keep generation cleanup bounded.
- [ ] Add generation ID and launch ID to ownership records so stale ownership cannot kill or clear a newer process.
- [ ] Make both PowerShell deployment paths use the same atomic active/LKG algorithm while preserving external config and credentials.
- [ ] Add red tests for A→B→rollback A, B promotion, no-LKG candidate failure, config/secret hash preservation, interrupted swap, stale ownership, repeated operations, and injected updater failures.
- [ ] Run:

```powershell
.\gradlew.bat verifyCore --no-daemon --console=plain
powershell -ExecutionPolicy Bypass -File scripts\verify-distribution-runtime.ps1
powershell -ExecutionPolicy Bypass -File scripts\test-install-normal-profile-update.ps1 -ProjectRoot (Get-Location)
```

- [ ] Commit: `fix retain a verified coordinator runtime for rollback`

## Task 5: Start core first and isolate provider/catalog failures

**Files:**

- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/provider-service.mjs`
- Modify: `coordinator/src/model-catalog-cache.mjs`
- Modify: `coordinator/src/codex-service.mjs`
- Test: `coordinator/test/dynamic-main.test.mjs`
- Test: `coordinator/test/provider-service.test.mjs`
- Test: `coordinator/test/model-catalog-cache.test.mjs`
- Test: `coordinator/test/codex-service.test.mjs`

- [ ] Bind/start the Minecraft bridge without awaiting provider startup. Make provider startup coalesced and lazy for providers used by the roster or agent creation.
- [ ] Replace fail-fast provider aggregation with settled per-provider reconciliation and catalog refresh. Preserve healthy results, retain each provider’s last valid assignment/catalog, and expose a bounded recovery record for degraded providers.
- [ ] Make model catalogs return `live`, `last_valid`, or deterministic exact-profile `builtin` snapshots. Never invent an alternate model or replace a valid snapshot with malformed data.
- [ ] Fence reconciliation by Task 2’s connection epoch and bound it so goal controls do not queue forever.
- [ ] Add red tests for hung/throwing Codex startup while bridge and Gemini remain usable, one failed catalog among healthy providers, stale fallback promotion, malformed refresh retention, duplicate startup coalescing, and provider restoration without coordinator restart.
- [ ] Run:

```powershell
Push-Location coordinator
node --test test/dynamic-main.test.mjs test/provider-service.test.mjs test/model-catalog-cache.test.mjs test/codex-service.test.mjs
Pop-Location
```

- [ ] Commit: `fix isolate provider startup and catalog recovery`

## Task 6: Replace dead exact-profile sessions and own retry deadlines

**Files:**

- Modify: `coordinator/src/provider-session.mjs`
- Modify: `coordinator/src/provider-service.mjs`
- Modify: `coordinator/src/codex-service.mjs`
- Modify: `coordinator/src/acp-service.mjs`
- Modify: `coordinator/src/antigravity-service.mjs`
- Modify: `coordinator/src/cursor-service.mjs`
- Modify: `coordinator/src/codex-app-server.mjs`
- Modify: `coordinator/src/provider-health-registry.mjs`
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/src/planning-scheduler.mjs`
- Modify: `coordinator/src/work-lease-supervisor.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Test: corresponding provider, planner, scheduler, lease, and lifecycle test files under `coordinator/test/`

- [ ] Add `replaceAgent(profile, options)` to provider routing and each backend. Atomically fence/dispose the old generation and recreate only an identical exact profile; coalesce concurrent replacements.
- [ ] Observe transport exit/protocol loss. Invalidate the owning session generation, clear dead thread/session/continuation IDs, and reject late callbacks.
- [ ] Key provider circuits by the existing exact `profileFingerprint`, not just provider/model/operation. Expose the next half-open probe deadline.
- [ ] Key recovery leases by `{agentId, goalRevision, lifecycleGeneration, sessionEpoch, profileFingerprint}`. Provider/session/circuit/scheduler timeouts remain active work, replace the exact session, request fresh facts, and schedule one retry. Only verified domain-terminal outcomes may enter `ERROR`.
- [ ] Give planning turns hard lease deadlines. If abort is ignored, tear down only the exact provider session and release scheduler capacity.
- [ ] Make reactive program suspension recover through the same lease path rather than mapping internal `SUSPENDED` to player `PAUSED`.
- [ ] Add red tests for transport death in every backend, service-tier mismatch rejection, replacement coalescing, profile-isolated circuits, repeated cooldown/probe recovery, ignored abort, duplicate callbacks, bridge disconnect during recovery, and reactive planner failure followed by resumed work.
- [ ] Run:

```powershell
Push-Location coordinator
node --test test/provider-service.test.mjs test/codex-service.test.mjs test/acp-service.test.mjs test/antigravity-service.test.mjs test/cursor-service.test.mjs test/provider-health-registry.test.mjs test/agent-planner.test.mjs test/planning-scheduler.test.mjs test/work-lease-supervisor.test.mjs test/program-runtime-manager.test.mjs test/authoritative-goal-lifecycle.test.mjs
Pop-Location
```

- [ ] Commit: `fix recover exact provider sessions and planning leases`

## Task 7: Isolate voice and diagnostics, and expose actionable recovery status

**Files:**

- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/voice/voice-http-server.mjs`
- Modify: `coordinator/src/trace-writer.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java`
- Test: `coordinator/test/voice-bootstrap.test.mjs`
- Test: `coordinator/test/voice-edge-cases.test.mjs`
- Test: `coordinator/test/trace-writer.test.mjs`
- Test: `coordinator/test/protocol-v2.test.mjs`
- Test: `coordinator/test/dynamic-main.test.mjs`

- [ ] Make voice startup/bind/provider failure non-blocking and retryable. Aborted TTS/STT must release concurrency slots; start/close must be idempotent.
- [ ] Make diagnostic writes best-effort, bounded, redacted, and incapable of rejecting control work.
- [ ] Publish component states, failure boundary/code, next retry deadline, bridge session epoch, runtime generation, and exact-profile service tier. Keep player chat transition-based rather than log spam.
- [ ] Add red tests for voice bind recovery, TTS/STT timeout recovery, concurrent start/close, diagnostic write failure, bounded diagnostics, and status schema.
- [ ] Run:

```powershell
Push-Location coordinator
node --test test/voice-bootstrap.test.mjs test/voice-edge-cases.test.mjs test/trace-writer.test.mjs test/protocol-v2.test.mjs test/dynamic-main.test.mjs
Pop-Location
.\gradlew.bat verifyCore --no-daemon --console=plain
```

- [ ] Commit: `fix isolate optional services and report recovery state`

## Task 8: Add the deterministic recovery fault matrix

**Files:**

- Create: `coordinator/test/coordinator-recovery.test.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `coordinator/test/provider-process-cleanup.test.mjs`
- Modify: `coordinator/test/program-runtime-manager.test.mjs`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorProcessSupervisorVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/CoordinatorStartupSmokeVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

- [ ] Cover provider-start hang, provider outage/restoration, coordinator crash loops, auth hang, half-open socket, bridge bind conflict, disconnect during active work, stale callback/action replay, runtime corruption/rollback, ignored abort, voice bind failure, and diagnostic failure.
- [ ] Assert no permanent failure state, no `PAUSED`/`ERROR` for infrastructure faults, exact-profile equality, bounded retry delay/resource use, one active lease/session/action, and automatic promotion back to healthy.
- [ ] Run focused deterministic gates, then the real-process smoke:

```powershell
.\gradlew.bat verifyCore --no-daemon --console=plain
Push-Location coordinator
node --test --test-name-pattern "recovery|reconnect|provider|voice|lease|generation" test/*.test.mjs
Pop-Location
.\scripts\run-performance-reliability-verification.ps1
.\scripts\run-antigravity-headless-smoke.ps1
```

- [ ] Commit: `test prove coordinator recovery across injected failures`

## Task 9: Full verification and first PR update

- [ ] Run the complete repository gate exactly once after focused tests are green:

```powershell
.\scripts\run-automated-verification.ps1
```

- [ ] Fix every reproducible failure at its source, add/adjust the smallest covering regression test, rerun the focused test, then rerun the complete gate.
- [ ] Review `git diff`, packaging contents, secret exclusions, and commit history. Commit any suite-driven fixes.
- [ ] Fast-forward push the completed branch to PR #8’s head branch. Update the normal PR title/body to explain reported failures, root causes, recovery design, and user-visible reliability improvements.

## Task 10: Process bot review and update PR again

- [ ] Wait for all PR checks and bot reviews to settle.
- [ ] Use `gh-address-comments` for comments and `gh-fix-ci` only for failing checks. Validate each comment against the current code; fix every valid issue with a covering test and reject invalid suggestions with evidence.
- [ ] Rerun focused tests and the full repository gate after comment fixes.
- [ ] Commit and fast-forward push the review fixes. Recheck that all conversations are resolved and required checks are green.

## Task 11: Fresh reliability, performance, and agent-capability review swarm

- [ ] Only after Task 10, dispatch a fresh read-only swarm with non-overlapping charters:
  1. Java supervisor/runtime rollback reliability.
  2. Node bridge/protocol/reconciliation reliability.
  3. Provider/session/circuit/scheduler reliability.
  4. Goal lifecycle/action idempotency and persistence.
  5. Voice/diagnostics isolation and latency.
  6. CPU, memory, I/O, process, and network performance.
  7. Minecraft movement/pathfinding and obstacle/hazard handling.
  8. Planning/tool schema/executor feedback for faster, more complex, more reliable in-game actions.
- [ ] Require source-line evidence, reproduction/test proposals, severity, and separation of correctness bugs from optional optimizations.
- [ ] Use one fresh implementation agent to resolve all validated blocking findings, then one fresh scoped reviewer. Add focused tests, rerun the full repository gate, commit, and update PR #8 one final time.
- [ ] Record non-blocking capability/performance recommendations in a concise repository document; do not silently broaden this reliability implementation.

## Completion Evidence

- Every implementation task has a red-to-green test record and task review.
- `run-automated-verification.ps1` passes after implementation, after bot-comment fixes, and after validated review-swarm fixes.
- PR #8 contains all commits, all required checks are green, and all valid review comments are resolved.
- Recovery diagnostics demonstrate automatic return from degraded/backoff/LKG states to exact-profile healthy work without Minecraft restart or player resume.
