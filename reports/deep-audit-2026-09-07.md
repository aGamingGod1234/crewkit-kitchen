# Arena Agents audit results

Date: 7 September 2026, Singapore time.

The audit fixes are implemented locally. The final clean automated verifier exited with code 0 after rebuilding the Java mod, voice add-on, and bundled coordinator, then exercising packaging, rollback, retained features, and coordinator behavior. This is headless verification, not live gameplay or a guarantee of universal model and Minecraft interaction support.

## Implemented fixes

### Combined player inputs and provider consistency

ArenaScript now exposes `player.control`, connecting Kimi/Cursor programs to the same complete input-frame action used by Codex's native control tool. Movement, aiming, jumping, sprinting, sneaking, attacking, and item use can be expressed together. Programs still await physical completion and fresh observations before issuing the next body command. Separate agents can act concurrently; speech playback can overlap physical actions.

Provider instructions describe every control field and its bounds. The equip-item signature no longer advertises an unsupported durability argument. The existing observation/action architecture is retained.

Sources: [ArenaScript API](../coordinator/src/arena-script/minecraft-api.mjs), [provider instructions](../coordinator/src/prompts.mjs), [runtime regressions](../coordinator/test/program-runtime-manager.test.mjs).

### Input and cancellation reliability

Strafing now uses the correct sign during turns and fallback movement. One agent's input-sink or expiry failure no longer prevents other agents from updating. Failed release cleanup remains eligible for retry.

Native actions and goal completion can cancel even when bridge publication stalls. Late publication failures cannot delete a replacement action or completion after reconnect. Synchronous telemetry failures cannot interrupt gameplay.

Sources: [input state](../src/main/java/dev/agaminggod/arenaagents/server/runtime/input/AgentInputStates.java), [input leases](../src/main/java/dev/agaminggod/arenaagents/server/runtime/input/LeasedServerInputController.java), [native runtime](../coordinator/src/native-tool-runtime.mjs).

### Factual perception

Cached container candidates are checked against current block state, capabilities, and the player's actual interaction reach. Block mutations invalidate nearby spatial candidates, including placements across revision-region boundaries. Long sight rays stop before the first unloaded chunk, including diagonal and negative-coordinate cases.

The final reproduced defect was that an unobstructed visual ray could omit water and other non-solid or partial blocks. A clear ray now permits an actual non-air target; a different block hit still occludes it. Regressions use vanilla block shapes and clipping for stone, water, lava, flowers, torches, and slabs, with walls and absent targets checked separately.

Sources: [observation collector](../src/main/java/dev/agaminggod/arenaagents/server/perception/ServerObservationCollector.java), [visibility](../src/main/java/dev/agaminggod/arenaagents/server/perception/ObservationVisibility.java), [perception regressions](../src/test/java/dev/agaminggod/arenaagents/server/perception/ObservationBudgetVerification.java).

### Director, skits, and camera

Skit playback is bound to the actual actor and world. Death, removal, replacement, stop, disabling skit mode, and unexpected world changes release the actor's inputs and item use. Timeline validation catches invalid dimensions and equipment before playback. Action durations, movement endpoints, step transitions, relative-right placement, and display names containing spaces are corrected.

Director fields survive widget rebuilds and tab changes. Stop and actor-override commands, literal speech text, provider-specific model selection, scrolling, and footer clipping are corrected.

Camera replacements preserve the existing saved path until the new path is saved successfully. Failed writes preserve the in-memory library and recording. Playback restores a valid camera and perspective, handles world changes, and fixes delayed first keyframes and large-angle interpolation. These behaviors have headless regression coverage; their rendered appearance was not inspected in Minecraft.

Sources: [skit runtime](../src/main/java/dev/agaminggod/arenaagents/server/SkitModeRuntime.java), [Director screen](../src/client/java/dev/agaminggod/arenaagents/client/gui/SkitDirectorScreen.java), [camera director](../src/client/java/dev/agaminggod/arenaagents/client/camera/CameraDirectorClient.java), [camera regressions](../src/test/java/dev/agaminggod/arenaagents/client/camera/CameraPathVerification.java).

### Validation, overhead, and updater verification

Mining consistently uses shared action validation, including air-target rejection. Nested action-type shadowing is rejected in native actions, sequences, and simulation/benchmark paths. Normal-sized tool results avoid eager fallback compaction and duplicate JSON serialization. No live FPS or TPS improvement is claimed.

The temporary Windows updater test handles transient executable-sharing locks with bounded retries, preserving its full rollback assertions and original failure reporting. This change is in the test harness, not a change to the user's installed profile. Temporary investigation helpers were removed.

Sources: [native tool handling](../coordinator/src/native-minecraft-tools.mjs), [updater fixture](../scripts/test-install-normal-profile-update.ps1).

## Final verification

Local evidence: `reports/deep-audit-2026-09-07-final-integrated.log`. The generated log is not committed; the results below summarize that complete clean run.

| Gate | Result |
| --- | --- |
| Clean Gradle `check build verifyCore`, cache disabled and tasks rerun | Passed; all 30 tasks executed |
| Protocol and core verification | 14,557 assertions passed |
| Voice add-on verification | 385 assertions passed |
| Coordinator `npm test` | 1,424 total; 1,421 passed; 0 failed; 0 cancelled; 3 skipped |
| Coordinator JAR packaging | 96 bundled files verified |
| Runtime installation and rollback | Passed, including interruption and concurrent-install fixtures |
| Normal-profile updater | Temporary end-to-end rollback and successful-replacement scenarios passed |
| Retained features | 14 files and six core verification entrypoints passed |
| Launcher bridge-secret contract | Passed as the verifier's final gate |

The three coordinator skips are two POSIX process-group tests unavailable on Windows and one native Windows speech integration test. Mocked speech subprocess handling and cancellation tests passed. The test totals above describe the final complete run, without adding earlier focused runs.

Generated local outputs, not committed: `build/libs/arena-agents-0.2.0.jar` and `voice-addon/build/libs/arena-agents-voice-0.2.0.jar`.

## Remaining capability gaps and verification limits

The exposed native-tool and ArenaScript APIs contain no wiki lookup. They provide factual observations, not an external game-knowledge service. This gap is documented in the README; no unrestricted provider tools or network access were enabled.

Gemini compatibility remains present, but its production planner deliberately reports unavailable because Antigravity lacks an enforceable no-tool execution boundary. Codex, Kimi, and Cursor adapter behavior was verified through automated fixtures, without live authenticated provider sessions.

No Minecraft client, visible browser, live speech playback, or original world was launched or modified for this pass. Actual visual appearance, provider latency, live FPS/TPS, and universal vanilla/modded interaction parity remain unverified. No profile installation or deployment was performed. The existing user plan remains local and is excluded from the audit changes.
