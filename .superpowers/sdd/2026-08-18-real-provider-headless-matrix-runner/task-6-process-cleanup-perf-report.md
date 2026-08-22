# Task 6 process cleanup and lifecycle verification

## Root cause

The lifecycle harness was spending most of its time in repeated process-tree and redirected-output cleanup. The wrapper now refreshes one parent/child snapshot per cleanup pass, keeps traversal rooted at already tracked PIDs, and allows the test fixture to use bounded graceful-stop and output-drain limits. The fake server still starts a real child process so descendant cleanup remains exercised.

## Verification

- Correctly quoted PowerShell invocation from the space-containing worktree completed in 41.705 seconds with exit code 0.
- All lifecycle assertions passed: occupied-port validation, duplicate-port validation, bounded scenario selection, successful normal cleanup with descendant verification, report forwarding/KeepArtifacts/loopback RCON, timeout cleanup, port verification, and provider-isolation hooks.
- `git diff --check` passed.
- The test's `finally` cleanup completed; no fixture Java, Node, PowerShell helper, or fake-server listener remained after the run.
- External provider calls remained disabled; the fixture uses only its local fake Codex executable and fake Java server.
