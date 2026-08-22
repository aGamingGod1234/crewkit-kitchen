# Task 5 report: headless scenario runner

## Scope

- Added the injected `runHeadlessScenario` orchestration path in `coordinator/src/headless-matrix.mjs`.
- Added evidence-backed `evaluateHeadlessAssertions` and bounded `writeHeadlessReport` exports.
- Added `coordinator/test/headless-runner.test.mjs`.
- No protocol, trace-writer, ledger, provider, Java, or external-process changes.

## TDD evidence

1. RED: `node --test test/headless-runner.test.mjs` failed because the requested runner exports were absent.
2. GREEN: the runner tests passed after implementing exact RCON sequencing, injected evidence evaluation, classifications, cleanup, and report serialization.
3. Focused verification: `node --test test/headless-runner.test.mjs test/headless-matrix.test.mjs test/headless-rcon.test.mjs` — 20 passed, 0 failed.
4. Full coordinator verification: `npm test` — 353 passed, 0 failed.
5. Formatting verification: `git diff --check` — passed.

## Safety/behavior evidence

- Commands use `codex summon-configured`, `codex start`, and repeated `codex status` with a generated agent name; read-only assertion commands are guarded against mutating RCON verbs.
- Protocol/action evidence is consumed from injected audit rows or bounded JSONL traces. The runner never creates or injects an `action_result`, and never calls the provider-turn recorder to fabricate evidence.
- Assertions cover lifecycle, exact chat, action type/argument subsets, program event/status, and bounded read-only RCON matches.
- Reports are bounded plain JSON with artifact paths and excerpts only, and classify timeout, `ERROR`, `DEAD`, skipped profile, assertion mismatch, and cleanup failure.

## Commit

Commit: scoped commit `Implement headless scenario runner` (this report is included in that commit; use `git log -1` for its final hash). The report was force-added because `.superpowers/` is ignored.
