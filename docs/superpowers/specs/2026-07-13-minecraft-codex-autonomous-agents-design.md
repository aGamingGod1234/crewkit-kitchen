# Minecraft Codex Autonomous Agents Design

**Date:** 2026-07-13

**Status:** Approved by the user on 2026-07-13

## Objective

Build two autonomous Minecraft Java players that join a Minecraft 26.1.2 server as genuine client connections. Both players receive the same initial goal, observations, action vocabulary, timing policy, and world conditions. The only intentional experimental difference is their Codex model configuration:

- Agent 55: `gpt-5.5`, `xhigh` reasoning, Fast service tier.
- Agent 56: `gpt-5.6-sol`, `high` reasoning, Fast service tier.

The user owns arena construction, equipment, match rules, and initial-goal wording. This project owns the behavior runtime that keeps each agent observing, deciding, acting, recovering, and progressing without waiting for repeated user prompts.

## Scope

The first production-ready release must support the behaviors needed for an arena video and useful general interaction:

- Receive, replace, pause, resume, complete, or stop a goal.
- Observe player state, inventory, nearby entities, nearby blocks, time, and action status.
- Navigate through ordinary terrain with local path planning and stuck recovery.
- Look at coordinates or entities.
- Attack players and other living entities.
- Select and equip items, eat, and use the active item.
- Break and place blocks.
- Send chat, wait, and report status.
- Continue planning automatically after every action result or important world event.
- Preserve a structured trace for comparison and debugging.

Complex recipe-book automation, enchanting, trading, redstone design, and arbitrary GUI manipulation are outside the first release. They can be added as new validated actions without changing the agent loop.

## Evaluated Approaches

### 1. Fabric real-client mod plus local coordinator — selected

Each bot runs a normal authenticated Minecraft client with the same Fabric mod. A local coordinator connects the mod to an isolated Codex app-server process. Minecraft sees normal player connections and normal client-originated actions.

This is the only approach that satisfies the official-launcher, installed-mod, genuine-player, and exact-version requirements together.

### 2. Mineflayer headless clients — rejected

Mineflayer offers a mature bot API, but it bypasses the requested launcher/mod workflow and current Minecraft 26.1.2 protocol support cannot be assumed.

### 3. Carpet fake players or custom NPC entities — rejected

These are easier to control and inspired useful action-executor patterns, but they are server-created simulations rather than independently authenticated players.

## Architecture

### Fabric mod

One universal Fabric 26.1.2 JAR runs on the dedicated/integrated server and each bot client.

Server responsibilities:

- Register `/arenaagent goal <targets> <goal>`, `/arenaagent stop <targets>`, and `/arenaagent status <targets>`.
- Deliver goal-control payloads only to selected bot clients.
- Avoid storing model credentials or Codex authentication data.

Client responsibilities:

- Start a loopback-only newline-delimited JSON bridge on its configured port.
- Collect bounded structured observations.
- Validate and queue one macro action at a time.
- Execute movement and interaction incrementally on client ticks.
- Emit action progress, completion, failure, and significant-event messages.
- Run low-latency survival and combat reflexes without waiting for a model turn.

### Local coordinator

The dependency-free Node.js coordinator owns one `AgentRuntime` per bot. Each runtime:

1. Connects to the assigned Minecraft bridge port.
2. Starts a dedicated Codex app-server process using the bot's model, reasoning effort, and Fast configuration.
3. Starts one persistent Codex thread.
4. On a new goal, significant observation, action completion, action failure, or planning timeout, sends a compact state update to Codex.
5. Extracts and validates exactly one structured macro action from the final agent message.
6. Dispatches the action to Minecraft and records an append-only JSONL trace.
7. Repeats until completion, impossibility, stop, or disconnect.

The coordinator never gives Codex unrestricted control of the computer. Codex proposes only allowlisted Minecraft actions; deterministic code validates types, ranges, and preconditions.

### Timing model

- Minecraft tick loop: input execution, collision response, reflexes, and action progress.
- Event-driven planner loop: runs after meaningful state changes, not every game tick.
- While a Codex turn is pending, the current safe deterministic action or reflex remains active.
- Timeouts and transient Codex failures use bounded exponential backoff.
- Repeated no-progress results trigger local recovery, path replanning, and then model replanning.

## Protocol

Every bridge message is one UTF-8 JSON object followed by a newline. Messages include `protocolVersion`, `agentId`, `type`, and a unique `messageId`.

Coordinator-to-mod messages:

- `hello`
- `action_command`
- `cancel_action`
- `request_observation`
- `shutdown`

Mod-to-coordinator messages:

- `hello_ack`
- `goal_event`
- `observation`
- `action_progress`
- `action_result`
- `significant_event`
- `error`

Unknown versions, message types, action types, malformed JSON, excessive line sizes, non-loopback peers, and invalid numeric ranges are rejected explicitly.

## Action Vocabulary

The initial action union is:

- `move_to(x, y, z, tolerance, sprint)`
- `look_at(x, y, z)`
- `attack(targetSelector, timeoutMs)`
- `select_item(itemId)`
- `use_item(durationMs)`
- `break_block(x, y, z, timeoutMs)`
- `place_block(x, y, z, face, itemId)`
- `chat(message)`
- `wait(durationMs)`
- `complete_goal(summary)`

Each action has a terminal `SUCCEEDED`, `FAILED`, `CANCELLED`, or `TIMED_OUT` result and a machine-readable reason code.

## Navigation and Combat

Navigation uses a bounded local A* graph over walkable block positions. Edges cover ordinary walking, one-block jumps, safe short drops, and cardinal/diagonal movement. The executor steers through client key states, rotates gradually, sprints when safe, jumps when the next edge requires it, and replans after collision or displacement.

Combat is split between strategic and reflex layers. Codex chooses targets and broad intent. The deterministic controller tracks the target, approaches, maintains reach, faces the target, respects attack cooldown, attacks through the normal interaction manager, switches to an appropriate inventory item, eats when safe, and exits on death, invalid target, timeout, stop, or goal replacement.

## Fairness

Both agents use:

- The same mod JAR and coordinator source.
- The same action validators and timeouts.
- The same observation radius and field ordering.
- The same system prompt and action schema.
- Separate isolated Codex processes, threads, logs, bridge ports, launcher directories, and runtime state.

Model, reasoning effort, and the account/player identity are the only runtime differences.

## Safety and Failure Handling

- Bridge servers bind to `127.0.0.1` only.
- No arbitrary shell command, source-code generation, or file-edit action is exposed to the Minecraft agent.
- Goal text and chat text have configured length limits.
- Destructive actions remain limited to what the authenticated player can normally do on the server.
- A stop command immediately cancels key states, item use, block breaking, and combat.
- Disconnects release all synthetic key states.
- The original world is never used directly for automated tests; testing uses a verified copy.
- Logs redact Codex authentication and launcher credentials.

## Runtime Layout

- Project: `C:\Users\lucas\Desktop\minecraft\5.5 vs 5.6`
- Agent 55 game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-55`
- Agent 56 game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-56`
- Test server and copied world: project-local `runtime/server`
- Evidence and traces: project-local `runtime/evidence` and `runtime/traces`

Runtime directories are excluded from Git.

## Dependencies

- Eclipse Temurin JDK 25.
- Gradle wrapper matching the Fabric 26.1.2 example project.
- Fabric Loom `1.16-SNAPSHOT`.
- Fabric Loader `0.19.3` (current stable release for Minecraft 26.1.2).
- Fabric API `0.150.0+26.1.2`.
- Node.js 25 already installed; coordinator uses only built-in modules.
- Codex CLI 0.144.0 already installed and authenticated with ChatGPT.

No additional npm runtime dependencies are allowed.

## Testing Strategy

### Automated

- Node built-in tests for framing, schema validation, scheduler transitions, retries, prompt construction, and Codex-event extraction.
- Pure Java verification mains for protocol parsing, action validation, path graph behavior, and state transitions without adding a test framework dependency.
- Gradle compilation, resource processing, mod metadata validation, and JAR build.
- Coordinator smoke test against a deterministic fake Minecraft bridge and fake Codex transport.

### In-game

- Install the same built JAR into isolated launcher game directories.
- Launch Minecraft 26.1.2 through the official launcher with Computer Use.
- Verify bridge startup, goal receipt, observation content, movement, stop behavior, reconnect behavior, block interaction, and combat against an in-world target.
- Run each model configuration sequentially with the currently authenticated account.
- Run the simultaneous authenticated two-player fight after a second licensed Java account is present in the launcher.

## Known External Constraint

The launcher currently contains one authenticated Minecraft account. Implementation and single-client live testing can proceed autonomously. A simultaneous genuine two-player online-mode test cannot be completed until a second licensed Java account is added; the system must report this honestly rather than weakening the requirement or silently substituting fake players.
