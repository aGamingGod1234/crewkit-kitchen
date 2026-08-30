# Arena Agents: summonable AI-controlled players for Minecraft

Arena Agents is a Fabric + Carpet mod pack and local coordinator for Minecraft Java 26.1.2. It adds offline fake players controlled by Codex, Antigravity CLI (shown as Gemini in Minecraft), Kimi CLI, or the native Windows Cursor CLI. Each backend reuses its existing local login; the mod stores no provider API keys.

Each agent is a real `ServerPlayer` with vanilla collision, gravity, health, hunger, inventory, and Survival/Creative/Adventure capabilities. It also has its own provider, model, thinking setting, planner session, provider-scoped working directory, goal queue, lifecycle, readable model name, and provider-themed client skin.

The coordinator uses one model-authored ArenaScript control path for every summonable NPC. The former fixed two-client planner is retired and is not a runtime fallback.

## Safety boundaries

- The authenticated bridge binds only to `127.0.0.1:25570` and accepts bounded, schema-validated Minecraft actions, not shell or filesystem tools.
- A shared secret of at least 32 characters is stored in `runtime\bridge-secret.txt`, passed to the server by file path, and exposed to the coordinator only through its process environment.
- Goal revisions and action IDs prevent late Codex turns or delayed Minecraft results from controlling a newer goal.
- Stop, steer, remove, disconnect, timeout, and shutdown paths cancel outstanding work.
- The default scheduler permits all 16 registered agents to plan concurrently. Each agent still has at most one provider turn and one physical world action in flight.
- The original world at `%APPDATA%\.minecraft\saves\New World (76)` is never opened by preparation or automated verification; scripts work from a copied runtime world.

## Build and automated verification

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

This uses the project-local Java 25 toolchain, runs the Java/Fabric verification suite and build, then runs the dependency-free coordinator tests. Live launcher/gameplay validation is documented separately below.

For the heavier eight-agent reliability gate, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1
```

This repeats the isolated eight-agent soak 50 times after the clean verifier. The summonable-agent path permits 16 concurrent planner turns, sends at most eight queued observations per server tick, caches expensive spatial sections for 10 ticks, and throttles action progress to a material 5% change or a one-second heartbeat. Each provider turn returns one ArenaScript envelope; the local interpreter schedules one authorized physical primitive at a time. Optional bounded `coordinator_status.latencies` rows expose sample count, p50, and p95 durations without prompts, observations, model output, or credentials. See [the measured headless report](docs/plans/2026-08-11-eight-agent-performance-reliability-report.md).

## Prepare the isolated mod-pack runtime

Close Minecraft and the official launcher, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install-launcher-profiles.ps1
```

Preparation is repeatable and verifies copied world artifacts before reuse. See [runtime/README.md](runtime/README.md) for the isolated installation layout.

The Reliability workflow publishes a verified `arena-agents-modpack-0.2.0` artifact. Build the same self-contained archive locally with `.\gradlew.bat packageWindowsDistribution`; it is written to `build\distributions\arena-agents-modpack-0.2.0.zip`. Its installer creates a separate `%APPDATA%\.minecraft-arena-agents` launcher profile, generates a local bridge secret, and copies Arena Agents, Fabric API, Fabric Carpet, the coordinator, and its pinned Node.js runtime without packaging credentials, worlds, logs, or secrets.

## Run summonable NPC mode

Start the server and dynamic coordinator in separate terminals:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1
```

The coordinator checks the local provider CLIs, publishes a provider-aware model catalog, and multiplexes all NPCs over one authenticated Minecraft bridge. Join `127.0.0.1:25565` with the Arena Agents Fabric profile and use operator commands:

```text
/codex summon
/codex summon <model> <reasoning> [name]
/codex summon gemini <model> <thinking> [name]
/codex summon kimi <model> <thinking> [name]
/codex summon cursor <model> <thinking> [name]
/codex summon-configured <provider> <model> <thinking> <speed_mode> <game_mode> [name]
/msg <online-agent-name> <message>
/codex start <agent> <prompt>
/codex stop <agent>
/codex resume <agent>
/codex queue <agent> <prompt>
/codex steer <agent> <prompt>
/codex group save <group-name> <agent-uuid> [agent-uuid...]
/codex group spawn <group-name>
/codex group delete <group-name>
/codex status [agent]
/codex list
/codex remove <agent>
```

`/codex summon` and the legacy two-argument form remain Codex-compatible and default to `gpt-5.6-luna` with `xhigh` reasoning in Fast mode. The command center consumes the live Codex app-server catalog, Antigravity's installed `agy models` list, Kimi's installed provider aliases, and Cursor's native `agent models` catalog; a bounded offline catalog is used only when discovery is unavailable. Cursor is intentionally restricted to Composer 2.5, Grok 4.5, and Grok 4.6. Kimi's `kimi-for-coding` aliases are shown by their current CLI names, `K2.7 Coding` and `K2.7 Coding Highspeed`. Player-facing speed choices are `Normal` and `Fast mode`; provider-native wire values such as Codex `priority` stay internal. A newly summoned NPC remains idle until `/codex start`; `stop` freezes its active work, `queue` preserves later goals, and `steer` interrupts the current plan and applies the new instruction at a higher revision.

Every NPC receives a stable directory beneath `runtime/agent-workspaces/<provider>/<agent-id>`. Codex keeps the efficient shared app-server but starts each isolated thread with that NPC's directory. Each Gemini decision runs one cancellable, sandboxed Antigravity print process in that NPC's directory; its visible model and thinking map to one exact CLI model ID such as `gemini-3.1-pro-low`. Kimi retains one isolated ACP process and session per NPC. Kimi effort is process-scoped and works with ACP sessions that expose exact levels, only an on/off thinking switch, or no thinking switch. Cursor runs its native `agent.ps1` launcher in read-only ask mode, supplies the prompt over standard input, and resumes the isolated agent session on later turns. Windows launches omit Cursor's unsupported sandbox flag; supported non-Windows launches enable it. Player-facing Composer and Grok settings map to exact native IDs such as `composer-2.5-fast` and `cursor-grok-4.6-high-fast`. No OAuth state or bridge secret is copied into an agent directory.

Invalid ArenaScript source receives bounded compiler diagnostics and a corrective turn from the same selected provider/model/session. Repeated physical-action failures remain bounded factual evidence for the next model decision; they never make the runtime choose to abandon the goal. Kimi reads the existing `~/.kimi-code` OAuth state and receives its effort through an isolated process environment. Gemini reuses the existing Antigravity login through `agy`; missing authentication, unavailable models, oversized Windows command-line prompts, timeouts, and output overflow fail closed for only that NPC.

Press the configured Agent Controls key (`G` by default) in a world to open the custom Field Console. Agent creation, one-at-a-time configuration, individual tasks, saved groups, lifecycle controls, arena construction, and live match telemetry use separate workspaces behind persistent `Agents`, `Group`, `Live`, and `Build` navigation. The Group workspace saves an ordered roster of stable agent identities, so spawning it restores the same characters without duplicating agents that are already present. Direct messages stay in Minecraft's native `/msg`, `/tell`, and `/w` flow; the mod routes messages addressed to online agents into their private conversation memory and mirrors them to operators. The console renders its own flat controls, text fields, confirmation surface, selection rows, no-shadow labels, and mod-local Roboto typography instead of exposing Minecraft's default button grid or pixel type. Full-row selection, explicit `SELECTED` / `IN GROUP` labels, keyboard focus outlines, scroll-safe 320x240 layouts, and plain status copy keep the current target unmistakable.

Arena setup defaults to building 80 blocks in front of the operator so construction remains visible; exact server coordinates are published before the reset job starts. `At my position` and deterministic fixed lanes remain available. World mutation is deliberately paced while bookkeeping and verification retain their higher bounded throughput, so the arena visibly grows instead of appearing all at once. The build dashboard reports the current phase, processed work, successful world changes, exact origin, actionable failures, and retry state. Each preset authors only the contestant stations, building plots, or parkour lanes required by the configured roster and adds a connected operator observation deck. Parkour lanes progress through easy, medium, hard, and expert sections; decorated checkpoints save progress, and lava deaths respawn Adventure-mode participants at their latest checkpoint. The compact in-world HUD shows four readable health/score cards, while the Live Arena workspace exposes the full scrollable roster and meaningful activity feed. Authored-map research and attribution are recorded in [MAP-SOURCES.md](MAP-SOURCES.md).

## Visual release validation

The current offline-player release is covered by the headless Java/Fabric and coordinator verification suites. Live Minecraft gameplay and provider-latency acceptance remain pending; headless checks do not establish live success.

## Current action surface

The server-authoritative executor drives Carpet's real player action pack for movement, looking, jumping, attacking, item use, and block interaction. Observations include vanilla HUD/player state, inventory/equipment, visible nearby entities and blocks, world state, the active action, and the last result. Entity and block facts are gated by the current view and line of sight; hidden creature health and other server-only combat facts are not exposed as sight. Damage publishes a fresh factual observation without cancelling the current action or choosing fight, flight, or replanning for the model. Death is reconciled into a persistent `DEAD` lifecycle state and remains under the selected model's coordinate-free respawn primitive.

Container transfers, crafting recipes, and furnace transactions use server-authoritative, fail-closed adapters with inventory-conservation checks and rollback paths. Crafting expands Minecraft's trimmed recipe remainders back into the full grid before validating ownership, including horizontally and vertically offset recipes. Forced chunk tickets remain fail-closed until their Minecraft 26.1.2 adapter is runtime-validated; unsupported operations never report false success.

## ArenaScript control boundary

The selected provider, model, reasoning effort, and service tier own gameplay strategy, program source, watcher conditions, interruption policy, fallbacks, and respawn decisions. The coordinator only validates the envelope, compiles the source, evaluates it in the local interpreter, and enforces goal/version/provenance fences. Invalid source is returned to that same selected model with bounded compiler diagnostics; no heuristic or alternate model supplies a replacement.

The interpreter has no shell, filesystem, network, credential, or ambient Minecraft authority. Its exact `SCRIPT_PRIMITIVES` set is:

```text
move_to, navigate_to, look_at, attack, select_item, use_item, break_block, place_block, chat, wait, set_door, drop_item, transfer_container, craft_inventory, craft_table, furnace_transaction, equip_item, select_tool, block_with_shield, use_ranged, respawn
```

Model-authored `repeatUntil`/`watch` loops and factual queries replace high-level fight, flee, follow, pickup, and build controllers; the runtime does not synthesize those strategies. Every command carries the agent, goal revision, program/version, source step, event sequence, and selected-model provenance before Minecraft accepts it.

Death preserves the logical agent and goal while suspending its program. The selected model receives vanilla death facts and must author `await player.respawn()`; the mod follows vanilla respawn, inventory, experience, drop, bed/anchor, world-spawn, and gamerule behavior without an operator or runtime fallback.

Diagnostics keep provider inference, event publication, interpreter selection, bridge send/acceptance, and physical completion as separate timing segments. In particular, `event_receipt_to_branch`, `branch_to_bridge_send`, `command_to_first_progress`, `action_completion`, and provider-turn duration are never combined into a live-success claim. Public traces store source hashes; bounded source is restricted to the agent-private diagnostic trace, with credentials and tokens redacted.
