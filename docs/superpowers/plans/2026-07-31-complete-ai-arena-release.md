# Complete AI Arena Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a rebuilt and installed Arena Agents mod whose offline players can perform validated vanilla inventory transactions, whose matches are reproducible and auditable, and whose spectator experience is ready for model-comparison videos.

**Architecture:** Keep strategic choices in each provider model and execute only explicit, strictly validated actions on Minecraft's server tick. Add transaction state machines around vanilla player/menu APIs, project scenario evidence into bounded public snapshots, and keep provider telemetry factual and private. Every state mutation is fail-closed, cancellable, postcondition-checked, and isolated per agent.

**Tech Stack:** Java 21, Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric API 0.150.0+26.1.2, Fabric Carpet fake `ServerPlayer`s, Gson, Node.js built-in test runner.

## Global Constraints

- Preserve the existing normal summon workflow, G control center, Survival/Creative/Adventure behavior, provider/model/thinking isolation, and all existing scenario presets.
- Preserve the current hard cap of 16 agents, default four concurrent provider turns, 256 bridge queue entries, and 32 messages per agent.
- The model selects recipes, slots, targets, equipment, destinations, and combat intent; runtime code may enforce safety and postconditions but must not silently choose strategy.
- All Minecraft state access and mutation happens on `END_SERVER_TICK`; network and provider callbacks only enqueue bounded immutable work.
- New actions reject unknown fields, invalid enum values, stale revisions, reused action IDs with changed payloads, unloaded dimensions/chunks, out-of-reach targets, protection denial, and postcondition conflicts.
- Every menu, item-use state, Carpet action, resource lease, and carried stack is cleaned up on success, failure, cancellation, timeout, damage interruption, death, disconnect, and shutdown.
- Public UI and artifacts contain allowlisted facts and concise formatted summaries only; never expose prompts, observations, provider transcripts, secrets, stack traces, or private chain-of-thought.
- HUD state targets at most 8 KiB at 5 Hz, retains at most 50 public feed entries, displays at most eight ranked agents, and holds no more than 32 six-second highlight markers.
- Match results are deterministic for equal authoritative inputs and use score descending then participant ID ascending as the tie-break.
- Scenario building/reset remains bounded by 2,048 inspections and 4 ms per tick and never force-loads chunks outside the managed arena.
- No new external dependency is permitted.
- The checkout contains authoritative pre-existing uncommitted work. Do not stage, commit, reset, clean, or overwrite unrelated changes; reports and focused file lists replace commit-based review packages.

---

### Task 1: Strict transaction protocol and pure state machines

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Modify: `coordinator/src/constants.mjs`
- Modify: `coordinator/src/schema.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/src/protocol-v2.mjs`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/transaction/TransactionPostcondition.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/transaction/ActionIdempotencyLedger.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/transaction/UseConfirmation.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/transaction/TransactionProtocolVerification.java`
- Test: `coordinator/test/schema.test.mjs`
- Test: `coordinator/test/protocol-v2.test.mjs`

**Interfaces:**
- Produces action names `transfer_container`, `craft_inventory`, `craft_table`, `furnace_transaction`, `equip_item`, `select_tool`, `block_with_shield`, and `use_ranged` in Java and Node.
- Produces public `ProtocolCodec.validateActionArguments(ActionType, JsonObject)` and calls it from the live multiplexed bridge before constructing `ServerActionRequest`.
- Produces bounded replay key `(agentId, goalRevision, actionId, canonicalArgumentHash)` and immutable postcondition verdicts.

- [ ] **Step 1: Write failing Java and Node protocol tests**

```java
verifyRejects("transfer_container", json("sourceKind", "player", "count", 0), "OUT_OF_RANGE");
verifyRejects("equip_item", json("targetSlot", "mainhand"), "INVALID_FIELD");
verifyLiveBridgeRejectsUnknownArgumentBeforeSubmit();
verifyReplayReturnsRecordedResultAndChangedHashFails();
```

```js
assert.deepEqual(validateAction(validTransfer), validTransfer);
assert.throws(() => validateAction({...validTransfer, count: 0}), /count/);
assert.throws(() => validateAction({...validTransfer, extra: true}), /Unknown/);
```

- [ ] **Step 2: Run focused tests and confirm RED because the actions and bridge validator do not exist**

Run: `./gradlew.bat compileTestJava verifyCore --no-daemon --console=plain`

Run: `cd coordinator; node --test test/schema.test.mjs test/protocol-v2.test.mjs`

- [ ] **Step 3: Implement strict schemas and pure state machines**

```java
public static JsonObject validateActionArguments(ActionType type, JsonObject arguments);

public record ReplayKey(AgentId agentId, long goalRevision, String actionId, String argumentHash) {}

public sealed interface Verdict {
    record Succeeded(String message) implements Verdict {}
    record Failed(String reasonCode, String message) implements Verdict {}
}
```

The exact action fields are:

- `transfer_container(x,y,z,sourceKind,sourceSlot,destinationKind,destinationSlot,count,expectedItemId,timeoutMs)` where kinds are `player|container`.
- `craft_inventory(recipeId,count,timeoutMs)`.
- `craft_table(recipeId,x,y,z,count,timeoutMs)`.
- `furnace_transaction(x,y,z,operation,inventorySlot,count,expectedItemId,timeoutMs)` where operation is `insert_input|insert_fuel|take_output`.
- `equip_item(sourceSlot,targetSlot,expectedItemId)` where target is `head|chest|legs|feet|offhand`.
- `select_tool(sourceSlot,hotbarSlot,expectedItemId,minRemainingDurability)`.
- `block_with_shield(durationMs)`.
- `use_ranged(targetSelector,drawDurationMs,timeoutMs)`.

- [ ] **Step 4: Run focused Java and Node tests and confirm GREEN**

### Task 2: Vanilla offline-player transaction adapters

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/transaction/ServerTransactionAdapter.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/transaction/TransactionSnapshot.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerRangedUseController.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/AdvancedInteractionService.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerProtectionPolicy.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ResourceLeaseManager.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/transaction/TransactionPostconditionVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/transaction/EquipmentAndUseVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`

**Interfaces:**
- Consumes every validated Task 1 action.
- Produces one cancellable active action and exactly one terminal result per accepted action.
- Uses vanilla menu slot rules for containers/crafting/equipment and `AbstractFurnaceBlockEntity` menu semantics for furnace variants.

- [ ] **Step 1: Write failing pure postcondition and cleanup tests**

```java
assertSucceeded(exactDebitCredit(before, after, "minecraft:oak_log", 4));
assertConflict(unrelatedSlotChanged(before, after));
assertRollbackRestoresSourceWhenDestinationRejects());
assertCleanupClosesMenuClearsCarriedStackReleasesLease());
assertShieldRequiresObservedUseStartAndRelease());
assertBowRequiresOwnedProjectileConfirmation());
```

- [ ] **Step 2: Run focused tests and verify the missing adapters fail RED**

- [ ] **Step 3: Implement server-tick adapters with reach, type, protection, lease, conservation, timeout, and cancellation checks**

```java
public interface ServerTransactionAdapter {
    ActiveTransaction begin(ServerPlayer player, ServerActionRequest request, JsonObject arguments);
}

public interface ActiveTransaction {
    TickResult tick(long nowEpochMs);
    void cancel(String reason);
    void cleanup();
}
```

Only mark an action succeeded after the exact requested inventory/menu delta is observed. Recipe actions must account for remainder items; furnace actions operate only on furnace, smoker, or blast-furnace menus; ranged use initially supports bows and requires ammo unless vanilla Infinity permits release.

- [ ] **Step 4: Add bounded nearby-container capability summaries without loading chunks**

- [ ] **Step 5: Run focused tests, compile Java, and confirm GREEN**

### Task 3: Reproducible scenario evidence, recovery, and results

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/result/ScenarioPublicEvent.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/result/ScenarioPublicFormatter.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/result/MatchResultV1.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/result/MatchResultWriter.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRunSnapshot.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioResetReceipt.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/ScenarioSession.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeClock.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioMatchResultVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioRecoveryVerification.java`

**Interfaces:**
- Produces `ScenarioRuntimeService.onAgentEvent(ScenarioAgentEvent)` and bounded public projections.
- Produces canonical match result JSON with SHA-256 and append-only `runtime/match-results/match-results.jsonl` plus `match-<id>.json`.
- Produces recovery state `PAUSED_RECOVERY`; missing dimensions fail recovery instead of falling back to overworld.

- [ ] **Step 1: Write failing deterministic result, privacy, pause, and reset-receipt tests**

```java
assertEquals(resultA.canonicalSha256(), resultB.canonicalSha256());
assertRanksByScoreThenParticipantId();
assertPublicProjectionExcludesPromptObservationAndRawSummary();
assertClockDoesNotAdvanceWhilePaused());
assertMissingDimensionFailsRecovery());
assertResetReceiptVerifiesBlueprintAndManagedVolumeHashes());
```

- [ ] **Step 2: Run focused tests and confirm RED**

- [ ] **Step 3: Implement typed event ingress, public formatter, immutable canonical result, async atomic writer, paused recovery, and exhaustive managed-volume reset receipt**

```java
public record MatchResultV1(String matchId, String scenarioId, String mapVersion,
        long worldSeed, long eventSeed, List<Standing> standings,
        List<ScenarioPublicEvent> events, ScenarioResetReceipt reset,
        String canonicalSha256) {}
```

- [ ] **Step 4: Run focused scenario tests and confirm GREEN**

### Task 4: Provider health, bounded memory, and 16-agent preflight

**Files:**
- Create: `coordinator/src/provider-turn-telemetry.mjs`
- Create: `coordinator/src/provider-health-registry.mjs`
- Create: `coordinator/src/fact-ledger.mjs`
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/src/agent-runtime.mjs`
- Modify: `coordinator/src/dynamic-main.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Test: `coordinator/test/provider-health-registry.test.mjs`
- Test: `coordinator/test/fact-ledger.test.mjs`
- Test: `coordinator/test/agent-planner.test.mjs`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioPreflightVerification.java`

**Interfaces:**
- Produces redacted telemetry fields only: provider, model, operation, attempt, queueWaitMs, durationMs, normalized error code, timeout, retry, restart.
- Produces rolling provider count, p50, p95, failure rate, and circuit state without prompts or outputs.
- Produces a per-agent factual ledger capped at 12 entries and 1,536 UTF-8 bytes, each with source, tick, dimension, expiry, and confidence.
- Produces a preflight verdict that checks bridge reconciliation, supported profiles, roster readiness, dimension/reset hash, scheduler headroom, and a 30-second first-wave deadline.

- [ ] **Step 1: Write failing telemetry, circuit, bounded-memory, and preflight tests**

```js
assert.deepEqual(registry.snapshot('gemini').p95Ms, 12000);
assert.equal(registry.canAttempt('gemini', now), false);
assert.ok(Buffer.byteLength(ledger.toPlannerFacts()) <= 1536);
assert.equal(JSON.stringify(telemetry).includes('prompt'), false);
```

- [ ] **Step 2: Run focused Node and Java tests and confirm RED**

- [ ] **Step 3: Implement telemetry spans, health circuit, factual memory, backpressure warnings at 75%, hard-cap rejection, and scenario activation preflight**

- [ ] **Step 4: Run focused tests and confirm GREEN**

### Task 5: Spectator HUD, feed, results, and camera recommendations

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/presentation/ArenaSpectatorSnapshot.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/presentation/ArenaSpectatorSnapshotPayload.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/presentation/DirectorRecommendation.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/presentation/ArenaSpectatorState.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/presentation/ArenaSpectatorHud.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/presentation/ScenarioResultsScreen.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/presentation/SpectatorCameraAssistant.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentControlSync.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/control/AgentControlClient.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ArenaSpectatorSnapshotVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/client/ArenaSpectatorStateVerification.java`

**Interfaces:**
- Publishes delta-aware snapshots at most every four server ticks with phase/time, eight standings, six feed rows, health/status badges, and one recommendation.
- Camera assistant is opt-in and spectator-only; movement, mouse look, screen opening, disconnect, or leaving spectator mode disables it immediately.

- [ ] **Step 1: Write failing codec, bounds, ordering, TTL, privacy, and manual-override tests**

```java
assertTrue(encoded.length() <= 8192);
assertEquals(8, snapshot.standings().size());
assertEquals(6, snapshot.feed().size());
assertTrue(state.onManualInput().cameraDisabled());
assertFalse(encoded.contains("prompt"));
```

- [ ] **Step 2: Run focused tests and confirm RED**

- [ ] **Step 3: Implement payload/store, compact HUD, formatted feed, terminal results screen, recommendation ranking, and optional smooth focus with immediate manual override**

- [ ] **Step 4: Run client compilation and focused tests and confirm GREEN**

### Task 6: Release documentation, full verification, packaging, and installation

**Files:**
- Modify: `README.md`
- Modify: `runtime/README.md`
- Modify: `docs/plans/task-4e-full-visual-release-validation-report.md`
- Modify: `dist/arena-agents-modpack-0.1.0/**`
- Modify: `%APPDATA%/.minecraft/mods/arena-agents-0.1.0.jar`

**Interfaces:**
- Produces one clean tested JAR copied byte-for-byte to normal, isolated, runtime, audit, and distribution destinations.
- Produces an LF-only checksum manifest and integrity-tested ZIP.

- [ ] **Step 1: Update capability and limitation documentation, including current artifact hashes**

- [ ] **Step 2: Run the complete clean verification gate**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-automated-verification.ps1`

- [ ] **Step 3: Run a real headless Minecraft smoke covering one transaction action, one scenario result, provider telemetry, and spectator payload generation**

- [ ] **Step 4: Run an 8-agent fake-provider concurrency soak and confirm caps, cancellation, and per-agent isolation**

- [ ] **Step 5: Rebuild runtime/distribution, synchronize all JAR copies, regenerate checksums and ZIP, and verify every hash**

- [ ] **Step 6: Replace only the exact existing Arena Agents artifact in `%APPDATA%/.minecraft/mods`; preserve every unrelated mod**

## Plan Self-Review

- All requested mechanics, benchmarking, presentation, resilience, perception/memory, documentation, build, distribution, and installation work map to Tasks 1–6.
- Every production behavior begins with an observable failing test and has a focused GREEN command.
- Shared names and payload limits are consistent across tasks; no arbitrary provider output is exposed publicly.
- No placeholder implementation steps remain; version, caps, paths, formats, and failure behavior are explicit.
