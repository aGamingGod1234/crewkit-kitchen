# Run the chef as a real Agent Arena agent

The chef is a normal Agent Arena NPC. A provider (Claude or Codex) drives it through the native Minecraft tools, and one of those tools is `crewkit_shop`. When the model calls it, the coordinator runs the purchase (replay, simulate or live), streams `crewkit_state` events to the mod, and the kitchen plays every step. The model only sees the run id and status. It never sees the Reap key, card data or the approval URL.

Verified on the Mini PC on 2026-10-09 with Claude Sonnet 5.5 (medium) in replay mode. The agent's first tool call was `mcp__minecraft__crewkit_shop` 3.4 s after the goal started. It returned `SUCCEEDED`, 39 events reached the mod, and the run ended `COMPLETED`.

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
3. Summon the chef next to the stove (ck_agent is at 8,1,6 relative to the origin), and give it the chef skin:
   ```text
   /execute positioned 8.5 101 6.5 run codex summon claude claude-sonnet-5-5 medium Chef
   /crewkit chef Chef
   ```
   Codex works too: `/codex summon <codex-model> <reasoning> Chef`. Wait until `/codex list` shows `Chef | Ready`.
4. Give it the job:
   ```text
   /codex start Chef Shop for the workshop kit: use crewkit_shop to start the order for the posted ticket.
   ```

## What to expect

- Tool call: `crewkit_shop {"action":"start"}`. `mode` is optional and defaults to `CREWKIT_AGENT_MODE`, else `replay`. Leaving out `brief` uses the posted ticket (`coordinator/src/crewkit/fixtures/demo-brief.json`, "Order ticket #001: Hackathon workshop kit").
- Result to the model: `{state:"SUCCEEDED", reasonCode:"CREWKIT_STARTED", runId}`. The agent may follow up with `crewkit_shop {"action":"status"}` and then `finish`.
- In the world: ticket on the rail and guests sit, items stack on the chef, the budget board counts down and the gate drops then lifts, the QR code appears on the pass, then delivery and plating, and the bill board stamps the record. The server log shows `CrewKit event <name> seq=<n>` for each step.
- Check from a shell: `curl http://127.0.0.1:4777/crewkit/status` shows `active`, then `last.status: "COMPLETED"`.

## If it does not start

- The model replies `CREWKIT_BUSY`: a run is already active. Wait for it to finish. `POST /crewkit/reset` also returns 409 during a run.
- Nothing moves in the kitchen: the coordinator log shows `crewkit_state ... not delivered: no server connected`, which means the bridge is not connected. Check that the coordinator and server share the bridge port and secret.
- On a headless server with no player online, the server pauses after 60 s (`pause-when-empty-seconds`) and the set markers only spawn in loaded chunks. Run the demo with a player present.
- Clear the room between takes with `/crewkit reset`. It removes every `crewkit`-tagged entity and keeps the set.
