# Arena Agents: summonable AI-controlled players for Minecraft

Arena Agents is a Fabric + Carpet mod pack and local coordinator for Minecraft Java 26.1.2. It adds offline fake players controlled by Codex, Antigravity CLI (shown as Gemini in Minecraft), or Kimi CLI. Each backend reuses its existing local login; the mod stores no provider API keys.

Each agent is a real `ServerPlayer` with vanilla collision, gravity, health, hunger, inventory, and Survival/Creative/Adventure capabilities. It also has its own provider, model, thinking setting, planner session, provider-scoped working directory, goal queue, lifecycle, readable model name, and provider-themed client skin.

The earlier fixed GPT-5.5-versus-GPT-5.6 two-client arena mode remains available through the legacy scripts and `/arenaagent` commands. It is separate from the summonable NPC mode described below.

## Safety boundaries

- The authenticated bridge binds only to `127.0.0.1:25570` and accepts bounded, schema-validated Minecraft actions—not shell or filesystem tools.
- A shared secret of at least 32 characters is stored in `runtime\bridge-secret.txt`, passed to the server by file path, and exposed to the coordinator only through its process environment.
- Goal revisions and action IDs prevent late Codex turns or delayed Minecraft results from controlling a newer goal.
- Stop, steer, remove, disconnect, timeout, and shutdown paths cancel outstanding work.
- The default scheduler permits four concurrent planner turns across all providers. The default persistent registry cap is 16 agents with 32 queued goals per agent.
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

This repeats the isolated eight-agent soak 50 times after the clean verifier. The summonable-agent path keeps the default four concurrent planner turns, sends at most two queued observations per server tick, caches expensive spatial sections for 10 ticks, and throttles action progress to a material 5% change or a one-second heartbeat. Optional bounded `coordinator_status.latencies` rows expose sample count, p50, and p95 durations without prompts, observations, model output, or credentials. See [the measured headless report](docs/plans/2026-08-11-eight-agent-performance-reliability-report.md).

## Prepare the isolated mod-pack runtime

Close Minecraft and the official launcher, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install-launcher-profiles.ps1
```

Preparation is repeatable and verifies copied world artifacts before reuse. See [runtime/README.md](runtime/README.md) for the isolated installation layout.

The standalone release is [dist/arena-agents-modpack-0.1.0.zip](dist/arena-agents-modpack-0.1.0.zip). Its installer creates a separate `%APPDATA%\.minecraft-arena-agents` launcher profile, generates a local bridge secret, and copies Arena Agents, Fabric API, and Fabric Carpet without packaging credentials, worlds, logs, or secrets.

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
/codex start <agent> <prompt>
/codex stop <agent>
/codex resume <agent>
/codex respawn <agent>
/codex queue <agent> <prompt>
/codex steer <agent> <prompt>
/codex status [agent]
/codex list
/codex remove <agent>
```

`/codex summon` and the legacy two-argument form remain Codex-compatible and default to `gpt-5.6-sol` with `high` reasoning. Gemini defaults to `gemini-3.1-pro` plus `high`; the GUI also exposes `gemini-3.6-flash` and `gemini-3.5-flash` with the exact thinking levels installed by Antigravity. Kimi defaults to `kimi-code/k3` plus `high`, with exact `low`, `high`, and `max` effort isolation for K3. Agent arguments support command suggestions from the persistent registry. A newly summoned NPC remains idle until `/codex start`; `stop` freezes its active work, `queue` preserves later goals, and `steer` interrupts the current plan and applies the new instruction at a higher revision.

Every NPC receives a stable directory beneath `runtime/agent-workspaces/<provider>/<agent-id>`. Codex keeps the efficient shared app-server but starts each isolated thread with that NPC's directory. Each Gemini decision runs one cancellable, sandboxed Antigravity print process in that NPC's directory; its visible model and thinking map to one exact CLI model ID such as `gemini-3.1-pro-low`. Kimi retains one isolated ACP process and session per NPC. No OAuth state or bridge secret is copied into an agent directory.

Malformed planner JSON receives one bounded corrective retry before the NPC enters `ERROR`; failed or rejected Minecraft actions are never retried blindly. Kimi reads the existing `~/.kimi-code` OAuth state and receives its effort through an isolated process environment. Gemini reuses the existing Antigravity login through `agy`; missing authentication, unavailable models, oversized Windows command-line prompts, timeouts, and output overflow fail closed for only that NPC.

Press `G` in a world to open the Arena Agent Control Center. The remappable hotkey exposes provider/model/thinking selection, optional names, agent selection, prompt entry, and Start, Queue, Steer, Stop, Resume, Remove, Refresh, and Done controls. The layout compacts vertically on small launcher windows so every action remains visible.

## Visual release validation

The earlier entity-backed release was visually tested in the isolated `Arena Agents Visual QA` world. The current offline-player release has passed the full nonvisual Java/Fabric verification suite and all coordinator tests; it has not been visually relaunched during this patch.

## Current action surface

The server-authoritative executor drives Carpet's real player action pack for movement, looking, jumping, attacking, item use, and block interaction. Observations include vanilla health, hunger, saturation, game mode, last attacker, inventory/equipment, nearby threats/players/blocks, world state, the active action, and the last result. Damage interrupts long actions so the model can immediately reassess fight or flight. Death is reconciled into a persistent `DEAD` lifecycle state; `/codex respawn` creates a replacement offline player with the same logical agent identity.

Container transfers, crafting recipes, furnace transactions, and forced chunk tickets remain fail-closed until their Minecraft 26.1.2 transaction adapters can be validated during the later runtime-testing phase; they never report false success.

## Legacy two-client arena mode

The original isolated-player comparison still uses:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-coordinator.ps1
```

and `/arenaagent goal <player> ...`. It requires distinct licensed Java accounts for simultaneous authenticated players; the runtime does not disguise offline duplicates as authenticated evidence.
