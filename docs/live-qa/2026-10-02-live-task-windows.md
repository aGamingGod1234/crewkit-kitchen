# Local live task windows verification

The original window implementation and its initial checks are recorded below. Subsequent work completed native lifecycle and real Minecraft Manage-screen verification, corrected Minecraft's forced headless flag, and fixed the protocol/shutdown defects. See the [current implementation report](2026-10-02-remaining-improvements.md) for the final artifact, checks and scope; the older pending status and artifact below are historical.

Implemented the approved B connected branch map and an independent Codex activity terminal toggle in Manage agent. Both default off and remember local preferences. The windows follow the selected agent, receive snapshots while open, and display offline/stale state. Closing a viewer does not cancel the task.

Luna's existing goal-translation request can return advisory prerequisites. The main agent can read and replace the plan with `taskPlan`; it still chooses every action and can revise dependencies. Final Minecraft goal verification remains independent. Plans persist by world, agent and task. Current inventory requirements are reconciled with observations and become lost on death; known world structures and agent-reported historical milestones are retained. A remembered green structure is not a current visual confirmation.

The terminal displays messages, available summaries, tool requests/results and usage emitted by the same Codex app-server process. It is a read-only activity viewer, not a second Codex session or an interactive native CLI. Raw reasoning is excluded. Token snapshots are cumulative thread totals rather than sums of repeated notifications. Account allowance is explicitly shared account data. Usage snapshots are also retained in coordinator traces with a hashed session identifier. Display polling makes no provider requests; maintaining the advisory plan can consume tokens as part of normal planning.

## Checks

- Full coordinator suite: 1,865 passed, three skipped, zero failures (1,868 total).
- Java protocol/core verification: 15,834 assertions passed.
- Voice addon: 387 assertions passed.
- Camera/presentation mixin verification: 19 checks passed, without invoking the Minecraft client.
- Actual native Swing graph rendered headlessly before and after equipment loss. Step selection and selected-detail refresh passed. Images are under `build/reports/live-task-windows/`.
- Entry-point regression verifies coordinator view requests return a valid snapshot, reject stale goal revisions and start zero extra planner requests.
- Focused tests cover invalid dependency graphs, fabricated inventory/world completion, retained portal knowledge, recovery, world/task-scoped restart persistence, optional invalid Luna advice, streamed message spacing, secret redaction and cumulative token updates without turn IDs.
- Final `verifyCore build` succeeded. Packaged JAR contains the new coordinator module, client window classes and server packet/sync classes.

Artifact: `build/libs/arena-agents-0.2.0.jar`

SHA256: `974C19708C1670368FA11991C312C71A6EA52EA6AD4CA0D579B577F924F088C5`

## Limits and scope

Desktop's installed JAR was not changed. Native windows, toggle persistence, reconnect, agent switching and close/reopen still need testing through the installed Minecraft client. Headless graph rendering, unit/integration tests and a successful build do not establish installed-app behavior. No paid provider run was used for these tests.

The infinite-Haste protocol disconnect and separate shutdown exception remain diagnosed, not fixed in this window implementation. Cave navigation and preparation improvements remain proposed. Existing unrelated local changes were preserved; no commit, push, PR or merge was performed.

Approved plan and current status: https://vnheb2pqbsru.postplan.dev (version 3, hosted HTML verified against the local document).
