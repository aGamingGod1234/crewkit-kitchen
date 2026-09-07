# PR 36 independent audit

Six subagents split independent verification, CodeRabbit triage, other bot triage, server lifecycle review, client review, and Windows CI diagnosis. Follow-up reviews checked the fixes. Review began at `ef1e9e95` against base `4f60f00c`.

## Bot review status

GraphQL and REST both returned no review threads or line comments. There was nothing to resolve. CodeRabbit supplied a summary, and its latest attempt was rate-limited. Codex's earlier summary had no findings; Copilot hit its quota. These statuses were not treated as current-head review approval.

## Findings fixed

| Finding | Cause and correction | Proof |
| --- | --- | --- |
| Offline ownership migration missed current Minecraft files | Reservation checks used legacy paths. They now use `LevelResource` paths for current player data, statistics and advancements, while retaining legacy checks. | All ten current/legacy artifact cases execute in tests. A live GUI summon using an orphaned current-path player-data fixture is rejected. |
| Server command failures were hidden behind Director | The GUI optimistically reported submission while errors went to obscured chat. A bounded, correlated request/result channel now returns the actual server result to the form. | Live red rejection in the open form; tests cover permissions, command scope, wire bounds, zero-result success and stale responses. |
| Background updates interrupted typing | The Director compared volatile normal-agent records and rebuilt widgets. It now compares only imported IDs/names and retains the focused editor. Explicit focus keys also remain distinct instead of being truncated at their colon. | Real Screen/EditBox tests cover later fields, caret/selection retention and distinct cycle controls under keyboard and mouse focus. |
| Hardcore connected respawn applied human death policy to actors | Vanilla's client respawn handler changes hardcore players to Spectator and changes a global gamerule. Cast-only body replacement now preserves fake-player identity, resets connection state and returns Survival mode. Normal agent respawn is unchanged. | Pinned Minecraft/Carpet call tracing, full build; final native proof recorded below. |
| Windows Java/package CI failed during fixture cleanup | Windows sharing violations throw `FileSystemException`, bypassing the old retry for `AccessDeniedException`. Cleanup now handles both with the existing bounded retry and still throws persistent failures. | Real locked-file reproduction failed with the old helper and passed with the fix; the new regression and complete startup smoke pass in the full build. |

## Independent verification

The baseline passed 14,796 core assertions. Native Computer Use then checked disabled summon, GUI enable, retry with the same name, same-world duplicate rejection, separate normal/cast rosters, the same name in two worlds sharing a profile cache, dead-state persistence after switching worlds, and manual respawn with the original actor identity.

The combined build passes 14,845 core assertions and 385 voice-addon assertions, plus camera and packaging checks. Local evidence is in `build/pr36-review/final-build.log`. The independent baseline trace and report are in `build/pr36-review/independent-client.log` and `independent-verification.md`.

The final native run uses an isolated hardcore copy. Its trace is `build/pr36-review/final-hardcore-client.log`. The current-path ownership fixture is protected and its rejection is visible in the Director screen. Lethal damage followed by manual hardcore respawn preserved the actor identity, returned Survival mode, and allowed placement controls to move the body. All 58 saved gamerules were unchanged, including spectators_generate_chunks=true. The protected player-data sentinel was unchanged. The client saved and closed cleanly. Saved-state proof is in `build/pr36-review/hardcore-state-proof.log`.

## Cleared and limited cases

- The world name index is saved per world; shared lookup-cache presence alone is not ownership.
- Saved cast, normal registry, live players, pending fake spawns and retained cancellations protect their physical names. A suspected duplicate-respawn race was cleared by the existing `OfflineAgentPlayers.spawn` guard.
- The new command bridge keeps the player's entity, location and permissions, checks operator permission before execution, and restricts requests to the skit subtree. It does not elevate command authority.
- Old cast identities remain stable through save/reload and conversion. Ordinary agent hardcore respawn is unchanged.
- Live authenticated multiplayer, audible speech, and complete camera recording were not exercised in this pass. Their relevant existing automated checks passed where available. No exhaustive absence-of-bugs claim is made.

Main JAR SHA-256: `859C74D99B39C83F3633F4A3E1355E2502A6C7BD98B5D44EE2889CC3B65AE604`.
