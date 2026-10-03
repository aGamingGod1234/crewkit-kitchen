The final jar passed all four provider-free headless Minecraft checks on 2026-10-02. The probe used the public Minecraft tool normalizer, the real `NativeToolRuntime`, the production observation adapter, and the authenticated bridge to the installed Fabric mod. No AI provider, visible app, or remote device was used.

The installed `arena-agents-0.2.0.jar` SHA-256 was `1cc6dbda2c338c70563c977b27a33ca6bce2c2efd53aefacb40f609e58f4f4eb`. All 29 files in the production runtime import graph matched the files bundled in that jar byte for byte. The probe itself is a deterministic authorship fixture; its registered profile was Codex / GPT-6.1 Sol / medium / fast.

| Check | Result |
| --- | --- |
| Parameterized routine plus queued successor | The same source executed two parameter sets in separate programs. All four wait/look actions succeeded. Source and expected/actual action hashes matched; profile provenance stayed unchanged. The predecessor and successor each kept their own action and timeout bounds. |
| Fresh false guard | The queued guard was initially true at world tick 87, with threshold 92. A passive authoritative sample at handoff reported tick 114, so the successor was rejected without dispatching an action. |
| Exactly true prerequisite | A guard returning health `20` was rejected as `SUCCESSOR_PRECONDITION_FALSE`. |
| Optional exact-target mining | Default mining while facing away returned `TARGET_NOT_VISIBLE`. `autoAim:true` dispatched `look_at` at the chosen block center followed by `break_block` with the original coordinates and block ID. RCON confirmed that block became air and its adjacent oak-log decoy remained. A real `pick_up_item` action collected one oak log, confirmed by fresh inventory facts and RCON. |

The observed successor gap was 77.435 ms, measured from receipt of the predecessor's final action result to dispatch of the successor's first action. This is one physical mechanics sample. It does not measure provider latency or establish an AI speedup.

The final run was `run-1790914640321-5aa1a0d8`. The wrapper reported `CLEAN` process and listener cleanup, removed its generated server/world/private artifacts, and retained only small local reports. Peak tracked RSS was 1,432,248,320 bytes. Earlier attempts stopped on probe setup or evidence assertions and are retained under ignored runtime directories; production code was unchanged.

The compact evidence is [native-successor-live-evidence.json](native-successor-live-evidence.json), and the bundle comparison is [native-successor-live-source-manifest.json](native-successor-live-source-manifest.json). The executable probe is [native-successor-live.mjs](../../coordinator/src/benchmark/native-successor-live.mjs).

The local run reused a copy of `runtime/proximity-goal-harness/run-proximity-goal.ps1` under `runtime/action-speed-harness`, changing only its probe path and run-directory boundary. With the existing prepared `runtime/proximity-goal-server-template`, the successful entry point was:

```powershell
$env:ARENA_HEADLESS_JAVA = 'C:\Program Files\Java\jdk-25\bin\java.exe'
powershell -NoProfile -ExecutionPolicy Bypass -File runtime/action-speed-harness/run-native-successor-live.ps1 -ProjectRoot . -MatrixPath runtime/action-speed-harness/matrix.json -ScenarioId native-successor-live -ServerTemplate runtime/proximity-goal-server-template -CapabilityProbe
```

The probe can also run directly against a prepared isolated server using its `--run-directory`, `--rcon-host 127.0.0.1`, `--rcon-port`, `--rcon-password-file`, `--bridge-port`, `--bridge-secret-file`, and `--timeout-ms` arguments. Its run directory must contain the server's `server/mods` directory so it can hash the actual installed jar. The source server template was left unchanged.
