# Task 5 fix report: reviewer round 1

## Root causes addressed

- Java `codex status` emits presentation strings (`Task complete`, `Needs attention`, `Dead - awaiting model`) rather than `state=...`; the runner now maps those exact labels to terminal lifecycle states.
- Status polling now uses the configured scenario deadline, has no short fixed-attempt cap, and races each RCON/status/poll operation against the remaining deadline.
- Evidence is read from bounded file tails and re-read during evidence polling so late chat/action/program markers are observable.
- Report diagnostics, command text, assertion `actual` values, cleanup details, and evidence excerpts pass through trace-compatible bounded redaction, including quoted/escaped secret-shaped values.
- Read-only RCON assertions use a conservative query allowlist with optional leading slash normalization and reject mutation commands/control separators.
- Summon runs at the explicit safe headless position `execute positioned 0 64 0 run`.
- Execution, cleanup, and report-write failures return deterministic in-memory `CLEANUP_FAILURE` reports rather than escaping.
- Runner-bound scenario fields reject control characters before command interpolation.

## TDD evidence

1. RED: added reviewer regressions and ran `node --test test/headless-runner.test.mjs`; 7 new tests failed at the old parser, 256-attempt cap, head-only evidence, raw report fields, denylist, summon command, and report-write path.
2. GREEN: implemented each root-cause fix and reran the runner suite; 11 runner tests passed.
3. Focused verification: `node --test test/headless-runner.test.mjs test/headless-matrix.test.mjs test/headless-rcon.test.mjs` — 26 passed, 0 failed.
4. Full coordinator verification: `npm test` — 359 passed, 0 failed.
5. Formatting verification: `git diff --check` — passed.

## Safety evidence

- No external provider, process, protocol, trace-writer, Java, or ledger changes were made.
- The runner still observes protocol/audit evidence only and never injects an `action_result` or bypasses the Java executor.
- Focused tests explicitly assert the safe summon command, late/tail evidence, secret absence from serialized reports, mutation-command rejection, deadline polling beyond 256 status reads, and cleanup/report-write classification.

## Commit

Commit: scoped fix commit `Fix headless runner review findings` (this report is included in the commit; use `git log -1` for its final hash). The `.superpowers/` report is force-added because that directory is ignored.
