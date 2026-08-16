# Task 8 - Model-authored vanilla respawn

Implementation commits: `170271b` (`fix: make model-authored vanilla respawn restart safe`), `f83d18c` (`fix: fence respawn on coordinator loss and remove operator affordance`), `cf75331` (`fix: publish scenario respawn events after commit`), and `c7a4b49` (`fix: isolate scenario telemetry after respawn commit`)

## Delivered

- Persisted death facts now include the actual live vanilla game mode and the complete respawn configuration: dimension, block position, yaw, pitch, and forced flag. Legacy snapshots and the prior constructor shape remain readable.
- A persisted `DEAD` agent remains dead across reloads and can be respawned without a transient pre-existing `ServerPlayer`. The server revalidates the saved bed, charged anchor, or forced target against the current world and falls back to vanilla shared world spawn when invalid or unavailable.
- Respawn uses the coordinate-free authored `await player.respawn()` primitive only. The operator command/UI bypass is removed; no `/codex respawn` path remains.
- Vanilla target details are preserved: exact bed/anchor stand-up position and look-at angle, one deferred anchor-charge consumption, actual saved game mode, dimension, and vanilla shared-spawn adjustment. Inventory, XP, drops, and keep-inventory behavior are not reconstructed by the mod.
- Physical spawn verification is completed before lifecycle commit. Chunk tickets, player entities, pending spawn state, anchor consumption, and the exact persisted `DEAD` record roll back on any verification, publication, or persistence failure; no ghost `PAUSED`/`IDLE` state is exposed.
- Coordinator reconnect/restart reconciliation retains death facts, reissues one dead-state turn to the same selected provider/model, suspends stale active programs, and avoids duplicate replays. Successful respawn publication has an explicit action-result-before-goal-control barrier and stale-result tests.
- Only an observed, present, non-alive player creates `DEAD`. A missing player is handled as disconnect/recovery and never fabricates a death snapshot.
- Scenario death interception remains disabled, so scenario recovery cannot silently respawn or replace the model-authored lifecycle.
- A coordinator disconnect now increments an executor session generation, rolls back any in-flight physical respawn, and fences its result/lifecycle publication. Session closure also rechecks authentication after registry commit, while coordinator reconciliation ignores stale action results from the prior dead turn.
- The operator and client control surfaces no longer expose a respawn operation or `/codex respawn`; the `DEAD` presentation explicitly remains model-owned.
- Scenario action-success and state notifications are now emitted only after the paired action-result/goal-control publication and lifecycle commit succeed. Publication failures therefore leave scenario records untouched; the bridge verification covers failure and successful publication ordering.
- Post-commit scenario callbacks are best-effort and non-throwing. A telemetry exception is logged without affecting the already committed physical spawn, registry state, or queued success messages; the verification covers this throwing-callback case.

## Verification

- Focused coordinator tests: `node --test coordinator/test/program-runtime-manager.test.mjs coordinator/test/protocol-v2.test.mjs coordinator/test/dynamic-main.test.mjs` - **46/46 passed**, including disconnect while respawn is pending, reconnect, and stale old completion.
- Dirty-tree Java 25: `gradlew.bat verifyCore` using `runtime/toolchains/temurin-25/jdk-25.0.3+9` - **5,999 assertions passed**.
- Clean archive of commit `c7a4b49`, Java 25: `gradlew.bat clean verifyCore --rerun-tasks --no-daemon --console=plain` - **5,597 assertions passed**.
- Full coordinator suite: **289/291 passed**. The only two failures are the existing `coordinator/test/end-to-end.test.mjs` two-runtime fixture timeouts (`agents remain isolated while progressing concurrently` and `reconnects, retries malformed planner output, and shuts down with trace evidence`); they are the scheduled legacy failures and do not involve Task 8 protocol/lifecycle tests.

## Live validation boundary

No live Minecraft death/bed/anchor/keepInventory run was performed. The implementation delegates physical entity and vanilla player-data behavior to Carpet/Minecraft; the completed evidence is headless Java25 and coordinator verification. The shared worktree retains unrelated uncommitted changes; the implementation commit contains only the staged Task 8 slices and tests.
