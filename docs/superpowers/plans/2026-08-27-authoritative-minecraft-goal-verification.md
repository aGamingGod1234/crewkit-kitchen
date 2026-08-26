# Authoritative Minecraft Goal Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Minecraft server own each agent's immutable completion rule, reject false completion claims, and keep unfinished agents working and recovering without player reminders.

**Architecture:** Add a persisted goal-specification module to the Java mod, compile exact requests locally, route uncertain requests through a constrained proposal plus player confirmation, and evaluate predicates from live server state. The coordinator receives the frozen specification, treats `finish` as a verification request, and keeps a deadline-bound work lease until Minecraft reports the goal satisfied or the player cancels it.

**Tech Stack:** Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric API 0.150.0, Java 25, Gson, Node.js 22 ESM, `node:test`, Gradle `verifyCore` and `check`.

**Spec:** `docs/superpowers/specs/2026-08-26-authoritative-minecraft-goal-verification-design.md`

## Global Constraints

- Execute from a clean worktree based on `origin/codex/fix-coordinator-auth-and-console-navigation` at or after `5d17e94`; do not implement against the stale, dirty root `main` checkout.
- Preserve the existing dirty `.worktrees/codex-fix-auth-navigation` deployment worktree. Do not stage, reset, move, or overwrite its unrelated changes.
- Minecraft is the sole completion authority. Model output and coordinator tool results are never proof.
- Deterministically recognized exact requests start immediately. Every model-proposed rule requires player confirmation.
- The original request and accepted verifier rule are immutable for one goal revision.
- An unfinished goal may not remain without a valid work lease for more than 2 seconds.
- Stuck detection occurs after 30 seconds without factual world progress and cannot pause or complete the goal.
- Death preserves the goal revision, verifier, evidence, and queue.
- All Java lifecycle mutations run on the Minecraft server thread and reject stale revisions.
- Keep normal chat concise. Verbose mode reports formatted verifier and recovery summaries, never raw protocol spam.
- Do not redesign agent selection, groups, voice transcription, or scenario completion.

---

## Planned file structure

### New Java goal module

- `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalPredicate.java`: closed predicate hierarchy.
- `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalSpec.java`: immutable original request, predicate, and hash.
- `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalStatus.java`: goal-level lifecycle independent of body/planner state.
- `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalEvidence.java`: immutable accepted verification evidence.
- `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalSpecCodec.java`: strict JSON persistence and wire codec.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalCompiler.java`: deterministic exact-request compiler.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalCompilation.java`: exact, clarification-required, or unsupported result.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/PendingGoalDraft.java`: persisted confirmation and replace/queue choice.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/PendingGoalDraftCodec.java`: draft persistence codec.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalVerificationRuntime.java`: server-thread evaluation, evidence, and proactive completion.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/AgentKillLedger.java`: agent-specific credited kills after goal creation.
- `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalSafetyController.java`: bounded immediate body protection.

### New coordinator modules

- `coordinator/src/goal-spec.mjs`: closed JavaScript mirror of the wire schema and hash validation.
- `coordinator/src/goal-spec-translator.mjs`: constrained proposal prompt, parser, and provider call.
- `coordinator/src/work-lease-supervisor.mjs`: deadline-bound extension of active-goal supervision.

### Existing seams to modify

- Java goal records, registry codecs, saved data, conversation routing, commands, bridge protocol, chat reporting, server runtime, completion verifier, action runtime, and verification main.
- Coordinator protocol, registry, native tools, native runtime, dynamic coordinator, prompts, bridge fixtures, and targeted tests.

---

### Task 1: Persist the immutable goal specification

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalPredicate.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalSpec.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalStatus.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalEvidence.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/goal/GoalSpecCodec.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentGoal.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistrySnapshotCodec.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/GoalSpecVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java`

**Interfaces:**
- Produces: `GoalSpec`, `GoalPredicate`, `GoalStatus`, `GoalEvidence`, and strict JSON round trips used by all later tasks.
- Consumes: existing `AgentGoal`, `AgentRecord`, and snapshot persistence.

- [ ] **Step 1: Write failing closed-schema and round-trip tests**

```java
GoalSpec spec = GoalSpec.create(
        "Get an iron pickaxe",
        new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
        1200L
);
String encoded = new GoalSpecCodec().encode(spec);
assertEquals(spec, new GoalSpecCodec().decode(encoded), "goal spec round-trip");
expectFailure(
        () -> new GoalSpecCodec().decode(encoded.replace("inventory_contains", "invented_rule")),
        "UNKNOWN_GOAL_PREDICATE"
);
```

- [ ] **Step 2: Run the focused verification and confirm it fails**

Run: `./gradlew.bat testClasses verifyCore`

Expected: compilation fails because the goal types and codec do not exist.

- [ ] **Step 3: Add the closed predicate hierarchy and immutable spec**

```java
public sealed interface GoalPredicate permits
        GoalPredicate.InventoryContains,
        GoalPredicate.PositionWithin,
        GoalPredicate.AdvancementGranted,
        GoalPredicate.EntityKilledByAgent,
        GoalPredicate.BlockMatches,
        GoalPredicate.SurviveDuration,
        GoalPredicate.OperatorConfirmed,
        GoalPredicate.AllOf,
        GoalPredicate.AnyOf {
    record InventoryContains(String itemId, int count) implements GoalPredicate {}
    record PositionWithin(double x, double y, double z, double radius, int stableTicks) implements GoalPredicate {}
    record AdvancementGranted(String advancementId) implements GoalPredicate {}
    record EntityKilledByAgent(String entityType, boolean afterGoalStart) implements GoalPredicate {}
    record BlockMatches(int x, int y, int z, String blockId, Map<String, String> properties) implements GoalPredicate {}
    record SurviveDuration(long ticks) implements GoalPredicate {}
    record OperatorConfirmed() implements GoalPredicate {}
    record AllOf(List<GoalPredicate> predicates) implements GoalPredicate {}
    record AnyOf(List<GoalPredicate> predicates) implements GoalPredicate {}
}
```

```java
public record GoalSpec(String originalRequest, GoalPredicate completion, long createdAtTick, String fingerprint) {
    public static GoalSpec create(String request, GoalPredicate completion, long createdAtTick) {
        GoalSpec unhashed = new GoalSpec(request, completion, createdAtTick, "pending");
        return new GoalSpec(request, completion, createdAtTick, GoalSpecCodec.fingerprint(unhashed));
    }
}
```

```java
public enum GoalStatus { AWAITING_CLARIFICATION, ACTIVE, RECOVERING, SATISFIED, CANCELLED }

public record GoalEvidence(long verifiedAtTick, String reasonCode, List<Fact> facts) {
    public record Fact(String type, boolean satisfied, String expectedValue, String observedValue) {}
}
```

`GoalSpecCodec.fingerprint` hashes canonical JSON containing only `originalRequest`, `completion`, and `createdAtTick`. It excludes the fingerprint field, mutable status, and evidence. Predicate decoding is limited to 16 leaf nodes and depth 4.

- [ ] **Step 4: Extend `AgentGoal` and snapshot persistence without changing runtime lifecycle semantics yet**

Add `GoalSpec spec`, `GoalStatus status`, and `Optional<GoalEvidence> evidence` to `AgentGoal`. Preserve the current prompt and steering list so existing coordinator input remains compatible during migration. Version the snapshot codec and decode an old active goal as `GoalStatus.AWAITING_CLARIFICATION` with `OperatorConfirmed`, never as an automatically satisfiable goal. Completed legacy goals remain completed historical records.

- [ ] **Step 5: Run focused and full core verification**

Run: `./gradlew.bat verifyCore`

Expected: all prior snapshots still decode; new goal specifications round-trip byte-for-byte and reject unknown fields.

- [ ] **Step 6: Commit the domain slice**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/agent/goal src/main/java/dev/agaminggod/arenaagents/agent/AgentGoal.java src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistrySnapshotCodec.java src/test/java/dev/agaminggod/arenaagents/agent
git commit -m "feat: persist authoritative agent goal specifications"
```

---

### Task 2: Compile exact requests and persist clarification drafts

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalCompiler.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalCompilation.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/PendingGoalDraft.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/PendingGoalDraftCodec.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentSavedData.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/conversation/ConversationWakePolicy.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/conversation/ServerAgentConversationRouter.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/goal/GoalCompilerVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/conversation/AgentConversationRouterVerification.java`

**Interfaces:**
- Consumes: Task 1 `GoalSpec` and `GoalPredicate`.
- Produces: `GoalCompilation compile(String request, RegistryAccess registries, long createdAtTick)` and durable `PendingGoalDraft` values.

- [ ] **Step 1: Write failing compiler tests for exact, ambiguous, and active-goal requests**

```java
GoalCompilation exact = compiler.compile("Hey, get an iron pickaxe", registries, 1200L);
assertEquals(
        new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
        exact.acceptedSpec().orElseThrow().completion(),
        "exact item request"
);
assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION,
        compiler.compile("Get a good pickaxe", registries, 1200L).kind(),
        "ambiguous adjective requires clarification");
```

- [ ] **Step 2: Run `verifyCore` and confirm the tests fail**

Run: `./gradlew.bat verifyCore`

Expected: missing `GoalCompiler` and draft codec.

- [ ] **Step 3: Implement deterministic recognizers for the approved exact forms**

Recognize exact registered item IDs and display-name phrases, integer coordinate triples, exact entity kill requests, and exact advancement IDs. Normalize polite prefixes such as `hey`, `please`, `can you`, and `go` without changing target nouns. Return `NEEDS_TRANSLATION` when more than one registered target matches or subjective language remains.

```java
public sealed interface GoalCompilation {
    enum Kind { ACCEPTED, NEEDS_TRANSLATION, REJECTED }
    Kind kind();
    Optional<GoalSpec> acceptedSpec();
    String playerMessage();
}
```

- [ ] **Step 4: Persist a separate pending goal draft**

```java
public record PendingGoalDraft(
        UUID draftId,
        AgentId agentId,
        UUID requestingPlayerId,
        String originalRequest,
        Optional<GoalPredicate> proposedPredicate,
        DraftIntent intent,
        long createdAtTick
) {}
```

`DraftIntent` is `START`, `REPLACE_OR_QUEUE`, or `CONFIRM_TRANSLATION`. A draft never changes the active goal revision.

- [ ] **Step 5: Replace generic conversation-wake goals**

Remove the two strings ending in “then finish” from `ConversationWakePolicy`. Route the delivered transcript through `GoalCompiler`. Exact requests start with the original transcript and frozen spec. Follow-ups such as `continue`, `watch out`, and `try another route` steer the existing goal. Unclear additional requests persist a draft and ask `Replace / Queue / Cancel` while current work continues.

- [ ] **Step 6: Run focused persistence and conversation tests**

Run: `./gradlew.bat verifyCore`

Expected: exact requests no longer create generic wrapper goals; draft save/reload preserves the active goal unchanged.

- [ ] **Step 7: Commit the exact compiler slice**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/goal src/main/java/dev/agaminggod/arenaagents/server/AgentSavedData.java src/main/java/dev/agaminggod/arenaagents/server/conversation src/test/java/dev/agaminggod/arenaagents/server/goal src/test/java/dev/agaminggod/arenaagents/server/conversation
git commit -m "feat: compile exact Minecraft goals and clarify ambiguity"
```

---

### Task 3: Add constrained goal proposals without giving the model authority

**Files:**
- Create: `coordinator/src/goal-spec.mjs`
- Create: `coordinator/src/goal-spec-translator.mjs`
- Create: `coordinator/test/goal-spec.test.mjs`
- Create: `coordinator/test/goal-spec-translator.test.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java`

**Interfaces:**
- Consumes: Task 2 `PendingGoalDraft` and the Task 1 wire schema.
- Produces: revision-independent `goal_spec_request` and `goal_spec_proposal` protocol messages. A proposal remains a draft until Minecraft validates it and the requesting player confirms it.

- [ ] **Step 1: Write failing JavaScript parser tests**

```js
assert.deepEqual(parseGoalSpecProposal({
  requestId: 'request-1',
  summary: 'Obtain one iron pickaxe',
  predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
}), {
  requestId: 'request-1',
  summary: 'Obtain one iron pickaxe',
  predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
});
assert.throws(() => parseGoalSpecProposal({
  requestId: 'request-1', summary: 'weak',
  predicate: { type: 'action_success_count', actionType: 'craft_inventory', count: 1 },
}), /UNKNOWN_GOAL_PREDICATE/);
```

- [ ] **Step 2: Run focused coordinator tests and confirm failure**

Run: `cd coordinator; npm test -- --test-name-pattern="goal spec"`

Expected: modules and protocol types are missing.

- [ ] **Step 3: Implement the closed proposal schema and translator prompt**

The translator receives only the original player request, registered candidate IDs supplied by Minecraft, and the allowlisted predicate schema. It returns one JSON proposal or an explicit ambiguity list. It never receives a completion tool and cannot activate the proposal.

```js
export const GOAL_SPEC_PROPOSAL_SCHEMA = Object.freeze({
  type: 'object', additionalProperties: false,
  required: ['requestId', 'summary', 'predicate'],
  properties: {
    requestId: { type: 'string', minLength: 1, maxLength: 128 },
    summary: { type: 'string', minLength: 1, maxLength: 256 },
    predicate: GOAL_PREDICATE_SCHEMA,
  },
});
```

- [ ] **Step 4: Add bounded protocol messages**

```json
{"type":"goal_spec_request","agentId":"...","payload":{"requestId":"...","originalRequest":"Get a good pickaxe","candidateIds":["minecraft:iron_pickaxe","minecraft:diamond_pickaxe"]}}
{"type":"goal_spec_proposal","agentId":"...","payload":{"requestId":"...","summary":"Obtain an iron pickaxe or better","predicate":{"type":"any_of","predicates":[...]}}}
```

Require exact keys, bounded request text, at most 64 candidate IDs, and one matching outstanding request. Stale or duplicate proposals cannot alter a goal.

- [ ] **Step 5: Route the proposal back to Minecraft for confirmation**

`dynamic-main.mjs` calls `AgentPlanner.requestGoalSpec`, sends the proposal, and forgets the request after acknowledgement or disconnect. Java validates all registry IDs and stores the result in `PendingGoalDraft`. It shows the player the original request and exact proposed rule before confirmation.

- [ ] **Step 6: Run coordinator and bridge verification**

Run: `cd coordinator; npm test -- --test-name-pattern="goal spec|protocol"`

Run: `./gradlew.bat verifyCore`

Expected: proposals round-trip, invalid rules fail closed, and no proposal starts a goal without player confirmation.

- [ ] **Step 7: Commit the translation seam**

```powershell
git add coordinator/src/goal-spec.mjs coordinator/src/goal-spec-translator.mjs coordinator/src/protocol-v2.mjs coordinator/src/agent-planner.mjs coordinator/src/dynamic-main.mjs coordinator/test/goal-spec.test.mjs coordinator/test/goal-spec-translator.test.mjs src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java
git commit -m "feat: propose constrained goal rules for player confirmation"
```

---

### Task 4: Verify goals continuously from live Minecraft state

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalVerificationRuntime.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/AgentKillLedger.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/GoalCompletionVerifier.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentRuntimeRouter.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistry.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/GoalCompletionContractVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/goal/GoalVerificationRuntimeVerification.java`

**Interfaces:**
- Consumes: persisted `GoalSpec`, agent player, server tick, advancement state, block state, and kill ledger.
- Produces: `VerificationResult evaluate(AgentRecord, ServerPlayer)` and one idempotent `SATISFIED` transition with `GoalEvidence`.

- [ ] **Step 1: Write failing factual-verifier tests**

```java
runtime.start(ironPickaxeGoal(agentId, revision));
inventory.setItem(0, new ItemStack(Items.STONE_PICKAXE));
assertEquals(GoalStatus.ACTIVE, runtime.tick(agentId).status(), "stone is not iron");
inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
assertEquals(GoalStatus.SATISFIED, runtime.tick(agentId).status(), "iron satisfies exact item");
assertEquals(1, transitions.count("goal_satisfied"), "completion is emitted once");
```

- [ ] **Step 2: Run `verifyCore` and confirm failure**

Run: `./gradlew.bat verifyCore`

Expected: missing runtime and unsupported new predicates.

- [ ] **Step 3: Replace model-authored contract evaluation with stored-spec evaluation**

```java
public VerificationResult evaluate(AgentRecord record, ServerPlayer player) {
    AgentGoal goal = record.currentGoal().orElseThrow();
    return evaluatePredicate(goal.spec().completion(), new VerificationContext(record, player, goal));
}
```

Return structured expected and observed values. Evaluate `all_of` and `any_of` recursively with depth and node-count limits. Track `position_within.stableTicks` by goal ID and reset stability when the agent leaves the radius.

- [ ] **Step 4: Add agent-specific kill and advancement evidence**

Register Fabric server living-entity death handling. Record a kill only when the damage source's responsible entity is the assigned agent player. Advancement checks read that agent's server advancement progress. Another player killing the dragon must not satisfy Sol's goal.

- [ ] **Step 5: Evaluate every active goal on the server tick and reconcile every 20 ticks**

At the current maximum of 16 agents, a bounded predicate walk each tick is simpler and safer than many event-specific hooks. Use direct tick evaluation for inventory, position, blocks, survival time, advancement, and operator confirmation. The kill ledger remains event-fed. Store first accepted evidence before transitioning the registry.

- [ ] **Step 6: Run verifier and persistence tests**

Run: `./gradlew.bat verifyCore`

Expected: exact inventory, stable coordinates, block properties, advancements, kill attribution, compound predicates, save/reload, and duplicate events pass.

- [ ] **Step 7: Commit the authoritative verifier**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/goal src/main/java/dev/agaminggod/arenaagents/server/runtime/GoalCompletionVerifier.java src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java src/main/java/dev/agaminggod/arenaagents/server/AgentRuntimeRouter.java src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistry.java src/test/java/dev/agaminggod/arenaagents/server
git commit -m "feat: verify agent goals from live Minecraft state"
```

---

### Task 5: Make `finish` a verification request and keep false claims active

**Files:**
- Modify: `coordinator/src/native-minecraft-tools.mjs`
- Modify: `coordinator/src/native-tool-runtime.mjs`
- Modify: `coordinator/src/goal-contract.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Test: `coordinator/test/native-minecraft-tools.test.mjs`
- Test: `coordinator/test/native-tool-runtime.test.mjs`
- Test: `coordinator/test/dynamic-main.test.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java`

**Interfaces:**
- Consumes: server-owned goal fingerprint and Task 4 verification result.
- Produces: `finish({status, summary})` that requests verification without accepting a model-authored contract.

- [ ] **Step 1: Write the false-finish regression before changing tools**

```js
const result = await harness.callFinish({ summary: 'I made a pickaxe' });
assert.deepEqual(result, {
  state: 'ACTIVE', verified: false, reasonCode: 'PREDICATE_FAILED',
  facts: [{ type: 'inventory_contains', expected: 'minecraft:iron_pickaxe x1', observed: 'minecraft:stone_pickaxe x1' }],
});
assert.equal(harness.registry.get('agent-a').state, 'PLANNING');
assert.equal(harness.supervisor.isActive('agent-a'), true);
```

- [ ] **Step 2: Run the focused coordinator test and confirm failure**

Run: `cd coordinator; npm test -- --test-name-pattern="false finish|native completion"`

Expected: the old finish accepts a model-supplied contract and marks the agent completed.

- [ ] **Step 3: Remove `completionContract` from the model tool**

```js
tool('finish', 'Ask Minecraft to verify the immutable active goal.', objectSchema({
  summary: { type: 'string', minLength: 1, maxLength: 512 },
}, ['summary']))
```

The coordinator may echo the server-provided goal fingerprint, but it never accepts predicates or a terminal status from the model. “Impossible” becomes a blocked/stuck report that keeps the goal active until the player clarifies, replaces, or cancels it.

- [ ] **Step 4: Simplify the completion protocol**

`goal_completed` carries `goalRevision`, `goalFingerprint`, `traceId`, and provider profile. Minecraft loads the stored spec, rejects stale fingerprints, evaluates it, and returns `goal_completion_result` with structured facts. Delete `action_success_count` as a root-goal verifier and remove JavaScript contract binding from native finish.

- [ ] **Step 5: Keep the turn and supervisor active on failed verification**

Only `verified: true` may transition the coordinator registry to completed. `verified: false` returns tool evidence to the same selected-model turn. If that turn ends, Task 6 schedules another turn.

- [ ] **Step 6: Run cross-language protocol tests**

Run: `cd coordinator; npm test -- --test-name-pattern="finish|completion|protocol"`

Run: `./gradlew.bat verifyCore`

Expected: false finish remains active, stale fingerprints fail, valid Minecraft evidence completes once, and Java/JavaScript wire fixtures match.

- [ ] **Step 7: Commit completion ownership**

```powershell
git add coordinator/src/native-minecraft-tools.mjs coordinator/src/native-tool-runtime.mjs coordinator/src/goal-contract.mjs coordinator/src/protocol-v2.mjs coordinator/src/dynamic-main.mjs coordinator/src/prompts.mjs coordinator/test src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java src/test/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridgeVerification.java
git commit -m "fix: let Minecraft decide when agent goals are complete"
```

---

### Task 6: Enforce work leases and preserve goals through death

**Files:**
- Create: `coordinator/src/work-lease-supervisor.mjs`
- Create: `coordinator/test/work-lease-supervisor.test.mjs`
- Modify: `coordinator/src/active-goal-supervisor.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/native-goal-error-policy.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`
- Modify: `coordinator/test/native-agent-resilience-headless.test.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentLifecycleReducer.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentRuntimeHooks.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java`

**Interfaces:**
- Consumes: active goal revision, provider/action/wait/recovery events, and a controllable clock.
- Produces: deadline-bound `acquire`, `progress`, `release`, `recover`, and `terminate` operations.

- [ ] **Step 1: Write the exact inactivity replay and fake-clock lease tests**

```js
const supervisor = new WorkLeaseSupervisor({ clock, schedule, requestObservation });
supervisor.activate(key);
clock.advance(2_001);
schedule.runDue();
assert.equal(requestObservation.calls.length, 1);
assert.deepEqual(requestObservation.calls[0], key);
assert.equal(supervisor.snapshot(key).state, 'recovering');
```

Also replay: one successful block break, provider turn completion, no pending work. The test fails if another observation and turn are not scheduled within 2 seconds.

- [ ] **Step 2: Run focused liveness tests and confirm failure**

Run: `cd coordinator; npm test -- --test-name-pattern="work lease|unfinished native agent|inactivity"`

Expected: the existing supervisor has no deadline for hung work and `native_turn_completed` can leave no pending turn.

- [ ] **Step 3: Implement deadline-bound leases**

```js
const LEASE_TIMEOUTS_MS = Object.freeze({
  provider: 45_000,
  action: 125_000,
  wait: 10_000,
  recovery: 15_000,
  scheduled: 2_000,
});
```

Each lease records `goalRevision`, lifecycle generation, kind, deadline, and last progress. Progress may renew within the kind's cap. Expiry interrupts the owning provider/action where possible, requests an observation, and schedules recovery without changing the goal revision.

- [ ] **Step 4: Schedule the next turn whenever the root goal remains unfinished**

After `native_turn_completed`, query coordinator state. If Minecraft has not reported `SATISFIED` or `CANCELLED`, acquire a scheduled lease and request an observation. A zero-tool turn uses the same path with a diagnostic reason.

- [ ] **Step 5: Preserve the goal through death and respawn**

Java transitions the body to `DEAD` while leaving `AgentGoal.status()` active. Respawn moves the body through recovery and sends an urgent `player_death` observation under the same revision. The coordinator suspends physical work during death, reacquires recovery on respawn, and resumes automatically. Conversation steers the same goal unless the player explicitly cancels or replaces it.

- [ ] **Step 6: Add 30-second factual-progress detection**

Track position displacement, inventory deltas, block changes, advancement changes, kill evidence, and successful action signatures. After 30 seconds without any of them, emit one coalesced `stuck` observation with recent position history and repeated failures. A stuck event may not pause or complete the goal.

- [ ] **Step 7: Run deterministic and headless resilience tests**

Run: `cd coordinator; npm test -- --test-name-pattern="work lease|resilience|death|stuck"`

Run: `./gradlew.bat verifyCore`

Expected: false finish, normal turn end, provider hang, action timeout, death, respawn, and reconnect all retain autonomous work.

- [ ] **Step 8: Commit liveness and recovery**

```powershell
git add coordinator/src/work-lease-supervisor.mjs coordinator/src/active-goal-supervisor.mjs coordinator/src/dynamic-main.mjs coordinator/src/native-goal-error-policy.mjs coordinator/test src/main/java/dev/agaminggod/arenaagents/agent/AgentLifecycleReducer.java src/main/java/dev/agaminggod/arenaagents/server/AgentRuntimeHooks.java src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java
git commit -m "fix: keep unfinished Minecraft goals alive and recoverable"
```

---

### Task 7: Add bounded safety reflexes and player-visible clarification

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/goal/GoalSafetyController.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/goal/GoalSafetyControllerVerification.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentCommands.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentChatReporter.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentVerboseChat.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/input/AgentInputStates.java`

**Interfaces:**
- Consumes: live body hazard facts, pending goal drafts, and requesting player identity.
- Produces: bounded `SafetyDirective` values and `/agent goal confirm|replace|queue|cancel <draftId>` commands.

- [ ] **Step 1: Write failing safety-authority tests**

```java
assertEquals(SafetyDirective.SWIM_UP, controller.decide(drowningSnapshot), "surface while drowning");
assertEquals(SafetyDirective.LEAVE_FIRE, controller.decide(burningSnapshot), "leave fire");
assertEquals(SafetyDirective.NONE, controller.decide(healthySnapshot), "no invented strategy");
assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("ATTACK")), "safety cannot represent attack");
assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("CONSUME_ITEM")), "safety cannot represent item spending");
```

- [ ] **Step 2: Run `verifyCore` and confirm failure**

Run: `./gradlew.bat verifyCore`

Expected: missing controller and goal commands.

- [ ] **Step 3: Implement the closed safety directive set**

```java
public enum SafetyDirective {
    NONE, SWIM_UP, LEAVE_LAVA, LEAVE_FIRE, RAISE_EQUIPPED_SHIELD, RETREAT_FROM_REPEATED_DAMAGE, RESPAWN_RECOVERY
}
```

Only execute retreat when the server navigation controller has a verified standable route away from the attacker. Safety cannot attack, consume items, change goals, select completion, or override a current physical action unless the hazard is immediately lethal.

- [ ] **Step 4: Add clarification commands with requester authorization**

Only the player who created the draft or an operator may confirm it. `replace` cancels the current goal only after the draft validates. `queue` leaves the active goal untouched. `cancel` removes only the draft. Duplicate and expired draft commands are idempotent.

- [ ] **Step 5: Report concise normal and formatted verbose output**

```text
Goal set: obtain minecraft:iron_pickaxe x1
Goal not complete: iron pickaxe 0/1. Continuing.
Recovering after death. Goal preserved.
Goal verified: obtained minecraft:iron_pickaxe x1.
```

Verbose output adds one message per verifier transition, lease recovery, or stuck diagnosis. It suppresses repeated unchanged checks.

- [ ] **Step 6: Run safety, command, and reporting verification**

Run: `./gradlew.bat verifyCore`

Expected: hazards receive only allowed reflexes; unauthorized confirmation fails; current work continues while a draft awaits a choice.

- [ ] **Step 7: Commit safety and clarification UX**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/goal/GoalSafetyController.java src/main/java/dev/agaminggod/arenaagents/server/CodexAgentCommands.java src/main/java/dev/agaminggod/arenaagents/server/AgentChatReporter.java src/main/java/dev/agaminggod/arenaagents/server/AgentVerboseChat.java src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java src/main/java/dev/agaminggod/arenaagents/server/runtime/input/AgentInputStates.java src/test/java/dev/agaminggod/arenaagents/server/goal
git commit -m "feat: protect active agents and clarify ambiguous goals"
```

---

### Task 8: Prove the complete behavior in headless Minecraft

**Files:**
- Create: `coordinator/test/authoritative-goal-lifecycle.test.mjs`
- Create: `docs/live-qa/2026-08-27-authoritative-goal-verification.md`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`
- Modify: `coordinator/test/fixtures/native-goal-harness.mjs`
- Modify: `coordinator/test/native-agent-resilience-headless.test.mjs`
- Modify: `scripts/verify-coordinator-packaging.ps1`
- Modify: `scripts/test-install-normal-profile-update.ps1`

**Interfaces:**
- Consumes: every prior task.
- Produces: deterministic acceptance evidence, packaged-runtime verification, and a concise live QA record.

- [ ] **Step 1: Register all new Java verifications in `VerificationMain`**

```java
assertions += GoalSpecVerification.run();
assertions += GoalCompilerVerification.run();
assertions += GoalVerificationRuntimeVerification.run();
assertions += GoalSafetyControllerVerification.run();
```

- [ ] **Step 2: Add the full false-completion headless scenario**

The fixture starts `Get an iron pickaxe`, supplies a stone pickaxe, has the selected model call `finish`, verifies the goal stays active, ends the provider turn, advances 2 seconds, verifies another turn begins, supplies an iron pickaxe, and verifies one Minecraft-owned completion event.

- [ ] **Step 3: Add dragon attribution, coordinate stability, death, and reload scenarios**

Use real server-owned facts where the headless bridge supports them. Keep pure schema cases in focused unit tests. Assert exact goal revision and fingerprint through every transition.

- [ ] **Step 4: Re-run the captured Sol inactivity replay**

Feed the recorded sequence: starter crafting, weak finish claim, provider turn end, damage, death, respawn. The replay passes only if the false finish is rejected, recovery schedules without player speech, and no unexplained idle gap exceeds 2 seconds.

- [ ] **Step 5: Run the focused test matrix**

Run: `cd coordinator; npm test -- --test-name-pattern="authoritative goal|work lease|completion|resilience"`

Run: `./gradlew.bat verifyCore`

Expected: all focused Java and coordinator tests pass.

- [ ] **Step 6: Run the full suite and package verification**

Run: `cd coordinator; npm test`

Run: `./gradlew.bat check build`

Run: `powershell -NoProfile -File scripts/verify-coordinator-packaging.ps1`

Run: `powershell -NoProfile -File scripts/test-install-normal-profile-update.ps1`

Expected: zero failures; the built jar contains the new coordinator modules and manifest hashes.

- [ ] **Step 7: Perform one live selected-model acceptance run after deterministic tests pass**

Use an isolated test world. Give Sol `Get an iron pickaxe`. Confirm the displayed verifier, let it work without follow-up speech, reject any early finish, observe death recovery if triggered, and confirm automatic completion only when the exact item enters inventory. Record timestamps and relevant log lines in the QA document.

- [ ] **Step 8: Commit acceptance evidence**

```powershell
git add coordinator/test src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java scripts/verify-coordinator-packaging.ps1 scripts/test-install-normal-profile-update.ps1 docs/live-qa/2026-08-27-authoritative-goal-verification.md
git commit -m "test: prove authoritative goals persist until verified"
```

---

## Integration and deployment gate

Do not copy the build into any device's `.minecraft/mods` directory during implementation. First:

1. Compare the feature branch against the dirty deployed worktree without overwriting it.
2. Reconcile overlapping `dynamic-main.mjs`, native runtime, voice, navigation, and installer changes with an explicit diff review.
3. Re-run the full suite on the reconciled tree.
4. Ask Lucas before updating devices, opening a PR, or changing the deployed Minecraft profile.

The implementation is complete only when the server owns completion, false claims remain active, liveness and death recovery pass deterministically, and the live iron-pickaxe test finishes without a player saying “continue.”
