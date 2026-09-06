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

The virtual simulator does not implement combined `control` physics. Scenario compilation now rejects unsupported simulator commands before a benchmark starts. Production ArenaScript still supports them. Provider-authored control frames sent directly to the simulator remain unsupported, so such failures cannot be interpreted as provider capability failures.

No live Minecraft rendering, authenticated provider sessions, real speech playback, or FPS/TPS measurement was performed. Skit lifecycle paths were traced through production callers, but current skit tests use fake performers rather than actual Carpet player death/replacement. Camera tests use headless Minecraft fixtures. These checks do not establish universal vanilla or modded parity.

Malformed manually edited skit persistence can still throw from constructors. No valid user flow producing this corruption was established, so speculative persistence changes were omitted.

## First batch verification

| Check | Final result |
| --- | --- |
| Gradle `check build verifyCore`, Java 25 | Passed; 14,575 protocol/core assertions and 385 voice assertions |
| Full coordinator `npm test` | 1,425 total; 1,422 passed, 3 platform skips, zero failures or cancellations |
| Embedded coordinator packaging | Passed; 96 files verified |
| Distribution runtime installation/rollback | Passed, including interruption and concurrent-install checks |
| Windows normal-profile updater | Temporary end-to-end rollback and successful replacement passed |
| Patch whitespace validation | Passed |

The first Gradle attempt used an older configured JDK and failed before compilation; setting JAVA_HOME to the installed Java 25 toolchain resolved it. Final local evidence is in `audit-gradle-final.log` and `audit-coordinator-final.log`; generated logs are excluded from version control. The installer touched only its generated temporary profile.

All GitHub checks on the original PR head passed. CodeRabbit completed with a walkthrough and no actionable inline findings; Copilot could not review because its quota was exhausted. Codex subsequently reported three findings: invisible blocks, stale Director catalog controls, and unsupported simulator commands. The first batch fixed the first two; the next batch fixes the compiler boundary. The first pushed batch exposed a Windows replay fixture timing race, described below.

## Second iteration

After permission to push and continue, the reviewers revisited the affected production paths and the new CI results. Additional verified fixes:

- **Failed physical application.** A sink can start movement or attack before throwing. Failed application previously left an empty logical lease, and release/expiry then skipped physical cleanup. The controller now retains uncertain physical state until it can clear it and restore the correct owner. Tests cover first application, expiration, preemption, and failed restoration.
- **Ray endpoint and center recheck.** Vanilla expands both clipping endpoints. The far expansion could enter an unloaded chunk just beyond range, and a final center-directed ray could cross a different unloaded corner than its original sampling ray. Shared chunk checks now cover each actual segment, with vanilla traversal regressions in positive, negative, and diagonal directions.
- **Unavailable observation recovery.** A PLAYER_UNAVAILABLE snapshot could overwrite the last live inventory with synthetic empty data. It now remains visible as unavailable while preserving real inventory evidence for a later death observation.
- **Async diagnostics.** Rejected trace promises are contained alongside synchronous trace failures. The regression waits across event-loop turns so an escaping rejection fails the test.
- **Simulator admission and respawn.** Compilation uses the simulator's shared action capabilities and fails unsupported manifests early. A check of every shipped command-bearing scenario also exposed incorrect `player.respawn({})` generation; the compiler now emits the required zero-argument call.
- **Camera clock corrections.** Capturing after a backwards server clock update could introduce duplicate keyframe ticks and make a take unsaveable. GUI and command capture reject backwards samples while preserving the take.
- **Camera eye height.** Actual Minecraft Camera alignment showed a recorded Y of 64 rendering at 65.62 because the camera retained the player's interpolated eye height. A narrowly scoped accessor resets both cached heights on playback start and restoration. Headless tests exercise the real alignment method at partial ticks.
- **Short Director windows.** A 120-pixel logical viewport, reachable with forced Unicode scaling, hid all content controls. Compact header/footer spacing leaves a scrollable content row. A new verification suite exercises real widgets on all four tabs, hit testing, draft retention, and catalog alias removal without opening a window.
- **All vanilla air variants.** Shared mining validation now rejects cave air and void air before dispatch, with all native entrypoints checked.
- **Deterministic hazard replay fixture.** Windows CI reproduced a turn-2 prompt mismatch because independent provider timers allowed different lava damage during capture and replay. The fixture now gates its world clock until all four initial actions are accepted. A deliberately late third provider callback reproduces the original failure without this gate; exact prompt hashing remains unchanged.

Second-iteration local verification passed: Gradle `check build verifyCore`, 14,716 protocol/core assertions, 385 voice assertions, and the full coordinator suite with 1,428 total tests, 1,425 passed, three platform skips, and no failures or cancellations. Logs: `audit-iteration2-gradle.log`, `audit-iteration2-coordinator.log`. An independent reviewer inspected the combined follow-up patch and found no further confirmed regression. Fresh GitHub CI remains necessary for each pushed head.

## Final iteration and verification

The second pushed batch, `3a60553e`, passed both coordinator CI jobs and the Windows Java, package, installer, and staged Fabric boot job. All three verified Codex review threads were resolved. CodeRabbit's subsequent reviews were rate-limited; Copilot's quota remained exhausted.

The last adversarial checks produced two further corrections and one permanent verification gate:

- Near-tied ray crossings at legal world-border coordinates can round differently in Minecraft's actual clipping arithmetic. Mixed-sign rays starting exactly at chunk corners also traverse a side chunk behind the origin. The guard now covers both sides of that tiny initial segment and conservatively checks nearly tied corner crossings. Four focused assertions reproduce the failures. A fixed-seed check of 100,000 rays produced 83,180 guarded endpoints and zero endpoints that traversed an unloaded chunk; rejected rays correctly returned no endpoint.
- The initial invisible-block filter also hid End portals and gateways, which use block-entity rendering. Explicit portal exceptions preserve these visible surfaces while retaining dry LIGHT and STRUCTURE_VOID filtering. Regressions cover clear and occluded portals and a direct hit on real moving-piston geometry.
- `verifyCameraMixin` now runs as part of Gradle `check`. A separate JVM initializes Fabric's actual client transformation environment and verifies five checks against the real Camera accessor. It never invokes Minecraft client main or opens a window/world. This complements the headless camera position regressions rather than relying on their stand-in accessor implementation.

Final local checks:

| Gate | Result |
| --- | --- |
| Gradle `check build verifyCore` | BUILD SUCCESSFUL; 14,726 protocol/core assertions and 385 voice assertions |
| Required Fabric camera transformation check | Passed, five checks in a separate JVM |
| Coordinator `npm test` | 1,428 total; 1,425 passed, three platform skips, no failures or cancellations |
| Embedded coordinator packaging | 96 files verified |
| Installer/rollback | Passed locally in the temporary fixture; passed again in the second batch's GitHub CI |
| Independent review of the final delta | No further confirmed regression |

Final local logs are `audit-final-gradle.log` and `audit-final-coordinator.log`. No diagnostics or temporary harnesses are included in the change. The focused regressions and required Fabric verification harness are committed. The PR's live checks are the authority for the latest pushed commit; passing an earlier commit is not a substitute for those checks.
