# Task 6 setup-failure cleanup verification

## Fix

Scenario setup now tracks whether a credential may have been created. Failures before credential creation preserve their original validation error; failures after that point sanitize diagnostics, remove generated server/workspace/log/credential artifacts unless `KeepArtifacts` was requested, and write only a bounded scenario failure report. Windows-reserved filename characters are rejected in scenario IDs before setup.

## Verification

- PowerShell parser checks passed for both lifecycle scripts.
- Default lifecycle harness passed all required assertions and all six PASS lines; the correctly quoted run completed successfully (the run was 60.6 seconds on a loaded Windows host).
- `-SetupFailureOnly` passed the malformed-coordinator setup test in 9.46 seconds with exit code 0, including matrix/scenario report checks, no retained credential/server/provider workspace, and no raw malformed configuration text.
- Existing occupied-port/duplicate-port checks still preserve their original validation errors.
- No external provider calls were made.
