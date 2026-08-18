# Exact Agent Action Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make model-authored Minecraft action programs execute with deterministic placement, exact state validation, and far fewer model round-trips.

**Architecture:** Keep the existing model-decision and per-action acknowledgement protocol, but normalize structured actions by selected type, allow 32 ordered actions, and carry an optional desired block-state string through the protocol. Replace camera-raycast placement with the server player's explicit `useItemOn` interaction, then add a bounded `build_sequence` controller that mechanically navigates through up to 32 model-ordered exact placements.

**Tech Stack:** Node.js 22 test runner, Java 25, Fabric/Minecraft 26.1.2, Carpet fake players, Gson protocol codecs, Gradle verification mains.

## Global Constraints

- The model chooses every gameplay decision; runtime code only validates and mechanically executes the exact decision.
- Survival placement must enforce inventory, reach, support, protection, and normal item consumption.
- Known fields for other action types are discarded; truly unknown fields are rejected.
- Ordered programs accept 1-32 actions and reject 33.
- Intentional model cancellation remains observable to the model and logs but is not displayed in Minecraft chat.
- Do not use computer control for validation.
- Preserve unrelated dirty-worktree changes.

---

### Task 1: Normalize planner actions and expand ordered programs

**Files:**
- Modify: `coordinator/src/decision-parser.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`
- Modify: `coordinator/test/dynamic-main.test.mjs`

**Interfaces:**
- Consumes: `ACTION_FIELDS[type]` from `coordinator/src/constants.mjs`.
- Produces: `parseDecision(text)` accepting at most 32 normalized actions.

- [ ] **Step 1: Write failing normalization and boundary tests**

Add literal fixtures proving a `wait` action with non-null `summary` is normalized to `{type:'wait', durationMs:25}`, a truly unknown `rogue` field still raises `INVALID_ACTION`, 32 waits parse, and 33 waits fail.

- [ ] **Step 2: Run the tests and verify RED**

Run: `node --test coordinator/test/decision-parser.test.mjs`

Expected: the irrelevant-field and 32-action cases fail against the four-action/null-only implementation.

- [ ] **Step 3: Implement type-directed compaction**

Use the selected action type to retain only `type` plus `ACTION_FIELDS[type]`. Keep unknown fields outside the globally known structured field set so `validateAction` still rejects them. Set `MAX_PROGRAM_ACTIONS` and schema `maxItems` to `32`.

- [ ] **Step 4: Run focused coordinator tests and verify GREEN**

Run: `node --test coordinator/test/decision-parser.test.mjs coordinator/test/dynamic-main.test.mjs`

Expected: all selected tests pass with no test failures.

### Task 2: Carry desired block state through both protocols

**Files:**
- Modify: `coordinator/src/constants.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/src/schema.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Produces: `place_block(x,y,z,face,itemId,desiredState)` where `desiredState` is nullable or a canonical block-state string no longer than 512 characters.
- Produces: Java `ServerActionRequest.arguments().get("desiredState")` unchanged from coordinator input.

- [ ] **Step 1: Write failing Node and Java protocol tests**

Use the hand-authored value `minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`. Assert round-trip preservation, null acceptance, invalid overlength rejection, and mismatched block IDs rejection at execution validation rather than wire parsing.

- [ ] **Step 2: Run the tests and verify RED**

Run: `node --test coordinator/test/decision-parser.test.mjs coordinator/test/protocol-v2.test.mjs`

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-automated-verification.ps1 -ProjectRoot .`

Expected: both suites expose the absent `desiredState` field.

- [ ] **Step 3: Implement the minimal schema and codec additions**

Add `desiredState` to `ACTION_FIELDS.place_block`, the planner schema string/null branch, the prompt signature, and Java `PLACE_BLOCK` field list. Keep parsing syntax out of the protocol layer.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `node --test coordinator/test/decision-parser.test.mjs coordinator/test/protocol-v2.test.mjs`

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: both commands exit zero.

### Task 3: Execute and verify exact player placement

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/DesiredBlockState.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/BlockPlacementPostcondition.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/BlockPlacementAttemptPolicy.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/DesiredBlockStateVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/BlockPlacementPostconditionVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/BlockPlacementAttemptPolicyVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Produces: `DesiredBlockState.parse(String desiredState, String fallbackBlockId)` returning an immutable expected block ID and property map.
- Produces: placement postcondition comparison using actual stable properties when requested.
- Produces: `player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit)` for the exact support face.

- [ ] **Step 1: Write failing parser, postcondition, and attempt-exhaustion tests**

Test the literal stair state above; reject duplicate/unknown properties and a desired-state block ID different from `itemId`; reject wrong `facing`; accept correct properties; assert attempt number eight returns an exhausted decision instead of waiting until five seconds.

- [ ] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: compilation or assertions fail because the desired-state parser and exhausted decision do not exist.

- [ ] **Step 3: Implement desired-state parsing and exact `useItemOn` placement**

Build `BlockHitResult` from the support-face point already calculated by `placementLookTarget`. Invoke the player's game-mode interaction directly, swing the main hand when the result consumes the action, and retain the existing protection/reach/inventory/support checks. Include expected/actual/support/distance/item-count details in terminal failure messages.

- [ ] **Step 4: Implement immediate bounded-attempt termination**

After the eighth unsuccessful exact interaction, return `PLACEMENT_NOT_CONFIRMED` on the next postcondition evaluation instead of waiting out five seconds. A changed conflicting block still returns `PLACEMENT_CONFLICT` immediately.

- [ ] **Step 5: Run the verifier and verify GREEN**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: all Java assertions pass.

### Task 4: Execute model-ordered build sequences

**Files:**
- Modify: `coordinator/src/constants.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/src/schema.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`
- Modify: `coordinator/test/protocol-v2.test.mjs`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerBuildSequenceController.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/BuildSequenceProgressVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutorVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Produces: action `build_sequence(placements, timeoutMs)` with 1-32 entries shaped `{x,y,z,face,itemId,desiredState}`.
- Produces: `ServerBuildSequenceController` that preserves entry order, uses `ServerNavigationController` only to enter legal range, and stops at the first terminal placement failure.

- [ ] **Step 1: Write failing Node and Java sequence tests**

Assert 32 literal placement entries validate, 33 fail, execution preserves indices `0,1,2`, and a failure at index one prevents index two. Assert the reported message includes `completed=1`, `failedIndex=1`, and the placement reason.

- [ ] **Step 2: Run tests and verify RED**

Run: `node --test coordinator/test/decision-parser.test.mjs coordinator/test/protocol-v2.test.mjs`

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: both sides reject the unknown action/controller.

- [ ] **Step 3: Implement the bounded composite controller**

Parse immutable placement entries at action creation. For each entry, if the player is outside normal interaction range, instantiate `ServerNavigationController` toward the exact destination with a tolerance that enters the six-block reach bound. Once in range, call the same exact placement operation and postcondition used by `place_block`. Advance only after confirmation. Never reorder or substitute entries.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `node --test coordinator/test/decision-parser.test.mjs coordinator/test/protocol-v2.test.mjs coordinator/test/dynamic-main.test.mjs`

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: all selected tests pass.

### Task 5: Suppress intentional-cancellation chat and integrate

**Files:**
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/AgentActivityPresentation.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/server/AgentActivityPresentationVerification.java`

**Interfaces:**
- Produces: empty chat presentation for reason code `ACTION_CANCELLED` while retaining other failures.

- [ ] **Step 1: Write a failing presentation test**

Assert intentional cancellation yields no chat line while `PLACEMENT_NOT_CONFIRMED` remains visible.

- [ ] **Step 2: Run the verifier and verify RED**

Run: `./gradlew.bat verifyCore --no-daemon --console=plain`

Expected: cancellation is currently rendered as attention text.

- [ ] **Step 3: Add the narrow suppression and run full verification**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-performance-reliability-verification.ps1 -ProjectRoot . -SoakRuns 10`

Expected: full automated verifier and 10/10 soak runs pass.
