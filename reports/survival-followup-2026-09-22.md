# Continuous survival reliability follow-up

Three Astra subagents at medium reasoning inspected execution, perception, and coordinator continuity on merged main (`08d20c98`). Three demonstrated failures were corrected without adding gameplay tactics, changing models, or changing operator-confirmation policy.

| Controlled scenario | Before | After |
| --- | --- | --- |
| Cancellation transport fails after the original action acknowledged and its interrupt handler started | Active routine disappears; handler action is still outstanding | Handler remains active and its successful receipt is retained |
| A second steering message is queued when the first is in flight, then the agent dies at the same goal revision | 1 obsolete delivery after death | 0 obsolete deliveries after death |
| An observed door changes from closed to open in a previously blocked cell | 1 blocked cell remains | 0 blocked cells remain |

These are deterministic runtime regressions, not live gameplay or latency benchmarks. The before behavior was reproduced against the original implementation. No movement speed, token-cost reduction, or arbitrary-seed success rate is claimed.

## Changes

- Cancellation failures apply only to the original still-pending action. Genuine cancellation uncertainty continues to return `UNKNOWN`; the handler cannot start without acknowledgement.
- Steering verifies work ownership, connection epoch, lifecycle generation, expiry, and goal revision before delivery and after asynchronous success or failure. Obsolete failures cannot restore conversation delivery or queue recovery work.
- Spatial memory retains real wire-format block properties in canonical order. Sparse landmarks cannot erase detailed properties, and property order alone does not invalidate blockage. Changed properties reach exploration candidates; durable reload retains them. Candidate reachability remains explicitly unknown.

## PR review follow-up

A fresh three-agent Astra review of PR #39 found and corrected three additional edge cases. The cancellation fix had no findings.

| Scenario against the initial PR commit `e18acab5` | Before | After |
| --- | --- | --- |
| Unacknowledged player message during death/reconnect | Missing from replacement turn | Replayed to replacement; late rejection cannot duplicate delivery |
| Current sighting contains only a sparse landmark | Remembered properties disappear from exploration candidate | Fresh same-block properties retained |
| Door properties seen at tick 10, then identity-only sightings through tick 41 with existing freshness duration set to 30 ticks | Old properties remain present | Old properties omitted; full observation at tick 42 restores them |

In-flight conversation delivery is restored when its lifecycle is invalidated, before replacement work consumes it. Property freshness has a persisted observation timestamp independent of block identity. Existing memory records remain readable using their prior last-seen timestamp. The original freshness duration is unchanged.

All demonstrated failures were reproduced before their correction. The final combined suite passes 363 tests; independent re-review found no remaining concrete issue in these changes. Review evidence is retained locally in `build/pr39-steering-before.log`, `build/pr39-steering-after.log`, `build/pr39-memory-before.txt`, `build/pr39-memory-after.txt`, `build/pr39-reviewed-tests.log`, and `build/pr39-reviewed-assemble.log`.

## Verification

**363 tests passed** across coordinator scheduling, native program execution, background programs, program runtime management, observed memory, exploration, observation adaptation, native tools, and survival tooling. New tests include both stale-steering resolution and rejection, genuine current cancellation failure, and wire observation through exploration candidates with duplicate sparse landmarks. Independent review found no blocking issue.

Java 25 assembly passed. All **103 embedded manifest entries** verified, and **88 packaged source files** matched the checkout. Built JAR SHA-256: `8418ad38504ac4c835fd84c70ab17288dce8c93f72183a12084a6d68afa276df`.

The focused command is:

```powershell
.\runtime\toolchains\node\node.exe --test --test-reporter=tap coordinator/test/dynamic-main.test.mjs coordinator/test/native-program-executor.test.mjs coordinator/test/background-program.test.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/observed-memory-store.test.mjs coordinator/test/explore-frontier.test.mjs coordinator/test/observation-adapter.test.mjs coordinator/test/native-tool-runtime.test.mjs coordinator/test/survival-tooling.test.mjs
```

Local raw evidence is retained in `build/survival-followup-tests.log`, `build/survival-cancellation-before.log`, `build/continuity-before.txt`, `build/continuity-after.txt`, `build/survival-perception/before.log`, `build/survival-perception/after.log`, and `build/survival-followup-assemble.log`. These ignored logs are not published because they contain machine-local paths.

No new live Minecraft trial or GUI test was run for this change. Audible speech remains deferred. The previous gathering results belong to the earlier build and must not be attributed to this one.
