# Task 1 implementation report

Implemented the strict real-provider headless scenario matrix boundary.

## Changes

- Added `coordinator/src/headless-matrix.mjs` with versioned matrix/scenario normalization, provider allowlisting (`codex`, `gemini`, `kimi`), bounded fields and timeout, assertion normalization for lifecycle/chat/action/program/rcon, unknown-key and duplicate-ID rejection, exact-ID selection, and deeply immutable report data.
- Added focused tests in `coordinator/test/headless-matrix.test.mjs` covering normalization, rejection paths, assertion shapes, selection, immutability, serialization, and report bounds.
- Added secret-free opt-in example matrix in `coordinator/config/headless-provider-matrix.json` with Codex chat and movement/chat marker scenarios.

## TDD evidence

The focused test was run before implementation and failed with `ERR_MODULE_NOT_FOUND` for the missing `headless-matrix.mjs` module. After implementation it passed all five tests.

## Verification

`node --test test/headless-matrix.test.mjs`: 5 passed, 0 failed.

The example JSON parsed successfully and `git diff --check` reported no whitespace errors. Existing unrelated worktree modifications were preserved.

## Concerns

The assertion field names follow the design's observable assertion model (`actionType`, `args`, `event`, `status`, `command`, `match`). No external provider calls are made by this module or its tests.
