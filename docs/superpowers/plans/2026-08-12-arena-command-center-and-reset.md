# Arena command center and reliable reset implementation plan

## Task 1: Lock reset lifecycle behavior

- Extend scenario recovery verification with managed-chunk deduplication and load-phase assertions.
- Add a transient scenario ticket type and a `LOAD_CHUNKS` phase to `ScenarioArenaResetJob`.
- Load one managed chunk per bounded step, hold tickets through verification, and release them on every terminal path.
- Update runtime progress and error presentation.

## Task 2: Lock command-center state and safety behavior

- Change scenario state verification to require a three-step Preset, Roster, Launch flow.
- Add pure selection movement, batch-removal description, and lifecycle-action availability assertions.
- Add a pure responsive layout model with non-overlap assertions for compact and standard screens.

## Task 3: Implement the command center

- Open `AgentControlScreen` from `G` and add Agents/Arena sibling navigation.
- Replace free-text model entry with the shared model catalogue.
- Keep visual and command selections synchronized.
- Make lifecycle buttons state-aware and destructive confirmation batch-accurate.
- Replace ambiguous local dispatch feedback with truthful pending-server language.

## Task 4: Rebuild arena setup layout

- Remove the introductory mode step.
- Recompose Preset, Roster, and Launch using reserved header/content/status/footer geometry.
- Provide wide two-column and compact stacked roster layouts with bounded visible rows.
- Keep feedback and validation in the dedicated status region.

## Task 5: Verify end to end

- Run focused Java verification during red/green development.
- Run Gradle check/build and the complete performance/reliability script.
- Launch the development client/server and verify an arena progresses past chunk loading into apply/verify without `UNLOADED_MANAGED_CHUNK`.
- Review screenshots at the GUI scale that previously overlapped.
