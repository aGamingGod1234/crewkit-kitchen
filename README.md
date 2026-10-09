# CrewKit Kitchen

**An AI chef turns a workshop attendee list into a complete supply order through Reap's Agentic API, inside a small approved budget, and a Minecraft kitchen shows every step.**

Built for the Reap x 65labs Agentic Buildathon (Singapore). Track: Most Worthwhile Problem. Build path: Reap Agentic API (sandbox).

Demo video: TODO (link)

## The problem

**The user:** a student-club workshop organiser. They have an attendee list and a small approved budget, and they need a complete supply order: badges, notebooks, pens, drinks, shared cables, a speaker.

**The pain:** turning that list into an order is tedious and easy to get wrong. The organiser has to compare products across listings, work out which items are per person and which are shared, add shipping to the total, and remember everything. One forgotten item or one over-budget cart means a second order.

**Why an agent, and why this one:** an agent can do the legwork, but the organiser still has to trust the result. CrewKit makes the agent's work checkable. Minecraft is the shared visual interface that shows the state of the order to everyone in the room. It is the display, not the point.

## What CrewKit does

The organiser gives the chef a brief: guests, a budget, and what each person needs. The chef searches Reap's catalogue, builds a cart, and asks Reap for a quote. Two server checks run before any checkout:

1. **Requirements check.** Every mandatory quantity is covered, for example one badge per guest and one cable per pair.
2. **Budget gate.** The quote total, shipping included, is within budget.

If either check fails, no checkout is created. For a budget failure, the chef reworks the cart by substituting cheaper products while keeping the requirements. If nothing fits, the brief is reported as infeasible instead of quietly dropping items.

When both checks pass, Reap returns a hosted approval page. A human scans a QR code and approves there. When Reap reports the order as `COMPLETED`, the kitchen shows the order as placed, plays the delivery and plating scene for each named guest, and stamps a bill board with budget, quoted, charged, variance, and order id. Order placed; delivery visualized. The sandbox does not ship anything.

The room is the audit log. A red ticket means over budget. Dropped gate bars mean the checkout is blocked. A bag at the door means Reap reported the order placed.

> TODO: organiser testimonial (quote, name, club)

## What's real: a recorded sandbox run

A real Reap sandbox run completed, and the demo replay is that recorded run (the approval URL is redacted).

- **Order:** `ord_01M4G0J3JSEP31G7NASSA0K649` at Popular Bookstore (Singapore). 6 guests, budget S$105.
- **Gate fired:** the first real quote was S$196.85, so the server gate blocked checkout.
- **Stock was limited:** badges and notebooks were sold out at quantity 6, and no single cable listing had 3. The agent swapped to in-stock listings and split quantities (cables 1+1+1, notebooks 3+3).
- **Rework:** the quote went S$196.85, S$137.25, S$131.65, S$102.75. It dropped only the optional sticky notes.
- **Checks passed:** the requirements check and the budget gate both passed. A human approved on Reap's hosted page with a passkey.
- **Result:** `COMPLETED`, charged S$102.75, variance S$0.00. The run used 154 Reap API calls. Reap returned several 503 responses and our backoff recovered.

This is the sandbox. No real money moved, and delivery is visualized, not shipped.

## What you see in the kitchen

- A fan of candidate products from real search results.
- Ghost plates for the cart, which turn solid once paid.
- A typewriter bill and a written-book receipt.
- The chef answers by voice (OpenAI speech-to-text and text-to-speech through Simple Voice Chat) and speaks lines at key beats.
- The chef is summoned and placed automatically. It cannot mine or wander.

## How it works

```mermaid
flowchart LR
    Human([Human: writes brief]) --> Chef[Chef agent<br/>LLM plans the cart]
    Chef -->|tool calls| Engine[CrewKit engine<br/>coordinator/src/crewkit]
    Engine -->|search, quote, checkout, poll| Reap[Reap Agentic API<br/>sandbox]
    Engine --> Gate{Server checks:<br/>requirements + budget gate}
    Gate -->|fails: rework or infeasible| Chef
    Gate -->|passes| Engine
    Reap -->|approval URL| Page[Reap hosted approval page]
    Page -->|QR scan, approve| Human2([Human approves on phone])
    Human2 --> Page
    Engine -->|crewkit_state events| Bridge[crewkit_state bridge]
    Bridge --> Mod[Fabric mod<br/>Minecraft kitchen]
    Mod --> Room[Ticket, counter, gate, QR,<br/>delivery bag, plating, bill board]
```

1. **Chef agent.** An LLM reads the brief and chooses what to search for and buy. It works through a small set of tools and never touches money directly.
2. **CrewKit engine.** Node code in `coordinator/src/crewkit/`. It calls Reap's endpoints, holds the API key, runs the budget gate, and emits events.
3. **Reap Agentic API.** `POST /agentic/products/search`, `POST /agentic/quotes`, `POST /agentic/checkouts`, then `GET /agentic/checkouts/:id` until the status is terminal.
4. **`crewkit_state` bridge.** One new message type on the existing coordinator-to-server bridge. Each event carries a run id and a sequence number, so the mod ignores stale or repeated events. See [docs/crewkit/CONTRACT.md](docs/crewkit/CONTRACT.md).
5. **Fabric mod kitchen.** Turns each event into a physical action in the world.
6. **Human approval.** Happens on Reap's own hosted page, not in our code.

## Money safety

- **Two checks live on the server.** The chef can propose any cart. Our code checks that every mandatory quantity is covered and that the shipping-inclusive quote is within budget. Over-budget quotes cannot create a checkout through our tool. The model cannot skip these checks. This is a property of our tool, not of Reap's API: a client that bypasses our tool is not covered.
- **The key and card never reach the LLM.** The Reap API key stays in a gitignored `.env` read by the engine. Card details are entered on Reap's hosted pages and never pass through our code or logs.
- **A human approves every purchase.** Reap mandates (pre-approved spending) are not live yet, so each checkout needs approval on Reap's hosted page.
- **Show the delivery scene only on `COMPLETED`.** Reap defines it as "the merchant order is placed". `PROCESSING`, `FAILED`, and `EXPIRED` never trigger it. The scene visualizes delivery; it does not confirm that goods arrived.
- **Idempotency.** Every enrollment, quote, and checkout call carries an `Idempotency-Key` that is stored before sending. A retry replays the same result and cannot double-buy.
- **Reconcile against what was charged.** The bill board uses `finalAmount` from the checkout, not the quote.

## Honest disclosure

- The Minecraft agent platform underneath is **Agent Arena**, existing work by the project owner, published under MIT. The fork's base content is kept below.
- **Built at the event:** the Reap purchasing client, the chef and its tools, the budget gate, the run record, the `crewkit_state` bridge message, the kitchen set, the visual choreography, and this documentation. Everything under `coordinator/src/crewkit/`, `src/main/java/dev/agaminggod/arenaagents/crewkit/`, and `docs/crewkit/` is new.
- This runs against the **Reap sandbox**. No real money moves and nothing ships.
- Two things are separate in a demo run. **Hosted approval** is a real human step on Reap's sandbox page. **Simulated completion** (`X-Simulate-Checkout: COMPLETED`, used in `simulate` mode) skips that step, and the run is labeled simulated.
- CrewKit makes no onchain claims.

## Quick start

1. **Game and Java.** Minecraft Java 26.1.2 with Fabric Loader 0.19.3, Fabric API, and Fabric Carpet. Java 25.
2. **Mod.** Put the CrewKit jar from [Releases](https://github.com/aGamingGod1234/crewkit-kitchen/releases) into `mods/`. Only one `arenaagents` jar may be installed, so remove any other Agent Arena jar.
3. **Voice (optional).** Install Simple Voice Chat 2.6.21 or newer and the `arena-agents-voice` jar. Set `OPENAI_API_KEY` as a user environment variable.
4. **Node for the coordinator.** The mod needs Node 22 or newer at this exact path, and it does not use the system PATH:
   ```text
   .minecraft\arena-agents-runtime\runtime\toolchains\node\node.exe
   ```
   You can point somewhere else with the JVM flag `-Darenaagents.nodePath=<path to node.exe>`.
5. **Reap key (simulate or live mode only).** Put `REAP_API_KEY` and `REAP_EMAIL` in `coordinator/src/crewkit/.env` (gitignored). Replay needs no key.
6. **In game.**
   ```text
   /crewkit build            (or: /crewkit build here)
   /crewkit stage            (summons Chef, Claude Opus 5.5 low; the Claude CLI must be logged in)
   /ckcam on
   /codex voice-consent on
   ```
   Then talk to Chef, or send `/msg Chef <order>`. The exact demo message is in [docs/crewkit/AGENT-RUN.md](docs/crewkit/AGENT-RUN.md).
7. **Between takes.** `/crewkit reset`, then `/crewkit chef ready`.
8. **Developer CLI.**
   ```text
   node coordinator/src/crewkit/cli.mjs run [brief] --mode replay|simulate|live
   ```

| Mode | What it does |
|---|---|
| `replay` | Plays a recorded run through the kitchen. No network. |
| `simulate` | Calls the sandbox with `X-Simulate-Checkout: COMPLETED`. Completion is simulated. |
| `live` | Full flow: sandbox card, hosted approval page, polling. Approval is a real human step. |

Docs: [contract](docs/crewkit/CONTRACT.md), [brief and script](docs/crewkit/brief-and-script.md), [Reap API notes](docs/crewkit/reap-api-notes.md), [submission text](docs/crewkit/SUBMISSION.md), [judge Q&A](docs/crewkit/JUDGE-QA.md).

## Team

The Greek Warriors. `TODO: member names`

Repository: https://github.com/aGamingGod1234/crewkit-kitchen (public, MIT)

## License

MIT. See [LICENSE](LICENSE).

---

# Agent Arena (base platform)

## Arena Agents: summonable AI-controlled players for Minecraft

Arena Agents is a Fabric + Carpet mod pack and local coordinator for Minecraft Java 26.1.2. It adds offline fake players controlled through local provider CLIs. Codex and Claude use their existing local CLI logins (Codex CLI and Claude Code); the mod stores no provider API keys. The Gemini provider ID remains visible for saved-profile compatibility, but production planning fails closed because Antigravity CLI has no enforceable no-tool boundary.

Each agent is a real `ServerPlayer` with vanilla collision, gravity, health, hunger, inventory, and Survival/Creative/Adventure capabilities. It also has its own provider, model, thinking setting, planner session, provider-scoped working directory, goal queue, lifecycle, readable model name, and provider-themed client skin.

The coordinator uses the selected provider's control path for every summonable NPC: Codex and Claude both drive the body through the same native Minecraft tools, workspace, AGENTS.md, and minecraft-control skill. The former fixed two-client planner is retired and is not a runtime fallback.

### Safety boundaries

- The authenticated bridge binds only to `127.0.0.1:25570` and accepts bounded, schema-validated Minecraft actions, not shell or filesystem tools.
- A shared secret of at least 32 characters is stored in `runtime\bridge-secret.txt`, passed to the server by file path, and exposed to the coordinator only through its process environment.
- Goal revisions and action IDs prevent late Codex turns or delayed Minecraft results from controlling a newer goal.
- Stop, steer, remove, disconnect, timeout, and shutdown paths cancel outstanding work.
- The default scheduler permits all 16 registered agents to plan concurrently. Each agent still has at most one provider turn and one physical world action in flight.
- The original world at `%APPDATA%\.minecraft\saves\New World (76)` is never opened by preparation or automated verification; scripts work from a copied runtime world.

### Build and automated verification

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-automated-verification.ps1
```

This source-checkout command expects the project-local Java 25 toolchain under `runtime/toolchains/temurin-25`. It runs the Java/Fabric verification suite and coordinator tests. CI also checks the tracked map catalog and modules, Linux process-group cleanup, the Windows distribution transaction, and a bounded Fabric boot using the exact ZIP mod set. These checks do not call a model provider.

For fast local iteration when the source and dependencies are unchanged, run the incremental route:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-incremental-verification.ps1
```

It keeps Gradle outputs and the build cache. It supplements, and does not replace, the clean no-cache command above or the release/CI checks.

For the heavier eight-agent reliability gate, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1
```

This repeats the isolated eight-agent soak 50 times after the clean verifier. The summonable-agent path permits 16 concurrent planner turns, sends at most eight queued observations per server tick, caches expensive spatial sections for 10 ticks, and throttles action progress to a material 5% change or a one-second heartbeat. ArenaScript providers return one validated program envelope and the local interpreter schedules one authorized physical primitive at a time; Codex's native-tools path retains its own tool-session contract. Optional bounded `coordinator_status.latencies` rows expose sample count, p50, and p95 durations without prompts, observations, model output, or credentials. See [the measured headless report](docs/plans/2026-08-11-eight-agent-performance-reliability-report.md).

### Prepare the source-checkout runtime

These scripts are for the repository's legacy two-client test layout. They are not in the release ZIP. `prepare-runtime.ps1` requires the project-local JDK, an existing Fabric API JAR in the normal Minecraft directory, and the exact source world `%APPDATA%\.minecraft\saves\New World (76)`. It copies that world into `runtime/server`; it never starts the source world. Close Minecraft and the official launcher, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-runtime.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\install-launcher-profiles.ps1
```

Preparation verifies the copied world before it reuses it. It creates `runtime/server` with `online-mode=true` on `127.0.0.1:25565`, matching the default server launcher. Joining players need an authenticated Minecraft account. Existing offline settings are rejected without conversion; see [runtime/README.md](runtime/README.md) for this development-only layout and migration boundary.

### Install the Windows release ZIP

The Reliability workflow publishes `arena-agents-modpack-<version>.zip`. Build it with `.\gradlew.bat packageWindowsDistribution`. Extract the complete ZIP and follow its `README.md`. The shipped command is `.\scripts\install-distribution.ps1`; source-only preparation and launcher-profile scripts are deliberately absent. The installer creates `%APPDATA%\.minecraft-arena-agents`, installs the exact Arena Agents, Arena Agents Voice, Fabric API, Fabric Carpet, and Simple Voice Chat JARs, removes older package-owned JARs, and installs the matching coordinator and Node.js runtime. It preserves unrelated mods and restores the previous package files if an update fails.

#### Provider CLI checks

Agents think through a provider CLI installed on the machine that runs the coordinator: `claude` (Claude Code), `codex` (Codex CLI) or `agy` (Antigravity). Each time an agent is launched, or re-registered after a coordinator reconnect, the coordinator probes that CLI with the same executable resolution and environment the agent process uses and reports three failure states to everyone in Minecraft chat: **not installed** (`claude`, `codex` or `agy` was not found on PATH, under `%APPDATA%\npm` or beside an npm `.cmd` shim, in `%USERPROFILE%\.local\bin` or in `%LOCALAPPDATA%\agy\bin`), **broken** (the CLI exists but `--version` fails, cannot start or times out), and **not signed in** (`claude auth status`, `codex login status` or `agy models` reports no login; an `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY` in the coordinator environment counts as signed in). The chat line names the fix: install the CLI and restart Minecraft, reinstall or update it, or run `claude auth login`, `codex login` or `agy` on the server machine and relaunch the agent. Players who join later see the latest notice for each affected agent. The same three results are logged once per provider with a `[provider-cli]` prefix when the coordinator first connects to the server. A healthy CLI produces no chat message.

### Run summonable NPC mode

For the source-checkout runtime, start the Fabric server and coordinator in separate terminals:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1
```

`start-test-server.ps1` starts only the prepared server. `start-dynamic-coordinator.ps1` is a manual development entrypoint and requires an authenticated Codex CLI before it starts. The installed Windows release uses the mod's bundled coordinator supervisor instead, so do not start a second coordinator for that profile. Join the configured local server with the Arena Agents Fabric profile and use operator commands:

```text
/codex summon
/codex summon <model> <reasoning> [name]
/codex summon gemini <model> <thinking> [name]
/codex summon claude <model> <thinking> [name]
/codex summon-configured <provider> <model> <thinking> <speed_mode> <game_mode> [name]
/msg <online-agent-name> <message>
/codex start <agent> <prompt>
/codex stop <agent>
/codex resume <agent>
/codex queue <agent> <prompt>
/codex steer <agent> <prompt>
/codex group save <group-name> <agent-uuid> [agent-uuid...]
/codex group spawn <group-name>
/codex group delete <group-name>
/codex voice-consent <on|off|status>
/codex status [agent]
/codex list
/codex remove <agent>
/spectate <agent> [start|stop]
/spectate exit
/takeover <agent> [start|stop]
/takeover exit
```

`/spectate <agent>` switches your view to the agent's first-person camera, one-to-one with what a player in its body would see: its hands, skin, held tools and how it swings, eats, blocks or draws them, plus its hotbar, hearts, food, armor, air, XP, effects, game mode, open container screens and death screen. Nothing else is drawn over the view; a lost signal or a hit on your own body shows briefly on the action bar. `<agent>` is the tab-completed selector (the agent's username or short id, no spaces), not the display name shown in chat. `<agent>` alone means `start`; `/spectate <agent> stop` (or `exit`) ends that agent's view and `/spectate exit` ends whichever view is active. Your own body stays where it is, so your surroundings stop rendering while you watch. `/takeover <agent>` uses the same grammar and routes your movement, mouse look, jump, sneak, sprint, attack, use, hotbar and inventory clicks to the agent's body while its model is paused; the model resumes from whatever state you leave the agent in and receives a short report of what you did. A takeover ends when you run `/takeover exit`, when your own body loses two hearts, dies, changes dimension or disconnects, or when the agent is removed or enters another dimension (run the command again once you are in the same dimension). Both commands need operator permission and the client mod. The mod replaces vanilla `/spectate` (which would move your body to the target) on servers it runs on.

`/codex summon` and the legacy two-argument form use the configured Codex default. The command center asks each installed provider CLI for its current model catalog and uses a bounded built-in catalog when discovery is unavailable. Availability still depends on the installed CLI, its login, and that provider's model access. Player-facing speed choices are `Normal` and `Fast mode`; provider wire values stay internal. A newly summoned NPC remains idle until `/codex start`; `stop` freezes its active work, `queue` preserves later goals, and `steer` interrupts the current plan at a higher revision.

#### Run skit mode

Skit mode is a deliberate, world-persisted toggle for staging short-form scenes. It
does not give agents a second control system. Turn it on, summon a named agent,
place it at your feet or at exact coordinates, then replay a saved timeline:

For a point-and-click workflow, press `G` to open the Field Console and choose
**Director**. Each tab opens with a focused simple view. Saved scripts, delivery settings,
cast management, and dolly controls open separately; **Simple view** returns to the everyday controls.
Its Cast, Actions, Voice, and Camera tabs cover the staging workflow
without requiring chat commands. In Actions or Voice, create or load a saved script,
select a row to edit it, then save the row. You can remove rows and undo your last edit.
Creating an existing name is rejected instead of erasing its contents. The editor
keeps drafts while you close and reopen it in the same connection; saved scripts
and takes persist in the world. Durations in the editor use seconds.

In Actions, enter a new script name and describe the scene, then choose **Write
script with Luna**. GPT-5.6 Luna uses low reasoning and writes an editable motion
script. "Here" is your position when you click Write; "start" is the actor's position.
Generation needs the connected coordinator and Codex authentication. A completion
message tells you when the script is saved. Review it before clicking Play.

Voice choices appear in a dropdown as `Name - sound, gender`. Fish synthesis uses
`s2.1-pro-free`. Character defaults give Astra, Fable, Grok, Gemini, and Kimi distinct
original voices. **Say line** uses the currently selected settings; **Set voice**
saves them for that actor's future dialogue. In legacy speech mode, named Director voices require Fish and
never fall back to Windows or local synthesis. On the host, configure `FISH_AUDIO_API_KEY`
or save a valid key in `arena-agents-runtime/runtime/fish-api-key.txt` beneath the game
directory, then restart Minecraft. Missing or rejected keys produce a setup error.

Find **Tripod Camera** in the **Cameras** creative tab, or choose **Get tripod camera**
in Director. Place it on a solid floor and right-click it. The camera has its own
**Record** and **Position** tabs: name and record a shot directly, raise or lower
the lens, pan, tilt, and dolly across the floor without rails. Movement stops after
five seconds unless braked sooner. **Stop and save** preserves the shot for preview
or use in a take. Closing the camera brakes it and saves any active recording.

**Viewfinder** opens the lens view; the mouse pans, **K** returns to camera controls,
and sneak saves and exits. A physical pass supports about 102 seconds. This records
camera motion, not a video file; use your usual video recorder to capture playback.
Existing rail-camera saves and manual keyframe commands remain compatible.

For a shared take, scroll down in Cast, create a take, and load it. Select an actor,
enter its action and/or voice script names, then choose **Save actor + starting mark**.
Repeat for each actor. In Camera, assign a saved path to that take if needed.
**Play take** restores the recorded actor positions and starts all tracks after a
three-second countdown. **Stop take** releases the cast, stops speech and restores
the camera. You can reopen Director during playback. Editing a track preserves its
starting mark; Save actor + starting mark explicitly captures a new mark.

Camera paths live on the recording client, so play the take from that client.
Each actor's dialogue waits for audible completion before starting its next pause
and line. Initial speech can still wait for voice-provider synthesis; takes do not
pre-render audio or promise frame-exact dialogue against camera movement.

```text
/codex skit on
/codex skit summon codex ChatGPT
/codex skit summon claude Claude
/codex skit summon codex model gpt-6.1-sol "GPT 6.1-Sol"
/codex skit place ChatGPT here
/codex skit place Claude at 12 72 -4 180 0
/codex skit place Claude relative 2 0 4
/codex skit place Claude look_at 12 73 -4
/codex skit script create takeoff ChatGPT
/codex skit script add takeoff 0 12 72 -4 180 0
/codex skit script add takeoff 40 12 80 -4 180 10
/codex skit script add takeoff 80 12 72 -4 180 0
/codex skit script action takeoff walk 40 1 0 true
/codex skit script action takeoff equip minecraft:elytra
/codex skit script action takeoff jump
/codex skit script play takeoff
/codex skit voice profile Claude voice.adrian.v1 dramatic 1.05 64
/codex skit voice say Claude You should not have come here.
/codex skit voice script create intro Claude
/codex skit voice script add intro 0 Now run.
/codex skit voice script play intro
/codex skit off
/camera path start intro
/camera path keyframe
/camera path keyframe
/camera path stop
/camera path play intro
```

Run the `/camera` commands on the client that is recording. Camera paths are
client-local and do not change server or agent state.

`place` stores position, yaw, and pitch. Script steps store a delay in ticks and
teleport the selected agent when they fire, which makes takeoff, flight poses, and
landings repeatable for recording. `relative` places an actor by right/up/forward
offsets from the operator, while `look_at` keeps the actor at the operator and
turns them toward a target point. Action steps can move smoothly between the
current placement and the saved endpoint, wait, jump, equip an item, use the held
item, swing, or hold a sneak/emote pose. New non-placement actions hold the actor's
current position instead of snapping back to an earlier mark. In the editor,
**Glide to my position** records the operator's position when added. `walk` uses
Minecraft movement and collision with forward, strafe and sprint inputs; `move`
with a single duration keeps the saved-endpoint glide behavior. Existing saved
placement timelines retain their behavior.

Use `skit summon <provider> model <exact-slug> <name>` when a scene needs a
specific model. The technical Minecraft username remains safe and unique, while
the visible name tag uses the readable model/name label.

Voice profiles are persisted per agent. The profile ID selects the local TTS voice
identity, while `tone`, `speed`, and `radius` control delivery and proximity range.
Voice script pauses are measured from completion of the preceding line. They
require the optional Arena Agents Voice add-on and Simple Voice Chat. Missing voice
support or a failed line gives an error and stops the dialogue, including a take
that depends on it. Stop also cancels the final audible line. Explicit speed and
supported delivery tone settings reach the synthesis provider.

The default speech provider is OpenAI: `gpt-4o-mini-tts` reads the selected agent's
exact reply, and `gpt-transcribe` turns consented microphone audio into text for
that same agent. Speech models do not decide gameplay or generate replies. Add
`OPENAI_API_KEY` in Windows **Environment Variables for your account**, then
restart the launcher and Minecraft. The key is read on the host, never embedded
in the JAR, and is withheld from gameplay provider processes so Codex keeps its
existing sign-in. In `arena-agents-runtime/runtime/dynamic-agents.json`, set
`voice.provider` to `openai`; `openaiTtsModel`, `openaiSttModel`, and
`openaiApiKeyEnvironmentVariable` select the speech models and key variable.
Existing external configuration is preserved during mod updates, so older
installs need this provider setting once. Saved agent and Director profile IDs
map consistently to OpenAI's built-in voices. Use `legacy` explicitly to retain
the previous Fish, Deepgram, or local speech routing.

The client-only camera director records the operator's current camera position and
rotation as keyframes, then replays them with smooth position interpolation and
shortest-turn rotation. Paths are stored locally in
`config/arenaagents/camera-paths.json`, support looping playback, and can be
deleted/listed without changing server or agent state. The four brand-forward skin families live in
the main renderer, including the standalone DeepSeek texture family. DeepSeek is
not a summon provider in this checkout yet, so its skin is available for the
identity pipeline without pretending that a backend exists.
Brand agent textures are authored at 512x512 while retaining the vanilla 64x64 UV
layout. That gives each visible 8x8 head face a 64px logo raster, while ordinary
player and mob textures remain at their native resolutions. These are mod assets,
not files for the vanilla skin-upload screen.

Every NPC receives a stable directory beneath `runtime/agent-workspaces/<provider>/<agent-id>`. Codex uses an isolated thread per NPC on its shared app server. Claude runs one Claude Code process per NPC in the same `runtime/minecraft-agent/workspace` directory Codex uses, with the same AGENTS.md and minecraft-control skill as its system prompt and the native Minecraft tools served over a loopback MCP endpoint. Claude models (Opus 5.5, Sonnet 5.5, Fable 5.1) offer Low, Medium, and High reasoning only. Gemini records remain recoverable, but attempts to plan return `PROVIDER_UNAVAILABLE`. The coordinator passes only the provider-specific environment allowlist. It does not copy bridge secrets or OAuth state into agent directories.

Invalid ArenaScript source receives bounded compiler diagnostics and a corrective turn from the same selected provider/model/session. Repeated physical-action failures remain bounded factual evidence for the next model decision; they never make the runtime choose to abandon the goal. Claude Code reuses its existing login; user CLAUDE.md files, settings, hooks, plugins, and other MCP servers are not loaded for agents. Missing authentication, unavailable models, timeouts, and bounded-output failures stop only the affected NPC.

Spatial voice is optional to use. The Windows release ZIP includes the Arena Agents Voice add-on and Simple Voice Chat. In a separate main-mod installation without them, `/codex voice-consent` reports `VOICE_UNAVAILABLE` and leaves consent unchanged. With the add-on and its compatible Simple Voice Chat dependency active, the command changes the player's transcription consent state. Text control and ArenaScript do not require voice.

Press the configured Agent Controls key (`G` by default) in a world to open the custom Field Console. Agent creation, one-at-a-time configuration, individual tasks, saved groups, lifecycle controls, arena construction, and live match telemetry use separate workspaces behind persistent `Agents`, `Group`, `Live`, and `Build` navigation. The Group workspace saves an ordered roster of stable agent identities, so spawning it restores the same characters without duplicating agents that are already present. Direct messages stay in Minecraft's native `/msg`, `/tell`, and `/w` flow; the mod routes messages addressed to online agents into their private conversation memory and mirrors them to operators. The console renders its own flat controls, text fields, confirmation surface, selection rows, no-shadow labels, and mod-local Roboto typography instead of exposing Minecraft's default button grid or pixel type. Full-row selection, explicit `SELECTED` / `IN GROUP` labels, keyboard focus outlines, scroll-safe 320x240 layouts, and plain status copy keep the current target unmistakable.

Arena setup defaults to building 80 blocks in front of the operator so construction remains visible; exact server coordinates are published before the reset job starts. `At my position` and deterministic fixed lanes remain available. World mutation is deliberately paced while bookkeeping and verification retain their higher bounded throughput, so the arena visibly grows instead of appearing all at once. The build dashboard reports the current phase, processed work, successful world changes, exact origin, actionable failures, and retry state. Each preset authors only the contestant stations, building plots, or parkour lanes required by the configured roster and adds a connected operator observation deck. Parkour lanes progress through easy, medium, hard, and expert sections; decorated checkpoints save progress, and lava deaths respawn Adventure-mode participants at their latest checkpoint. The compact in-world HUD shows four readable health/score cards, while the Live Arena workspace exposes the full scrollable roster and meaningful activity feed. Authored-map research and attribution are recorded in [MAP-SOURCES.md](MAP-SOURCES.md).

### Visual release validation

The release gate starts a temporary offline Fabric server with the exact five JARs from the Windows ZIP: Arena Agents, Arena Agents Voice, Fabric API, Fabric Carpet, and Simple Voice Chat. It waits for Minecraft's `Done` marker, runs `codex status`, checks for mixin and crash failures, sends `stop`, and requires a clean exit. Live launcher gameplay and provider-latency acceptance remain separate manual checks.

### Current action surface

The server-authoritative executor drives Carpet's real player action pack for movement, looking, jumping, attacking, item use, and block interaction. Observations include vanilla HUD/player state, inventory/equipment, visible nearby entities and blocks, world state, the active action, and the last result. Entity and block facts are gated by the current view and line of sight; hidden creature health and other server-only combat facts are not exposed as sight. Damage publishes a fresh factual observation without cancelling the current action or choosing fight, flight, or replanning for the model. Death is reconciled into a persistent `DEAD` lifecycle state and remains under the selected model's coordinate-free respawn primitive.

Container transfers, crafting recipes, and furnace transactions use server-authoritative, fail-closed adapters with inventory-conservation checks and rollback paths. Crafting expands Minecraft's trimmed recipe remainders back into the full grid before validating ownership, including horizontally and vertically offset recipes. Forced chunk tickets remain fail-closed until their Minecraft 26.1.2 adapter is runtime-validated; unsupported operations never report false success.

### ArenaScript control boundary

The selected provider, model, reasoning effort, and service tier own gameplay strategy, program source, watcher conditions, interruption policy, fallbacks, and respawn decisions. The coordinator only validates the envelope, compiles the source, evaluates it in the local interpreter, and enforces goal/version/provenance fences. Invalid source is returned to that same selected model with bounded compiler diagnostics; no heuristic or alternate model supplies a replacement.

The interpreter has no shell, filesystem, network, credential, or ambient Minecraft authority. Its exact `SCRIPT_PRIMITIVES` set is:

```text
move_to, navigate_to, look_at, attack, select_item, use_item, break_block, pick_up_item, place_block, chat, wait, set_door, drop_item, transfer_container, craft_inventory, craft_table, furnace_transaction, equip_item, select_tool, block_with_shield, use_ranged, interact_block, interact_entity, dismount, start_fall_flying, menu_transfer, menu_button, anvil_rename, respawn, control
```

`await player.control({...})` combines movement, looking, jumping, sneaking, sprinting, attacking, and item use in one complete input frame for 1 to 200 ticks. It also accepts the selected hotbar slot and hand. Codex's native `control` tool uses the same action. Different agents can act concurrently; physical commands for one body remain sequential, while speech can run independently.

The virtual benchmark simulator supports a smaller action set and does not simulate `control` frames. Scenario compilation rejects unsupported actions with `SIMULATOR_UNSUPPORTED_ACTION` before generating a program. Provider-authored programs using them still return that error during simulation. This error identifies a simulator limitation; production ArenaScript continues to support `player.control`.

The current native-tool and ArenaScript APIs do not expose a wiki lookup. Their factual queries report observed world/player/inventory state; they do not fetch external game knowledge. External wiki access would require a separately bounded, source-attributed knowledge integration within the provider execution boundary.

Model-authored `repeatUntil`/`watch` loops and factual queries replace high-level fight, flee, follow, pickup, and build controllers; the runtime does not synthesize those strategies. Every command carries the agent, goal revision, program/version, source step, event sequence, and selected-model provenance before Minecraft accepts it.

Death preserves the logical agent and goal while suspending its program. The selected model receives vanilla death facts and must author `await player.respawn()`; the mod follows vanilla respawn, inventory, experience, drop, bed/anchor, world-spawn, and gamerule behavior without an operator or runtime fallback.

Diagnostics keep provider inference, event publication, interpreter selection, bridge send/acceptance, and physical completion as separate timing segments. In particular, `event_receipt_to_branch`, `branch_to_bridge_send`, `command_to_first_progress`, `action_completion`, and provider-turn duration are never combined into a live-success claim. Public traces store source hashes; bounded source is restricted to the agent-private diagnostic trace, with credentials and tokens redacted.
