# Run the chef as a real Agent Arena agent

The chef is a normal Agent Arena NPC. A provider (Claude or Codex) drives it through the native Minecraft tools, and one of those tools is `crewkit_shop`. When the model calls it, the coordinator runs the purchase (replay, simulate or live), streams `crewkit_state` events to the mod, and the kitchen plays every step. The model only sees the run id and status. It never sees the Reap key, card data or the approval URL.

Verified on the Mini PC on 2026-10-09 with Claude Sonnet 5.5 (medium) in replay mode. One-step flow verified on the Laptop on 2026-10-09 with Claude Opus 5.5 (low), replay: `/crewkit stage` placed Chef at 8.5,101,6.5 facing south in the chef skin; the DM below produced `crewkit_shop` with a brief built from the message (190 SGD, 12 guests, 12 badges/notebooks/pens, 6 cables, 2 extras), events reached the mod 22 s after the DM, 12 guests sat, and the run ended `COMPLETED` (181.45 of 190 SGD). The agent's first tool call was `mcp__minecraft__crewkit_shop` 3.4 s after the goal started. It returned `SUCCEEDED`, 39 events reached the mod, and the run ended `COMPLETED`.

## Before you start

- The mod and coordinator come from this branch, and the runtime is already prepared (`runtime/server`, `runtime/bridge-secret.txt`). Use the laptop's existing setup; do not re-run preparation on demo day.
- At least one provider CLI is signed in on the laptop. In the coordinator's startup log it shows as `[provider-cli] claude: Claude Code CLI ... ready` or `[provider-cli] codex: Codex CLI ... ready`. Gemini is not needed.
- For `simulate` or `live`, `coordinator/src/crewkit/.env` holds the Reap sandbox key. Replay needs no key.

## Steps

1. Start the server and the coordinator in two terminals:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
   powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1
   ```
   To make the agent's default mode something other than replay, set `$env:CREWKIT_AGENT_MODE = "simulate"` (or `live`) in the coordinator terminal before starting it. Wait for `[crewkit] CrewKit trigger listening on http://127.0.0.1:4777/crewkit/run`.
2. Join the server as an operator. Build and stage the kitchen while standing near it:
   ```text
   /crewkit build at 0 100 0
   /crewkit stage
   ```
   `/crewkit stage` also runs `/crewkit chef ready`: it summons an agent named `Chef` (provider `claude`, model `claude-opus-5-5`, effort `low`), or reuses it if it already exists, puts it on the ck_agent anchor (8,1,6 from the origin) facing the pass, and gives it the chef skin. Wait until `/codex list` shows `Chef | Ready` (about 5 s). Run `/crewkit chef ready` on its own at any time to bring Chef back to the anchor. It is idempotent, and `/crewkit reset` keeps the agent.
3. DM Chef the order. Its standing instructions (`coordinator/config/minecraft-agent/AGENTS.md`, "CrewKit Kitchen chef") make `crewkit_shop` its first tool call. Use this exact text (Minecraft caps a typed command at 256 characters, so keep it short):
   ```text
   /msg Chef New order: hackathon workshop, 6 guests: James, John, Sandy, Priya, Wei Ling, Arjun. Budget 105 SGD. Per guest: name badge, A5 notebook, gel pen. One USB-C cable per pair. Extras: sticky notes, whiteboard markers.
   ```
   Add the word `simulate` or `live` to the message to pick that mode; otherwise it runs `replay` (or `CREWKIT_AGENT_MODE`). Replay plays the recorded Popular tape, so keep the DM close to the posted ticket's items for replay. Other items need `simulate` or `live`.

The old manual path still works: `/codex summon claude claude-opus-5-5 low Chef`, `/crewkit chef Chef`, then `/codex start Chef <task>`.

## What to expect

- Tool call: `crewkit_shop {"action":"start","brief":{...}}` with a brief built from the DM (title, one guest entry per guest with generic names filling in for unnamed ones, budget, needs per person/pair/room, extras). `mode` is optional and defaults to `CREWKIT_AGENT_MODE`, else `replay`. Leaving out `brief` uses the posted ticket (`coordinator/src/crewkit/fixtures/demo-brief.json`, "Order ticket #001: Hackathon workshop kit").
- During the run the kitchen owns the chef: CastFeature walks the real agent player (pantry, pass, door) and holds the agent's own inputs from `brief` until the walks after `completed` finish, so its movement never fights the choreography. Chef ends the run at the delivery door; `/crewkit chef ready` puts it back on the anchor for the next take.
- Result to the model: `{state:"SUCCEEDED", reasonCode:"CREWKIT_STARTED", runId}`. The agent may follow up with `crewkit_shop {"action":"status"}` and then `finish`.
- In the world: ticket on the rail and guests sit, items stack on the chef, the budget board counts down and the gate drops then lifts, the QR code appears on the pass, then delivery and plating, and the bill board stamps the record. The server log shows `CrewKit event <name> seq=<n>` for each step.
- Check from a shell: `curl http://127.0.0.1:4777/crewkit/status` shows `active`, then `last.status: "COMPLETED"`.

## If it does not start

- The model replies `CREWKIT_BUSY`: a run is already active. Wait for it to finish. `POST /crewkit/reset` also returns 409 during a run.
- Nothing moves in the kitchen: the coordinator log shows `crewkit_state ... not delivered: no server connected`, which means the bridge is not connected. Check that the coordinator and server share the bridge port and secret.
- On a headless server with no player online, the server pauses after 60 s (`pause-when-empty-seconds`) and the set markers only spawn in loaded chunks. Run the demo with a player present.
- Clear the room between takes with `/crewkit reset`. It removes every `crewkit`-tagged entity and keeps the set.
