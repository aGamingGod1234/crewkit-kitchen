# Task 2 report: bounded RCON client

## TDD evidence

- RED: `node --test test/headless-rcon.test.mjs` failed because `src/headless-rcon.mjs` did not exist.
- GREEN: `node --test test/headless-rcon.test.mjs test/jsonl.test.mjs` passed (10 tests).
- `git diff --check` passed.

## Delivered

`HeadlessRconClient` implements little-endian RCON framing, one authenticated session, fragmented reads, bounded UTF-8 decoding, malformed-length rejection, request-ID pending commands, typed close/auth/timeout errors, and idempotent close. Tests use local TCP fixtures only.

Commit: `1c54c99` (amended to include this report)
