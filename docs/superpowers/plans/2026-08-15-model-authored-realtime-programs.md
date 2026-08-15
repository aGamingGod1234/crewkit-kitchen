# Model-Authored Real-Time Minecraft Programs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace linear model action lists with bounded model-authored ArenaScript programs that react at local runtime speed while the exact user-selected model remains the only source of gameplay decisions.

**Architecture:** The coordinator parses and interprets a restricted JavaScript-like language without `eval`, exposes immutable Minecraft facts and small physical primitives, and attaches model-program provenance to every command. Minecraft publishes immediate factual deltas, executes one verified physical primitive at a time, preserves dead agents, and performs vanilla respawn only when the selected model's program calls `player.respawn()`.

**Tech Stack:** Java 25, Fabric/Minecraft 26.1.2 server APIs, Node.js 22+ ESM, Acorn 8 parser, protocol v2 JSON over the loopback bridge, `node:test`, the dependency-free Java verification main, Gradle, and PowerShell verification scripts.

## Global Constraints

- The exact provider, model, reasoning effort, and service tier selected by the user author all gameplay intentions, conditions, fallbacks, interruption policies, and respawn decisions.
- Do not introduce a tactical model, fallback model, heuristic survival response, automatic target substitution, or silent legacy-plan fallback.
- ArenaScript source is at most 64 KiB and 4,096 AST nodes, with at most 16 watchers, 1,024 interpreter operations per resume, 128 loop iterations before yielding, and 256 physical primitives per program version.
- ArenaScript must not use Node `eval`, `Function`, `vm`, imports, filesystem, network, processes, Node globals, prototypes, constructors, dynamic code generation, reflection, recursion, or unbounded timers.
- One agent may have one active program version, one physical action, and at most one selected-model turn. Up to 16 agents remain independently concurrent.
- Every physical command must include agent/model identity, goal revision, program ID/version, source step, and event sequence. Reject commands without valid provenance.
- Runtime-only stops are limited to death, operator stop, disconnect, invalid protocol/program, impossible mechanics, timeout, cleanup, and exhausted interpreter bounds.
- `continue_and_notify` and `pause_and_notify` are selected in model-authored source. The runtime never chooses between them.
- Death preserves the logical agent, goal, selected-model session, and history. Respawn uses vanilla spawn, inventory, XP, drops, and gamerules.
- Routine action failure, cancellation, and retry diagnostics belong in structured traces and the field console, not noisy red Minecraft chat.
- Preserve all unrelated dirty-worktree changes. Before every commit, stage only the paths named in that task and inspect `git diff --cached --name-only`.
- Do not use computer-use or GUI automation. Headless checks may run locally; live Minecraft acceptance is performed manually by Lucas with logs inspected afterward.
- Use focused red-green tests for each task, then run the full performance/reliability verifier and soak only at the final integration gate.

---

## File and responsibility map

### New coordinator units

- `coordinator/src/arena-script/limits.mjs`: immutable ArenaScript limit constants and limit normalization.
- `coordinator/src/arena-script/errors.mjs`: stable compiler, sandbox, and execution error types.
- `coordinator/src/arena-script/parser.mjs`: Acorn parsing, AST allowlist validation, static bounds, source-step locations, and compiled-program creation.
- `coordinator/src/arena-script/interpreter.mjs`: explicit frame-stack interpreter and resumable execution; never invokes native JavaScript from model source.
- `coordinator/src/arena-script/facts.mjs`: immutable factual views and deterministic candidate filtering/distance helpers.
- `coordinator/src/arena-script/minecraft-api.mjs`: allowed ArenaScript namespaces and conversion of model API calls into physical primitive requests.
- `coordinator/src/arena-script/program-engine.mjs`: per-agent program lifecycle, watcher evaluation, unhandled-attention policy, checkpoints, command provenance, and action-result resumption.
- `coordinator/src/program-runtime-manager.mjs`: integrates per-agent engines with the planner, bridge, lifecycle revisions, and coalesced same-model reactive turns.

### New Java units

- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProvenance.java`: validated model/program/source/event identity attached to a server action.
- `src/main/java/dev/agaminggod/arenaagents/server/perception/AttentionFactDelta.java`: factual changed-field paths and event sequence, without danger labels.
- `src/main/java/dev/agaminggod/arenaagents/agent/AgentDeathSnapshot.java`: persisted vanilla death and respawn facts.

Existing `dynamic-main.mjs` remains the coordinator entrypoint, but delegates program state to `ProgramRuntimeManager`. Existing `ServerActionExecutor` retains physical mechanics and postcondition checks, but ArenaScript exposes only primitives that do not choose ongoing strategy.

---

### Task 1: ArenaScript parser, limits, and static sandbox

**Files:**
- Modify: `coordinator/package.json`
- Create: `coordinator/package-lock.json`
- Create: `coordinator/src/arena-script/limits.mjs`
- Create: `coordinator/src/arena-script/errors.mjs`
- Create: `coordinator/src/arena-script/parser.mjs`
- Test: `coordinator/test/arena-script-parser.test.mjs`

**Interfaces:**
- Produces `parseArenaScript(source, { limits? }): CompiledArenaProgram`.
- `CompiledArenaProgram` is `{ source, ast, nodeCount, stepLocations, unhandledPolicy, watcherCount }` and is deeply frozen.
- Produces stable `ArenaScriptError` codes: `SOURCE_TOO_LARGE`, `SYNTAX_ERROR`, `AST_TOO_LARGE`, `UNSUPPORTED_SYNTAX`, `UNSAFE_MEMBER_ACCESS`, `UNBOUNDED_LOOP`, `RECURSION_FORBIDDEN`, `MISSING_UNHANDLED_POLICY`, and `TOO_MANY_WATCHERS`.

- [ ] **Step 1: Add failing parser and sandbox tests**

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

test('compiles a bounded program and records its model-owned policy', () => {
  const compiled = parseArenaScript(`
    program.onUnhandledAttention("continue_and_notify");
    await program.repeatUntil(
      () => inventory.countTag("#minecraft:logs") >= 8,
      { maxIterations: 16 },
      async () => { await player.wait(1); }
    );
  `);
  assert.equal(compiled.unhandledPolicy, 'continue_and_notify');
  assert.equal(compiled.watcherCount, 0);
  assert.ok(compiled.nodeCount > 0);
  assert.ok(compiled.stepLocations.size > 0);
});

for (const source of [
  'import fs from "node:fs";',
  'globalThis.process.exit(0);',
  'player["constructor"];',
  'while (true) {}',
  'function again() { again(); } again();',
]) {
  test(`rejects unsafe source: ${source}`, () => {
    assert.throws(() => parseArenaScript(source), /ArenaScript|policy|unsupported|unsafe|bounded|recursion/i);
  });
}
```

- [ ] **Step 2: Run the focused test and verify it fails before the module exists**

Run:

```powershell
node --test coordinator/test/arena-script-parser.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `arena-script/parser.mjs`.

- [ ] **Step 3: Install the parser dependency and define exact limits/errors**

Run:

```powershell
Push-Location coordinator
npm install --save-exact acorn@8
Pop-Location
```

Implement these exports:

```js
export const DEFAULT_ARENA_SCRIPT_LIMITS = Object.freeze({
  sourceBytes: 65_536,
  astNodes: 4_096,
  watchers: 16,
  operationsPerResume: 1_024,
  loopIterationsPerYield: 128,
  commandsPerProgram: 256,
});

export class ArenaScriptError extends Error {
  constructor(code, message, location = null, options = undefined) {
    super(message, options);
    this.name = 'ArenaScriptError';
    this.code = code;
    this.location = location;
  }
}
```

- [ ] **Step 4: Implement parsing and an allowlisted AST walk**

Use Acorn only to create an AST. Walk every node yourself, count nodes, record `{start, end, line, column}` as `step-${start}-${end}`, reject unsupported node kinds and computed/member escape paths, require exactly one top-level `program.onUnhandledAttention(...)`, allow only literal-bounded `for` loops and `program.repeatUntil(..., {maxIterations: N}, ...)`, and reject a local-function call graph containing a cycle.

```js
export function parseArenaScript(source, { limits = DEFAULT_ARENA_SCRIPT_LIMITS } = {}) {
  const sourceBytes = Buffer.byteLength(source, 'utf8');
  if (sourceBytes > limits.sourceBytes) throw arenaError('SOURCE_TOO_LARGE', source);
  const ast = parse(source, { ecmaVersion: 2024, sourceType: 'script', locations: true, allowAwaitOutsideFunction: true });
  const analysis = validateProgram(ast, limits);
  return deepFreeze({ source, ast, ...analysis });
}
```

- [ ] **Step 5: Run parser tests and the coordinator suite**

Run:

```powershell
node --test coordinator/test/arena-script-parser.test.mjs
Push-Location coordinator
npm test
Pop-Location
```

Expected: parser tests PASS and existing coordinator tests remain green.

- [ ] **Step 6: Commit the parser boundary**

```powershell
git add coordinator/package.json coordinator/package-lock.json coordinator/src/arena-script/limits.mjs coordinator/src/arena-script/errors.mjs coordinator/src/arena-script/parser.mjs coordinator/test/arena-script-parser.test.mjs
git diff --cached --name-only
git commit -m "feat: add bounded ArenaScript parser"
```

---

### Task 2: Resumable interpreter and execution sandbox

**Files:**
- Create: `coordinator/src/arena-script/interpreter.mjs`
- Test: `coordinator/test/arena-script-interpreter.test.mjs`
- Modify: `coordinator/src/arena-script/errors.mjs`

**Interfaces:**
- Consumes `CompiledArenaProgram` from Task 1.
- Produces `new ArenaScriptInterpreter(compiled, bindings, { limits? })`.
- Produces `start(facts)`, `resume(result, facts)`, and `runWatcher(watcherId, facts)` returning exactly one yield:
  - `{ kind: 'command', stepId, call, stateToken }`
  - `{ kind: 'checkpoint', stepId, reason }`
  - `{ kind: 'finish', stepId, summary }`
  - `{ kind: 'idle' }`
- Never calls native `eval`, `Function`, `vm`, or model-defined native JavaScript.

- [ ] **Step 1: Add failing resumability and bound tests**

```js
test('yields a command and resumes from its typed result', () => {
  const vm = interpreter(`
    program.onUnhandledAttention("continue_and_notify");
    const moved = await tryResult(player.moveTo({ x: 4, y: 64, z: 2 }));
    if (!moved.succeeded) program.checkpoint(moved.reason);
    program.finish("arrived");
  `);
  const first = vm.start(facts());
  assert.equal(first.kind, 'command');
  assert.deepEqual(first.call, { primitive: 'move_to', arguments: { x: 4, y: 64, z: 2 } });
  const second = vm.resume({ state: 'SUCCEEDED', reasonCode: 'ARRIVED' }, facts({ x: 4 }));
  assert.deepEqual(second, { kind: 'finish', stepId: second.stepId, summary: 'arrived' });
});

test('operation exhaustion checkpoints instead of ending the goal', () => {
  const vm = interpreter(boundedCounterSource(), { operationsPerResume: 4 });
  assert.throws(() => vm.start(facts()), (error) => error.code === 'OPERATION_LIMIT');
});
```

- [ ] **Step 2: Run the focused test and verify the missing interpreter failure**

```powershell
node --test coordinator/test/arena-script-interpreter.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `interpreter.mjs`.

- [ ] **Step 3: Implement an explicit frame stack and immutable lexical environments**

Use project-owned objects, never JavaScript closures from source:

```js
export class ArenaScriptInterpreter {
  #frames = [];
  #waiting = null;
  #operations = 0;

  start(facts) {
    if (this.#frames.length !== 0) throw executionError('ALREADY_STARTED');
    this.#frames.push(frameForProgram(this.#compiled.ast, createRootEnvironment(this.#bindings, facts)));
    return this.#run();
  }

  resume(result, facts) {
    if (this.#waiting === null) throw executionError('NOT_WAITING');
    this.#waiting.environment.setResult(normalizeActionResult(result), facts);
    this.#waiting = null;
    return this.#run();
  }
}
```

Implement evaluators only for node kinds admitted by Task 1. Represent objects as frozen null-prototype records; resolve only declared identifiers and allowlisted namespace members.

- [ ] **Step 4: Implement command yielding, checkpoints, finish, bounded loops, and `tryResult`**

Every awaited physical call stores a continuation frame and yields once. `checkpoint` and `finish` stop the current activation. Count every visited AST node; on a limit, throw `ArenaScriptError('OPERATION_LIMIT', ...)` so the engine can convert it into a model checkpoint.

- [ ] **Step 5: Add sandbox regression cases**

Add explicit assertions that `this`, `new`, prototype fields, unknown globals, computed properties, mutation of factual objects, recursion, and more than 128 loop iterations cannot execute. Require error codes, not only message text.

- [ ] **Step 6: Run focused and parser/interpreter tests**

```powershell
node --test coordinator/test/arena-script-parser.test.mjs coordinator/test/arena-script-interpreter.test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit the interpreter**

```powershell
git add coordinator/src/arena-script/errors.mjs coordinator/src/arena-script/interpreter.mjs coordinator/test/arena-script-interpreter.test.mjs
git diff --cached --name-only
git commit -m "feat: interpret ArenaScript without native eval"
```

---

### Task 3: Factual bindings, physical API, watchers, and objective loops

**Files:**
- Create: `coordinator/src/arena-script/facts.mjs`
- Create: `coordinator/src/arena-script/minecraft-api.mjs`
- Create: `coordinator/src/arena-script/program-engine.mjs`
- Test: `coordinator/test/arena-script-facts.test.mjs`
- Test: `coordinator/test/arena-script-program-engine.test.mjs`

**Interfaces:**
- Produces `createFactView(observation): Readonly<ArenaFacts>`.
- Produces deterministic helpers `nearest(candidates, origin)`, `inventory.count(itemId)`, `inventory.countTag(tag)`, and explicit filters over currently observed candidates.
- Produces `ArenaScriptEngine` with `install`, `ingestObservation`, `ingestActionResult`, `applyDirective`, `suspend`, and `dispose`.
- Engine callbacks are `dispatch(command)`, `cancel(actionId)`, and `requestModel(context)`.

- [ ] **Step 1: Add failing factual-query tests**

```js
test('nearest considers only the candidate set selected by model code', () => {
  const facts = createFactView(observation({
    items: [
      { stableId: 'log-far', itemId: 'minecraft:oak_log', count: 2, x: 8, y: 64, z: 0, reachable: true },
      { stableId: 'dirt-near', itemId: 'minecraft:dirt', count: 1, x: 1, y: 64, z: 0, reachable: true },
    ],
  }));
  const logs = facts.world.items({ itemId: 'minecraft:oak_log', reachable: true });
  assert.equal(facts.world.nearest(logs).stableId, 'log-far');
});

test('factual views are immutable', () => {
  const facts = createFactView(observation());
  assert.throws(() => { facts.player.health = 1; }, TypeError);
});
```

- [ ] **Step 2: Add failing engine tests for multiple trees and pickup range**

Create a program that loops until `inventory.countTag('#minecraft:logs') >= 8`. Feed it a first tree yielding five logs, an item eight blocks away, a successful `move_to`, and a second tree yielding three logs. Assert that the engine emits only model-authored `move_to`/`break_block` calls and finishes only after the measured inventory reaches eight.

```js
assert.deepEqual(dispatched.map((row) => row.action.type), [
  'break_block', 'move_to', 'break_block', 'move_to',
]);
assert.equal(engine.snapshot().status, 'FINISHED');
```

- [ ] **Step 3: Run focused tests and confirm missing modules fail**

```powershell
node --test coordinator/test/arena-script-facts.test.mjs coordinator/test/arena-script-program-engine.test.mjs
```

Expected: FAIL with missing module imports.

- [ ] **Step 4: Implement immutable fact views and deterministic helpers**

Copy only validated observation fields into frozen null-prototype records. Item and entity queries must never synthesize candidates. `nearest` sorts by squared distance and stable ID for deterministic ties.

- [ ] **Step 5: Implement the small physical API**

Map ArenaScript calls to these existing wire primitives only:

```js
export const SCRIPT_PRIMITIVES = Object.freeze(new Set([
  'move_to', 'navigate_to', 'look_at', 'attack', 'select_item', 'use_item',
  'break_block', 'place_block', 'chat', 'wait', 'set_door', 'drop_item',
  'transfer_container', 'craft_inventory', 'craft_table', 'furnace_transaction',
  'equip_item', 'select_tool', 'block_with_shield', 'use_ranged',
]));
```

Do not expose `fight_target`, `flee_from`, `follow_entity`, `pick_up_item`, `build_sequence`, or `complete_goal`. ArenaScript performs their strategy through loops and smaller calls; ordinary vanilla proximity performs pickup after model-authored movement.

- [ ] **Step 6: Implement program lifecycle and watcher semantics**

```js
engine.install({ agentId, goalRevision, modelIdentity, programId, version, compiled, observation, eventSequence });
engine.ingestObservation({ observation, eventSequence, attention: true });
engine.ingestActionResult({ actionId, state, reasonCode, message, eventSequence });
```

Watchers fire only on false-to-true edges and rearm after false. `boundary` queues the handler behind the active primitive. `interrupt` invokes `cancel(activeActionId)` and runs the handler only after `CANCELLED` is acknowledged. Unmatched attention invokes the program-authored `continue_and_notify` or `pause_and_notify` policy and `requestModel` exactly once for the newest coalesced event.

- [ ] **Step 7: Add watcher and unhandled-policy regression tests**

Cover matching damage watcher, unchanged-health non-refiring, boundary ordering, interrupt acknowledgement, unmatched continue-and-notify, unmatched pause-and-notify, and a stale event sequence. Assert the runtime never invents a command.

- [ ] **Step 8: Run all ArenaScript tests and commit**

```powershell
node --test coordinator/test/arena-script-*.test.mjs
git add coordinator/src/arena-script/facts.mjs coordinator/src/arena-script/minecraft-api.mjs coordinator/src/arena-script/program-engine.mjs coordinator/test/arena-script-facts.test.mjs coordinator/test/arena-script-program-engine.test.mjs
git diff --cached --name-only
git commit -m "feat: add model-owned Minecraft program engine"
```

---

### Task 4: Compact provider envelope and ArenaScript prompt

**Files:**
- Modify: `coordinator/src/decision-parser.mjs:4-103`
- Modify: `coordinator/src/prompts.mjs:25-130`
- Modify: `coordinator/src/agent-planner.mjs`
- Test: `coordinator/test/decision-parser.test.mjs`
- Test: `coordinator/test/agent-planner.test.mjs`
- Test: `coordinator/test/acp-service.test.mjs`
- Test: `coordinator/test/antigravity-service.test.mjs`
- Test: `coordinator/test/codex-app-server.test.mjs`
- Test: `coordinator/test/codex-service.test.mjs`
- Test: `coordinator/test/provider-service.test.mjs`

**Interfaces:**
- `parseDecision` accepts only compact `replace`, `continue`, `pause`, and terminal `finish` envelopes.
- `replace` returns `{ summary, directive: 'replace', source }`.
- `continue`/`pause` return `{ summary, directive }` without `source` or `status`.
- `finish` returns `{ summary, directive: 'finish', status: 'completed'|'impossible' }`.
- Compiler corrections go back to the same provider session with source location and stable error code.

- [ ] **Step 1: Replace linear-output tests with failing discriminated-envelope tests**

```js
assert.deepEqual(parseDecision('{"summary":"Gather logs","directive":"replace","source":"program.onUnhandledAttention(\\"continue_and_notify\\");"}'), {
  summary: 'Gather logs', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify");',
});
assert.throws(() => parseDecision('{"summary":"x","directive":"continue","source":"bad"}'), /source/);
assert.throws(() => parseDecision('{"summary":"x","directive":"replace"}'), /source/);
assert.throws(() => parseDecision('{"summary":"x","directive":"finish","status":"unknown"}'), /status/);
```

- [ ] **Step 2: Run parser/provider tests and verify old action-schema expectations fail**

```powershell
node --test coordinator/test/decision-parser.test.mjs coordinator/test/agent-planner.test.mjs coordinator/test/acp-service.test.mjs coordinator/test/antigravity-service.test.mjs coordinator/test/codex-app-server.test.mjs coordinator/test/codex-service.test.mjs coordinator/test/provider-service.test.mjs
```

Expected: FAIL because the current parser requires `goalStatus`, `actions`, and the old directives.

- [ ] **Step 3: Implement the compact cross-field parser and provider schema**

Use one shared output schema with optional `source` and `status`, then enforce the discriminated combinations in `parseDecision`. Do not require unused `null` fields.

- [ ] **Step 4: Replace the system prompt with the exact authority/API contract**

The prompt must state that only the selected model writes strategy, output is ArenaScript source inside the envelope, every program declares one unhandled policy, candidate queries use observed facts only, physical API names and signatures are fixed, and compiler diagnostics must be corrected rather than bypassed. Include one multi-tree/pickup example and one watcher example, but no hidden heuristic instructions.

- [ ] **Step 5: Add same-session compiler-correction input**

```js
buildPlannerInput({
  decisionContext: 'arena_script_compiler_error',
  compilerError: { code, message, line, column },
  rejectedSourceHash,
  observation,
});
```

Never send source from world text, and never create a replacement locally.

- [ ] **Step 6: Run focused provider tests and commit**

```powershell
node --test coordinator/test/decision-parser.test.mjs coordinator/test/agent-planner.test.mjs coordinator/test/acp-service.test.mjs coordinator/test/antigravity-service.test.mjs coordinator/test/codex-app-server.test.mjs coordinator/test/codex-service.test.mjs coordinator/test/provider-service.test.mjs
git add coordinator/src/decision-parser.mjs coordinator/src/prompts.mjs coordinator/src/agent-planner.mjs coordinator/test/decision-parser.test.mjs coordinator/test/agent-planner.test.mjs coordinator/test/acp-service.test.mjs coordinator/test/antigravity-service.test.mjs coordinator/test/codex-app-server.test.mjs coordinator/test/codex-service.test.mjs coordinator/test/provider-service.test.mjs
git diff --cached --name-only
git commit -m "feat: let selected models author ArenaScript"
```

---

### Task 5: Coordinator program orchestration and stale-decision guards

**Files:**
- Create: `coordinator/src/program-runtime-manager.mjs`
- Modify: `coordinator/src/dynamic-main.mjs:44-649`
- Modify: `coordinator/src/agent-registry.mjs`
- Modify: `coordinator/test/fixtures/fake-minecraft-bridge.mjs`
- Modify: `coordinator/test/fixtures/two-agent-fixture.mjs`
- Test: `coordinator/test/program-runtime-manager.test.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `coordinator/test/eight-agent-soak.test.mjs`
- Create: `coordinator/test/sixteen-agent-program-soak.test.mjs`

**Interfaces:**
- Produces `ProgramRuntimeManager` with one `ArenaScriptEngine` per agent.
- Consumes planner envelopes from Task 4 and bridge events.
- Produces bridge `action_command`/`action_cancel`, same-model reactive turns, and lifecycle-safe engine disposal.
- Program IDs are `program-<goalRevision>-<version>` and versions increase monotonically per agent/goal.

- [ ] **Step 1: Add failing orchestration tests**

Test initial install/dispatch, immediate next primitive without a provider turn, compiler correction to the same model, matching watcher without a provider turn, unhandled continue-and-notify with a concurrent program, pause-and-notify cancellation, replace-after-cancel, stale program response rejection, goal steer disposal, death suspension, and 16 independent agents.

```js
assert.equal(planner.requests[0].agent.model, 'gpt-5.6-sol');
assert.equal(bridge.sent.filter((m) => m.type === 'action_command').length, 2);
assert.equal(planner.requests.length, 1, 'pre-authored branch must not call the provider');
assert.equal(bridge.sent[0].payload.provenance.programId, 'program-1-1');
```

The sixteen-agent soak creates distinct records and source per agent, emits one observation for each, and asserts independent program/action identity:

```js
const agentIds = Array.from({ length: 16 }, (_, index) => `agent-${index + 1}`);
await eventually(() => bridge.sent.filter((row) => row.type === 'action_command').length === 16);
assert.equal(new Set(bridge.sent.map((row) => `${row.agentId}:${row.payload.provenance.programId}`)).size, 16);
assert.equal(Math.max(...planner.activeTurnSamples), 16);
```

- [ ] **Step 2: Run focused tests and confirm the missing manager/old linear path fails**

```powershell
node --test coordinator/test/program-runtime-manager.test.mjs coordinator/test/dynamic-main.test.mjs
```

- [ ] **Step 3: Implement `ProgramRuntimeManager` and delegate from `dynamic-main.mjs`**

The manager owns program versions, engines, current action IDs, newest pending attention, and reactive turn tokens. `dynamic-main.mjs` remains responsible for bridge lifecycle, registry reconciliation, provider health, and status publication.

```js
runtime.installDecision(record, decision, { observation, eventSequence });
runtime.onObservation(record, payload);
runtime.onActionProgress(record, payload);
runtime.onActionResult(record, payload);
runtime.onGoalControl(record, operation);
```

Remove `#outstandingActions`, `#pendingReplacements`, and linear `remainingActions` only after their tests have moved to the manager.

- [ ] **Step 4: Implement same-selected-model reactive turns without blocking local execution**

`continue_and_notify` keeps the model-authored program running while one `requestPlan({preserveState:true})` is in flight. `pause_and_notify` cancels and waits. Coalesce later attention to one newest snapshot. Apply a response only when `{goalRevision, programId, programVersion, eventSequence}` still matches; otherwise discard it and schedule the newest facts.

- [ ] **Step 5: Implement compiler correction with no fallback**

Catch `ArenaScriptError` only around parsing/install. Request a correction from the same agent planner/session with exact diagnostics. Provider exhaustion leaves the agent in planning/waiting state and reports a system error; it never dispatches an old linear action or another model.

- [ ] **Step 6: Run focused orchestration and soak tests**

```powershell
node --test coordinator/test/program-runtime-manager.test.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/eight-agent-soak.test.mjs coordinator/test/sixteen-agent-program-soak.test.mjs
```

- [ ] **Step 7: Commit coordinator integration**

```powershell
git add coordinator/src/program-runtime-manager.mjs coordinator/src/dynamic-main.mjs coordinator/src/agent-registry.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/eight-agent-soak.test.mjs coordinator/test/sixteen-agent-program-soak.test.mjs coordinator/test/fixtures/fake-minecraft-bridge.mjs coordinator/test/fixtures/two-agent-fixture.mjs
git diff --cached --name-only
git commit -m "feat: orchestrate model-authored programs"
```

---

### Task 6: Command provenance and primitive-only server execution

**Files:**
- Modify: `coordinator/src/constants.mjs:1-82`
- Modify: `coordinator/src/schema.mjs:79-184`
- Modify: `coordinator/src/protocol-v2.mjs:690-725`
- Test: `coordinator/test/schema.test.mjs`
- Test: `coordinator/test/protocol-v2.test.mjs`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProvenance.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionRequest.java:8-30`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java:458-501`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java:108-401`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/BridgeEnvelopeCodecVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Produces wire provenance `{provider, model, reasoningEffort, serviceTier, programId, programVersion, sourceStepId, eventSequence}`.
- `ServerActionRequest` gains non-null `ActionProvenance provenance`.
- Server accepts ArenaScript commands only for the Task 3 physical primitive allowlist.

- [ ] **Step 1: Add failing Node provenance tests**

```js
const provenance = {
  provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
  programId: 'program-1-1', programVersion: 1, sourceStepId: 'step-80-126', eventSequence: 4,
};
assert.doesNotThrow(() => validateActionCommandPayload({ goalRevision: 1, actionId: 'action-1', summary: 'Move', action, provenance }));
assert.throws(() => validateActionCommandPayload({ goalRevision: 1, actionId: 'action-1', summary: 'Move', action }), /provenance/);
```

- [ ] **Step 2: Add failing Java provenance/allowlist tests**

Construct a valid primitive request with provenance, reject blank source step, reject negative event sequence, reject absent provenance, and reject a provenance-carrying `FIGHT_TARGET` request from the bridge normal-control path.

- [ ] **Step 3: Run focused Node and Java tests and confirm failures**

```powershell
node --test coordinator/test/schema.test.mjs coordinator/test/protocol-v2.test.mjs
& '.\runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe' -version
.\gradlew.bat verifyCore
```

- [ ] **Step 4: Implement shared provenance validation**

```java
public record ActionProvenance(
    String provider, String model, String reasoningEffort, String serviceTier,
    String programId, long programVersion, String sourceStepId, long eventSequence
) { /* bounded nonblank validation; defensive values only */ }
```

Decode the nested payload in `MultiplexedServerBridge`, attach it to `ServerActionRequest`, and reject a command before `actionExecutor.submit` when any provenance field is missing, stale, or invalid.

- [ ] **Step 5: Enforce primitive-only normal control**

Add a server-side `ARENA_SCRIPT_PRIMITIVES` set matching Task 3. Existing high-level controllers may remain compiled for scenario/compatibility tests, but no normal coordinator command with program provenance may invoke them. This is enforcement, not only a prompt rule.

- [ ] **Step 6: Run focused protocol/core verification and commit**

```powershell
node --test coordinator/test/schema.test.mjs coordinator/test/protocol-v2.test.mjs
.\gradlew.bat verifyCore
git add coordinator/src/constants.mjs coordinator/src/schema.mjs coordinator/src/protocol-v2.mjs coordinator/test/schema.test.mjs coordinator/test/protocol-v2.test.mjs src/main/java/dev/agaminggod/arenaagents/server/runtime/ActionProvenance.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionRequest.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java src/test/java/dev/agaminggod/arenaagents/server/bridge/BridgeEnvelopeCodecVerification.java src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java
git diff --cached --name-only
git commit -m "feat: require model program provenance"
```

---

### Task 7: Immediate factual deltas and segmented latency telemetry

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/perception/AttentionFactDelta.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java:50-180,390-430`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java:63-135,543-550`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java`
- Modify: `coordinator/src/protocol-v2.mjs:630-665`
- Modify: `coordinator/src/control-latency-registry.mjs`
- Modify: `coordinator/src/program-runtime-manager.mjs`
- Test: `coordinator/test/protocol-v2.test.mjs`
- Test: `coordinator/test/control-latency-registry.test.mjs`
- Test: `coordinator/test/program-runtime-manager.test.mjs`

**Interfaces:**
- Ready observations gain required `eventSequence`, `attention`, and `changedFacts` fields.
- `changedFacts` contains factual paths such as `player.health`, `player.onFire`, `player.fallDistance`, `inventory`, `entities.<stableId>`, `blocks.<position>`, and `currentAction`; it never contains `danger`, `flee`, `fight`, or recommendations.
- Latency operations are `minecraft_change_to_publication`, `event_receipt_to_branch`, `branch_to_bridge_send`, `command_to_first_progress`, and `action_completion`.

- [ ] **Step 1: Add failing factual-delta and event-sequence tests**

```java
AttentionFactDelta delta = AttentionFactDelta.between(previous, current, 7L, now);
check(delta.changedFacts().contains("player.health"));
check(!delta.changedFacts().stream().anyMatch(path -> path.contains("danger")));
check(delta.eventSequence() == 7L);
```

Verify a burst for 16 agents remains bounded, coalesces per agent, and all agents publish within two drains at the existing eight-observations-per-tick budget.

- [ ] **Step 2: Add failing Node schema and latency tests**

```js
registry.record('event_receipt_to_branch', 3);
registry.record('branch_to_bridge_send', 2);
assert.deepEqual(registry.snapshot().map((x) => x.operation), ['branch_to_bridge_send', 'event_receipt_to_branch']);
```

Assert watcher handling receives the exact event sequence and emitted command provenance repeats it.

- [ ] **Step 3: Run focused tests and confirm new fields are missing**

```powershell
node --test coordinator/test/protocol-v2.test.mjs coordinator/test/control-latency-registry.test.mjs coordinator/test/program-runtime-manager.test.mjs
.\gradlew.bat verifyCore
```

- [ ] **Step 4: Implement event sequencing and factual changed paths**

Maintain a monotonic per-agent sequence in the server bridge. Reuse the current 20 Hz active-action fingerprint path, publish immediately when it changes, and attach the current compact observation plus factual changed paths. Initial and heartbeat observations use `attention:false`; material changes use `attention:true`.

- [ ] **Step 5: Record separated local latency**

Use `observedAtEpochMs`, coordinator receipt time, branch-selection time, bridge-send time, and first-progress time. Never merge provider inference into local reaction metrics. Keep bounded percentile windows.

- [ ] **Step 6: Add a deterministic local benchmark**

In the manager test, run 1,000 watcher events using an injected monotonic clock and assert p95 event-receipt-to-branch and branch-to-send are each below 5 ms in the test harness. The live two-tick requirement remains a later acceptance gate.

- [ ] **Step 7: Run focused suites and commit**

```powershell
node --test coordinator/test/protocol-v2.test.mjs coordinator/test/control-latency-registry.test.mjs coordinator/test/program-runtime-manager.test.mjs
.\gradlew.bat verifyCore
git add src/main/java/dev/agaminggod/arenaagents/server/perception/AttentionFactDelta.java src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java coordinator/src/protocol-v2.mjs coordinator/src/control-latency-registry.mjs coordinator/src/program-runtime-manager.mjs coordinator/test/protocol-v2.test.mjs coordinator/test/control-latency-registry.test.mjs coordinator/test/program-runtime-manager.test.mjs
git diff --cached --name-only
git commit -m "feat: stream factual attention events"
```

---

### Task 8: Persistent death state and model-commanded vanilla respawn

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/AgentDeathSnapshot.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRecord.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentLifecycleReducer.java:193-218`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistrySnapshotCodec.java:45-130`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentManager.java:174-245,258-290`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/OfflineAgentPlayers.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java:108-146`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `coordinator/src/constants.mjs`
- Modify: `coordinator/src/schema.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/src/arena-script/minecraft-api.mjs`
- Modify: `coordinator/src/program-runtime-manager.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`
- Test: `coordinator/test/program-runtime-manager.test.mjs`
- Test: `coordinator/test/protocol-v2.test.mjs`

**Interfaces:**
- `AgentDeathSnapshot` persists `{cause, dimensionId, x, y, z, respawnDimensionId, respawnX, respawnY, respawnZ, diedAtEpochMs}` with optional respawn coordinates when no valid bed/anchor exists.
- `player.respawn()` produces primitive `respawn` with no coordinates.
- `CodexAgentManager.respawnVanilla(AgentId)` resolves the saved vanilla bed/anchor/world spawn and never accepts a model-provided position.

- [ ] **Step 1: Add failing persistence and no-auto-respawn tests**

```java
AgentRecord dead = AgentLifecycleReducer.die(active, deathSnapshot, now);
check(dead.state() == AgentLifecycleState.DEAD);
check(dead.currentGoal().equals(active.currentGoal()));
check(dead.deathSnapshot().orElseThrow().cause().equals("fell from a high place"));
AgentRecord roundTrip = codec.decode(codec.encode(snapshotOf(dead))).records().getFirst();
check(roundTrip.deathSnapshot().equals(dead.deathSnapshot()));
```

Assert `start()` and the player reconciler do not implicitly respawn a `DEAD` logical agent. Only `respawnVanilla` may transition it.

- [ ] **Step 2: Add failing coordinator dead-state tests**

Emit `goal_control dead` with death facts. Assert the selected model session is retained, the active program is suspended, the dead-state prompt reaches the same provider/model, ordinary movement is rejected, and a program containing `await player.respawn()` emits only `respawn` with valid provenance.

- [ ] **Step 3: Run focused tests and confirm current respawn path fails requirements**

```powershell
node --test coordinator/test/program-runtime-manager.test.mjs coordinator/test/protocol-v2.test.mjs
.\gradlew.bat verifyCore
```

- [ ] **Step 4: Persist death facts and retain logical state**

Capture cause and position before the dead fake player disappears. Add an optional persisted field with backward-compatible decode for older saves. Clear it only after successful respawn. Preserve current goal, queue, model profile, and coordinator provider session.

- [ ] **Step 5: Implement vanilla respawn resolution**

Add `OfflineAgentPlayers.resolveVanillaRespawn(...)` returning a validated dimension/position/angle derived from the player's vanilla respawn data, falling back to world spawn exactly as a player would. `CodexAgentManager.respawnVanilla` uses that target with the existing offline-player spawn path and the stored game mode. Do not restore inventory or XP.

- [ ] **Step 6: Add the provenance-guarded `respawn` primitive**

Handle it before `router.actionAccepted` requires a live player. Require lifecycle `DEAD`, current dead revision, and valid provenance. Emit success, then publish the normal `goal_control respawn` revision; make protocol ordering explicit in tests so stale results cannot affect the resumed program.

- [ ] **Step 7: Run death/respawn and persistence verification**

```powershell
node --test coordinator/test/program-runtime-manager.test.mjs coordinator/test/protocol-v2.test.mjs
.\gradlew.bat verifyCore
```

- [ ] **Step 8: Commit death and respawn**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/agent/AgentDeathSnapshot.java src/main/java/dev/agaminggod/arenaagents/agent/AgentRecord.java src/main/java/dev/agaminggod/arenaagents/agent/AgentLifecycleReducer.java src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistrySnapshotCodec.java src/main/java/dev/agaminggod/arenaagents/server/CodexAgentManager.java src/main/java/dev/agaminggod/arenaagents/server/OfflineAgentPlayers.java src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java coordinator/src/constants.mjs coordinator/src/schema.mjs coordinator/src/protocol-v2.mjs coordinator/src/arena-script/minecraft-api.mjs coordinator/src/program-runtime-manager.mjs src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java coordinator/test/program-runtime-manager.test.mjs coordinator/test/protocol-v2.test.mjs
git diff --cached --name-only
git commit -m "feat: let selected models command vanilla respawn"
```

---

### Task 9: Diagnostics, quiet chat, tracing, and legacy-path retirement

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentChatReporter.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentActivityPresentation.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/AgentActivityPresentationVerification.java`
- Modify: `coordinator/src/trace-writer.mjs`
- Test: `coordinator/test/trace-writer.test.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/decision-parser.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`
- Modify: `README.md`

**Interfaces:**
- Traces add `program_compiled`, `program_step`, `watcher_fired`, `attention_unhandled`, `program_checkpoint`, `program_replaced`, `program_finished`, and `program_sandbox_error`.
- Trace records redact credentials and bound source/diagnostic payloads.
- Minecraft chat suppresses routine `CANCELLED`, retry, and recoverable physical failure messages; the field console retains structured reason codes.

- [ ] **Step 1: Add failing presentation and trace tests**

```java
check(!AgentActivityPresentation.shouldShowInChat("ACTION_CANCELLED", true));
check(!AgentActivityPresentation.shouldShowInChat("PLACEMENT_NOT_CONFIRMED", true));
check(AgentActivityPresentation.shouldShowInChat("COORDINATOR_UNAVAILABLE", false));
```

```js
await writer.write('program_step', { programId: 'program-1-1', sourceStepId: 'step-1-9', token: 'secret-value' });
assert.match(line, /program-1-1/);
assert.doesNotMatch(line, /secret-value/);
```

- [ ] **Step 2: Run focused tests and confirm current noisy/legacy behaviour fails**

```powershell
node --test coordinator/test/trace-writer.test.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/decision-parser.test.mjs
.\gradlew.bat verifyCore
```

- [ ] **Step 3: Route recoverable results to structured diagnostics**

Keep concise public activity summaries optional, but do not broadcast routine physical failure/cancellation as red chat. System failures that break the model-control contract remain visible.

- [ ] **Step 4: Add bounded program tracing and authority audit fields**

Record provider/model/reasoning/service tier, goal revision, program/version, step, event sequence, timing segments, and typed result for every command. Store a source hash in ordinary traces; store bounded source only in the agent's private diagnostic trace.

- [ ] **Step 5: Remove the linear action-list planner path**

Delete remaining `remainingActions`, action-array prompt/schema, and implicit normalization. Assert old action-array provider output fails with `INVALID_DECISION` and is returned to the same model as a correction error. Keep no silent fallback flag.

- [ ] **Step 6: Document the ArenaScript control boundary**

Update README developer sections with the exact selected-model ownership rule, local interpreter security boundary, physical primitive list, death/respawn behaviour, and segmented latency fields. Do not claim live success yet.

- [ ] **Step 7: Run focused tests and commit**

```powershell
node --test coordinator/test/trace-writer.test.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/decision-parser.test.mjs
.\gradlew.bat verifyCore
git add src/main/java/dev/agaminggod/arenaagents/server/AgentChatReporter.java src/main/java/dev/agaminggod/arenaagents/server/AgentActivityPresentation.java src/test/java/dev/agaminggod/arenaagents/server/AgentActivityPresentationVerification.java coordinator/src/trace-writer.mjs coordinator/test/trace-writer.test.mjs coordinator/src/dynamic-main.mjs coordinator/src/decision-parser.mjs coordinator/src/prompts.mjs coordinator/test/dynamic-main.test.mjs coordinator/test/decision-parser.test.mjs README.md
git diff --cached --name-only
git commit -m "feat: expose quiet model-program diagnostics"
```

---

### Task 10: End-to-end scenarios, performance gates, deployment, and manual live acceptance

**Files:**
- Create: `coordinator/test/model-authored-programs-e2e.test.mjs`
- Modify: `coordinator/test/end-to-end.test.mjs`
- Modify: `coordinator/test/eight-agent-soak.test.mjs`
- Modify: `coordinator/test/sixteen-agent-program-soak.test.mjs`
- Modify: `coordinator/test/fixtures/fake-minecraft-bridge.mjs`
- Create: `scripts/verify-model-authored-programs.ps1`
- Modify: `scripts/run-performance-reliability-verification.ps1`
- Create: `docs/live-qa/2026-08-15-model-authored-realtime-programs.md`

**Interfaces:**
- Automated scenarios produce machine-readable pass/fail and latency summaries.
- Manual live QA records the exact jar/coordinator hashes, chosen model, model latency, local latency, program provenance, and physical outcomes without secrets.

- [ ] **Step 1: Add failing end-to-end authority scenarios**

The fake bridge must exercise:

1. eight logs across two trees;
2. drops outside pickup range;
3. unreachable/disappearing drops;
4. matching and unmatched damage;
5. pre-authored falling/lava interrupt without another provider turn;
6. disappearing placement support;
7. path failure and timeout;
8. death, retained session, and model-commanded respawn;
9. invalid source corrected by the same model;
10. rejection of a command without model-program provenance.

For every emitted command:

```js
assert.equal(command.payload.provenance.model, selected.model);
assert.equal(command.payload.provenance.programId, expectedProgramId);
assert.ok(command.payload.provenance.sourceStepId.startsWith('step-'));
assert.equal(Number.isSafeInteger(command.payload.provenance.eventSequence), true);
```

- [ ] **Step 2: Run the new E2E test and verify at least one scenario fails**

```powershell
node --test coordinator/test/model-authored-programs-e2e.test.mjs
```

Expected: FAIL until all integrated paths and fixtures implement the final protocol.

- [ ] **Step 3: Complete only integration gaps exposed by the E2E test**

Keep fixes within files already owned by Tasks 1-9. For each fresh failure, add the narrow assertion first, implement the smallest correction, and rerun this E2E file. Do not add heuristic rescue behaviour to make a scenario pass.

- [ ] **Step 4: Add a one-command automated gate**

`scripts/verify-model-authored-programs.ps1` must run the ArenaScript unit tests, program manager/protocol tests, E2E scenarios, and Java `verifyCore`, fail on any nonzero exit, and print segmented p50/p95 latency. Add it to the performance/reliability verifier before the soak loop.

- [ ] **Step 5: Run the complete automated verifier and 50-run soak**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File '.\scripts\run-performance-reliability-verification.ps1' -ProjectRoot 'C:\Users\aGamingGod\Desktop\Projects\agent arena'
```

Expected: zero failures, full Java/Fabric/coordinator verification green, and 50/50 soak runs. Record the fresh assertion/test counts and elapsed time; do not reuse older counts.

- [ ] **Step 6: Build and stage the exact distribution**

```powershell
.\gradlew.bat clean build
powershell -NoProfile -ExecutionPolicy Bypass -File '.\scripts\install-distribution.ps1' -ProjectRoot 'C:\Users\aGamingGod\Desktop\Projects\agent arena'
Get-FileHash -Algorithm SHA256 '.\build\libs\arena-agents-0.1.0.jar'
Get-FileHash -Algorithm SHA256 "$env:APPDATA\.minecraft\mods\arena-agents-0.1.0.jar"
```

Require matching hashes and exact coordinator distribution parity. Preserve the install script's rollback backup. Do not launch or control Minecraft.

- [ ] **Step 7: Prepare and perform manual live acceptance with Lucas**

Write the live QA document before testing, with checkboxes for gathering, building, combat, matching watcher, unmatched continue, unmatched pause, death, respawn, and coordinator restart. Lucas restarts Minecraft and operates the UI. After each case, inspect only logs/traces through the shell and record:

- exact selected provider/model/reasoning/service tier;
- program ID/version/source hash and source step;
- event and action IDs;
- provider inference time;
- fact-change-to-publication, event-to-branch, branch-to-send, and command-to-progress time;
- physical result and visible outcome reported by Lucas.

Require median fact-change-to-command within one server tick and p95 within two ticks on the local machine, excluding provider and physical action duration.

- [ ] **Step 8: Audit the authority boundary after live evidence**

Search all normal coordinator dispatches and server normal-control entries. Prove every physical command requires provenance and no model-independent combat/flee/collect/build/respawn decision is reachable. Add a regression for any violation, fix it narrowly, and repeat Steps 5-7.

```powershell
rg -n "action_command|actionExecutor\.submit|fight_target|flee_from|pick_up_item|build_sequence|respawn" coordinator/src src/main/java/dev/agaminggod/arenaagents/server
```

- [ ] **Step 9: Commit final verification assets and evidence**

```powershell
git add coordinator/test/model-authored-programs-e2e.test.mjs coordinator/test/end-to-end.test.mjs coordinator/test/eight-agent-soak.test.mjs coordinator/test/sixteen-agent-program-soak.test.mjs coordinator/test/fixtures/fake-minecraft-bridge.mjs scripts/verify-model-authored-programs.ps1 scripts/run-performance-reliability-verification.ps1 docs/live-qa/2026-08-15-model-authored-realtime-programs.md
git diff --cached --name-only
git commit -m "test: verify model-authored realtime control"
```

---

## Completion evidence

Before claiming completion, provide all of the following from the fresh implementation run:

- commits for every task with no unrelated staged files;
- ArenaScript parser/interpreter/sandbox test results;
- same-selected-model correction and reactive-turn proof;
- command-provenance rejection proof;
- eight-log, pickup-range, watcher, failure, death, and respawn E2E results;
- segmented local and provider p50/p95 latency;
- full automated verifier result and 50/50 soak result;
- built and installed jar SHA-256 equality plus coordinator distribution parity;
- manual live acceptance evidence explicitly distinguished from headless evidence.
