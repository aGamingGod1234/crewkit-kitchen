# World-scoped player names

The reported failed summon did not create an actor. Logs show it stopped at `SKIT_MODE_DISABLED`. The retry was blocked because Minecraft's shared profile cache already contained `GPT_6_Astra` from a previous world. Treating cache presence as ownership was wrong.

`WorldPlayerNames` now persists a case-insensitive name index in each world. Successful player joins populate it. Existing saves import a name on demand only when the current world contains playerdata, backup playerdata, statistics or advancements for the matching offline or cached online UUID. Shared-cache entries alone do not reserve names. Neither lookup nor failed disabled-mode summons manufacture identities.

Live players, normal agents, the saved cast, Carpet's pending spawns and both retained cancellation ledgers still reserve their identities. This prevents cleanup from removing a new actor that reused a pending body's name. No existing actor identity or player data is migrated or deleted.

The UI toggle and chat consistently say skit mode. The screen is named Skit Director. Bare `/codex skit` now prints status and complete enable/disable commands instead of an incomplete-command error. Normal agent goal completion once again suggests normal agents.

## Proof

The central fact is that a cached name from another world must remain available here while this world's players stay protected.

- Level 5, running Minecraft through Computer Use: seeded the isolated profile cache with the reported `GPT_6_Astra` identity, created a fresh world, attempted a summon while disabled, ran bare `/codex skit`, enabled with `/codex skit on`, and retried through the UI. `GPT_6_Astra` appeared Alive under its original name. The nonempty-cache path exercised the new nested profile accessor successfully.
- A second summon of that name was rejected with a world-specific collision message. The ordinary agent roster remained empty.
- The saved world's `player_names.dat` contains the actor and human join names. Screenshot: `run/screenshots/2026-09-08_00.58.15.png`. Local log: `build/director-separation/world-name-client.log`.
- Level 4, executed tests: cache-only names are available; online/offline artifacts in the current world are protected; another world's artifacts do not reserve the name; all four artifact types remain protected even if corrupt; world index serialization retains case-insensitive ownership despite shared-cache changes; mode command forms parse as executable commands.
- Final `gradlew.bat build` passed with 14,796 core assertions and 385 voice-addon assertions, plus packaging and camera checks. Log: `build/director-separation/world-name-final-build.log`.
- Two independent read-only reviews checked command behavior, identity persistence and cleanup reservations. A review correction makes artifact import store the UUID that actually matched. That final metadata correction passed the full build after the live test.

Historical unmanaged player names that were never recorded and whose old global-cache mappings have already been overwritten cannot be reconstructed reliably from UUID-only files. Newly observed world names are independent of later global-cache changes. Pending cancellation behavior was reviewed and covered by existing ledger checks, not reproduced as a live race in this session.

The focused reproduction before merging is the disabled summon, enable, retry sequence in a fresh world with an existing shared-cache entry. It passed locally without the AI coordinator.

## Build

Main JAR SHA-256: `1CF7C00EE4DF1B371FD780F8F069BABCC0864444BB7695D049ED6725FDA219EB`.

Desktop installation evidence is kept locally in `build/director-separation/desktop-world-names-update.log`. The installer preserves a rollback backup and checks the installed hashes.
