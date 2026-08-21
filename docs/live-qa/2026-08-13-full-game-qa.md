# Full-game QA, 2026-08-13

## Scope

Live single-player testing used the development client and the `ArenaAgentsLiveQA` world until computer control was revoked. Later work used logs and headless verification only. Minecraft was not relaunched after the live client stopped at 21:34:01.

## Live results

| Flow | Result | Evidence |
| --- | --- | --- |
| Thinking Tower, two agents, underwater origin | Pass | 7,139-block build began at 20:47:48, arena ready and both agents launched at 20:47:52. The lava floor remained lava after adding its containment curb. |
| Thinking Tower lane execution | Pass | Lane 0 completed at 20:54:32 and lane 1 at 20:57:40. The match produced a durable terminal result at 21:00:59. |
| Thinking Tower death recovery | Pass | One contestant was damaged lethally with lava during the run and recovered at full health without a leave/join cycle. |
| The Last Valley build and activation | Pass | 51,674-block build began at 21:15:08, was ready at 21:15:22, and launched its contestant at 21:15:23. |
| The Last Valley autonomy | Partial | The agent mined, crafted, placed, and engaged a zombie. It later died normally at 21:18:43. The live trace exposed repeated placement confirmation failures and intermittent empty Codex turns. |
| Cross-run cleanup | Failed before fix | A completed parkour run left contestants in the registry and a following Last Valley launch collided with duplicate display names. Cleanup is now strict and blocks final state clearing until all run-owned registry rows are gone. |
| Building and PvP presets | Not rerun live | Computer control was revoked before these remaining presets could be exercised on the rebuilt client. Their blueprint and deterministic headless gates pass, but that is not a live-play claim. |

## Bugs fixed from live evidence

1. Underwater parkour lava changed to obsidian. Fluids are now applied last and the lava basin has a fluid-level containment curb.
2. Completed scenario contestants poisoned the next launch. Durable-result cleanup now deletes every bound scenario agent strictly before clearing the run.
3. Double-digit parkour recovery could associate agents with the wrong lane because IDs were sorted lexicographically. Spawn allocation now preserves roster binding order.
4. Parkour checkpoint progress was lost after reload. Snapshot schema v2 persists agent-keyed checkpoint indices and migrates v1 safely.
5. Codex app-server turns could complete without a final completed-message item. Streaming agent-message deltas are now accepted; a genuinely empty turn gets one immediate retry, then remains in planning for a quiet delayed observation retry instead of erroring the agent or poisoning provider circuit health.
6. Block placement aimed at the support block center, which could raycast to a different face. It now aims at the requested support face, and the planner contract explicitly defines destination coordinates and support-face direction.
7. Craft requests confused requested output quantity with recipe execution count. Craft count now means the minimum required output from one vanilla recipe execution; the complete vanilla output stack is always retained.
8. Melee, follow, and ranged actions rejected observation-style selectors such as `uuid:<id>` and `name=<agent>`. All entity-target actions now share one normalizer, while the planner is told to copy the observed UUID or name without decoration.
9. A coordinator action could race its immediately preceding planning-state update and be rejected while the agent still read `STARTING`. The lifecycle reducer now accepts the first correctly revisioned action from `STARTING` or `PLANNING`.
10. Failed and completed runs could lose their in-memory roster before terminal cleanup. Cleanup now merges live and snapshot-owned agent IDs, so restart and partial-memory paths remove all run-owned contestants.
11. Arena lifecycle and launch-rejection messages still leaked into chat. Construction and lifecycle notices now use the action bar above the hotbar; actionable failures remain durable in the field console.

## Verification after fixes

- Java 25 `gradlew check build`: passed.
- Java 25 `gradlew verifyCore compileClientJava`: passed with 5,998 assertions after the additional lifecycle, target-selector, recovery, and 2/8/16 geometry coverage.
- Coordinator `node --test`: passed 154 tests.
- Performance/reliability verifier: passed full automated verification plus 10/10 repeated eight-agent soak runs.

## Remaining live gates

- Rebuild/relaunch and confirm a formerly failing placement succeeds in-world.
- Run Building and PvP from setup through activation, agent action, terminal result, and immediate next-run launch.
- Repeat a 16-agent parkour run across a save/reload boundary to confirm the live lane binding and checkpoint persistence fixes.
- Confirm the quiet empty-turn retry against a real Codex app-server turn rather than only its deterministic transport tests.
