# Adaptive Arenas and Field Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver participant-scaled arenas, reliable parkour recovery, provider-safe agent creation, readable activity and identities, bounded crafting recovery, and one polished custom Field Console.

**Architecture:** Pure policy objects own reset transitions, geometry, checkpoint recovery, speed translation, activity copy, identity generation, and retry decisions. Runtime services consume those policies, keeping Minecraft side effects thin and testable. One shared console navigation/layout contract drives both agent control and arena setup screens.

**Tech Stack:** Java 25, Fabric API/Minecraft 26.1.2, Gradle/Loom, Node.js coordinator with ACP stdio adapters, dependency-free Java verification main, Node test runner.

## Global Constraints

- Do not use Computer Use or launch/control visible Minecraft.
- Preserve the existing incremental visible construction process.
- Support one through sixteen participants subject to each preset's existing minimum.
- Keep default placement deterministic and approximately 80 blocks in front of the operator.
- Use player-facing Normal/Fast speed names and translate provider wire values internally.
- Preserve explicit user-entered names and persisted agents.
- Apply every production behavior through a failing test first.
- Do not revert unrelated working-tree changes.

---

### Task 1: Terminal build-state transition

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/presentation/ScenarioBuildProgress.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioBuildProgressVerification.java`

**Interfaces:**
- Produces: `ScenarioBuildProgress.fromResetTick(...)`, which maps nonterminal ticks to Building, `COMPLETE` to Ready, and `FAILED` to Failed.

- [ ] Add literal complete/failed tick assertions and run `gradlew.bat verifyCore` to observe the missing API failure.
- [ ] Implement `fromResetTick(...)` and make `tickBuild` branch on the terminal phase before publishing an in-progress milestone.
- [ ] Run `gradlew.bat verifyCore` and confirm the regression assertions pass.

### Task 2: Participant-scaled geometry and aligned starts

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioArenaProfile.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/ScenarioSpawnLayout.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/ScenarioSpawnAllocator.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioArenaBlueprint.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`

**Interfaces:**
- Produces: `ScenarioSpawnLayout.slots(ScenarioCategory, int)` and `ScenarioArenaBlueprint.create(ScenarioPreset, BlockPos, int)`.
- Consumes: the same slot list for geometry and allocation.

- [ ] Add count-by-count centering, uniqueness, symmetry, and pad-alignment assertions for all four presets; observe failures against fixed sixteen-slot geometry.
- [ ] Implement bounded count-aware layouts and pass the configured participant count into blueprint creation.
- [ ] Author only the required building plots and parkour lanes, and scale radial starts for Survival/PvP.
- [ ] Run the scenario core verification and refactor duplicate geometry calculations without changing results.

### Task 3: Progressive parkour and recovery

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioParkourCourse.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioParkourRecovery.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioArenaBlueprint.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/scenario/runtime/ScenarioRuntimeService.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioCoreVerification.java`
- Test: `src/test/java/dev/agaminggod/arenaagents/scenario/ScenarioLaunchRuntimeVerification.java`

**Interfaces:**
- Produces: course stages/checkpoints and a pure `ScenarioParkourRecovery.evaluate(...)` decision.

- [ ] Add assertions for four distinct stage signatures, reachable transitions, checkpoint indices, forward-only checkpoint advancement, and below-plane recovery.
- [ ] Implement the progressive lane generator and checkpoint materials.
- [ ] Apply checkpoint advancement, Adventure mode, fall-distance reset, teleport, health, and food restoration in active parkour ticks.
- [ ] Replace the detached platform with a connected command gantry and assert a 3-by-3 beacon base.

### Task 4: Provider speed and ACP capability safety

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/control/AgentControlCatalog.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/control/AgentControlCommandBuilder.java`
- Modify: `coordinator/src/acp-service.mjs`
- Modify: provider adapters under `coordinator/src/`
- Test: `src/test/java/dev/agaminggod/arenaagents/control/AgentControlVerification.java`
- Test: `coordinator/test/acp-service.test.mjs`

**Interfaces:**
- Produces: stable `normal`/`fast` player values and adapter-owned effective ACP options.

- [ ] Add tests that reject exposed `priority`, map Codex Fast to its effective wire tier, and omit unsupported Kimi ACP options.
- [ ] Implement catalog/command normalization while retaining backward decode compatibility for persisted `priority` values.
- [ ] Negotiate or omit optional ACP session settings rather than aborting Kimi creation.
- [ ] Run Java and coordinator provider suites.

### Task 5: Meaningful activity, identities, skins, and bounded crafting recovery

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/agent/AgentIdentity.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistry.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentChatReporter.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/AdvancedInteractionService.java`
- Modify: relevant coordinator prompt/event formatter files
- Test: `src/test/java/dev/agaminggod/arenaagents/agent/AgentRegistryVerification.java`
- Test: transaction and chat reporter verification files

**Interfaces:**
- Produces: deterministic provider-aware default identity, skin variant, concise activity projection, and bounded retry classification.

- [ ] Add tests for readable stable unique names, identity/skin pairing, hidden lifecycle noise, meaningful action/blocker output, and repeated identical craft-failure suppression.
- [ ] Implement identity generation without overriding explicit names.
- [ ] Project chat to intent/action/recovery/blocker messages and keep detailed lifecycle in the console log only.
- [ ] Classify restored craft-layout failures as safe/recoverable and stop identical retries after the bounded threshold.
- [ ] Run focused identity, reporting, and transaction suites.

### Task 6: Shared custom console shell

**Files:**
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlLayout.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/gui/scenario/ScenarioSetupLayout.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/gui/scenario/ScenarioSetupScreen.java`
- Modify: custom widgets under `src/client/java/dev/agaminggod/arenaagents/client/gui/widget/`
- Test: GUI layout/theme/localization verification files

**Interfaces:**
- Produces: a four-destination navigation contract shared by Agent Control and Build screens, stepped custom surfaces, and shadow-free control labels.

- [ ] Add layout assertions for all four destinations on both screen families, header/nav spacing, compact sizes, focus order, and button shadow policy.
- [ ] Expose an initial destination constructor/factory and preserve the nav row when entering Build.
- [ ] Move navigation below the header safe area, increase working-region spacing, and render sentence-case custom controls without text shadows.
- [ ] Verify responsive 320x240 through 4K layout invariants headlessly.

### Task 7: Full verification and distribution

**Files:**
- Modify: `PRODUCT.md`, `DESIGN.md`, `DESIGN.json`, `README.md`, and localization only where behavior/copy changed.
- Output: `build/libs/arena-agents-<version>.jar`

- [ ] Run `gradlew.bat clean check build` and inspect the complete output.
- [ ] Run `node --test coordinator/test/*.test.mjs` and inspect all test counts.
- [ ] Run `powershell -ExecutionPolicy Bypass -File scripts/run-performance-reliability-verification.ps1` and inspect all soak/headless results.
- [ ] Compare the built artifact hashes and install the exact rebuilt JAR into the configured mods directories without starting, stopping, or controlling Minecraft.
- [ ] Re-read the approved specification and record any limit that remains unverified visually.
