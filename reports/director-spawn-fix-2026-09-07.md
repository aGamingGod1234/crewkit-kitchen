# Director spawn fix verification, 2026-09-07

The Desktop log reproduced the screenshot at 14:00:12 and 14:00:24. The Director displayed an example name as a placeholder, then sent no actor name. The real production Brigadier tree reproduced the same cursor 42 error after `gpt-5.6-luna`.

The fix labels the actor name as required, rejects blank required names/selectors and speech before building commands, and checks complete commands against the server-provided client command tree. Optional playback actor overrides remain optional. Diagnostics record schema paths, positions, lengths, provider/model, IDs and failure codes, without copying actor names or speech into the new diagnostic messages. See `SkitDirectorScreen.java:221`, `:296`, and `:433`.

Live testing exposed a second independent bug in `CodexAgentManager.java:302`, introduced in 147ddcfac. The collision check called Minecraft's resolving name getter. An unknown name caused remote lookup followed by offline fallback, which populated the cache and marked each proposed suffix occupied. The allocation loop did not terminate during the test.

The replacement uses a narrow Fabric accessor to inspect existing cached names without resolving. It also reserves existing offline UUID playerdata, backup playerdata, stats and advancement files by existence, including corrupt artifacts. It does not delete cache entries or human data. The failed test had created 293 reserved candidates; the fixed build skipped them and spawned the next available candidate within the same logged second.

Safety facts and proof:

- New names must not become reserved merely by checking availability. `SkitModeVerification.verifyNameReservations` runs the real pinned Minecraft resolver with a counting repository. Production reservation checks make zero repository calls; the old getter makes one and manufactures an offline profile. Cached online human names remain reserved case-insensitively, and all four saved-artifact types remain protected even when corrupt. Proven by executable tests and live spawning.
- A complete Director command must remain executable after Minecraft sends its command tree to the client. Two independent read-only reviews checked vanilla executable-node serialization. Live Computer Use sent spawn and placement commands through that actual client tree successfully. Empty actor and selector forms produced local feedback. Proven in the running app.

Validation:

- `gradlew.bat build`: BUILD SUCCESSFUL. 14,769 core assertions, 385 voice assertions. Camera mixin verification and entrypoint checks passed.
- Real parser checks cover blank final arguments, optional playback override, names with spaces/quotes/Unicode, and model IDs containing slashes.
- Computer Use on Mini PC: empty actor prompt; empty middle selector prompt; successful named spawn; recovery from polluted cache; actor restored after world reload; fresh StageActor spawn; both actors visible in roster; relative placement succeeded.
- Local dev-profile startup initially lacked Carpet and packaged coordinator runtime. These were added only to the isolated test profile. A focus-loss pause delayed roster publication; resuming simulation completed the handshake and showed both actors.
- Desktop normal-profile updater succeeded and verified installed JAR SHA256 `BB1BE2B236FAAA2595971871A5DA3B3D78A2130559970D7728D7DBEFBBA07673`. Previous installation backed up by updater.

Remaining limits: live autonomous provider planning and live speech generation were not exercised. This task verified character creation, identity preservation, restoration, roster publication, and placement. The reservation accessor is pinned to Minecraft 26.1.2 and must be checked on a Minecraft upgrade. Existing failed-attempt cache names remain conservatively reserved.

Recheck before merging with `gradlew.bat build`, then use an empty name followed by a fresh valid name in Director and verify its roster entry.


