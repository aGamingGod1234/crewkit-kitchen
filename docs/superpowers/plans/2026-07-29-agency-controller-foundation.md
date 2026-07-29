# Agency-Preserving Controller Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace fragile straight-line movement and single-hit combat with bounded navigation, sustained target control, and emergency survival reflexes while preserving each model's strategic decisions.

**Architecture:** The planner chooses an explicit destination, opponent, or retreat intent. Pure Java pathfinding/reflex components produce deterministic bounded plans, and server adapters translate those plans into Carpet fake-player controls. The existing authenticated bridge, goal-revision cancellation, action-result flow, and normal spawning remain authoritative.

**Tech Stack:** Java 25, Minecraft Java 26.1.2, Fabric, Fabric Carpet `EntityPlayerActionPack`, Gson, Node.js built-in test runner, Gradle `verifyCore`.

## Global Constraints

- Do not expose shell, filesystem, source-edit, or arbitrary executable tools to Minecraft agents.
- Preserve existing action wire names and decode behavior.
- New controller actions must emit bounded progress and machine-readable terminal results.
- The model selects destinations and targets; the controller cannot invent strategic objectives.
- User stop, goal replacement, death, and disconnect immediately release all fake-player controls.
- Creative and Spectator players are invalid hostile targets.
- Path searches and per-tick controller work use hard caps.
- No new runtime dependency is added.
- No visual or gameplay success is claimed from automated verification.

---

## File Structure

Create focused controller files beneath:

`src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/`

- `NavigationNode.java` — integer feet position.
- `NavigationStep.java` — node plus walk/jump/drop transition.
- `NavigationWorld.java` — pure world-query interface.
- `NavigationRequest.java` — bounded navigation inputs.
- `NavigationPlan.java` — success/failure result and explored-node count.
- `BoundedAStarPathfinder.java` — deterministic capped A*.
- `MinecraftNavigationWorld.java` — server-level adapter.
- `ServerNavigationController.java` — path following and local replanning.
- `CombatIntent.java` — target, timeout, desired range, and retreat flag.
- `ServerCombatController.java` — target tracking and vanilla cooldown attacks.
- `SurvivalThreat.java` — provider-independent threat facts.
- `SurvivalReflex.java` — bounded emergency response choice.

Modify:

- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`
- `coordinator/src/constants.mjs`
- `coordinator/src/schema.mjs`
- `coordinator/src/prompts.mjs`
- `coordinator/test/schema.test.mjs`
- `coordinator/test/decision-parser.test.mjs`
- `coordinator/test/agent-planner.test.mjs`

---

### Task 1: Add controller macro actions to both protocols

**Files:**

- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`
- Modify: `coordinator/src/constants.mjs`
- Modify: `coordinator/src/schema.mjs`
- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/test/schema.test.mjs`
- Modify: `coordinator/test/decision-parser.test.mjs`

**Interfaces:**

- Produces Java enum values:
  - `NAVIGATE_TO("navigate_to")`
  - `FIGHT_TARGET("fight_target")`
  - `FLEE_FROM("flee_from")`
  - `FOLLOW_ENTITY("follow_entity")`
- Produces exact wire fields:
  - `navigate_to`: `x`, `y`, `z`, `tolerance`, `sprint`, `timeoutMs`
  - `fight_target`: `targetSelector`, `desiredRange`, `timeoutMs`
  - `flee_from`: `targetSelector`, `distance`, `timeoutMs`
  - `follow_entity`: `targetSelector`, `distance`, `timeoutMs`

- [ ] **Step 1: Add failing Java protocol assertions**

Add to `VerificationMain.verifyActionTypes()`:

```java
assertDecodedType(
    codec,
    "navigate_to",
    "\"x\":10,\"y\":64,\"z\":-5,\"tolerance\":1.25,\"sprint\":true,\"timeoutMs\":30000",
    ActionType.NAVIGATE_TO
);
assertDecodedType(
    codec,
    "fight_target",
    "\"targetSelector\":\"nearest_hostile\",\"desiredRange\":2.5,\"timeoutMs\":15000",
    ActionType.FIGHT_TARGET
);
assertDecodedType(
    codec,
    "flee_from",
    "\"targetSelector\":\"last_attacker\",\"distance\":16,\"timeoutMs\":10000",
    ActionType.FLEE_FROM
);
assertDecodedType(
    codec,
    "follow_entity",
    "\"targetSelector\":\"player:Lucas\",\"distance\":3,\"timeoutMs\":30000",
    ActionType.FOLLOW_ENTITY
);
```

- [ ] **Step 2: Add failing Node schema assertions**

Add cases to `coordinator/test/schema.test.mjs`:

```js
assert.deepEqual(validateAction({
  type: 'navigate_to',
  x: 10, y: 64, z: -5,
  tolerance: 1.25,
  sprint: true,
  timeoutMs: 30_000,
}), {
  type: 'navigate_to',
  x: 10, y: 64, z: -5,
  tolerance: 1.25,
  sprint: true,
  timeoutMs: 30_000,
});

assert.throws(
  () => validateAction({
    type: 'fight_target',
    targetSelector: 'nearest_hostile',
    desiredRange: -1,
    timeoutMs: 1000,
  }),
  (error) => error.code === 'INVALID_ACTION',
);
```

- [ ] **Step 3: Run focused tests and confirm RED**

Run:

```powershell
.\gradlew.bat compileTestJava verifyCore --no-daemon --console=plain
Set-Location coordinator
node --test test/schema.test.mjs test/decision-parser.test.mjs
```

Expected: Java compilation fails because enum values are absent; Node rejects `navigate_to`.

- [ ] **Step 4: Implement Java enum, field maps, and validation**

Add enum values and field constants. Validate:

```java
case NAVIGATE_TO -> {
    validateCoordinates(arguments, false);
    requireFiniteRange(arguments, FIELD_TOLERANCE, 0.1D, 16.0D);
    requireBoolean(arguments, FIELD_SPRINT);
    requireDuration(arguments, FIELD_TIMEOUT_MS);
}
case FIGHT_TARGET -> {
    requireBoundedText(arguments, FIELD_TARGET_SELECTOR, ProtocolConstants.MAX_TARGET_SELECTOR_LENGTH, false);
    requireFiniteRange(arguments, FIELD_DESIRED_RANGE, 1.0D, 6.0D);
    requireDuration(arguments, FIELD_TIMEOUT_MS);
}
case FLEE_FROM, FOLLOW_ENTITY -> {
    requireBoundedText(arguments, FIELD_TARGET_SELECTOR, ProtocolConstants.MAX_TARGET_SELECTOR_LENGTH, false);
    requireFiniteRange(arguments, FIELD_DISTANCE, 1.0D, 64.0D);
    requireDuration(arguments, FIELD_TIMEOUT_MS);
}
```

- [ ] **Step 5: Implement Node field maps, numeric bounds, descriptions, and output schema**

Add:

```js
navigate_to: Object.freeze(['x', 'y', 'z', 'tolerance', 'sprint', 'timeoutMs']),
fight_target: Object.freeze(['targetSelector', 'desiredRange', 'timeoutMs']),
flee_from: Object.freeze(['targetSelector', 'distance', 'timeoutMs']),
follow_entity: Object.freeze(['targetSelector', 'distance', 'timeoutMs']),
```

Prompt descriptions must explicitly state that the model selects destination/target and that the controller only performs mechanics.

- [ ] **Step 6: Run focused tests and confirm GREEN**

Run the Task 1 commands. Expected: all focused Java and Node assertions pass.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/protocol src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java coordinator/src coordinator/test
git commit -m "feat: add agency-preserving controller actions"
```

---

### Task 2: Implement a bounded deterministic A* pathfinder

**Files:**

- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationNode.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationStep.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationWorld.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationRequest.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationPlan.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/BoundedAStarPathfinder.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/BoundedAStarPathfinderVerification.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**

```java
public record NavigationNode(int x, int y, int z) { }

public enum Transition { WALK, JUMP, DROP }

public record NavigationStep(NavigationNode node, Transition transition) { }

public interface NavigationWorld {
    boolean isPassable(NavigationNode node);
    boolean hasFloor(NavigationNode node);
    boolean isHazard(NavigationNode node);
}

public record NavigationRequest(
    NavigationNode start,
    NavigationNode destination,
    int tolerance,
    int maxExploredNodes,
    int maxPathLength,
    int maxDrop
) { }

public record NavigationPlan(
    boolean found,
    List<NavigationStep> steps,
    int exploredNodes,
    String reasonCode
) { }

public final class BoundedAStarPathfinder {
    public NavigationPlan find(NavigationWorld world, NavigationRequest request);
}
```

- [ ] **Step 1: Write pure failing verification cases**

Cover:

```java
findsStraightPath();
routesAroundWall();
usesOneBlockJump();
usesSafeDropWithinLimit();
rejectsHazardWhenSafeRouteExists();
returnsSearchLimitWithoutExceedingCap();
returnsNoPathForSealedDestination();
producesSamePathForSameWorld();
```

The fake world is an immutable set of blocked, solid, and hazard nodes.

- [ ] **Step 2: Register verification and confirm RED**

Call `BoundedAStarPathfinderVerification.run()` from `VerificationMain.main()`.

Run:

```powershell
.\gradlew.bat compileTestJava verifyCore --no-daemon --console=plain
```

Expected: compilation fails because controller types do not exist.

- [ ] **Step 3: Implement immutable value types and validation**

Constructors reject null inputs and nonpositive caps. `NavigationPlan.steps()` uses `List.copyOf`.

- [ ] **Step 4: Implement deterministic neighbor generation**

Order neighbors by:

1. Cardinal walk.
2. Diagonal walk when both adjacent cardinals are passable.
3. One-block cardinal jump.
4. Cardinal drops from one through `maxDrop`.

Hazards add a large finite cost; they are not selected when a safe path exists.

- [ ] **Step 5: Implement capped A***

Use:

```java
double heuristic(NavigationNode a, NavigationNode b) {
    return Math.abs(a.x() - b.x())
        + Math.abs(a.z() - b.z())
        + Math.abs(a.y() - b.y()) * 1.25D;
}
```

Tie-break by insertion order and node coordinates. Stop before exceeding `maxExploredNodes` or `maxPathLength`.

- [ ] **Step 6: Run focused verification and confirm GREEN**

Expected: all pathfinder cases pass with deterministic paths and caps.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/runtime/controller src/test/java/dev/agaminggod/arenaagents/server/runtime/controller src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java
git commit -m "feat: add bounded deterministic navigation"
```

---

### Task 3: Adapt Minecraft terrain and execute navigation plans

**Files:**

- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/MinecraftNavigationWorld.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerNavigationController.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/NavigationProgressVerification.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**

```java
public final class ServerNavigationController {
    public static final int DEFAULT_MAX_EXPLORED_NODES = 4096;
    public static final int DEFAULT_MAX_PATH_LENGTH = 256;

    public TickResult tick(ServerPlayer player, long now);
    public void cancel(ServerPlayer player);

    public record TickResult(
        State state,
        String reasonCode,
        String message,
        double progress
    ) { }

    public enum State { RUNNING, SUCCEEDED, FAILED }
}
```

- [ ] **Step 1: Write failing pure progress-state tests**

Test a small `WaypointProgress` helper for:

- Advancing after entering waypoint tolerance.
- Replanning after four seconds without material progress.
- Failing after three consecutive replans.
- Never reporting progress outside `[0, 1]`.

- [ ] **Step 2: Confirm RED**

Run `verifyCore`; expected missing navigation-progress types.

- [ ] **Step 3: Implement MinecraftNavigationWorld**

Feet/head blocks must be collision-free; floor must support standing. Penalize:

- Fire, lava, cactus, magma, campfire, powder snow, and void exposure.
- Deep water unless destination is in water.
- Drops greater than request maximum.

Do not load distant chunks during a bounded local search.

- [ ] **Step 4: Implement waypoint following**

For each tick:

```java
actions.lookAt(nextCenter);
actions.setSprinting(request.sprint() && safeToSprint(player, next));
actions.setForward(1.0F);
if (step.transition() == Transition.JUMP) {
    actions.start(ActionType.JUMP, Action.once());
}
```

Replan after collision/displacement or four seconds without progress. Release controls on every terminal path.

- [ ] **Step 5: Delegate NAVIGATE_TO and legacy MOVE_TO**

`NAVIGATE_TO` uses the controller. Legacy `MOVE_TO` remains accepted and delegates to the same controller using its existing timeout default.

- [ ] **Step 6: Emit bounded progress**

Progress is completed path distance divided by original path distance. Emit only on material change or a one-second heartbeat.

- [ ] **Step 7: Run focused verification and compile**

Run:

```powershell
.\gradlew.bat compileJava compileTestJava verifyCore --no-daemon --console=plain
```

Expected: compile and pure verification pass.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/runtime src/test/java/dev/agaminggod/arenaagents/server/runtime src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java
git commit -m "feat: execute bounded navigation plans"
```

---

### Task 4: Implement sustained combat and follow/flee controllers

**Files:**

- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/CombatIntent.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/CombatDecision.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/CombatPolicy.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerCombatController.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/CombatPolicyVerification.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**

```java
public record CombatIntent(
    String targetSelector,
    double desiredRange,
    long timeoutMs,
    Mode mode
) {
    public enum Mode { FIGHT, FLEE, FOLLOW }
}

public final class CombatPolicy {
    public CombatDecision decide(CombatSnapshot snapshot, CombatIntent intent);
}

public enum CombatDecision {
    APPROACH, RETREAT, FACE, ATTACK, HOLD, TARGET_DEFEATED, TARGET_INVALID
}
```

- [ ] **Step 1: Write failing policy tests**

Test:

- Fight approaches outside desired range.
- Fight attacks inside reach only when cooldown is ready.
- Fight never targets Creative or Spectator players.
- Flee retreats until requested distance.
- Follow approaches but never attacks.
- Dead or removed target terminates.
- Timeout and goal replacement cancel.

- [ ] **Step 2: Confirm RED**

Run `verifyCore`; expected missing combat types.

- [ ] **Step 3: Implement pure CombatPolicy**

No Minecraft types enter `CombatPolicy`. All decisions are derived from immutable snapshot facts.

- [ ] **Step 4: Implement ServerCombatController**

Resolve the model-selected target once, then validate it every tick. Use the navigation controller for approach/retreat and the action pack for facing and attack.

Never auto-switch to a different opponent.

- [ ] **Step 5: Integrate FIGHT_TARGET, FLEE_FROM, FOLLOW_ENTITY**

Return:

- `TARGET_DEFEATED`
- `RETREAT_DISTANCE_REACHED`
- `FOLLOW_DISTANCE_REACHED`
- `TARGET_INVALID`
- `TARGET_LOST`
- `ACTION_TIMED_OUT`

- [ ] **Step 6: Make legacy ATTACK a bounded one-swing compatibility action**

Do not change its existing wire contract. Prompt guidance should prefer `fight_target` for ongoing combat.

- [ ] **Step 7: Run focused verification and compile**

Expected: pure policies and all current core verification pass.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server/runtime src/test/java/dev/agaminggod/arenaagents/server/runtime src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java
git commit -m "feat: add target-preserving combat control"
```

---

### Task 5: Add bounded emergency survival reflexes

**Files:**

- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/SurvivalThreat.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/SurvivalReflex.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/SurvivalReflexVerification.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**

```java
public record SurvivalThreat(
    boolean onFire,
    int air,
    int maxAir,
    boolean suffocating,
    boolean dangerousFall,
    boolean tookDamage,
    double attackerX,
    double attackerZ
) { }

public enum SurvivalReflex {
    NONE, STOP, LEAVE_FIRE, SURFACE, ESCAPE_SUFFOCATION, BACK_AWAY
}
```

- [ ] **Step 1: Write failing priority tests**

Order:

```text
SURFACE > ESCAPE_SUFFOCATION > LEAVE_FIRE > STOP_DANGEROUS_FALL > BACK_AWAY > NONE
```

Test that no reflex chooses food, target, resource, or goal.

- [ ] **Step 2: Confirm RED**

Run `verifyCore`; expected missing reflex types.

- [ ] **Step 3: Implement pure reflex selection**

Reflex duration is capped at two seconds while a `THREAT_DETECTED` result triggers immediate replanning.

- [ ] **Step 4: Integrate with active actions**

On a threat:

1. Stop unsafe controls.
2. Emit `THREAT_DETECTED`.
3. Apply only the bounded motor reflex.
4. Release reflex controls after timeout or when the threat clears.

- [ ] **Step 5: Extend observations with hazard facts**

Add bounded booleans/numbers needed by the model: `onFire`, `air`, `maxAir`, `suffocating`, and `fallDistance`. Update protocol validators and fixtures if these are not already present.

- [ ] **Step 6: Run Java and Node protocol tests**

Expected: existing observations remain backward-compatible and new fields validate.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/server src/test/java/dev/agaminggod/arenaagents coordinator/src coordinator/test
git commit -m "feat: add bounded survival reflexes"
```

---

### Task 6: Update planner behavior and recovery evidence

**Files:**

- Modify: `coordinator/src/prompts.mjs`
- Modify: `coordinator/src/agent-planner.mjs`
- Modify: `coordinator/test/agent-planner.test.mjs`
- Modify: `coordinator/test/schema.test.mjs`

**Interfaces:**

- Planner output remains:

```json
{
  "summary": "Visible decision summary",
  "goalStatus": "in_progress",
  "action": {
    "type": "navigate_to"
  }
}
```

- Recovery input includes controller reason codes and consecutive counts.

- [ ] **Step 1: Write failing prompt and retry tests**

Assert:

- The prompt prefers `navigate_to` over `move_to` for nontrivial travel.
- The prompt states that the controller does not choose strategy.
- `PATH_SEARCH_LIMIT`, `PATH_BLOCKED`, and `TARGET_LOST` cause a materially different replan instruction.
- Transient provider retry does not duplicate an action command.

- [ ] **Step 2: Confirm RED**

Run:

```powershell
Set-Location coordinator
node --test test/agent-planner.test.mjs test/schema.test.mjs
```

- [ ] **Step 3: Implement prompt and recovery changes**

Keep the private-reasoning prohibition and exact JSON-only output.

- [ ] **Step 4: Confirm GREEN**

Run the focused Node tests, then `npm test`.

- [ ] **Step 5: Commit**

```powershell
git add coordinator/src coordinator/test
git commit -m "feat: teach planners controller macros"
```

---

### Task 7: Full nonvisual verification and artifact synchronization

**Files:**

- Modify only if required by verified behavior:
  - `README.md`
  - `PROJECT_LOG.md`
  - `scripts/run-automated-verification.ps1`

**Interfaces:**

- Produces one rebuilt `build/libs/arena-agents-0.1.0.jar`.
- All distribution/runtime copies must match its SHA-256.

- [ ] **Step 1: Run the full automated verification**

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

Expected: Gradle clean/check/build/verify tasks and all Node tests pass.

- [ ] **Step 2: Review the diff**

Confirm:

- No unrelated user changes were overwritten.
- No uncapped path search or uncontrolled target switching exists.
- Every terminal path releases controls.
- New actions are represented identically in Java and Node.

- [ ] **Step 3: Rebuild and synchronize the JAR**

Copy the exact artifact to:

- `dist/arena-agents-modpack-0.1.0/mods/`
- `runtime/server/mods/`
- `runtime/nonvisual-audit-server/mods/`
- `%APPDATA%\.minecraft\mods\`

Replace only the exact prior Arena Agents artifact.

- [ ] **Step 4: Update distribution checksums and ZIP**

Recompute `CHECKSUMS.sha256`, rebuild the ZIP, and verify every entry.

- [ ] **Step 5: Record nonvisual evidence**

Document assertion/test counts, JAR hash, ZIP hash, and the explicit fact that Minecraft was not visually tested in this stage.

- [ ] **Step 6: Commit**

```powershell
git add README.md PROJECT_LOG.md scripts dist
git commit -m "release: package controller foundation"
```
