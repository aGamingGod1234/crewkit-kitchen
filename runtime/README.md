# Isolated runtime layout

Everything generated beneath `runtime/` is local evidence or executable state and is ignored by Git except this file.

## Directories

- `server/`: Fabric 0.19.3 online-mode server and verified copy of `New World (76)`.
- `server-offline-smoke/`: optional, clearly labeled offline smoke server; never authenticated evidence.
- `downloads/`: SHA-256-verified Fabric installer.
- `evidence/`: world-copy, build, log, screenshot, and live-test summaries with credentials excluded.
- `agent55/traces/agent-55.jsonl` and `agent56/traces/agent-56.jsonl`: separate append-only coordinator traces.
- `bridge-secret.txt`: generated local bridge secret shared by the summonable-NPC server and dynamic coordinator; ignored by Git.
- `toolchains/temurin-25/jdk-25.0.3+9`: project-local Java runtime.

The isolated client directories are outside the project:

- `%APPDATA%\.minecraft-agent-55`
- `%APPDATA%\.minecraft-agent-56`

Only Fabric API and the final Arena Agents JAR are copied into their `mods` directories.

## Official launcher installations

After `prepare-runtime.ps1` installs `fabric-loader-0.19.3-26.1.2`, create two installations through the official launcher UI:

### Arena Agent 55 (GPT-5.5 xhigh Fast)

- Version: `fabric-loader-0.19.3-26.1.2`
- Game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-55`
- Java executable: `C:\Users\lucas\Desktop\minecraft\5.5 vs 5.6\runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\javaw.exe`
- JVM arguments: `-Xms1G -Xmx4G`

### Arena Agent 56 (GPT-5.6-Sol high Fast)

- Version: `fabric-loader-0.19.3-26.1.2`
- Game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-56`
- Java executable: same project-local `javaw.exe`
- JVM arguments: `-Xms1G -Xmx4G`

Do not add account identifiers or copy launcher credential files. Both installations share launcher-managed assets/libraries but isolate mods, config, logs, saves, and options.

## Summonable NPC mode

The summonable NPC architecture runs inside the server rather than consuming a licensed account per agent. Use either isolated Fabric client installation to join the server, then start:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1
```

Both scripts use `runtime\bridge-secret.txt`. The server receives only its absolute file path; the coordinator receives the secret in its process environment. Keep that file local and never attach it to evidence.

The Fabric mod must be present on both the server and joining client because the custom NPC entity and renderer are mod-defined. Unlike the legacy two-player arena, all summoned NPCs share one server bridge. Codex NPCs retain separate Codex threads on the shared app server. Gemini NPCs use cancellable sandboxed Antigravity CLI print processes with exact combined model/thinking IDs, while Kimi NPCs retain separate ACP processes and sessions. Every provider uses a stable per-agent directory under `runtime/agent-workspaces`, keeping cwd, cancellation, and per-agent thinking settings isolated without copying credentials.

## Evidence classes

Keep these categories distinct in `runtime/evidence/live-test-summary.json`:

1. Automated Java/Node/fake-bridge verification.
2. Sequential single-client authenticated testing for each model profile.
3. Offline two-client smoke, if used.
4. Simultaneous authenticated two-player testing, which requires two licensed accounts and distinct UUIDs.

If only one account is available, record `BLOCKED_SECOND_LICENSED_ACCOUNT` for category 4 without weakening online mode.
