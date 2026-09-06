# PR #35 follow-up audit

Reviewed PR head `73506783591245b5fd3a7d6a50707bb596bef1bc` against `origin/main` on 7 September 2026. Twelve subagents reviewed all 31 changed files and adjacent callers in two waves. Fixes were assigned to separate file owners and reviewed together by the primary agent.

## Confirmed issues fixed

1. **Input failure containment.** The controller deliberately aggregates sink failures, but its production caller let them escape the server tick callback. The runtime now logs the failure and permits subsequent work and cleanup retries. Regression checks a failed expiration followed by successful retry.
2. **Look-around supervision.** Native camera sweeps issue physical control actions but lacked an action supervision lease. They now stay ACTING with one lease until the sweep finishes. An integration test verifies both steps and final release.
3. **Unloaded chunk at a ray origin.** Vanilla clipping expands its start backwards. At exact chunk boundaries it could read an unloaded chunk behind the observer despite the forward guard. The guard now checks this expansion, including corner side chunks; tests use vanilla traversal.
4. **Invisible blocks in observations.** The new clear-ray fallback exposed dry LIGHT blocks. Invisible targets now require visible fluid; dry and waterlogged LIGHT regressions preserve the distinction.
5. **Camera playback after same-world respawn.** Minecraft replaces the player and camera without replacing the level. Playback now stops on player replacement and cannot restore the stale player. A headless fixture verifies anchor cleanup and perspective restoration.
6. **Camera mid-write errors.** Gson wraps writer failures in unchecked exceptions. The persistence boundary now restores checked IO errors so existing save-failure handling retains recording state and provides retry feedback. A failing writer verifies this boundary.
7. **Director catalog replacement.** An open Director retained model widgets after the shared catalog changed; rendering a removed model could throw. Catalog updates now rebuild Director widgets through its draft-preserving initialization path. Compiled and statically traced; no rendered UI test.
8. **Skit model reasoning defaults.** Summon commands hardcoded provider-level reasoning even when the selected model required another value. They now use the model catalog, preserving the Claude alias. Catalog regressions cover Claude and GPT OSS.

Some of these gaps predate the PR but sit directly in the behavior this improvement pass addresses.

## Review coverage

| Reviewer | Assigned scope | Result |
| --- | --- | --- |
| 01 | Native tools, ArenaScript bindings, schemas, mining | No additional actionable issue |
| 02 | Publication cancellation, reconnect races, program runtime | Fixed look-around supervision |
| 03 | Java input leases, strafe behavior, failure cleanup | Fixed server tick containment |
| 04 | Observation cache, container reach, mutations, loaded rays | Fixed ray-origin chunk guard |
| 05 | Visual shapes, non-solid targets, spatial revisions | Fixed invisible LIGHT disclosure |
| 06 | Skit actor identity, death, replacement, dimensions, cleanup | No additional verified defect |
| 07 | Director drafts, scrolling, commands, provider/model state | Fixed catalog refresh and reasoning defaults |
| 08 | Camera persistence, interpolation, playback lifecycle | Fixed respawn and mid-write failures |
| 09 | Skit placement, timeline bounds, selectors, persistence callers | No additional verified defect |
| 10 | Prompt parity, benchmark compiler, simulator, documentation | Documented simulator control limitation |
| 11 | Windows retry harness, process cleanup, CI test wiring | No actionable defect |
| 12 | Independent integration and regression-quality review | Independently confirmed input boundary gap and reviewed fix |

## Remaining limits

The virtual simulator rejects `control` with `SIMULATOR_UNSUPPORTED_ACTION` even though the benchmark compiler accepts it. README now states this explicitly. Implementing Minecraft control physics in the virtual simulator is separate work; these failures cannot be interpreted as provider capability failures.

No live Minecraft rendering, authenticated provider sessions, real speech playback, or FPS/TPS measurement was performed. Skit lifecycle paths were traced through production callers, but current skit tests use fake performers rather than actual Carpet player death/replacement. Camera tests use headless Minecraft fixtures. These checks do not establish universal vanilla or modded parity.

Malformed manually edited skit persistence can still throw from constructors, and shared mining validation accepts cave-air/void-air identifiers that Java later rejects. Neither produced a new valid-user-flow regression, so speculative changes were omitted.

## Verification

| Check | Final result |
| --- | --- |
| Gradle `check build verifyCore`, Java 25 | Passed; 14,575 protocol/core assertions and 385 voice assertions |
| Full coordinator `npm test` | 1,425 total; 1,422 passed, 3 platform skips, zero failures or cancellations |
| Embedded coordinator packaging | Passed; 96 files verified |
| Distribution runtime installation/rollback | Passed, including interruption and concurrent-install checks |
| Windows normal-profile updater | Temporary end-to-end rollback and successful replacement passed |
| Patch whitespace validation | Passed |

The first Gradle attempt used an older configured JDK and failed before compilation; setting JAVA_HOME to the installed Java 25 toolchain resolved it. Final local evidence is in `audit-gradle-final.log` and `audit-coordinator-final.log`; generated logs are excluded from version control. The installer touched only its generated temporary profile.

All GitHub checks on the original PR head passed. CodeRabbit completed with a walkthrough and no actionable inline findings; Copilot could not review because its quota was exhausted. Codex review still reported running at the final check, with no inline findings available. No bot comments were treated as verified defects without evidence. Follow-up changes require pushing and fresh CI before they can be considered checked on GitHub.
