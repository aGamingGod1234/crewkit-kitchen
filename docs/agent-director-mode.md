# Director mode

Director mode is the opt-in staging layer for repeatable short-form Minecraft
scenes. Enable it per world with `/codex skit on`. Normal agent control remains
available separately.

## In-game Director GUI

Open the Field Console with `G`, then choose **Director**. The four tabs keep the
same workflows available without typing commands:

- **Spawn** enables skit mode, chooses a provider and exact model slug, summons
  an actor at your position, and places it here, relative to you, or facing the
  point you are looking at.
- **Actions** creates a script and appends `move`, `wait`, `jump`, `equip`,
  `use`, `swing`, or `emote` actions. Enter the action arguments shown in the
  helper text, then play or stop the script.
- **Voice** selects the voice profile, tone, speed, and proximity radius, then
  sends a line or builds a short cue script.
- **Camera** records keyframes from your current camera, saves the take, and
  plays it once or on a loop. Camera paths stay local to the client.

The GUI sends the same validated server commands as the chat syntax, so existing
scripts and permissions continue to work. The **Agent selector** fields accept a
visible name or a normal Minecraft selector such as `@e`.

## Actor blocking

```text
/codex skit summon codex model gpt-6.1-sol "GPT 6.1-Sol"
/codex skit summon gemini model gemini-3.1-pro Gemini
/codex skit place ChatGPT here
/codex skit place Claude at 12 72 -4 180 0
/codex skit place Claude relative 2 0 4
/codex skit place Claude look_at 12 73 -4
```

Use `model` when you need an exact model slug. The model argument has tab
completion from the installed catalog. The technical Minecraft player name stays
safe and unique, while the visible tag uses the readable model or name, such as
`GPT 6.1-Sol` or `Gemini`.

`relative` uses right, up, and forward offsets from the operator's current view.
`look_at` keeps the actor at the operator and rotates it toward a world point.

## Actor timelines

```text
/codex skit script create takeoff ChatGPT
/codex skit script add takeoff 0 12 72 -4 180 0
/codex skit script action takeoff move 40 1 0 true
/codex skit script action takeoff equip minecraft:elytra
/codex skit script action takeoff jump
/codex skit script play takeoff
```

Action syntax is `action <script> <action> [arguments]`. Supported actions are
`move <durationTicks> [forward] [strafe] [sprint]`, `wait <durationTicks>`,
`jump`, `equip <namespace:item>`, `use [durationTicks]`, `swing`, and
`emote [durationTicks] [sneak]`. The action is appended at the agent's saved
placement. Delays are relative to the preceding step and 20 ticks equal one
second.

The runtime interpolates `move` poses but does not fake physics. An elytra actor
still needs the normal Minecraft equipment and flight conditions; the timeline
can stage the position/action cues but cannot guarantee a physically simulated
flight path.

## Voice cues

Install the optional Arena Agents Voice add-on and Simple Voice Chat for spatial
playback. Then choose a profile and schedule lines:

```text
/codex skit voice profile Claude voice.ember.v1 dramatic 1.05 64
/codex skit voice say Claude "You should not have come here."
/codex skit voice script create intro Claude
/codex skit voice script add intro 0 "Now run."
/codex skit voice script add intro 40 "I said run."
/codex skit voice script play intro
```

Profiles persist per agent. `profile` is the stable local voice identity, `tone`
supports `neutral`, `warm`, `excited`, `serious`, `dramatic`, `whisper`,
`robotic`, and `angry`, `speed` is bounded from 0.5 to 2.0, and `radius` is the
proximity-voice range in blocks. Use `voice.auto.v1` to return to the automatic
assignment. Cue text is bounded to 280 Unicode code points.

## Camera director

Camera paths are client-local, so each operator records the shot from their own
camera without mutating agents:

```text
/camera path start intro
/camera path keyframe
# move the player/camera to the next composition
/camera path keyframe
/camera path stop
/camera path play intro
/camera path play intro true
/camera path stop-playback
```

Playback uses smooth Catmull-Rom position interpolation, shortest-turn rotation,
and an invisible client-side camera anchor. Paths are stored in
`config/arenaagents/camera-paths.json`; `/camera path list`, `info`, `delete`, and
`clear` manage the local library.
