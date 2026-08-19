# Real-Provider Headless Matrix Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in developer runner that sends real tasks to real Codex, Gemini/Antigravity, and Kimi sessions, executes their ArenaScript in a dedicated headless Fabric server through the normal protocol path, and writes bounded evidence reports.

**Architecture:** A dependency-injected Node runner will validate a scenario matrix, drive RCON, capture validated protocol envelopes, record provider turns, evaluate assertions, and produce JSON reports. A Windows PowerShell wrapper will allocate isolated server directories/ports, launch the built Fabric server and real dynamic coordinator, invoke the Node runner, and always clean up the complete process trees. Production launches keep audits disabled and behavior unchanged.

**Tech Stack:** Node.js ESM (Node 22+), existing coordinator/provider services, protocol-v2 JSONL, Java 25/Fabric server, PowerShell, Node test runner.

**Spec:** `docs/superpowers/specs/2026-08-18-real-provider-headless-matrix-design.md`

## Global Constraints

- The first implementation uses real provider adapters and a real headless Fabric server; it does not add scripted model profiles or a virtual Minecraft backend.
- External provider calls are opt-in and must not run from `npm test`, Gradle `check`, or CI by default.
- Every scenario runs sequentially in a fresh server/world/coordinator/session directory and never reuses `run/world`.
- The runner must use the existing authenticated protocol-v2 and Java/Carpet action executor; it cannot inject action results, bypass validation, or install model source directly.
- Provider credentials, bridge secrets, OAuth state, process environments, and credential-bearing command lines never enter reports.
- Provider output and prompts are bounded by existing decision limits and redacted with existing trace rules; full bounded text is private-only.
- Java 25 and Node 22+ are required; every allocated process and listener must be cleaned up before the command exits.
- Existing coordinator and Java test suites remain green and unchanged in their default execution paths.

---

### Task 1: Define and validate the headless scenario matrix

**Files:**
- Create: `coordinator/src/headless-matrix.mjs`
- Create: `coordinator/test/headless-matrix.test.mjs`
- Create: `coordinator/config/headless-provider-matrix.json`

**Interfaces:**
- Produces `normalizeHeadlessMatrix(value) -> { version: 1, scenarios: HeadlessScenario[] }`.
- Produces `normalizeHeadlessScenario(value, index) -> HeadlessScenario` with `id`, `provider`, `model`, `reasoningEffort`, `serviceTier`, `task`, `timeoutMs`, and `assertions`.
- Produces `selectHeadlessScenarios(matrix, selector) -> HeadlessScenario[]`, where `selector` is `null` or one scenario ID.
- Produces `scenarioReport(status, scenario, fields) -> immutable serializable report data`.

- [ ] **Step 1: Write failing normalization tests**

```js
test('normalizes one bounded real-provider scenario', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [{
		id: 'codex-chat-completion', provider: 'codex', model: 'gpt-5.6-sol',
		reasoningEffort: 'high', serviceTier: 'fast', task: 'Send HEADLESS_PASS',
		timeoutMs: 180000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }],
	}] });
	assert.deepEqual(matrix.scenarios[0].assertions, [{ type: 'lifecycle', state: 'COMPLETED' }]);
});

test('rejects duplicate IDs, unknown assertion types, and unbounded timeouts', () => {
	assert.throws(() => normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario()] }), /duplicate/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), assert: [{ type: 'unknown' }] }, 0), /assert/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 0 }, 0), /timeout/i);
});
```

- [ ] **Step 2: Run the focused test to verify the expected missing-function failures**

Run: `node --test test/headless-matrix.test.mjs` from `coordinator`.

Expected: FAIL because the headless matrix module does not exist.

- [ ] **Step 3: Implement strict bounded normalization**

Implement provider allowlisting for `codex`, `gemini`, and `kimi`; require nonblank identifiers/text; require positive timeout `<= 900000`; require one or more assertions; normalize `assert` to `assertions`; reject unknown keys and duplicate IDs; deep-freeze the returned records. Support assertion forms `lifecycle`, `chat`, `action`, `program`, and `rcon` with exact required fields.

- [ ] **Step 4: Add the example matrix**

Create a secret-free matrix with one bounded Codex chat/completion scenario and one movement/chat scenario. Keep it opt-in and use unmistakable marker messages such as `HEADLESS_CODEX_PASS` and `HEADLESS_MOVE_PASS`.

- [ ] **Step 5: Run the focused tests**

Run: `node --test test/headless-matrix.test.mjs`.

Expected: PASS with normalization, selector, assertion-shape, and report-bound tests green.

### Task 2: Implement the bounded RCON client and command result handling

**Files:**
- Create: `coordinator/src/headless-rcon.mjs`
- Create: `coordinator/test/headless-rcon.test.mjs`

**Interfaces:**
- Produces `HeadlessRconClient({ host, port, password, connectTimeoutMs, commandTimeoutMs, socketFactory })`.
- Produces `await client.connect()`, `await client.command(text) -> { id, type, text }`, and `await client.close()`.
- Produces `normalizeRconResult(result) -> { text, success, boundedText }` without interpreting arbitrary command output.

- [ ] **Step 1: Write failing framing and lifecycle tests**

Test fragmented RCON response reads, little-endian packet framing, authentication failure, command timeout, bounded response text, and close idempotence using a local in-memory socket fixture. Assert that a command cannot be sent before authentication and that a response ID/type are preserved.

- [ ] **Step 2: Run the focused tests to verify red**

Run: `node --test test/headless-rcon.test.mjs`.

Expected: FAIL because the client module does not exist.

- [ ] **Step 3: Implement the minimal RCON transport**

Use `net.Socket`, exact-byte reads, little-endian `Int32` fields, one authenticated session, bounded UTF-8 response decoding, and a pending-command map keyed by request ID. Reject malformed lengths and close all pending commands with a typed `RCON_CLOSED` error.

- [ ] **Step 4: Run the focused tests and full framing tests**

Run: `node --test test/headless-rcon.test.mjs test/jsonl.test.mjs`.

Expected: PASS with no unbounded buffers or pending promises after close.

### Task 3: Add validated protocol-v2 audit capture for headless runs

**Files:**
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`

**Interfaces:**
- `MultiplexedServerBridge(config, { audit = null, ...dependencies })` accepts an optional `audit(direction, envelope) -> void|Promise<void>` callback.
- `createDynamicCoordinator(config, { protocolAudit, ...dependencies })` passes the callback to the bridge.
- The audit callback receives only already-validated, detached envelopes and never controls bridge flow.

- [ ] **Step 1: Write failing audit tests**

Add tests that start the existing fake socket bridge, record an inbound `hello_ack`/observation and outbound `agent_ready`/action command, and assert the audit rows have direction, message ID, type, agent ID, and detached payload. Add a test proving an audit callback that throws or rejects cannot interrupt bridge delivery.

- [ ] **Step 2: Run the focused tests to verify red**

Run: `node --test test/protocol-v2.test.mjs test/dynamic-main.test.mjs`.

Expected: FAIL because no audit callback is invoked.

- [ ] **Step 3: Implement optional audit injection**

Invoke the callback after `validateProtocolV2Envelope` in inbound acceptance and immediately before outbound encoded write. Use `structuredClone`/detached normalized envelopes, wrap callback failures in a swallowed diagnostic path, and leave `send`, revision fencing, queueing, and production defaults unchanged.

- [ ] **Step 4: Run focused and full coordinator tests**

Run: `node --test test/protocol-v2.test.mjs test/dynamic-main.test.mjs` and then `npm test`.

Expected: all existing tests plus the new audit tests pass.

### Task 4: Capture actual provider prompts and model output at the provider boundary

**Files:**
- Create: `coordinator/src/provider-turn-recorder.mjs`
- Create: `coordinator/test/provider-turn-recorder.test.mjs`
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/test/agent-planner.test.mjs`
- Modify: `coordinator/src/codex-service.mjs`
- Modify: `coordinator/src/acp-service.mjs`
- Modify: `coordinator/src/antigravity-service.mjs`
- Modify: `coordinator/src/provider-service.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`

**Interfaces:**
- `ProviderTurnRecorder({ runId, scenarioId, privatePath, publicSink, appendFile, now })` exposes `record({ provider, model, reasoningEffort, goalRevision, attempt, retry, input, output, error }) -> Promise<void>` and `close() -> Promise<void>`.
- `AgentPlanner({ ..., turnRecorder = null })` passes the optional recorder through each provider `decide(input, { goalRevision, signal, turnRecorder })` call; existing agents that ignore the extra option remain valid.
- Provider `decide(input, { goalRevision, signal, turnRecorder })` may call the recorder after raw output is assembled and before parsing completes; the callback is optional and observational.
- `createDynamicCoordinator(config, { providerTurnRecorder, ...dependencies })` wires the recorder to `AgentPlanner`/provider services without changing the public provider API used by existing callers.

- [ ] **Step 1: Write failing recorder tests**

Test that a record preserves bounded prompt/output text privately, writes only a hash/excerpt publicly, redacts `authorization`, `token`, `password`, and secret-shaped text, caps UTF-8 bytes, and remains non-blocking when the sink fails. Test error records contain typed provider errors without stack paths or environment values.

- [ ] **Step 2: Run the focused tests to verify red**

Run: `node --test test/provider-turn-recorder.test.mjs`.

Expected: FAIL because the recorder module and provider hook do not exist.

- [ ] **Step 3: Implement the recorder using existing trace redaction**

Reuse `redact`/bounded serialization behavior from `trace-writer.mjs`, write one JSONL record per attempt, include provider/model/effort/revision/attempt/retry/outcome, and make all writes serialized and close-aware.

- [ ] **Step 4: Thread the optional recorder through AgentPlanner**

Add a focused planner test proving the exact recorder reference reaches the selected provider session and that a recorder failure does not change the returned decision or retry count. Keep the default `null` path allocation-free.

- [ ] **Step 5: Add hooks without changing provider decisions**

In Codex, capture the exact planner input and collected turn text before `parseDecision`. In ACP providers, capture the exact prompt and joined agent-message chunks before `parseDecision`. In Antigravity, capture the exact process prompt and bounded stdout decision text at the same boundary. On every failure path, record the bounded error and output excerpt, then rethrow the original error.

- [ ] **Step 6: Verify focused provider tests and full coordinator suite**

Run: `node --test test/provider-turn-recorder.test.mjs test/codex-service.test.mjs test/provider-service.test.mjs test/acp-service.test.mjs test/antigravity-service.test.mjs` and then `npm test`.

Expected: all provider tests pass; default service construction has no recorder and produces no new files.

### Task 5: Build the scenario runner, evidence collector, and assertions

**Files:**
- Modify: `coordinator/src/headless-matrix.mjs`
- Create: `coordinator/test/headless-runner.test.mjs`
- Modify: `coordinator/src/trace-writer.mjs` only if a shared bounded helper is needed and covered by existing tests

**Interfaces:**
- `runHeadlessScenario({ scenario, runDirectory, rcon, now, readFile, writeFile, protocolAudit, providerTurnRecorder, poll }) -> Promise<HeadlessScenarioReport>`.
- `evaluateHeadlessAssertions(assertions, evidence) -> { passed: boolean, results: AssertionResult[] }`.
- `writeHeadlessReport(runDirectory, report) -> Promise<void>`.

- [ ] **Step 1: Write failing runner tests**

Use an injected RCON/client and evidence reader to test the complete orchestration contract without external processes: summon with exact provider/model/effort, start the goal, poll terminal status, wait for required marker/action/program evidence, evaluate every assertion, and classify timeout, `ERROR`, `DEAD`, skipped profile, assertion mismatch, and cleanup failure. Test that no action result is injected by the runner.

- [ ] **Step 2: Run the focused tests to verify red**

Run: `node --test test/headless-runner.test.mjs`.

Expected: FAIL because execution and assertion functions are not implemented.

- [ ] **Step 3: Implement command sequencing and evidence polling**

Send `codex summon-configured <provider> <model> <reasoningEffort> <serviceTier> survival <generatedName>` at a safe spawn position, wait for summon acceptance, send `codex start <generatedName> <task>`, poll `codex status <generatedName>`, and stop polling only at the configured terminal state or deadline. Read only bounded log/trace tails and use the audit JSONL as the protocol source of truth.

- [ ] **Step 4: Implement assertions and report serialization**

Implement exact chat matching, action type/argument matching, program event/status matching, lifecycle matching, and read-only RCON text matching. Include evidence file paths and bounded excerpts for failures. Ensure reports are plain serializable objects and contain no provider raw output beyond the documented public hash/excerpt.

- [ ] **Step 5: Run the focused runner tests**

Run: `node --test test/headless-runner.test.mjs test/headless-matrix.test.mjs test/headless-rcon.test.mjs`.

Expected: PASS with deterministic orchestration classifications and report output.

### Task 6: Add the real Fabric/coordinator PowerShell lifecycle wrapper

**Files:**
- Create: `scripts/run-headless-provider-matrix.ps1`
- Create: `scripts/test-run-headless-provider-matrix.ps1`
- Modify: `.gitignore` only if `runtime/headless-runs/` is not covered by the current runtime rule

**Interfaces:**
- `scripts/run-headless-provider-matrix.ps1 -ProjectRoot <path> [-MatrixPath <path>] [-ScenarioId <id>] [-ServerTemplate <path>] [-RequireAll] [-KeepArtifacts]` returns JSON `matrix-report.json` and exits nonzero on failed required scenarios or cleanup.
- The wrapper supplies `ARENA_AGENT_BRIDGE_SECRET`, `ARENA_HEADLESS_RUN_ID`, `ARENA_HEADLESS_SCENARIO_ID`, trace paths, and allocated ports to the Node coordinator process without copying secrets into provider workspaces.

- [ ] **Step 1: Write failing PowerShell lifecycle test**

Create a test script that invokes the wrapper with invalid Java, missing server template, and an occupied port fixture and asserts clear nonzero failures without starting provider processes. Add a cleanup fixture that starts a dummy child tree and asserts the wrapper terminates descendants.

- [ ] **Step 2: Run the script test to verify red**

Run: `powershell -NoProfile -File scripts/test-run-headless-provider-matrix.ps1`.

Expected: FAIL because the wrapper does not exist.

- [ ] **Step 3: Implement isolated process setup**

Resolve project paths, validate Java 25/Node 22+, built JAR, Fabric server launcher/template, and selected provider preflight. Create a unique run/scenario directory, copy the server template into it, generate a bridge/RCON secret with restrictive local file handling, allocate free ports, and configure `server.properties` with `online-mode=false`, RCON, a private world name, and `pause-when-empty-seconds=-1`.

- [ ] **Step 4: Implement startup, invocation, and teardown**

Start Fabric `nogui` with redirected log/stdin, wait for `Done (` and RCON, start `node src/headless-matrix.mjs` with the exact scenario and audit paths, forward its exit status, and always stop coordinator/server/provider descendants. The copied template must not include an existing `world` directory; the wrapper sets a unique `level-name` so Fabric generates a fresh world. Verify the allocated ports are closed before returning.

- [ ] **Step 5: Run lifecycle script tests**

Run: `powershell -NoProfile -File scripts/test-run-headless-provider-matrix.ps1`.

Expected: PASS for prerequisite failures, normal cleanup, timeout cleanup, and port verification without contacting a real provider.

### Task 7: Add the CLI entrypoint, matrix documentation, and operational safeguards

**Files:**
- Modify: `coordinator/package.json`
- Modify: `coordinator/src/headless-matrix.mjs`
- Create: `coordinator/test/headless-cli.test.mjs`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-18-real-provider-headless-matrix-design.md` only if implementation clarifies an interface

**Interfaces:**
- Add npm script `headless:matrix`: `node src/headless-matrix.mjs`.
- CLI accepts `--config <absolute-path>`, `--scenario <id>`, `--run-directory <absolute-path>`, `--rcon-host`, `--rcon-port`, `--rcon-password-file`, `--protocol-audit`, `--provider-turns`, and `--require-all`.
- CLI prints the report path and one-line scenario summary, then exits `0` only when the matrix status is acceptable.

- [ ] **Step 1: Write failing CLI argument tests**

Test missing/relative paths, unknown flags, absent password files, scenario selection, and `--require-all` classification. Assert that CLI help does not print secrets and that external-provider execution is never triggered by importing the module.

- [ ] **Step 2: Run the CLI tests to verify red**

Run: `node --test test/headless-matrix.test.mjs test/headless-runner.test.mjs`.

Expected: FAIL for missing CLI parser/entrypoint behavior.

- [ ] **Step 3: Implement the opt-in CLI and package script**

Keep module imports side-effect free. Load/normalize the matrix, select scenarios, read only the RCON password file, run the injected runner, write matrix reports, and map statuses to documented exit codes.

- [ ] **Step 4: Document prerequisites and examples**

Document provider login prerequisites, server-template preparation, exact commands for one Codex scenario and the full configured matrix, the cost/latency warning, report paths, `--require-all`, skipped-provider semantics, and the distinction between these real-provider checks and fast offline tests.

- [ ] **Step 5: Run focused CLI/documentation checks**

Run: `node --test test/headless-matrix.test.mjs test/headless-runner.test.mjs`; `git diff --check`.

Expected: PASS with no secret-bearing example values.

### Task 8: Run full verification and one real provider smoke

**Files:**
- Create: `docs/live-qa/2026-08-18-real-provider-headless-matrix.md`
- Modify: none unless verification exposes a defect

- [ ] **Step 1: Run the complete coordinator suite**

Run: `npm test` from `coordinator`.

Expected: all existing and new tests pass with zero failures.

- [ ] **Step 2: Run the clean Java build and core verification**

Run with JDK 25: `.\gradlew.bat clean check build verifyCore --no-daemon --console=plain`.

Expected: `BUILD SUCCESSFUL`, entrypoints verified, and all core assertions passing.

- [ ] **Step 3: Run one real Codex headless scenario**

Run: `powershell -NoProfile -File scripts/run-headless-provider-matrix.ps1 -ProjectRoot . -MatrixPath coordinator/config/headless-provider-matrix.json -ScenarioId codex-chat-completion -RequireAll`.

Expected: one `PASSED` scenario with a completed lifecycle, exact chat marker, `program_finished`, validated protocol rows, private provider prompt/output record, and zero remaining listeners.

- [ ] **Step 4: Run available Gemini/Kimi scenarios individually**

Run the same command with each provider scenario only when its executable/login/catalog is available. If unavailable, record an explicit `SKIPPED` report; do not claim a provider pass.

- [ ] **Step 5: Write the verification report and inspect artifacts**

Record exact provider/model/reasoning/service tier, report paths, provider output presence, action/result evidence, final state, world assertions, cleanup status, and any skipped entries. Run `git diff --check` and `git status --short` before reporting completion.

## Self-review checklist

- Matrix schema, RCON framing, protocol audit, provider capture, runner assertions, lifecycle wrapper, CLI, docs, and final live verification each have an independently testable task.
- No task depends on an undefined function: `normalizeHeadlessMatrix`, `HeadlessRconClient`, `ProviderTurnRecorder`, `runHeadlessScenario`, `evaluateHeadlessAssertions`, and the CLI flags are defined before use.
- Real-provider execution is never part of default automated suites and is explicitly opt-in.
- Every failure path includes process cleanup and a report classification.
- The existing fake-bridge and deterministic tests remain regression gates rather than being deleted or replaced.
