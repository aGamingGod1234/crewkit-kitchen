# Real-provider headless matrix verification

Date: 2026-08-19 (Asia/Singapore)

This report records the final offline gates and the opt-in live-provider preflight for the headless matrix runner. No provider prompt, model output, OAuth state, RCON password, or bridge secret is included here.

## Implemented path

- Commit under test: `ebbf95a` (`Forward headless CLI evidence dependencies`).
- Built mod: `build/libs/arena-agents-0.1.0.jar`.
- JAR SHA-256: `15D18573C8EBBD13FAF91BC0224D48F2B4E111CC7D7B026EE3A2D234A26D81AF`.
- The real-provider command uses the production coordinator/provider adapters and a dedicated Fabric server. The offline tests do not substitute scripted provider profiles or inject Minecraft action results.

## Completed verification

| Check | Result |
| --- | --- |
| `coordinator/npm test` | PASS, 372 tests, 0 failures |
| `gradlew verifyCore --no-daemon --console=plain` | PASS, 6,103 protocol/bridge assertions |
| `gradlew clean check build verifyCore --no-daemon --console=plain` | PASS, including `verifyEntrypoints`, `verifyCore`, tests, and JAR packaging |
| PowerShell parser checks | PASS for both headless wrapper scripts |
| `test-run-headless-provider-matrix.ps1 -SetupFailureOnly` | PASS, 5 cleanup/bounds checks |
| `test-run-headless-provider-matrix.ps1` | PASS, 7 lifecycle, cleanup, port, and isolation checks |
| `npm run headless:matrix -- --help` | PASS, secret-free usage output |
| `git diff --check` | PASS |

The wrapper lifecycle suite verifies that allocated listeners and tracked process descendants are cleaned up. It uses local fixtures and does not contact an external provider.

## Opt-in real-provider smoke preflight

The exact Codex smoke command was attempted with `--RequireAll`. It stopped before provider preflight because the prepared Fabric server template is absent:

`runtime/server-template` and `runtime/server` are not present, so there is no `fabric-server-launch.jar` to start.

The local CLI inventory was checked without sending a model task:

- Codex CLI: installed (`codex-cli 0.147.0`); local login status reports ChatGPT authentication.
- Kimi CLI: installed (`0.29.1`).
- Antigravity/Gemini CLI (`agy`): unavailable.

Therefore no real-provider scenario was claimed as passed, and no external model request was made. To complete the live smoke later, prepare the isolated server template with `scripts/prepare-runtime.ps1` (and its documented world/Fabric prerequisites), then rerun the documented Codex command. Use `--require-all` when a missing provider or failed assertion must fail the run.
