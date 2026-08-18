# Self-Healing Scenario Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make arenas repair verification mismatches, launch PvP with verified loot and containment, and run scenarios without arbitrary automatic deadlines.

**Architecture:** Extend the bounded reset state machine with full mismatch capture and up to three exact correction passes. Separate deterministic PvP loot manifests from runtime orchestration, reshape the PvP blueprint, clear contestant loadouts, and change the runtime clock into elapsed telemetry with gameplay/operator termination.

**Tech Stack:** Java 25, Fabric/Minecraft 26.1.2 server APIs, Gradle verification mains.

## Global Constraints

- Verification must correct exact authored states, including grass/dirt, rather than whitelist mismatches.
- Correction is bounded to three passes and reports non-convergence precisely.
- PvP agents start in Survival with empty inventories.
- Every authored chest and barrel must contain deterministic useful loot before activation.
- Citadel Collapse has no grace period; combat is available from tick zero.
- Built-in scenarios do not finish because a configured duration elapsed.
- Do not use computer control for validation.
- Preserve unrelated dirty-worktree changes.

---

### Task 1: Add bounded corrective verification

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioArenaResetJob.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/presentation/ScenarioBuildProgress.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioBuildProgressVerification.java`

**Interfaces:**
- Produces: reset phase `REPAIR`, `correctionPasses()`, and exhaustive internal mismatch placements.
- Produces: terminal failure `RESET_VERIFICATION_DID_NOT_CONVERGE` after three failed correction passes.

- [x] **Step 1: Write failing state-machine tests**

Drive a pure reset-decision helper with one mismatch and assert `VERIFY -> REPAIR -> VERIFY -> COMPLETE`. Drive a persistent mismatch and assert failure after exactly three repairs. Assert progress text distinguishes repair from verification.

- [x] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: `REPAIR` and correction-pass behavior are missing.

- [x] **Step 3: Implement mismatch capture, rewrite, and reverify**

Store mismatched canonical placements during verification. In `REPAIR`, call `level.setBlock(position, expected.state(), 2)` for each mismatch under existing work/time budgets, clear verification counters/hash/samples, increment the correction pass, and reverify the complete canonical list. Release chunk tickets only on convergence or terminal non-convergence.

- [x] **Step 4: Run the verifier and verify GREEN**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: all Java assertions pass.

### Task 2: Rebuild PvP landmarks and containment

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioArenaBlueprint.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`

**Interfaces:**
- Produces: bedrock floor/lower wall, barrier upper wall, six roofed cobbled-deepslate houses, and six underground cobblestone dungeons.
- Preserves: center containers, four tower barrels, roster-dependent symmetric spawns, and operator deck.

- [x] **Step 1: Write failing geometry tests**

Assert bedrock exists below every boundary lane, barrier height exceeds every contestant structure, each house has a roof/door/container, and each dungeon has a below-surface room/stair entrance/container. Use literal expected counts and relative Y bounds.

- [x] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: current cache boxes/ruins and missing containment fail the assertions.

- [x] **Step 3: Implement the minimal blueprint helpers**

Replace cache and ruin helpers without changing unrelated scenario blueprints. Extend `SiteBounds` to contain the new barrier and dungeon extents.

- [x] **Step 4: Run the verifier and verify GREEN**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: all Java assertions pass.

### Task 3: Verify deterministic tiered loot and empty PvP starts

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioLootManifest.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioLoadoutPlan.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioLaunchRuntimeVerification.java`

**Interfaces:**
- Produces: `ScenarioLootManifest.forContainer(origin, position, worldSeed)` with immutable slot/item/count entries and tier `HOUSE`, `DUNGEON`, or `CENTER`.
- Produces: `ScenarioLoadoutPlan.forContestant(PVP, SURVIVAL).entries()` as an empty list.

- [x] **Step 1: Write failing manifest and loadout tests**

Assert identical seed/position returns identical entries, every manifest is nonempty, houses contain food and at least one basic equipment item, dungeons contain at least one iron/ranged item, center contains at least one high-tier item, and PvP starts empty.

- [x] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: the manifest class is absent and PvP still supplies a wooden sword and bread.

- [x] **Step 3: Implement manifests and container correction**

After block convergence, write the expected manifest to each authored chest/barrel, read every expected slot back, and rewrite any mismatch before `beginActivation`. Treat a missing block entity as arena preparation failure with its exact position.

- [x] **Step 4: Run the verifier and verify GREEN**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: all Java assertions pass.

### Task 4: Remove automatic deadlines and PvP grace

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/ScenarioPresets.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeClock.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioLaunchRuntimeVerification.java`

**Interfaces:**
- Produces: `ScenarioRuntimeClock.tick()` that advances elapsed time and phases but never calls `session.finish` due only to duration.
- Produces: Citadel's first phase as open conflict at tick zero.

- [x] **Step 1: Write failing unlimited-clock and no-grace tests**

Advance a running clock beyond its former duration and assert it remains running with increasing elapsed ticks. Assert Citadel's phase at tick zero is not a grace phase and `playerCombat` is true.

- [x] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: the session currently finishes with `Scenario duration elapsed` and Citadel begins with scouting grace.

- [x] **Step 3: Remove duration termination and grace copy**

Keep duration only as descriptive phase pacing/legacy serialization. Do not stop agents when the final phase expires. Replace Citadel's opening phase and dynamic-event copy so combat is available immediately.

- [x] **Step 4: Run full verification**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-performance-reliability-verification.ps1 -ProjectRoot . -SoakRuns 10`

Expected: full automated verifier and 10/10 soak runs pass.
