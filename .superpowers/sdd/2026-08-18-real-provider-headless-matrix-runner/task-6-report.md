# Task 6 report: real-provider headless lifecycle wrapper

## Scope

- Added `scripts/run-headless-provider-matrix.ps1`.
- Added `scripts/test-run-headless-provider-matrix.ps1`.
- `.gitignore` was unchanged because the existing `runtime/*` rule already covers `runtime/headless-runs/`.
- The ledger was not modified.

## TDD evidence

1. RED: `powershell -NoProfile -File scripts/test-run-headless-provider-matrix.ps1` failed because the lifecycle wrapper did not exist.
2. GREEN: the focused PowerShell suite passes prerequisite failures, occupied-port rejection, startup timeout cleanup, report persistence, port closure, stale-world exclusion, and dummy descendant cleanup.
3. Verification: `powershell -NoProfile -File scripts/test-run-headless-provider-matrix.ps1` — PASS.
4. Formatting verification: `git diff --check` — PASS.
5. The tests use a fixture server and skip provider preflight; they never contact a real provider.

## Safety and lifecycle evidence

- Java 25+, Node 22+, built JAR, Fabric launcher/template, matrix, and selected provider executable preflight are validated before the live run.
- Every selected scenario receives a unique run/scenario directory, copied template, removed source world, unique level name, isolated provider workspace, fresh ports, RCON configuration, and a generated ACL-restricted secret.
- Coordinator-only environment carries the bridge secret, run/scenario identity, audit/trace paths, and ports. Provider children use the existing coordinator boundary that removes bridge-secret variables.
- Fabric, coordinator, runner, and descendant process trees are stopped in `finally`; allocated listeners are verified closed. Failed required scenarios and cleanup failures return nonzero.

## Commit

Task 6 commits are `72615cd` (wrapper and tests), `ae0660d` (credential-file ACL hardening), and `10d3b07` (scenario artifact path isolation). This report is included in the scoped Task 6 history.
