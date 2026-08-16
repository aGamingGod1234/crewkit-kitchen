# Model-authored real-time programs live QA

Date prepared: 2026-08-16
Implementation baseline: `79d10e66bd9cb4b3541720777e4c0997a49f7379` (Task 10 E2E)

## Acceptance boundary

This is an operator-run live acceptance sheet. Lucas must restart Minecraft, operate the Field Console, and record the visible result for every checked case. Headless Java, coordinator, fake-bridge, and soak results are not live Minecraft evidence and must not be copied into the live-result column.

Live Minecraft observations are **pending Lucas**. No live gameplay, live provider latency, or physical outcome is claimed by this document.

## Exact build and coordinator identity

Record these values from the same run. Do not record secrets, access tokens, or full private prompts.

| Field | Value | Evidence / command |
| --- | --- | --- |
| Git commit | `79d10e66bd9cb4b3541720777e4c0997a49f7379` baseline; update after the verification slice commit | `git rev-parse HEAD` |
| Mod JAR path | `build/libs/arena-agents-0.1.0.jar` | `Get-FileHash -Algorithm SHA256` |
| Mod JAR SHA-256 | `1294372DE8822EBDCD20C813A0CF38636B2C2F15C268849E4A257464F074B910` | Fresh full verifier build output; not installed |
| Installed JAR SHA-256 | **Pending Lucas live run** | Must equal build JAR; record both |
| Coordinator distribution path/hash | **Pending exact distribution staging** | Record each distributed file or archive hash |
| Coordinator source `dynamic-main.mjs` SHA-256 | `66890AAA08380E7DA5574C23635B8CF750718BE15B3DC44A9865703EC0C55F8E` | Current source hash |
| Coordinator source `program-runtime-manager.mjs` SHA-256 | `426D3543D005EEF89B484E0E8D3A16E63131255BF7F9DF0250B59AC575913CCA` | Current source hash |
| Decision parser SHA-256 | `C3FB1186A653104B5731953797EB3108042A810312E3C57D0D67AB38E4BF9CF6` | Current source hash |
| Planner prompt SHA-256 | `8AD301A54097BC4338A9AC311564C550E80EC93F4896681E4BACF3408D2314A9` | Current source hash |

## Exact selected-model settings

Record the values shown in the UI and the coordinator trace for each agent. The same selected model/session must own initial source, correction, attention turns, and respawn decisions.

| Agent ID | Provider | Model | Reasoning effort | Service tier / speed | Provider session identity | Recorded by |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |

## Per-case provenance and segmented latency record

For every emitted physical command, record the exact wire values below. `authority` must identify model-authored program execution; a missing or changed provenance tuple is a failure.

| Case | Agent ID | Goal revision | Program ID/version | Source SHA-256 | Source step ID | Event sequence | Action ID/type | Authority | Result / visible outcome |
| --- | --- | ---: | --- | --- | --- | ---: | --- | --- | --- |
|  |  |  |  |  |  |  |  |  |  |

Record latency segments separately in milliseconds. Provider inference and physical action duration are excluded from local reaction latency.

| Case / event | Provider inference | Fact change -> publication | Event receipt -> branch | Branch -> bridge send | Command -> first progress | Action completion | Fact change -> command | Server tick budget result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
|  |  |  |  |  |  |  |  |  |

Acceptance target: local fact-change-to-command median within one server tick and p95 within two server ticks on the supported machine. Record the sample count, p50, and p95 for each segment, not only a single best case.

## Lucas live checklist

- [ ] Restart Minecraft using the newly built/installed mod and exact coordinator distribution.
- [ ] Confirm the selected provider, model, reasoning effort, service tier, agent ID, and session identity in the UI/logs.
- [ ] Gathering: collect logs across two trees and verify the model-selected drop enters pickup range.
- [ ] Building / placement: execute a model-authored placement and verify disappearing support reports a typed failure.
- [ ] Combat / damage: apply damage and verify the matching authored watcher reacts without a provider turn.
- [ ] Unmatched attention, continue: verify the selected model receives the attention fact and the authored policy continues.
- [ ] Unmatched attention, pause: verify the selected model receives the attention fact and the authored policy pauses.
- [ ] Falling and lava: verify pre-authored watchers react locally without another provider turn.
- [ ] Path failure and timeout: verify typed failure is visible to the model program and no replacement destination is chosen locally.
- [ ] Death: verify logical agent/session/goal retention and no automatic replacement decision.
- [ ] Respawn: verify only the selected model-authored `player.respawn()` command performs vanilla respawn.
- [ ] Invalid source correction: verify the same selected model/provider/session supplies the correction.
- [ ] Coordinator restart: verify reconnect/reconciliation retains exact agent identity and rejects stale provenance.
- [ ] Inspect logs/traces after each case and fill the provenance and latency tables above.
- [ ] Record Lucas's visible physical outcome for each case; do not infer it from headless output.

## Headless verification evidence (not live acceptance)

Fresh Task 10 gate evidence from the repository checkout:

- Focused Node gate: 140/140 tests passed, including ArenaScript facts/interpreter/parser/program-engine, manager, protocol, and E2E files.
- E2E scenarios: 10/10 scenario records passed across 11 E2E tests.
- Java `verifyCore`: `PASS: 6019 protocol and bridge assertions`.
- Full performance/reliability verifier: passed the clean automated verifier, this Task 10 gate, and `50/50` eight-agent soak runs in `70.9s`.
- E2E timing basis: `deterministic_fake_clock`; local and provider p50/p95 values are synthetic fixture timing and are **not measured live latency**.
- Synthetic local segments: `action_completion` 20 samples p50 2 ms / p95 2 ms; `branch_to_bridge_send` 3 samples p50 1 ms / p95 1 ms; `command_to_first_progress` 20 samples p50 1 ms / p95 1 ms; `event_receipt_to_branch` 3 samples p50 3 ms / p95 3 ms; `minecraft_change_to_publication` 5 samples p50 1 ms / p95 1 ms.
- Synthetic provider segment: `provider_inference` 2 samples p50 4 ms / p95 4 ms.
- Real monotonic-clock manager benchmark: `branch_to_bridge_send` 1,000 samples p50 0.0118 ms / p95 0.0372 ms; `event_receipt_to_branch` 1,000 samples p50 0.1072 ms / p95 0.3966 ms. These values come from `performance.now()` in the existing 1,000-branch benchmark, not from Minecraft or a provider.

These results establish headless protocol/interpreter behavior only. They do not establish live Minecraft behavior, physical outcomes, model-provider availability, or live latency.

## Authority audit record

Run after implementation and again after any live fix:

```powershell
rg -n "action_command|actionExecutor\.submit|fight_target|flee_from|pick_up_item|build_sequence|respawn" coordinator/src src/main/java/dev/agaminggod/arenaagents/server
```

Record matching files/lines and limitations here. Expected result is that normal physical dispatch is provenance-gated and retired high-level strategy names are absent from normal coordinator dispatch. A text search is supporting evidence, not proof of live behavior.

Authority audit result: **Fresh source audit recorded; live acceptance remains pending Lucas.**

Evidence from the fresh command above:

- Normal coordinator dispatch is `program-runtime-manager.mjs:380`, where the interpreter's model-authored command is sent as `action_command`.
- Java normal dispatch is `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java:467`, where the accepted request enters `submitProgramPrimitive`; the surrounding bridge validation at lines 510 and 555-557 checks the exact payload, provenance/revision, and dead-state rule before execution.
- `fight_target`, `flee_from`, `pick_up_item`, and `build_sequence` were not found in the normal coordinator/server search results as runtime strategy dispatch names. Respawn references remain in the explicit ArenaScript API and lifecycle reconciliation paths and are not evidence of an automatic model-independent gameplay choice.
- This is a source-text audit only. It cannot establish provider behavior, a running Minecraft client's physical result, or live latency; those remain operator gates.

## Sign-off

| Gate | Owner | Status | Evidence |
| --- | --- | --- | --- |
| Headless Task 10 gate | Codex | Pass for the recorded checkout | Fresh command output above |
| Performance verifier and 50/50 soak | Codex | Pass | Full verifier + Task 10 gate + 50/50 soak in 70.9s |
| Jar/distribution hash parity | Codex/Lucas | Pending | Record exact hashes |
| Live Minecraft behavior | Lucas | **Pending Lucas** | Fill checklist and tables |
| Authority audit | Codex | Pass (source audit only) | Fresh rg evidence above; live behavior remains pending Lucas |
