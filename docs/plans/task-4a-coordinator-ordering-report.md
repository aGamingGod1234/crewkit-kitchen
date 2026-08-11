# Task 4A implementation report

## Status

Complete. The coordinator now preserves per-agent ingress order for synchronous terminal-result/lifecycle frames and consumes the exact queued head when Minecraft promotes a goal.

## Root causes and fixes

- `action_result` used the per-agent operation chain, but `goal_control` used an independent promise. When both frames arrived in one socket delivery, the lifecycle revision could advance before the earlier terminal result was checked. `goal_control` now joins the same per-agent chain. Stop, steer, disconnect, and dead controls still invoke planner interruption immediately, before their ordered state mutation runs. Other agents retain independent chains.
- Minecraft reports a queued-goal promotion as `start`. The coordinator replaced `currentGoal` but retained the promoted entry in its queue mirror. An active-state `start` is now treated as a server promotion: its goal must exactly match the queued head, that head alone is removed, and mismatches fail with `PROMOTED_GOAL_MISMATCH`. Ordinary starts from inactive states remain compatible.

## Files changed

- `coordinator/src/dynamic-main.mjs` — ordered goal controls per agent while preserving immediate interruption.
- `coordinator/src/agent-registry.mjs` — exact, fail-closed queued-head promotion handling.
- `coordinator/test/dynamic-main.test.mjs` — synchronous result/control regression.
- `coordinator/test/agent-registry.test.mjs` — A/B/C promotion-through-exhaustion and mismatch regressions.

## Test evidence

Red phase:

- Ordering regression failed with `STALE_GOAL_REVISION` because revision 2 was applied before the revision-1 result.
- Promotion regression retained `['B', 'C']` instead of `['C']`; mismatched promotion did not throw.

Green phase:

- Focused registry suite: 10 passed, 0 failed.
- Focused dynamic coordinator suite: 9 passed, 0 failed.
- Complete coordinator suite: 81 passed, 0 failed.
- Scoped `git diff --check`: clean.

## Remaining concerns

- Promotion compatibility intentionally uses the existing Java bridge `start` payload. Detection depends on the coordinator being in `STARTING`, `PLANNING`, or `ACTING` and on exact equality with the queued-head prompt; malformed or divergent mirrors fail closed and require reconciliation.
- No Minecraft client or GUI was launched for this task.
