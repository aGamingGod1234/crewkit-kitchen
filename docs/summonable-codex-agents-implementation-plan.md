# Summonable Codex Agents — Implementation Plan and Static Audit

**Status:** Architecture approved; implementation not started
**Target:** Minecraft Java 26.1.2, Fabric Loader 0.19.3, Fabric API 0.150.0+26.1.2, Java 25
**Audit boundary:** Static source and protocol analysis only. No Minecraft client/server launch, Codex launch, build, or runtime test was performed.

## 1. Confirmed product contract

The finished mod pack will provide server-owned, custom humanoid Codex agents rather than authenticated Minecraft player clients.

- Operators can summon up to the configured agent cap. The default cap is 16 and can be raised.
- Each agent has a stable logical ID, optional unique display name, Codex model, reasoning effort, skin variant, state, current goal, and queued goals.
- The visible nametag is exactly `<model> · <reasoning-effort>`.
- Skins are bundled, original pixel-art interpretations of the configured company visual identities. Official logo artwork is not copied; see `docs/agent-logo-skins.md` for references and attribution.
- A newly summoned agent is `IDLE`: it does not navigate, look around, attack, or interact until started.
- Codex uses the authenticated local Codex app-server on the same machine as the Minecraft server. No API key or OAuth token is stored in the mod, world, trace, or agent data.
- Singleplayer integrated servers and dedicated servers use the same server-authoritative implementation.
- Commands are operator-only by default.
- At most four Codex planning turns run concurrently by default. Minecraft action execution is independent, so more than four agents may act at once.
- Agents, model settings, current goals, queues, and compact recovery state persist. Agents that were active when the server stopped reload as `PAUSED`.

## 2. Current implementation audit

| Area | Current state | Required migration |
| --- | --- | --- |
| Agent representation | Two authenticated `LocalPlayer` clients | Add server-side custom humanoid entities with stable logical agent IDs |
| Configuration | Exactly two JSON profiles, fixed CLI filters and ports | Replace with dynamic per-world registry plus server configuration |
| Bridge topology | One loopback TCP server per Minecraft client | One server-hosted, authenticated, multiplexed JSONL bridge |
| Commands | `/arenaagent goal|stop|status <players>` | Add `/codex` commands targeting logical Codex agents |
| Control | Synthetic client keys and client interaction manager | Server navigation and server-authoritative interaction operations |
| Observation | `LocalPlayer` and `ClientLevel` snapshots | Equivalent bounded snapshots from agent entity and `ServerLevel` |
| Planner | One Codex process/thread per fixed client | Dynamic thread registry with a four-turn scheduler |
| Persistence | Server-session goal map only | Entity NBT plus world-level saved agent records and queues |
| Rendering | Normal player rendering/skins | Custom humanoid renderer and bundled deterministic texture variants |
| Gameplay | Movement, looking, combat, item selection/use, break/place/chat/wait | Port core actions, then add equipment, food, doors, containers, crafting, furnaces, death, and respawn |

### Reusable logic

- Bounded JSONL framing, strict allowlists, finite-number checks, message-ID bounds, cancellation, timeouts, retry policy, trace redaction, decision parsing, planner prompts, model-catalog validation, and observation size limits.
- The existing `AgentRuntime` planning loop is a useful behavioral baseline, but it must no longer own a dedicated bridge and Codex process.
- Existing action state/result contracts can be retained conceptually while their Minecraft implementation moves from client APIs to server APIs.

### Logic that cannot drive NPCs directly

- `ArenaAgentsClient`, `MinecraftActionContext`, client tick hooks, synthetic key presses, `LocalPlayer`, and `ClientLevel` access are inherently client-player-specific.
- `GoalPayload`, `ServerPlayer` selectors, launcher profiles, account profiles, and per-client ports do not apply to server-owned entities.
- The existing protocol identifies one agent per socket. Merely adding more configuration entries would not make it safe or scalable.

The existing comparison implementation must remain intact until the user explicitly authorizes removal. New summonable-agent code will be introduced alongside it, with legacy startup disabled by the new mod-pack configuration only after equivalent logic exists.

## 3. Target architecture

```text
/codex command
    -> CodexAgentManager (server authority)
        -> CodexAgentEntity + persistent AgentRecord
        -> ServerObservationCollector
        -> ServerActionRuntime
        -> MultiplexedServerBridge (one loopback port)
            -> Coordinator AgentRegistry
                -> PlanningScheduler (default concurrency 4)
                -> one persistent Codex thread per logical agent
                -> one shared local Codex app-server process
```

### Server authority

Minecraft owns entity existence, state transitions, goal revisions, action acceptance, inventory, navigation, persistence, death, respawn, and command authorization. The coordinator may propose actions but cannot directly mutate the world.

### Coordinator authority

The coordinator owns Codex process lifecycle, live model catalog, per-agent Codex threads, planning concurrency, prompt construction, retry policy, and redacted traces. It does not own the canonical Minecraft state.

### Client responsibility

The client source set only registers the renderer, model layer, textures, nametag rendering, and optional status particles. It never runs Codex or controls an agent.

## 4. Agent identity and data model

Every logical agent has a persistent UUID-like `agentId` independent of its current entity UUID. Death and respawn may replace the entity instance without changing command targeting, queue ownership, or Codex identity.

Persistent fields:

- schema version
- logical agent ID
- current entity UUID, when alive
- optional unique user name
- model and reasoning effort
- deterministic skin variant
- lifecycle state
- current goal and monotonically increasing goal revision
- bounded FIFO queue
- compact last planner/action summary
- inventory/equipment and respawn policy
- creation/update timestamps and last bounded error

Transient fields such as sockets, Codex thread IDs, active turn IDs, action handles, navigation handles, and secrets are never written to the world.

## 5. Lifecycle state machine

Canonical states:

- `IDLE` — spawned and inert with no active goal.
- `STARTING` — goal accepted; awaiting coordinator readiness and first observation.
- `PLANNING` — waiting for a scheduled Codex turn.
- `ACTING` — exactly one accepted Minecraft action is active.
- `PAUSED` — current goal and queue retained; no planning or action is allowed.
- `COMPLETED` — current goal completed; next queued goal is promoted or the agent returns to `IDLE`.
- `ERROR` — bounded failure visible to the operator; no automatic world mutation.
- `DEAD` — entity is absent while respawn policy is evaluated.
- `DISCONNECTED` — coordinator unavailable; the agent is frozen and its goal is retained.

Invariants:

1. An agent has at most one current goal, one Codex turn, and one Minecraft action.
2. Every start, steer, stop, resume, and queue promotion advances the relevant revision.
3. An action command includes the goal revision that produced it. The server rejects stale revisions.
4. `stop` first advances the revision, then cancels the action, interrupts the turn, and enters `PAUSED`.
5. Disconnect, unload, death, shutdown, or removal releases navigation and interaction resources idempotently.
6. Reload converts `STARTING`, `PLANNING`, `ACTING`, and `DISCONNECTED` to `PAUSED`.

## 6. Command contract

```text
/codex summon <model> <reasoning> [name]
/codex start <agent> <prompt>
/codex stop <agent>
/codex resume <agent>
/codex queue <agent> <prompt>
/codex steer <agent> <prompt>
/codex status [agent]
/codex list
/codex remove <agent>
```

- `<agent>` accepts a stable short ID, full logical ID, or unique user name. Ambiguous names fail explicitly.
- Model and reasoning suggestions come from the coordinator's cached live catalog. The coordinator performs final validation.
- `summon` creates an inert entity at the command source position.
- `start` creates a new current goal. On a paused agent it replaces only the paused current goal; already queued goals remain intact.
- `stop` pauses immediately and preserves the current goal and queue.
- `resume` continues the paused current goal with a fresh revision and observation.
- `queue` appends a bounded goal without interrupting current work.
- `steer` preserves the goal identity and queue, records a steering instruction, invalidates the active plan/action, and replans.
- `remove` cancels all work and removes the entity and persistent record after explicit command execution.
- Prompts are limited to 4,096 characters. The default queue limit is 32 goals per agent and is configurable.

## 7. Multiplexed protocol v2

The new bridge uses one loopback-only TCP listener owned by the Minecraft server. All frames remain length-bounded UTF-8 JSONL.

Required envelope fields:

- `protocolVersion`
- `serverInstanceId`
- `agentId` (`server` for connection-wide messages)
- `type`
- `messageId`
- `payload`

Important payloads:

- Coordinator to server: `hello`, `catalog_snapshot`, `agent_ready`, `planning_state`, `goal_completed`, `action_command`, `agent_error`, `heartbeat`.
- Server to coordinator: `hello_ack`, `catalog_request`, `agent_registered`, `agent_removed`, `goal_control`, `observation`, `action_progress`, `action_result`, `heartbeat`, `shutdown`.

Protocol rules:

- A generated per-install secret augments loopback isolation. It is stored only in server/coordinator configuration with restrictive local permissions and is redacted from logs.
- The handshake must complete before any agent message is accepted.
- Message IDs are connection-unique; action IDs are agent-unique; goal revisions are monotonically increasing per agent.
- Duplicate terminal results, unknown agents, stale revisions, invalid action types, and oversized frames fail closed with stable error codes.
- Reconnect begins with a complete non-secret registry snapshot. Agents remain frozen until reconciliation finishes.
- Backpressure is bounded per connection and per agent so one noisy agent cannot starve the others.
- Heartbeat loss moves active agents to `DISCONNECTED` and releases actions.

## 8. Coordinator redesign

The coordinator becomes a long-running service with:

- `AgentRegistry` — dynamically creates, pauses, resumes, and disposes logical runtimes.
- `PlanningScheduler` — fair FIFO scheduling with a default maximum of four concurrent turns and at most one queued planning request per agent.
- `CodexService` — one authenticated app-server transport and one persistent thread per active agent.
- `ModelCatalogCache` — refreshes the live model/effort catalog and publishes snapshots to Minecraft.
- `AgentPlanner` — reuses strict decision parsing, recovery rules, bounded retries, and per-agent turn interruption.
- `PersistenceReconciler` — creates fresh ephemeral Codex threads after coordinator/server restart using the server's compact recovery record.

The current app-server API already supplies model and effort on `turn/start`; sharing the process is therefore the target design. Before implementation, the local app-server schema will be statically rechecked. Live multi-thread behavior remains part of the later runtime-verification phase.

## 9. Server action and observation engine

### Core action set

- pathfind/move, look, attack, select/equip, use/eat, break, place, chat, wait, complete goal
- open/close doors and gates
- pick up and drop items
- open containers, move item stacks, and close screens logically server-side
- craft with inventory or crafting table recipes
- load/unload furnaces and collect output

Each operation performs reach, existence, dimension, permission, inventory, and state validation on the server immediately before mutation. Actions use bounded timeouts and publish progress no more frequently than the configured interval.

### Navigation and chunks

- Use Minecraft server navigation instead of synthetic key input.
- Active agents receive bounded chunk tickets only while planning/acting; tickets are released when idle, paused, dead, disconnected, or removed.
- The agent cap also bounds chunk-ticket pressure.
- No teleport fallback is permitted unless a future explicit command/configuration authorizes it.

### Observation

Port the existing bounded observation shape to server types, retaining deterministic ordering and wire-budget fitting. Extend it with navigation state, logical agent state, goal revision, queue depth, usable recipes, nearby container summaries, and interaction cooldowns. Observations must never force-load chunks.

### Death and respawn

The logical record survives death. A configurable delay respawns a new entity instance with the same logical agent ID. Inventory handling respects the configured agent policy and applicable gamerules. Planning remains stopped until the respawned entity is fully registered and observed.

## 10. Rendering and assets

- Add a humanoid entity model and renderer in the client source set.
- Bundle several original geometric knot/swirl textures inspired by the requested AI aesthetic.
- Choose the default variant deterministically from the logical agent ID; persist manual changes.
- Render the exact model and reasoning string as the nametag.
- Keep runtime state out of the nametag; status can use particles or an optional HUD later.
- Provide texture fallback so a missing or corrupt variant cannot crash rendering.

## 11. Implementation phases

### Phase 1 — contracts and server registry

- Add protocol-v2 constants, pure validators, agent records, lifecycle reducer, revision rules, scheduler contract, and persistence codecs.
- Add deterministic logic verification only; do not connect Minecraft or Codex.

### Phase 2 — entity and presentation

- Register the custom entity, attributes, spawn logic, renderer, nametag, textures, and inert default behavior.
- Add command parsing and targeting without enabling autonomous actions.

### Phase 3 — bridge and dynamic coordinator

- Add the single authenticated server bridge, registry reconciliation, shared Codex service, thread registry, catalog cache, and planning scheduler.
- Retain existing strict protocol limits and trace redaction.

### Phase 4 — core autonomy

- Port server observations and core movement/combat/block/item actions.
- Enforce revision cancellation, timeouts, chunk-ticket cleanup, and disconnect freezing.

### Phase 5 — survival interactions

- Add doors, pickups, equipment, eating, containers, crafting, furnaces, death, and respawn.
- Add conflict arbitration for multiple agents targeting the same block, entity, or container.

### Phase 6 — mod-pack packaging

- Add operator configuration, coordinator start/stop scripts, bundled assets, installation instructions, safe defaults, migration notes, and distributable artifacts.

### Phase 7 — deferred runtime verification

- Build and static suites.
- Dedicated-server startup and command smoke test.
- Integrated-server smoke test.
- Local OAuth/app-server connection and live catalog validation.
- One-agent behavior test, then four concurrent planners, then configured-cap soak.
- Restart, disconnect, death/respawn, chunk unload, stale-command, queue, steer, and stop race verification.

Phase 7 is explicitly outside the current authorization boundary.

## 12. Static verification matrix for implementation

Before any live launch, automated logic coverage must prove:

- every lifecycle transition and illegal transition
- stale goal/action revision rejection
- idempotent stop/removal/disconnect cleanup
- queue ordering, bounds, promotion, and persistence
- stable identity across entity replacement
- duplicate/ambiguous target handling
- permission enforcement
- protocol field allowlists, line bounds, authentication, reconnect reconciliation, and fair backpressure
- scheduler concurrency never exceeds four and never schedules two turns for one agent
- Codex turn interruption and late-result rejection
- observation determinism and non-loading chunk access
- inventory/container/crafting conservation rules
- trace redaction for credentials, prompts where configured, and local secrets
- migration that leaves the legacy two-client implementation untouched

## 13. Principal risks and mitigations

1. **Custom NPCs are not real players.** Vanilla APIs may assume `ServerPlayer` for containers, recipes, advancements, or protection callbacks. Isolate these behind server interaction adapters and fail explicitly when unsupported.
2. **Model catalog/API drift.** Validate the live catalog through the existing initialized app-server path, cache bounded snapshots, and reject unsupported profiles without spawning a half-configured agent.
3. **Late asynchronous results.** Goal revisions and action IDs are mandatory on every proposal/result; stale work never mutates the world.
4. **Scale pressure.** Bound agents, planning concurrency, queues, observations, sockets, traces, and active chunk tickets independently.
5. **World corruption on restart.** Persist versioned records atomically and reload uncertain active states as paused.
6. **Multi-agent contention.** Add short server-side resource leases for blocks, entities, and containers; release them on every terminal path.
7. **Legacy regression.** Add new packages and entrypoints incrementally. Do not delete the working client comparison path without explicit user approval.

## 14. Definition of done

The mod pack is complete only when an operator can install it, start the coordinator with existing Codex OAuth, summon differently configured NPC agents, see correct skins and nametags, control each with the complete `/codex` command tree, run concurrent persistent goals, and verify safe stop/steer/queue/restart behavior across the configured cap. Static correctness alone will not satisfy final completion, but live verification remains intentionally deferred for now.
