# CrewKit Kitchen: shared contract (read first)

Plan: https://mwvetbk1qiwd.postplan.dev . Concept: an agent chef buys an event's supplies through Reap's Agentic API within budget; every checkout step plays out in a Minecraft kitchen.

## Bridge message (Node coordinator -> mod)
One new bridge message type: `crewkit_state`, sent over the existing coordinator->server bridge the same way `director_script_result` / `task_view` are (see `MultiplexedServerBridge.java` ~line 1017 lane switch and ~1207 dispatch). agentId may be "server".

Payload:
```json
{ "runId": "string", "seq": 1, "event": "<event>", "data": { ... } }
```
The mod drops events from older runIds and any seq <= last applied seq for the run.

| event | data | kitchen reaction |
|---|---|---|
| `brief` | `{ guests:[{name, skin}], budget:{amount,currency}, needs:[string], title }` | ticket on rail, guests sit, timer starts |
| `item_added` | `{ id, realName, merchant, mcItem, unitPrice:{amount,currency}, qty, seats:[guestName] }` | item onto chef head stack, hover label, reap-calls +1 |
| `quote` | `{ total:{amount,currency}, budgetRemaining:{amount,currency}, shipping:{amount,currency}, quoteId }` | budget counter eases to new value |
| `gate_blocked` | `{ over:{amount,currency}, reason }` | red ticket, counter negative, gate bars drop |
| `item_removed` | `{ id, qtyRemoved }` | items tumble off stack |
| `gate_passed` | `{ total:{amount,currency} }` | bars lift, ticket green |
| `checkout` | `{ approvalUrl, status, checkoutId }` | QR on the pass, hourglass |
| `completed` | `{ orderId, finalAmount:{amount,currency} }` | bundle at door, unpack, plate per guest, serve |
| `record` | `{ budget, quoted, charged, variance, orderId, currency }` (amounts are numbers) | bill board stamps row |
| `failed` / `expired` | `{ status, reason }` | ticket back to rail |
| `calls` | `{ count }` | Reap-calls counter |
| `reset` | `{}` | clear all CrewKit displays |

Money: Reap amounts are decimal MAJOR units (`134` = 134.00). Currency SGD for the demo.

## Rules
- Reap key and card data never reach the LLM or logs. Key lives in `coordinator/src/crewkit/.env` (gitignored).
- Celebrations fire only on `completed`.
- Agent Arena must never open the world folder `New World (76)`.
- Never print or commit `runtime/bridge-secret.txt`.
- Polish bar: nothing snaps (display interpolation / teleport_duration), one focal motion at a time, a sound per motion (docs/crewkit/sounds.md), readable at projector resolution.

## Code ownership (avoid conflicts)
- Node, Reap + agent: `coordinator/src/crewkit/**`, plus minimal wiring in native-minecraft-tools.mjs / native-tool-runtime.mjs / provider name normalisers.
- Java visuals: `src/main/java/dev/agaminggod/arenaagents/crewkit/visual/**`, plus the `crewkit_state` case lines in MultiplexedServerBridge.java.
- Java set + cast: `src/main/java/dev/agaminggod/arenaagents/crewkit/set/**`, chef skin wiring (agent_visual_manifest.json, AgentPlayerSkins), one registration line in the mod initializer.
- Docs/demo: README.md CrewKit section, `docs/crewkit/**` (except CONTRACT.md).

Anchors and layout: docs/crewkit/kitchen-layout.html (28x22 footprint, anchors ck_budget, ck_ledger, ck_agent, ck_screen, ck_crate, ck_player).
