# Task 8 — Model-authored vanilla respawn

Commit: `bfb38e1` (`feat: require model-authored vanilla respawn`)

## Delivered

- Persisted exact `AgentDeathSnapshot` facts through the registry reducer and snapshot codec, including backward decoding of snapshots without `death_snapshot`.
- Removed normal coordinate-based/automatic respawn. A dead agent remains dead until its selected provider emits authored `await player.respawn()` with no coordinates.
- The server resolves the vanilla bed, anchor, or world spawn only while executing that primitive; it does not restore inventory, XP, drops, or gamerules.
- Added dead-state protocol facts, provenance/revision gating, and lifecycle-first respawn ordering so stale pre-death results cannot advance a restarted program.
- Disabled scenario death interception: `recoverParkourDeath` is explicitly a no-op and returns `false`.

## Verification

- Focused coordinator tests: `node --test coordinator/test/protocol-v2.test.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/dynamic-main.test.mjs` — **42/42 passed**.
- Dirty-tree Java 25: `gradlew.bat verifyCore` — **5,985 assertions passed**.
- Clean archive at commit `bfb38e1`, Java 25: `gradlew.bat verifyCore` — **5,585 assertions passed**.
- Full coordinator suite: **281/283 passed**. The two failures are the existing `end-to-end.test.mjs` two-runtime fixtures timing out after about five seconds; all Task 8 protocol, lifecycle, and runtime tests passed.

## Risks / remaining live validation

- No live Minecraft death/bed/anchor/keepInventory run was performed. The implementation delegates target selection and player data semantics to vanilla/Carpet rather than reconstructing them in mod code.
- The shared worktree intentionally retains unrelated uncommitted changes; this commit contains only the staged Task 8 slices and necessary predecessor context.
