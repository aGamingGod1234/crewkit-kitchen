# Director cast fixes, 7 September 2026

Director actors now have a saved cast independent of ordinary agents. Death retains the actor and its saved placement. Respawn is manual. Desktop's installed main JAR matches the verified build.

## Causes and changes

The old summon path registered Director characters as ordinary AI agents. That put them in the main roster and gave them the ordinary agent lifecycle. `SkitActors` now owns physical actor creation, death, respawn and removal, backed by a separate saved cast and client payload. It does not need the AI coordinator to start.

Carpet handles lethal damage and its direct kill command differently. The death hook retains a dead body; presence tracking also catches a fake player removed directly. Both paths persist the dead cast entry. See `src/main/java/dev/agaminggod/arenaagents/server/SkitActors.java:63`, `:89` and `:136`.

The Director screen now uses one cast selector throughout its actor controls. New actor name and Appearance replace the duplicate name fields and AI model selector. Enable/Disable follows server state. Labels, tab spacing and scrolling keep fields accessible. Returning to the console refreshes its local roster, including after conversion. See `src/client/java/dev/agaminggod/arenaagents/client/gui/SkitDirectorScreen.java:164` and `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java:138`.

Two independent read-only reviews checked lifecycle and separation. Follow-up fixes reject conversion during pending spawn, respawn or cleanup; preserve placement if conversion fails; stop voice playback for missing or dead actors; and restrict Director command suggestions to the cast.

## Evidence

The central safety fact is that cast membership survives loss of the physical body without creating an ordinary AI agent. This reached level 5 of the blast-radius proof scale: reproduced in the running application.

- Native Computer Use on Mini PC verified creation, separate rosters, Enable/Disable, legacy conversion, action playback, lethal damage, direct `/kill`, manual respawn and world reload.
- After `/kill`, CastProof remained Dead through a save and reload. Clicking Respawn changed it to Alive with the same actor ID. The final run deliberately used an unavailable AI coordinator and still restored live cast members and manually respawned the dead member.
- A zombie also killed StageActor during that run. The log recorded that it remained in the cast for manual respawn.
- Screenshot `run/screenshots/2026-09-07_23.33.04.png` shows the persisted dead cast member after reload. `build/director-separation/client-verified.log` records the subsequent manual respawn at 23:33:15.
- Full Gradle build passed in `build/director-separation/build-verified.log`, including 14,782 core assertions and 385 voice-addon assertions. After adding four conversion-state assertions, `verifyCore` passed with 14,786 assertions in `build/director-separation/conversion-proof.log`. No production code changed after the full build.
- Executed codec and UI checks cover old saves, dead actor identity and placement, name reservation, the dedicated payload, and all four Director tabs at a compact viewport. Conversion tests call the actual manager guard. These facts reached level 4, executed tests.

Live audible voice output and complete camera recording were not exercised. Their existing automated checks passed and the screens were inspected, but full end-to-end behavior remains unproven in this session. Permanent cast removal and delayed-spawn cancellation were reviewed rather than reproduced live.

## Compatibility and installation

Old saves have no reliable Director ownership marker. They load without silently moving ordinary agents. For the existing Desktop character, open Director, scroll to the conversion row, choose `GPT_6_Astra`, click Move to cast, then select it in the cast and click Respawn. Commands are also available: `/codex skit adopt GPT_6_Astra`, then `/codex skit respawn GPT_6_Astra`.

Desktop identity and closed Minecraft state were checked before installation. The normal-profile installer completed successfully and checked bundled runtime packaging. Installed file hashes were read back remotely:

- Main JAR SHA-256: `7748A5D7BCF40044C54F8C398D228803F661E5019644BAA5FEE1E81DE961932F`
- Voice JAR SHA-256: `9640383BE8257905FC1BEED74F4CE6813052AE2FDF0910571A1E3486EB1CF1DB`
- A rollback backup was preserved in the game directory. The local installer log records its location.
- Installer evidence: `build/director-separation/desktop-update.log`.

The local verification world was saved and Minecraft closed. Desktop Minecraft was not launched. These changes extend PR 36 on `codex/fix-director-spawn-validation`.

Before merging, the focused reproduction is to spawn a Director actor, kill it, reload the world, verify it remains Dead in the cast and absent from the ordinary roster, then click Respawn. That sequence passed locally on the installed build's production code.
