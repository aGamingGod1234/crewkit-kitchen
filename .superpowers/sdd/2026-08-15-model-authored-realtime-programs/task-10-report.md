# Task 10 report: model-authored real-time verification gate

Status: headless verification complete; live Minecraft acceptance pending Lucas.

## Delivered

- Added `scripts/verify-model-authored-programs.ps1`, a fail-closed one-command gate for the exact ArenaScript facts/interpreter/parser/program-engine tests, program manager tests, protocol-v2 tests, model-authored E2E scenarios, and Java `verifyCore`.
- The gate parses the E2E `TASK10_E2E_SUMMARY` JSON and prints every segmented local/provider p50/p95. The summary basis is checked and printed as `deterministic_fake_clock`; all values are labeled synthetic fixture timing and never presented as live measured latency.
- Integrated the gate into `scripts/run-performance-reliability-verification.ps1` immediately before the eight-agent soak loop. Nested Java stderr warnings are captured without masking the gate's real exit code.
- Added the live QA sheet with exact identity/hash, selected-model settings, per-command provenance, segmented latency fields, operator checkboxes, authority-audit evidence, and explicit pending-Lucas/live limitations.

## Fresh verification

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File '.\scripts\verify-model-authored-programs.ps1' -ProjectRoot 'C:\Users\aGamingGod\Desktop\Projects\agent arena'
```

Result: 140/140 focused Node tests passed; 10/10 E2E scenario records passed across 11 E2E tests; Java `verifyCore` passed with `6019 protocol and bridge assertions`.

Full performance command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File '.\scripts\run-performance-reliability-verification.ps1' -ProjectRoot 'C:\Users\aGamingGod\Desktop\Projects\agent arena' -SoakRuns 50
```

Result: automated Java/Fabric/coordinator/fake-E2E verification passed, Task 10 gate passed, and 50/50 eight-agent soak runs passed in 73.0 seconds.

Synthetic fixture timing parsed from the E2E summary:

- Local `action_completion`: count 20, p50 2 ms, p95 2 ms.
- Local `branch_to_bridge_send`: count 3, p50 1 ms, p95 1 ms.
- Local `command_to_first_progress`: count 20, p50 1 ms, p95 1 ms.
- Local `event_receipt_to_branch`: count 3, p50 3 ms, p95 3 ms.
- Local `minecraft_change_to_publication`: count 5, p50 1 ms, p95 1 ms.
- Provider `provider_inference`: count 2, p50 4 ms, p95 4 ms.

These are deterministic fake-clock values, not live Minecraft or provider measurements.

## Hash evidence

- Built JAR SHA-256 from the full verifier build: `1294372DE8822EBDCD20C813A0CF38636B2C2F15C268849E4A257464F074B910`.
- Installed JAR parity: not performed; no Minecraft install or launch was requested.
- `coordinator/src/dynamic-main.mjs`: `66890AAA08380E7DA5574C23635B8CF750718BE15B3DC44A9865703EC0C55F8E`.
- `coordinator/src/program-runtime-manager.mjs`: `426D3543D005EEF89B484E0E8D3A16E63131255BF7F9DF0250B59AC575913CCA`.
- `coordinator/src/decision-parser.mjs`: `C3FB1186A653104B5731953797EB3108042A810312E3C57D0D67AB38E4BF9CF6`.
- `coordinator/src/prompts.mjs`: `8AD301A54097BC4338A9AC311564C550E80EC93F4896681E4BACF3408D2314A9`.

## Authority audit

Fresh command:

```powershell
rg -n "action_command|actionExecutor\.submit|fight_target|flee_from|pick_up_item|build_sequence|respawn" coordinator/src src/main/java/dev/agaminggod/arenaagents/server
```

Evidence: `coordinator/src/program-runtime-manager.mjs:380` sends the interpreter command as `action_command`; `src/main/.../MultiplexedServerBridge.java:467` enters `submitProgramPrimitive`; bridge validation at lines 510 and 555-557 checks the exact payload, provenance/revision, and dead-state rule. The normal search found no `fight_target`, `flee_from`, `pick_up_item`, or `build_sequence` runtime strategy dispatch names. Respawn references are limited to the explicit ArenaScript API and lifecycle reconciliation paths. This source audit cannot establish running-client behavior or live latency.

## Live boundary

Live Minecraft observations, visible physical outcomes, provider availability, exact selected-model settings from the running UI, installed-JAR parity, and live latency remain pending Lucas. Headless verification is not live acceptance and is not reported as such.
