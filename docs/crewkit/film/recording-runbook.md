# CrewKit Kitchen: recording runbook (laptop)

Goal: one clean 3:00 MP4, 1080p60, uploaded with a working link before 21:00 SGT. Shot list: `docs/crewkit/film/shot-list.md`.

## Timeline (hard stops)

| Time (SGT) | What |
|---|---|
| **19:30** | Feature freeze. Merge what works, build once, stop coding. |
| 19:30–19:45 | Set up OBS, Minecraft and the phone (sections 1–3). One dry run in replay mode. |
| **19:45–20:15** | Record: live take first, then replay retakes for any shot that missed. Voiceover and organiser clip in parallel by a second person. |
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

## 4. The live take (with the phone)

1. Phone: Do Not Disturb on, brightness up, sandbox approval page will open from the QR. Start the phone's own screen recording (iPhone: Control Centre → Screen Recording; Android: Quick Settings → Screen record) **before** the run starts.
2. OBS scene `Game`, press `F9` to record.
3. In game: `/ckcam on` (HUD and chat hide, camera goes to `wide` and follows the run).
4. In the terminal (repo root): `node coordinator/src/crewkit/cli.mjs run --mode live --post`
5. When the QR shows on the pass, scan it with the phone, check the amount equals the board, approve. Keep the phone recording until Reap's page shows the result.
6. Let the run reach the bill board, hold 5 s on `[wide]` (`/ckcam` stays live; press `]` to step if needed), then `F9` to stop.
7. Stop the phone recording. Clap-sync is not needed: line the phone clip up with the moment the QR appears in-game.
8. Write down the tape path: the live run saves `crewkit-records/tapes/<runId>.tape.json`. Note the real waits (poll time) for the `Wait shortened` captions.

If the live take fails (Reap error, expired quote), the run sends `failed`/`expired` and the camera goes to the pass. Reset (section 3 step 6) and run again. Keep the failed footage; it is not needed in the film.

## 5. Replay retakes (no network, no phone)

Replays play back the Reap responses from a tape, so the kitchen does exactly what the live run did. Use them for any shot that missed or to get a cleaner camera pass.

```text
curl -X POST http://127.0.0.1:4777/crewkit/reset -H "content-type: application/json" -d "{}"
/ckcam on
node coordinator/src/crewkit/cli.mjs run --mode replay --tape crewkit-records/tapes/<runId>.tape.json --speed 1 --post
```

- `--speed 0.5` halves replay waits; caption those shots `Wait shortened`.
- Manual coverage during a replay: `/ckcam off` stops following; `/ckcam go line` (or `[` `]`) frames a close shot, e.g. the crosshair hover label on one item. For hover labels you need the HUD crosshair, so run `/ckcam off`, aim yourself, and press `F1` to hide the rest of the HUD.
- `/ckcam speed 45` slows manual glides (ticks; default 30).
- Footage from a replay is the same data as the live run but its approval is not live. Only use replay footage for the approval beat with the caption `Replayed from a recorded live run`.

## 6. Voiceover, organiser clip and code inserts

- **Voiceover:** record in the quietest room, phone or laptop mic 15 cm from the mouth, a jacket over the head kills echo. Read each beat from `shot-list.md` as its own clip, 3 takes each. Audacity (free): Effect → Noise Reduction (profile from 2 s of silence), Normalize to −1 dB, export WAV.
- **Organiser clip (0:00–0:20):** landscape, eye level, window light on the face, 10–15 s. Ask the prompt in the shot list; don't script their answer.
- **Code inserts:** OBS scene `Code`, 5 s each: terminal NDJSON `item_added` lines, `coordinator/src/crewkit/gate.mjs` gate check, the CSV in `crewkit-records/`. Hide the `.env` and anything with a key.

## 7. Edit (DaVinci Resolve free, or CapCut desktop)

1. New project 1920x1080, 60 fps. Import the remuxed game MP4s, phone clip, organiser clip, code clips, VO WAVs.
2. Lay the VO on track A1 first, beat by beat to the timestamps in the shot list. Cut picture to the VO, not the other way round.
3. Game audio on A2 at about −18 dB under the VO; let the sounds land in gaps (item pop, gate, bell).
4. Phone clip picture-in-picture on the right 40% during 1:45–2:15, with a thin white border.
5. Speed up long waits (poll, delivery) with a clean speed ramp and add `Wait shortened · real time [mm:ss]`.
6. Captions from the shot list: white text, dark box at 60% opacity, 42 px+, lower third. The standing `LIVE · Reap Agentic API sandbox · SGD · no real money moves` label top-left from 0:20.
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
