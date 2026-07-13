# Minecraft Codex Autonomous Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, install, and verify two proactive Minecraft 26.1.2 real-client agents driven by isolated Codex model configurations.

**Architecture:** A universal Fabric mod exposes bounded Minecraft observations and validated macro actions over a loopback JSONL socket. A dependency-free Node coordinator owns one persistent Codex app-server process and autonomous event loop per bot. A local Fabric server and two isolated launcher directories provide reproducible live testing without touching the original world.

**Tech Stack:** Java 25, Minecraft Java 26.1.2, Fabric Loader 0.19.3, Fabric API 0.150.0+26.1.2, Fabric Loom 1.16-SNAPSHOT, Gradle 9.5.1, Node.js 25 built-in modules, Codex CLI 0.144.0, Windows PowerShell, official Minecraft Launcher.

## Global Constraints

- Agent 55 is `gpt-5.5`, `xhigh`, Fast; Agent 56 is `gpt-5.6-sol`, `high`, Fast.
- The models receive identical prompts, observations, action schemas, timeouts, and runtime code.
- The original world at `%APPDATA%\.minecraft\saves\New World (76)` is read-only; live tests use a project-local copy.
- Minecraft bridges bind only to `127.0.0.1` and expose no arbitrary shell or file tools.
- Java code is typed, production-grade, small, and single-purpose; failures are explicit result objects.
- Node code has no third-party npm dependencies and uses `node:test`.
- Runtime data, copied worlds, credentials, traces, logs, and generated files are excluded from Git.
- A simultaneous online-mode fight is not considered verified until two distinct licensed Java accounts connect.

---

## File Map

### Fabric project

- `settings.gradle`: Fabric plugin repositories and project name.
- `build.gradle`: Loom, Java 25, Fabric dependencies, verification tasks, and packaging.
- `gradle.properties`: exact Minecraft/Fabric versions and project metadata.
- `src/main/resources/fabric.mod.json`: mod metadata, entrypoints, and dependency floors.
- `src/main/java/dev/agaminggod/arenaagents/ArenaAgents.java`: common/server entrypoint.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`: client entrypoint and lifecycle.
- `src/main/java/dev/agaminggod/arenaagents/protocol/*`: transport-neutral records, parsing, validation, and result codes.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/*`: loopback JSONL bridge.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/*`: bounded observation collection.
- `src/client/java/dev/agaminggod/arenaagents/client/action/*`: one-action state machine and executors.
- `src/client/java/dev/agaminggod/arenaagents/client/navigation/*`: local A* planner and movement controller.
- `src/client/java/dev/agaminggod/arenaagents/client/combat/*`: deterministic target tracking and melee controller.
- `src/main/java/dev/agaminggod/arenaagents/server/*`: goal commands and server-to-client payloads.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`: dependency-free Java assertions.

### Coordinator

- `coordinator/package.json`: ESM scripts with no dependencies.
- `coordinator/config/agents.json`: model and bridge configuration for both agents.
- `coordinator/src/protocol.mjs`: JSONL framing and socket client.
- `coordinator/src/schema.mjs`: strict observation/action validators.
- `coordinator/src/codex-app-server.mjs`: JSON-RPC process and thread client.
- `coordinator/src/prompts.mjs`: shared planner contract.
- `coordinator/src/agent-runtime.mjs`: proactive state machine.
- `coordinator/src/trace-writer.mjs`: append-only redacted JSONL evidence.
- `coordinator/src/main.mjs`: two-agent process lifecycle.
- `coordinator/test/*.test.mjs`: protocol, schema, Codex, and runtime tests.

### Operations

- `scripts/install-toolchain.ps1`: idempotent JDK 25 check/install guidance.
- `scripts/prepare-runtime.ps1`: isolated directories, JAR install, and verified world copy.
- `scripts/start-coordinator.ps1`: coordinator launch with project-local runtime paths.
- `scripts/start-test-server.ps1`: local Fabric server launch and health/log checks.
- `runtime/`: generated local server, evidence, traces, and profile state; ignored.

---

### Task 1: Reproducible Fabric and Node baseline

**Files:**
- Create: `.gitignore`
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `LICENSE`
- Create: `src/main/resources/fabric.mod.json`
- Create: `coordinator/package.json`

**Interfaces:**
- Consumes: official Fabric 26.1.2 example wrapper and dependency coordinates.
- Produces: `gradlew.bat build`, `gradlew.bat verifyCore`, and `npm test --prefix coordinator` entrypoints.

- [ ] **Step 1: Add baseline metadata and expected failing entrypoint checks**

```json
{
  "schemaVersion": 1,
  "id": "arenaagents",
  "version": "${version}",
  "name": "Arena Agents",
  "environment": "*",
  "entrypoints": {
    "main": ["dev.agaminggod.arenaagents.ArenaAgents"],
    "client": ["dev.agaminggod.arenaagents.client.ArenaAgentsClient"]
  },
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.1.2",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```

- [ ] **Step 2: Verify Java 21 fails the Java 25 build requirement**

Run: `java -version; javac -version; .\gradlew.bat compileJava`

Expected: system `javac 21.0.5` is reported and compilation refuses release 25 before JDK 25 is selected.

- [ ] **Step 3: Install or select Eclipse Temurin JDK 25**

Run: `winget install --id EclipseAdoptium.Temurin.25.JDK --exact --silent --accept-package-agreements --accept-source-agreements`

Then set project-local execution to the discovered JDK 25 using `JAVA_HOME` in verification commands; do not replace unrelated user-wide Java configuration.

- [ ] **Step 4: Add the official Gradle 9.5.1 wrapper and exact build configuration**

```properties
minecraft_version=26.1.2
loader_version=0.19.3
loom_version=1.16-SNAPSHOT
fabric_api_version=0.150.0+26.1.2
mod_version=0.1.0
maven_group=dev.agaminggod
archives_base_name=arena-agents
```

- [ ] **Step 5: Add minimal entrypoint classes and dependency-free coordinator package**

```json
{
  "name": "arena-agents-coordinator",
  "private": true,
  "type": "module",
  "scripts": {
    "start": "node src/main.mjs",
    "test": "node --test"
  }
}
```

- [ ] **Step 6: Verify the baseline**

Run: `.\gradlew.bat clean build` and `npm test --prefix coordinator`

Expected: Gradle `BUILD SUCCESSFUL`; Node reports zero failing tests.

- [ ] **Step 7: Commit**

```powershell
git add .gitignore settings.gradle build.gradle gradle.properties gradle gradlew gradlew.bat LICENSE src coordinator/package.json
git commit -m "build: scaffold Fabric agent project"
```

### Task 2: Shared protocol and validation core

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolConstants.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionState.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionCommand.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ActionResult.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolException.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java`
- Create: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Consumes: Gson supplied by Minecraft runtime, UTF-8 JSON objects.
- Produces: `ProtocolCodec.decodeCommand(String)`, `ProtocolCodec.encode(Object)`, and immutable action records.

- [ ] **Step 1: Write failing dependency-free assertions**

```java
expectThrows(() -> codec.decodeCommand("{\"type\":\"unknown\"}"), "UNKNOWN_ACTION");
expectThrows(() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":600001}"), "OUT_OF_RANGE");
assertEquals(ActionType.WAIT, codec.decodeCommand(validWait).type(), "wait action type");
```

- [ ] **Step 2: Run verification and confirm failure**

Run: `.\gradlew.bat verifyCore`

Expected: compilation fails because protocol classes do not exist.

- [ ] **Step 3: Implement strict records and codec**

```java
public record ActionCommand(
    String commandId,
    ActionType type,
    JsonObject arguments,
    long issuedAtEpochMs
) {}
```

Enforce protocol version `1`, a 64 KiB line limit, finite coordinates, text limits, duration limits, required fields, and the exhaustive wire action enum `move_to`, `look_at`, `attack`, `select_item`, `use_item`, `break_block`, `place_block`, `chat`, `wait`, and `complete_goal`.

- [ ] **Step 4: Run verification**

Run: `.\gradlew.bat verifyCore`

Expected: all verification assertions print `PASS` and process exits `0`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/dev/agaminggod/arenaagents/protocol src/test build.gradle
git commit -m "feat: add validated Minecraft action protocol"
```

### Task 3: Client configuration and loopback bridge

**Files:**
- Create: `src/client/java/dev/agaminggod/arenaagents/client/config/AgentConfig.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/config/AgentConfigLoader.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeServer.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeEventSink.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Consumes: `AgentConfig(agentId, port, observationRadius, enabled)` and `ProtocolCodec`.
- Produces: one authenticated loopback session, parsed action callbacks, and thread-safe outbound events.

- [ ] **Step 1: Add failing config and framing assertions**

```java
assertEquals(25571, AgentConfigLoader.parse(validJson).bridgePort(), "bridge port");
expectThrows(() -> AgentConfigLoader.parse(nonLoopbackJson), "LOOPBACK_REQUIRED");
expectThrows(() -> codec.readLine(oversizedLine), "LINE_TOO_LARGE");
```

- [ ] **Step 2: Run and observe failure**

Run: `.\gradlew.bat verifyCore`

Expected: missing config and bridge classes.

- [ ] **Step 3: Implement idempotent config creation and loopback server**

```java
ServerSocket socket = new ServerSocket();
socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.bridgePort()));
```

Use a single daemon accept thread, one reader thread, bounded outbound queue, explicit close, and Minecraft-client-thread dispatch for action callbacks.

- [ ] **Step 4: Verify socket rejection, reconnect, and clean shutdown**

Run: `.\gradlew.bat verifyCore`

Expected: verification exits `0`; no non-daemon thread remains.

- [ ] **Step 5: Commit**

```powershell
git add src/client src/test
git commit -m "feat: add loopback Minecraft bridge"
```

### Task 4: Bounded world observations

**Files:**
- Create: `src/client/java/dev/agaminggod/arenaagents/client/perception/Observation.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/perception/EntitySnapshot.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/perception/BlockSnapshot.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/perception/InventorySnapshot.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationCollector.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`

**Interfaces:**
- Consumes: `Minecraft`, active action state, configured radii and limits.
- Produces: deterministic `Observation` records ordered by distance then stable identifier.

- [ ] **Step 1: Add pure ordering and truncation assertions**

```java
assertEquals(List.of("near", "far"), ObservationOrdering.entities(input).stream().map(EntitySnapshot::name).toList(), "entity ordering");
assertEquals(128, ObservationLimits.truncateBlocks(blocks, 128).size(), "block cap");
```

- [ ] **Step 2: Run and confirm missing implementation**

Run: `.\gradlew.bat verifyCore`

- [ ] **Step 3: Implement null-safe collection**

Collect position, velocity, view, health, hunger, armor, effects, selected slot, summarized inventory, nearby living entities, selected nearby blocks, dimension, time, weather, current action, and last result. Never scan unloaded chunks.

- [ ] **Step 4: Compile against Minecraft 26.1.2 mappings and run verification**

Run: `.\gradlew.bat compileClientJava verifyCore`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add src/client/java/dev/agaminggod/arenaagents/client/perception src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java src/test
git commit -m "feat: expose bounded agent observations"
```

### Task 5: Action lifecycle and safe primitive executors

**Files:**
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/ActionExecutor.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/ActionContext.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/RunningAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/WaitAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/LookAtAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/ChatAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/SelectItemAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/UseItemAction.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Consumes: one validated `ActionCommand` at a time.
- Produces: progress events and exactly one terminal `ActionResult` per accepted command.

- [ ] **Step 1: Test lifecycle transitions before implementation**

```java
machine.accept(waitCommand);
assertEquals(ActionState.RUNNING, machine.state(), "accepted state");
machine.cancel("goal_replaced");
assertEquals(ActionState.CANCELLED, machine.state(), "cancel state");
assertEquals(1, sink.terminalResults(), "one terminal result");
```

- [ ] **Step 2: Run failing verification**

Run: `.\gradlew.bat verifyCore`

- [ ] **Step 3: Implement the state machine and primitives**

All client APIs run on the Minecraft client thread. `cancel()` must release key states, stop item use, abort block breaking, and remain idempotent.

- [ ] **Step 4: Run compile and verification**

Run: `.\gradlew.bat compileClientJava verifyCore`

- [ ] **Step 5: Commit**

```powershell
git add src/client src/test
git commit -m "feat: add cancellable Minecraft action lifecycle"
```

### Task 6: Local path planning and movement

**Files:**
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/GridPosition.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/TraversalType.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/PathNode.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/LocalPathfinder.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/MovementController.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/navigation/StuckDetector.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/MoveToAction.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Consumes: a bounded `WalkabilityView`, start, destination, maximum nodes, and time budget.
- Produces: `PathPlan(nodes, outcome, expandedNodes)` and client-key movement toward each node.

- [ ] **Step 1: Add deterministic path tests**

```java
assertPath(flatWorld, p(0, 64, 0), p(4, 64, 0), 5);
assertContainsTraversal(oneBlockStep, TraversalType.JUMP_UP);
assertNoPath(twoBlockWallWithoutGap);
assertNoPath(unsafeDrop);
```

- [ ] **Step 2: Run and confirm failure**

Run: `.\gradlew.bat verifyCore`

- [ ] **Step 3: Implement bounded A***

Use a priority queue, Manhattan-plus-height heuristic, stable tie-breaker, maximum 8,192 expanded nodes, maximum 40 ms planning budget, one-block jumps, and safe drops up to three blocks.

- [ ] **Step 4: Implement gradual rotation and key-state movement**

Release all movement keys on completion, cancellation, disconnect, death, or screen change. Replan after 2.5 seconds of insufficient displacement; fail after three unsuccessful recoveries.

- [ ] **Step 5: Run verification and client compilation**

Run: `.\gradlew.bat verifyCore compileClientJava`

- [ ] **Step 6: Commit**

```powershell
git add src/client/java/dev/agaminggod/arenaagents/client/navigation src/client/java/dev/agaminggod/arenaagents/client/action src/test
git commit -m "feat: add autonomous local navigation"
```

### Task 7: Combat and block interaction

**Files:**
- Create: `src/client/java/dev/agaminggod/arenaagents/client/combat/TargetSelector.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/combat/CombatController.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/combat/WeaponSelector.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/AttackAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/BreakBlockAction.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/action/PlaceBlockAction.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java`
- Modify: `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`

**Interfaces:**
- Consumes: player-name/UUID/type selectors and validated block coordinates.
- Produces: ordinary interaction-manager attacks, block breaks, placements, and explicit outcomes.

- [ ] **Step 1: Add target/weapon/result tests**

```java
assertEquals("Opponent", selector.select(players, "player:Opponent").name(), "named target");
assertEquals("minecraft:diamond_sword", weaponSelector.best(inventory).itemId(), "best melee item");
assertEquals("TARGET_GONE", CombatOutcomes.forMissingTarget(), "missing target result");
```

- [ ] **Step 2: Run failing verification**

Run: `.\gradlew.bat verifyCore`

- [ ] **Step 3: Implement deterministic combat**

Approach outside reach through `MoveToAction`, face the target, respect attack-strength cooldown, call the normal client interaction manager, swing the main hand, and stop on death, disconnect, invalid target, timeout, or cancellation.

- [ ] **Step 4: Implement break/place preconditions**

Require loaded chunk, reachable face, matching inventory item for placement, survival reach, and timeout. Do not teleport, send commands, or bypass server rules.

- [ ] **Step 5: Compile and verify**

Run: `.\gradlew.bat verifyCore compileClientJava`

- [ ] **Step 6: Commit**

```powershell
git add src/client src/test
git commit -m "feat: add combat and world interaction actions"
```

### Task 8: Server goal commands and client payloads

**Files:**
- Create: `src/main/java/dev/agaminggod/arenaagents/server/GoalControl.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/GoalPayload.java`
- Create: `src/main/java/dev/agaminggod/arenaagents/server/ArenaAgentCommands.java`
- Modify: `src/main/java/dev/agaminggod/arenaagents/ArenaAgents.java`
- Create: `src/client/java/dev/agaminggod/arenaagents/client/network/GoalReceiver.java`
- Modify: `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`

**Interfaces:**
- Consumes: operator-issued goal/stop/status commands and Fabric custom payloads.
- Produces: targeted `goal_event` bridge messages and immediate action cancellation on stop.

- [ ] **Step 1: Add parser and authorization assertions**

```java
assertEquals("gather wood", GoalControl.normalize("  gather   wood "), "normalized goal");
expectThrows(() -> GoalControl.normalize("x".repeat(4097)), "GOAL_TOO_LONG");
assertFalse(GoalControl.mayControl(nonOperatorSource), "operator required");
```

- [ ] **Step 2: Run failing verification**

Run: `.\gradlew.bat verifyCore`

- [ ] **Step 3: Implement Fabric command registration and payload codec**

Register `/arenaagent goal <players> <goal>`, `/arenaagent stop <players>`, and `/arenaagent status <players>`. Use Fabric's 26.1.2 networking API and acknowledge affected players.

- [ ] **Step 4: Compile both environments and verify**

Run: `.\gradlew.bat build verifyCore`

- [ ] **Step 5: Commit**

```powershell
git add src/main src/client src/test
git commit -m "feat: deliver server goals to agent clients"
```

### Task 9: Node bridge protocol and action schema

**Files:**
- Create: `coordinator/config/agents.json`
- Create: `coordinator/src/constants.mjs`
- Create: `coordinator/src/jsonl.mjs`
- Create: `coordinator/src/protocol.mjs`
- Create: `coordinator/src/schema.mjs`
- Create: `coordinator/test/jsonl.test.mjs`
- Create: `coordinator/test/schema.test.mjs`

**Interfaces:**
- Consumes: the Java protocol version and action union.
- Produces: `MinecraftBridge`, `parseDecision`, `validateObservation`, and `validateAction`.

- [ ] **Step 1: Write failing Node tests**

```javascript
test('frames fragmented JSONL', () => {
  const decoder = new JsonlDecoder({ maxBytes: 65_536 });
  assert.deepEqual(decoder.push('{"a":1}\n{"b"'), [{ a: 1 }]);
  assert.deepEqual(decoder.push(':2}\n'), [{ b: 2 }]);
});
```

- [ ] **Step 2: Run and confirm module-not-found failures**

Run: `npm test --prefix coordinator`

- [ ] **Step 3: Implement strict dependency-free validation**

Reject unknown keys where ambiguity matters, invalid coordinates, non-finite numbers, overlong strings, unsupported actions, duplicate terminal results, and protocol-version mismatch.

- [ ] **Step 4: Run Node tests**

Run: `npm test --prefix coordinator`

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add coordinator
git commit -m "feat: add coordinator bridge protocol"
```

### Task 10: Codex app-server client and shared planner prompt

**Files:**
- Create: `coordinator/src/codex-app-server.mjs`
- Create: `coordinator/src/prompts.mjs`
- Create: `coordinator/src/decision-parser.mjs`
- Create: `coordinator/test/codex-app-server.test.mjs`
- Create: `coordinator/test/decision-parser.test.mjs`

**Interfaces:**
- Consumes: Codex JSON-RPC JSONL events and compact Minecraft observations.
- Produces: `CodexAgent.start()`, `CodexAgent.decide(input)`, `CodexAgent.interrupt()`, and one validated planner decision.

- [ ] **Step 1: Test initialization and final-message extraction with a fake process**

```javascript
test('initializes before model/list and thread/start', async () => {
  const transport = new FakeCodexTransport();
  const agent = new CodexAgent(config, transport);
  await agent.start();
  assert.deepEqual(transport.methods(), ['initialize', 'initialized', 'model/list', 'thread/start']);
});
```

- [ ] **Step 2: Run and observe failures**

Run: `npm test --prefix coordinator`

- [ ] **Step 3: Implement request IDs, notifications, timeout, interrupt, and process cleanup**

Spawn a separate app-server per agent with explicit config overrides:

```javascript
[
  'app-server', '--stdio',
  '-c', `model="${model}"`,
  '-c', `model_reasoning_effort="${reasoningEffort}"`,
  '-c', 'service_tier="fast"',
  '-c', 'features.fast_mode=true'
]
```

Query `model/list` before starting the thread and fail closed if the required model, effort, or Fast tier is absent.

- [ ] **Step 4: Implement the identical planner contract**

The final response must contain one fenced or bare JSON object with `summary`, `goalStatus`, and one allowlisted `action`. The prompt explicitly forbids shell/file tools and asks the model to choose only from supplied world facts.

- [ ] **Step 5: Run tests and a read-only live catalog smoke test**

Run: `npm test --prefix coordinator` and `node coordinator/src/main.mjs --check-models`

Expected: both requested model configurations pass catalog validation without starting Minecraft.

- [ ] **Step 6: Commit**

```powershell
git add coordinator
git commit -m "feat: connect isolated Codex planner threads"
```

### Task 11: Proactive runtime, recovery, and evidence traces

**Files:**
- Create: `coordinator/src/agent-state.mjs`
- Create: `coordinator/src/agent-runtime.mjs`
- Create: `coordinator/src/retry-policy.mjs`
- Create: `coordinator/src/trace-writer.mjs`
- Create: `coordinator/src/main.mjs`
- Create: `coordinator/test/agent-runtime.test.mjs`
- Create: `coordinator/test/retry-policy.test.mjs`

**Interfaces:**
- Consumes: `goal_event`, `observation`, `action_result`, and `significant_event` messages.
- Produces: continuous planner turns and action commands until a terminal goal state or stop.

- [ ] **Step 1: Write proactive-loop tests**

```javascript
test('plans again after action completion without another user prompt', async () => {
  const harness = createRuntimeHarness();
  await harness.goal('reach the marker');
  await harness.completeFirstAction();
  assert.equal(harness.codexTurnCount(), 2);
});
```

Test goal replacement, stop, planner timeout, bridge reconnect, duplicate result, three stuck failures, and backoff reset after success.

- [ ] **Step 2: Run and confirm failures**

Run: `npm test --prefix coordinator`

- [ ] **Step 3: Implement serialized state transitions**

States are `IDLE`, `PLANNING`, `ACTING`, `RECOVERING`, `COMPLETED`, `STOPPED`, and `ERROR`. One async event queue owns transitions so socket and Codex callbacks cannot race.

- [ ] **Step 4: Add append-only redacted traces**

Every trace row includes timestamp, agent ID, goal revision, state, event, observation hash, action, result, model, effort, and service tier. Never write auth tokens, full launcher account data, or unrelated environment variables.

- [ ] **Step 5: Run all coordinator tests**

Run: `npm test --prefix coordinator`

Expected: all proactive and recovery tests pass.

- [ ] **Step 6: Commit**

```powershell
git add coordinator
git commit -m "feat: run proactive autonomous agent loops"
```

### Task 12: End-to-end fake bridge smoke test

**Files:**
- Create: `coordinator/test/fixtures/fake-minecraft-bridge.mjs`
- Create: `coordinator/test/fixtures/fake-codex-server.mjs`
- Create: `coordinator/test/end-to-end.test.mjs`
- Create: `scripts/run-automated-verification.ps1`

**Interfaces:**
- Consumes: production coordinator code with injected transports.
- Produces: deterministic evidence that two isolated agents progress concurrently.

- [ ] **Step 1: Write the failing two-agent scenario**

```javascript
test('agents remain isolated while progressing concurrently', async () => {
  const run = await startTwoAgentFixture();
  await run.goalBoth('enter the arena');
  await run.untilBothComplete();
  assert.equal(run.crossAgentMessages(), 0);
  assert.deepEqual(run.models(), ['gpt-5.5', 'gpt-5.6-sol']);
});
```

- [ ] **Step 2: Run and confirm failure before fixtures exist**

Run: `npm test --prefix coordinator`

- [ ] **Step 3: Implement deterministic fixtures and full verification script**

```powershell
$ErrorActionPreference = 'Stop'
& .\gradlew.bat clean build verifyCore
npm test --prefix coordinator
node coordinator/src/main.mjs --check-models
```

- [ ] **Step 4: Run full automated verification**

Run: `powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1`

Expected: exit `0`, Gradle success, Node success, both live model checks pass.

- [ ] **Step 5: Commit**

```powershell
git add coordinator/test scripts/run-automated-verification.ps1
git commit -m "test: verify isolated autonomous agent runtimes"
```

### Task 13: Safe runtime preparation and packaging

**Files:**
- Create: `scripts/install-toolchain.ps1`
- Create: `scripts/prepare-runtime.ps1`
- Create: `scripts/start-coordinator.ps1`
- Create: `scripts/start-test-server.ps1`
- Create: `runtime/README.md`
- Modify: `.gitignore`
- Create: `README.md`

**Interfaces:**
- Consumes: built remapped JAR and source world path.
- Produces: two isolated game directories, a local Fabric server, copied test world, configs, and launch instructions.

- [ ] **Step 1: Add Pester-free script validation helpers**

Scripts use `Set-StrictMode -Version Latest`, `$ErrorActionPreference = 'Stop'`, `Resolve-Path`, explicit absolute roots, SHA-256 checks, and idempotent directory creation.

- [ ] **Step 2: Implement verified world copy**

Before copying, assert source resolves to `%APPDATA%\.minecraft\saves\New World (76)` and target resolves under the project `runtime\server`. Write the source and destination hashes/metadata to `runtime\evidence\world-copy.json`.

- [ ] **Step 3: Install the JAR and per-agent config**

Agent config examples:

```json
{"agentId":"agent-55","bridgePort":25571,"observationRadius":12,"enabled":true}
```

```json
{"agentId":"agent-56","bridgePort":25572,"observationRadius":12,"enabled":true}
```

- [ ] **Step 4: Prepare the local Fabric server**

Keep `online-mode=true` for the final genuine-player test. A separate clearly labeled `runtime/server-offline-smoke` may use `online-mode=false` only for protocol/gameplay smoke tests and must never be reported as authenticated verification.

- [ ] **Step 5: Run preparation twice to prove idempotency**

Run: `powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1` twice.

Expected: second run reports existing verified artifacts and performs no duplicate profile or world copy.

- [ ] **Step 6: Commit only source and documentation**

```powershell
git add scripts .gitignore README.md runtime/README.md
git commit -m "ops: prepare isolated Minecraft agent runtime"
```

### Task 14: Official-launcher and in-game verification with Computer Use

**Files:**
- Modify: `PROJECT_LOG.md`
- Create at runtime only: `runtime/evidence/live-test-summary.json`
- Create at runtime only: screenshots and logs under `runtime/evidence/`

**Interfaces:**
- Consumes: built JAR, prepared profiles/server, coordinator, official launcher, authenticated account(s).
- Produces: evidence-backed capability matrix and an explicit authenticated-two-player status.

- [ ] **Step 1: Read Computer Use guidance, API, and confirmation rules**

Use the plugin bootstrap exactly; do not build a custom desktop-control client.

- [ ] **Step 2: Create or update two launcher installations through the official UI**

Both use Fabric Loader 0.19.3 for Minecraft 26.1.2 and their respective isolated game directories. Do not expose launcher credentials in screenshots or logs.

- [ ] **Step 3: Start the copied-world server and coordinator**

Verify ready logs, ports 25571/25572, and both Codex model catalog checks before entering the world.

- [ ] **Step 4: Run single-client live tests**

For each model configuration, verify bridge connection, observation, goal receipt, autonomous second action without another prompt, navigation, stop/cancel, reconnect, item selection/use, block break/place, and combat against a controlled target.

- [ ] **Step 5: Attempt authenticated two-player test only with two accounts**

If a second licensed Java account is absent, record `BLOCKED_SECOND_LICENSED_ACCOUNT` and do not weaken online mode. If available, connect both clients concurrently, issue one identical arena goal, verify distinct UUIDs, capture action traces, and observe the fight through completion or a defined timeout.

- [ ] **Step 6: Run regression verification after live fixes**

Run: `powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1`

Expected: all automated checks still pass.

- [ ] **Step 7: Update project log and commit**

```powershell
git add PROJECT_LOG.md README.md src coordinator scripts
git commit -m "test: validate autonomous agents in Minecraft"
```

The log must distinguish automated, single-client authenticated, offline two-client smoke, and simultaneous authenticated two-player evidence.
