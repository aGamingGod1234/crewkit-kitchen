# Task 7 fix report

## Findings addressed

- Forwarded `providerTurnsPath` from `runHeadlessMatrix` into scenario evidence loading. Reports now expose the configured private artifact path and bounded JSONL row count without copying provider input/output into reports. Added a CLI regression test for the path boundary and documented the behavior.
- Added the wrapper-equivalent 16-scenario selected-matrix limit and a 262,144-byte UTF-8 matrix-report limit. Oversized reports are rejected before the matrix report write. Added regression tests for both limits.
- Setup failures now retain `classification: ERROR` and a nonzero exit code while reporting `cleanup.status: CLEAN` when the RCON close succeeds, or `FAILED` when it throws. Added a regression test for successful close after connect failure.

## Verification

- TDD red: each new regression test failed against the pre-fix implementation (the report-size test was corrected to assert no write and then failed until the byte guard existed).
- TDD green: `node --test test/headless-cli.test.mjs` passed with 12 tests.
- Focused headless verification: `node --test test/headless-cli.test.mjs test/headless-matrix.test.mjs test/headless-runner.test.mjs` passed with 29 tests.
- Full coordinator verification: `npm test` passed with 372 tests.
- `git diff --check` passed.

No external provider, Minecraft server, or live RCON endpoint was contacted.
