# Model-Owned Minecraft Responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the model fresh information during Minecraft actions and make every continue, cancel, or replacement response an explicit model decision.

**Architecture:** The Java bridge publishes bounded raw observations without changing player controls. The coordinator runs a coalesced reactive decision turn and applies an explicit directive through an action-ID-guarded cancellation protocol. Runtime failures remain facts for the model rather than strategy chosen by the system.

**Tech Stack:** Java 25, Fabric/Minecraft server APIs, Node.js ESM, protocol v2 JSON, `node:test`, Gradle verification harness, PowerShell reliability scripts.

## Global Constraints

- The AI model alone chooses gameplay responses, including reactions to damage and danger.
- Observation code may report facts and wake the model but may not stop, cancel, replace, or select an action.
- The offline player bot only executes explicit model decisions and bounded mechanical action semantics.
- Preserve all unrelated dirty-worktree changes.
- Use focused red-green tests before each production change.
- Keep queues bounded and never block the Minecraft server tick on provider work.

---

### Task 1: Passive server-side observation

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Delete: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/DamageReplanSignal.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/SurvivalReflexVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/BridgeActionIntegrationVerification.java`

**Interfaces:**
- Produces observations that contain `observedAtEpochMs`, factual player state, and `currentAction` while the action continues.
- Does not emit `DAMAGE_OBSERVED` or call action cancellation from observation code.

- [ ] Write a failing verification asserting health-change classification cannot return a replan/stop decision.
- [ ] Run the focused Java verifier and confirm the old damage-interruption behavior fails the new assertion.
- [ ] Remove the damage termination path and its strategy-named helper.
- [ ] Add bounded in-action observation publication from factual state changes and progress heartbeats.
- [ ] Remove the derived `dangerousFall` field while retaining raw `fallDistance` and `onGround`.
- [ ] Run the focused Java and bridge integration verifiers.

### Task 2: Explicit planner directives

**Files:**
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/src/decision-parser.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`
- Modify: `coordinator/test/provider-service.test.mjs`
- Modify: `coordinator/test/codex-service.test.mjs`

**Interfaces:**
- Produces `decision.directive` with `continue`, `cancel`, or `replace`.
- `continue` and `cancel` require zero actions; `replace` requires one to four actions.

- [ ] Add failing parser and schema tests for all three directives and invalid combinations.
- [ ] Run the focused Node tests and confirm the new cases fail for the expected missing-field/schema reasons.
- [ ] Implement directive parsing, cross-field validation, schema, and concise prompt rules.
- [ ] Update provider fixtures to the required structured output.
- [ ] Run parser, prompt, and provider tests.

### Task 3: Action-ID-guarded cancellation protocol

**Files:**
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeActionIntegrationVerification.java`

**Interfaces:**
- Produces coordinator message `action_cancel` with `{goalRevision, actionId}`.
- Server cancellation succeeds only for the active matching revision and action ID and publishes a normal `CANCELLED` action result.

- [ ] Add failing Node protocol tests for valid, malformed, stale, and unknown cancellation messages.
- [ ] Add a failing Java bridge integration test for exact-ID cancellation.
- [ ] Implement protocol normalization and Java dispatch.
- [ ] Run both focused protocol suites.

### Task 4: Coalesced active-action decision loop

**Files:**
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/test/agent-planner.test.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`

**Interfaces:**
- Consumes active observations and explicit directives from Tasks 1 and 2.
- Produces no command for `continue`, `action_cancel` for `cancel`, and cancel-then-dispatch for `replace`.
- Coalesces one newest pending observation per agent and discards decisions whose action ID is stale.

- [ ] Replace tests that suppress active observations with failing tests proving they reach a non-overlapping reactive model turn.
- [ ] Add failing tests for continue, cancel, replace ordering, coalescing, and stale decisions.
- [ ] Implement a bounded per-agent reactive turn state outside the serialized control-event queue.
- [ ] Preserve the registry's `ACTING` state while a reactive turn is in flight.
- [ ] Run the focused coordinator suite.

### Task 5: Failure evidence and entrypoint cleanup

**Files:**
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `coordinator/package.json`
- Modify: `scripts/start-coordinator.ps1`
- Test: `coordinator/test/legacy-entrypoint.test.mjs`

**Interfaces:**
- Repeated failures remain fact-ledger input and trigger another model decision from the next observation.
- `npm start` and the standard PowerShell launcher use `dynamic-main.mjs`.

- [ ] Replace repeated-failure ERROR tests with failing tests that assert the model remains authoritative.
- [ ] Remove the automatic repeated-failure stop path without removing bounded factual ledger history.
- [ ] Add a failing entrypoint test and point standard launchers at the dynamic runtime.
- [ ] Run focused coordinator and launcher tests.

### Task 6: Perception fidelity and latency telemetry

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- Modify: `coordinator/src/control-latency-registry.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java`
- Test: `coordinator/test/control-latency-registry.test.mjs`

**Interfaces:**
- Entity facts distinguish visible from non-visible state and do not present hidden exact health as direct sight.
- Telemetry reports change-to-publication, observation-to-decision, and directive-to-control acknowledgement independently.

- [ ] Add failing perception tests for line of sight and hidden-detail filtering.
- [ ] Add failing latency tests for the three segments.
- [ ] Implement the minimal visibility and telemetry changes.
- [ ] Run focused perception and coordinator telemetry tests.

### Task 7: Repeated verification loop

**Files:**
- Modify only files implicated by fresh failures or live evidence.
- Record evidence under `docs/live-qa/` without secrets.

- [ ] Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1` and require 0 failures plus 50/50 soak runs.
- [ ] Launch the actual development client using the repository's supported run task.
- [ ] Exercise an agent during movement and a controlled damage/fire/fall scenario while recording protocol timestamps and player behavior.
- [ ] Verify that the action continues until the model explicitly chooses continue, cancel, or replace.
- [ ] Audit observation, coordinator, lifecycle, controller, and legacy paths again.
- [ ] Add a failing regression for every remaining autonomy violation, implement the narrow fix, and repeat the full verifier and live exercise.
