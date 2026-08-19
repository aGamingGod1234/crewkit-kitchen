# Task 6 fix round 2 report

## Changes

- Coordinator startup bind failures now retry with a fresh, distinct bridge/RCON/Minecraft allocation and restart the Fabric process so the new bridge port is actually served.
- The wrapper snapshots every server/coordinator/runner descendant before graceful server shutdown, force-cleans the tracked set, and verifies every tracked PID is gone.
- Selected scenarios are capped at 16. Matrix manifests, scenario manifests/reports, and the final matrix report are serialized through explicit byte limits.
- The fixture now includes a successful normal-cleanup case. Its fake server returns a completed lifecycle and starts a child helper, allowing cleanup verification on the normal path. Test-only runner grace and cleanup limits keep diagnostic runs bounded.

## Evidence

- `git diff --check`: clean.
- PowerShell parse checks for both modified scripts: passed.
- A bounded lifecycle attempt was started with an outer limit of 30 seconds. It reached the fake-server wrapper runs and did not return within that bound; the run was stopped. No matching FakeServer, dynamic coordinator, or headless runner helper remained afterward. This remains a verification gap, not a passing lifecycle result.
- No external provider was contacted. Loopback RCON, isolated CLI/audit/provider-turn paths, and `KeepArtifacts` wiring were preserved.

## Remaining gap

The provider-free lifecycle script still does not complete within the required 30-second diagnostic window in this environment. The fix is committed with that limitation recorded rather than claiming full lifecycle verification.
