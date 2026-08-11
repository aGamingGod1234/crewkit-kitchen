# Arena Agents control GUI and audit plan

## Confirmed requirements

- Add a native Minecraft client screen opened by a configurable key binding that defaults to `G`.
- Expose agent selection, live lifecycle/status details, provider/model/thinking selection, optional display name, summon, start, queue, steer, stop, resume, respawn, remove, and refresh.
- Require confirmation before removal.
- Keep the existing `/codex` command tree authoritative for mutations and server permissions.
- Synchronize a bounded, structured server snapshot to the client; never transmit credentials or bridge secrets.
- Preserve all existing Codex, Gemini, Kimi, legacy arena, persistence, bridge, and coordinator behavior.
- Add no dependencies.
- Use regression-first fixes for confirmed audit defects.
- Verify with automated tests, Gradle build/check, coordinator tests, packaging/static audits, and headless server checks only.
- Do not open a Minecraft client or perform visual testing in this phase.

## Task 1: Audit and lock shared control contracts

- Audit server/domain/coordinator and client lifecycle code for confirmed defects.
- Add failing verification for snapshot bounds, serialization, model/provider/thinking presets, command escaping, selection stability, and permission state.
- Implement only the minimal pure control contracts needed to make those tests pass.

## Task 2: Implement server-authoritative synchronization

- Register bounded serverbound snapshot requests and clientbound snapshot responses.
- Build snapshots on the server thread from `CodexAgentManager.records()`.
- Include whether the requesting player may mutate state.
- Fail closed for malformed/unsupported payloads and preserve command permission enforcement.

## Task 3: Implement the native client control screen

- Register the configurable `G` key binding in Minecraft Controls.
- Open only while connected to a world and no conflicting screen is active.
- Provide responsive, narrated controls for spawn configuration, agent selection/status, prompt submission, lifecycle actions, refresh, and confirmed removal.
- Disable invalid actions while disconnected, unauthorized, unselected, or missing required input.
- Poll bounded snapshots only while the screen is open.

## Task 4: Fix confirmed audit findings

- For each confirmed defect, first add a regression that fails for the correct reason.
- Apply the smallest compatible fix without unrelated refactors.

## Task 5: Verify and document

- Run focused red-green checks, the complete Java verification suite, coordinator tests, Gradle check/build, packaging audits, and the existing headless server harness.
- Update `PROJECT_LOG.md` with exact files, evidence, assumptions, known issues, and the visual-testing deferral.
