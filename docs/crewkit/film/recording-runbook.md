# CrewKit Kitchen: recording runbook (laptop)

Goal: one clean 3:00 MP4, 1080p60, uploaded with a working link before 21:00 SGT. Shot list: `docs/crewkit/film/shot-list.md`.

## Timeline (hard stops)

| Time (SGT) | What |
|---|---|
| **19:30** | Feature freeze. Merge what works, build once, stop coding. |
| 19:30–19:45 | Set up OBS and Minecraft (sections 1–3). One dry run of the replay. |
| **19:45–20:15** | Record: master replay take at speed 1, then take B close-ups. Voiceover and organiser clip in parallel by a second person. |
| **20:15–20:40** | Edit (section 7). |
| 20:40–20:50 | Export, upload, open the link in a private window on a phone and watch it to the end. |
| 20:50–21:00 | Submit. Buffer only. |

## 1. OBS settings (1080p60)

1. Settings → Video: Base and Output resolution `1920x1080`, Common FPS `60`.
2. Settings → Output → Output Mode `Advanced` → Recording:
   - Recording Format `mkv` (survives a crash), then File → Remux Recordings to `mp4` afterwards.
   - Encoder: `NVIDIA NVENC H.264` if available, otherwise `x264`.
   - Rate control `CQP` level `18` (NVENC) or `CRF` `18` (x264), Keyframe interval `2 s`, preset `Quality`.
3. Settings → Audio: Sample rate `48 kHz`. Desktop Audio on (game sounds), Mic/Aux **muted** (voiceover is recorded separately).
4. Settings → Hotkeys: Start/Stop Recording `F9`. Never alt-tab during a take.
5. Scenes:
   - `Game`: Source Game Capture → "Capture specific window" → Minecraft. Fallback: Window Capture (Windows 10+ capture method).
   - `Code`: Window Capture of VS Code / terminal, font size 18+, dark theme, nothing private on screen.
6. Recording path: a folder with 20 GB free. 1080p60 CQP 18 is roughly 1–2 GB per 10 minutes.

## 2. Minecraft setup

1. Laptop on mains power, Windows Focus Assist on, Discord/Slack/notifications closed.
2. Options → Video: Fullscreen off, then resize the window to fill the 1920x1080 display (or use borderless). Max framerate `60`, VSync on, Render distance `12`, Smooth lighting on, GUI scale `3`, FOV `70`, View bobbing off.
3. Options → Chat: Narrator off. Accessibility: subtitles off.
4. Controls → Key binds → Misc: confirm `CrewKit camera: next mark` = `]`, `previous mark` = `[`, `release` = `\`.

## 3. Launch order

Do **not** run `scripts/prepare-runtime.ps1`: it reads the `New World (76)` folder, which must never be opened. Use the kitchen world the set track built.

1. Start the server with the kitchen world (Arena Agents Fabric server, from the repo root):
   `powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1`
2. Start the coordinator in a second terminal (it carries the CrewKit trigger on `127.0.0.1:4777`):
   `powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1`
3. Launch Minecraft with the Arena Agents Fabric profile and join `127.0.0.1`.
4. In game, as operator:
   ```text
   /gamemode spectator
   /gamerule doDaylightCycle false
   /gamerule announceAdvancements false
   /time set noon
   /weather clear
   ```
   Then fly to the camera wall (`ck_player`, south edge of the kitchen) so the kitchen chunks stay loaded and your body is behind the camera.
5. Check every mark before rolling: `/ckcam list`, then `/ckcam go wide`, `]` through all 8 marks, `\` to release. Each move glides; if a mark frames badly, note it for the director track (marks live in `CrewkitMarks.java`).
6. Reset the kitchen: `curl -X POST http://127.0.0.1:4777/crewkit/reset -H "content-type: application/json" -d "{}"`

## 4. Master take: the default replay (no new live take)

We do not do a new live take. We record the default replay of the recorded real sandbox run. Do not pass `--tape`.

1. OBS scene `Game`, press `F9` to record.
2. Make sure the replay mode is set. Either run the CLI from the repo root:
   `node coordinator/src/crewkit/cli.mjs run --mode replay --speed 1 --post`
   or trigger it by talking to Chef (or `/msg Chef <order>`) with these set:
   ```text
   $env:CREWKIT_AGENT_MODE="replay"; $env:CREWKIT_REPLAY_SPEED="1"
   ```
   The environment must be in the launcher's environment before the game starts its coordinator. Set both as user environment variables, then restart the launcher and the game.
3. After joining the world, in this order:
   ```text
   /crewkit build
   /crewkit stage
   /codex voice-consent on
   ```
   Wait for `Chef | Ready` in chat, then `/ckcam on`.
4. Start the run. Record the master take at speed 1: the voice lines need the 6 s gaps. Cut the waits in the edit and add `Wait shortened · real time ~4:30`.
5. Let the run reach the bill board, hold 5 s on `[wide]` (press `]` to step if needed), then `F9` to stop.
6. Between takes: `/crewkit reset`, then `/crewkit chef ready`.

There is no phone shot. The approval caption reads `Recorded from the real run: human passkey approval on Reap's hosted page (sandbox)`. Use picture-in-picture only if real approval footage exists. Never film a phone scanning the replay QR: it encodes the GitHub URL.

## 5. Take B: close-ups and the voice clip

- Close-ups: run the same replay at `--speed 0.5` with `/ckcam off`. Frame by hand with `/ckcam go line` (or `[` `]`). For the crosshair hover label on one item, aim yourself and press `F1` to hide the rest of the HUD. Caption those shots `Wait shortened`.
- `/ckcam speed 45` slows manual glides (ticks; default 30).
- Voice conversation clip: record a 15 s conversation with Chef (organiser speaks, Chef answers) for the 0:12 beat.
- OBS must capture game audio, including Simple Voice Chat. Check the Desktop Audio meter moves when Chef speaks.
- Turn off the minimap and any FPS overlay. Press `F1` to hide the HUD when `/ckcam` is off.

## 6. Voiceover, organiser clip and code inserts

- **Voiceover:** record in the quietest room, phone or laptop mic 15 cm from the mouth, a jacket over the head kills echo. Read each beat's voiceover from `shot-list.md` as its own clip, 3 takes each. Audacity (free): Effect → Noise Reduction (profile from 2 s of silence), Normalize to −1 dB, export WAV.
- **Organiser clip (0:00–0:12):** landscape, eye level, window light on the face, about 10 s. Ask: "Tell us about the last time you bought supplies for a club workshop. What went wrong with the budget or the receipts?" Don't script their answer.
- **Code inserts:** OBS scene `Code`, 5 s each: terminal NDJSON `item_added` lines, `coordinator/src/crewkit/gate.mjs` gate check, the CSV in `crewkit-records/`. Keep the `.env` and anything with a key off screen.

## 7. Edit (DaVinci Resolve free, or CapCut desktop)

1. New project 1920x1080, 60 fps. Import the remuxed game MP4s, organiser clip, voice clip, code clips, VO WAVs.
2. Lay the VO on track A1 first, beat by beat to the timestamps in the shot list. Cut picture to the VO, not the other way round.
3. Game audio on A2 at about −18 dB under the VO; let the sounds land in gaps (item pop, gate, bell).
4. Only if real approval footage exists: picture-in-picture on the right 40% during 1:30–1:52, thin white border. Otherwise skip.
5. Speed up long waits (poll, delivery) with a clean speed ramp and add `Wait shortened · real time ~4:30`.
6. Captions from the shot list: white text, dark box at 60% opacity, 42 px+, lower third. The standing `Replayed from the recorded real sandbox run` label top-left for the whole demo.
7. End card 5 s: project name, repo URL, team names, the prior-work line.
8. Watch once on the laptop and once on a phone with sound off: every caption must be readable and every number must match the board.

## 8. Export and upload

1. Export H.264 MP4, 1920x1080, 60 fps, 16–20 Mbps, AAC 320 kbps. Check the length is ≤ 3:00.
2. Upload to YouTube as **Unlisted** (or the host the submission form asks for). Title: `CrewKit Kitchen · Reap x 65labs Agentic Buildathon`.
3. Wait for HD processing, then open the link in a private window on a phone and watch it to the end with sound. Paste the link into the submission form and the README.

## Troubleshooting

| Problem | Fix |
|---|---|
| `/ckcam on` says join the world first | You are in agent view (`/spectate exit`) or not in a world. |
| Camera does not follow events | Run `/ckcam on` again; check the coordinator terminal shows the run's events. `/ckcam go <mark>` works without events. |
| Marks frame the wrong place | The set origin differs; the director reads it from the server. Use `/ckcam go` and adjust `CrewkitMarks.java` before 19:30 only. |
| Kitchen chunks missing in the shot | Your spectator body is too far away; fly back to the camera wall. |
| HUD shows | `/ckcam hud true`, or press `F1`. |
| Need the normal view back | Press `\` or `/ckcam off`. |
