# Arena Agents: GPT-5.5 vs GPT-5.6-Sol

This project runs two proactive Minecraft Java 26.1.2 clients through one universal Fabric mod and a dependency-free local Node coordinator. The coordinator uses the authenticated local Codex app server; no API key is stored in the mod or runtime files.

The fixed comparison profiles are:

| Agent | Model | Reasoning | Service tier | Bridge |
| --- | --- | --- | --- | --- |
| `agent-55` | `gpt-5.5` | `xhigh` | Fast | `127.0.0.1:25571` |
| `agent-56` | `gpt-5.6-sol` | `high` | Fast | `127.0.0.1:25572` |

Both agents use identical code, prompts, action schemas, observation limits, timeouts, and recovery behavior. Only model/reasoning identity, player/account identity, port, and isolated runtime paths differ.

## Safety boundaries

- The bridge binds to loopback only and exposes validated Minecraft actions, never shell or file tools.
- The original world at `%APPDATA%\.minecraft\saves\New World (76)` is never opened by automated tests. Preparation makes a verified copy under `runtime\server\world`.
- Launcher profile installation preserves every existing entry, adds only the two isolated Arena Agent profiles, and writes a local backup before changing launcher JSON.
- The final server remains `online-mode=true`. Offline smoke evidence is labeled separately and is never presented as authenticated verification.
- Runtime traces and evidence are ignored by Git and must not contain launcher or Codex credentials.

## Build and automated verification

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

The script uses the project-local Java 25 toolchain, runs the Fabric/Java verification suite and build, then runs all dependency-free Node tests including the fake bridge end-to-end test.

## Prepare isolated runtime

Close Minecraft and the official launcher, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install-launcher-profiles.ps1
```

The second run must reuse the verified world copy, Fabric installation, configs, and mods without duplicating them. See [runtime/README.md](runtime/README.md) for the launcher installations and live-test sequence.

## Run

In separate terminals:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-coordinator.ps1
```

Launch the isolated official-launcher installation, join `127.0.0.1:25565`, then issue an operator command such as:

```text
/arenaagent goal <player> Prepare for the arena, survive, find useful equipment, and defeat the opposing agent.
```

The user supplies the creative goal; the runtime continues planning and acting without a new user prompt after every action. `/arenaagent stop <player>` cancels the active action and releases synthetic inputs immediately.

## Authentication limitation

Sequential authenticated testing works with one licensed Minecraft account. A simultaneous genuine two-player online-mode fight requires two distinct licensed Java accounts. The runtime never substitutes offline-mode duplicates and calls that authenticated evidence.
