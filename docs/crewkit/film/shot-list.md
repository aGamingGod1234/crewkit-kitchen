# CrewKit Kitchen: 3-minute film shot list

Track: **Most Worthwhile Problem**. Length: 3:00 exactly, pre-recorded, 1080p60.

**The user:** a student-club workshop organiser who has to turn an attendee list into a complete supply order inside a small approved budget, and then show the club treasurer where every dollar went. Lead with that problem. Minecraft is the shared visual interface everyone can read at a glance, not the point of the project.

**Rule for every number on screen:** it comes from the live Reap sandbox run (or its recorded tape). Fill the `[ ]` placeholders from the take you keep. Never type an amount into a caption that differs from the board.

## Shot sources

| Code | Source | How it is captured |
|---|---|---|
| **IG** | In-game kitchen | OBS game capture. Camera marks from `/ckcam` (in brackets, e.g. `[wide]`). |
| **PH** | Phone screen | Phone's own screen recorder during the live take: Reap's hosted approval page. |
| **CODE** | Code, terminal or README | OBS window capture of VS Code / terminal, or a still screenshot. |
| **TALK** | Organiser to camera | Phone or laptop webcam, landscape, quiet room. |

## Standing labels (burn in during editing)

- Top-left, whole film after 0:20: `LIVE · Reap Agentic API sandbox · SGD · no real money moves`
- Any shortened wait (polling, delivery, plating): `Wait shortened ·  real time [mm:ss]`
- Delivery scene: `Order placed · delivery visualized`
- Approval scene: see the disclosure rows at 1:45 and 2:40.
- No onchain, crypto or "agent pays" claims anywhere. The human approves; Reap places the order.

## Beat by beat

| Time | Shot | Picture | Voiceover | On-screen caption |
|---|---|---|---|---|
| 0:00–0:20 | **TALK** (placeholder), then IG `[wide]` under the last line | Organiser (club member) on camera, 10–15 s, their real story. Fade to the empty kitchen wide. | Organiser, own words. Prompt to give them: "Tell us about the last time you bought supplies for a club workshop. What went wrong with the budget or the receipts?" If no recording, use the fallback VO: "Last term I ran a workshop for forty people. I bought supplies the night before, went over budget, and spent a week matching receipts for the treasurer." | `[Name], [club], workshop organiser` |
| 0:20–0:40 | IG `[wide]` → `[gate]` (ticket on the rail) | Order ticket slides onto the rail. Guests walk in and sit by name. Timer starts. | "So we gave the job to an agent. The brief is what an organiser actually has: twelve attendees, what each one must get, what may be swapped, and a budget that includes shipping. In CrewKit, the agent is a chef and the brief is an order ticket." | `Brief: 12 attendees · per person: [badge, notebook, pen, drink] · per pair: [cable] · room: [speaker] · substitutions allowed: [shared-per-pair] · budget S$[budget] incl. shipping` |
| 0:40–1:10 | IG `[line]` ↔ `[budget]`, one manual `[line]` close for the hover label. **CODE** insert 4 s at ~0:58. | Chef walks the line; items stack on its head; Reap-calls counter ticks; budget board rolls down. Crosshair on one item: real name, price, merchant. Insert: terminal NDJSON `item_added` lines with the same name and price. | "Every search is a real call to Reap's Agentic API. Each item is a real listing at its real price, and the chef allocates it: one notebook per person, one cable per pair. That's [real product name] at S$[price] from [merchant]. The quantities follow the attendee list, not a guess." | `[Product name] · S$[price] · [merchant] · qty [n]` then `Reap calls: [n]` |
| 1:10–1:45 | IG `[budget]` → `[gate]` → `[line]` → `[gate]`. **CODE** insert 5 s at ~1:25 (the gate check in `coordinator/src/crewkit/gate.mjs`). | Quote lands with shipping. Counter rolls red; ticket turns red; CREWKIT GATE bars drop. Chef swaps per-person cables for one per pair; extras tumble off. Bars lift. | "Reap returns a quote with shipping: S$[quote 1], S$[over] over budget. Checkout is blocked, and the block is our server code, not the model's judgement. The agent substitutes the way the brief allows, shared cables per pair, and every attendee still gets every required item." | `Quote incl. shipping S$[quote 1] · OVER by S$[over] · CHECKOUT BLOCKED (server rule)` then `Substitution: cable per person → per pair · all requirements met` |
| 1:45–2:15 | IG `[gate]` → `[qr]`, then **PH** picture-in-picture (right 40% of frame) | Revised quote passes; ticket green. QR appears on the pass. Phone scans it; Reap's hosted approval page shows the same total; tap approve. Hourglass. | "The revised quote, S$[quote 2], passes both checks: under budget and every requirement covered. Now a human has to say yes. The QR opens Reap's own hosted approval page, showing the same S$[quote 2]. The agent never sees the card." | `Revised quote S$[quote 2] · within budget ✓ · requirements ✓` and on the phone: `Reap hosted approval (sandbox) · same amount as quote` |
| 2:15–2:40 | IG `[door]` → `[plating]` → `[bill]`. **CODE** insert 4 s at ~2:35 (the CSV in `crewkit-records/`). | Poll returns COMPLETED. Bundle lands at the door, chef unpacks, plates per guest by name. Bill board stamps the row. | "When Reap's poll returns COMPLETED, meaning the merchant order is placed, the bag arrives and each attendee's plate is served by name. The bill board is the receipt for the treasurer: budget, approved quote, final amount, variance and order reference, also saved as a CSV." | Door: `Order placed · delivery visualized`. Board: `Budget S$[b] · Quote S$[q] · Charged S$[f] · Variance S$[v] · Order [ref]` |
| 2:40–3:00 | IG `[wide]`, slow; end card | Whole kitchen, guests served. End card: name, repo link, team. | "An organiser writes a brief and approves one payment. The agent does the rest, can't overspend, and leaves a receipt anyone can read by looking at the room. Agent Arena is existing work; today we built the Reap purchasing, the gate, the record and the kitchen. That's CrewKit Kitchen." | `Sandbox run: Reap Agentic API, SGD, no real money moved.` · `Approval: real Reap hosted page on the phone (sandbox)` · `Agent Arena (Minecraft agents) = prior work · Built today: Reap purchasing, budget gate, record, kitchen` · `[repo URL] · [team names]` |

Voiceover length: about 390 words, which fits 3:00 at a calm pace with room for the organiser clip. Cut the fallback VO if the organiser clip is used.

## Disclosure lines (keep separate, never merge)

1. **Sandbox:** "Live Reap Agentic API sandbox, SGD. No real money moved."
2. **Approval:** if the phone shot is the real hosted page from this take, caption `Reap hosted approval (sandbox)`. If the take used `--mode simulate` (simulated approval) or `--mode replay` (recorded tape), caption that scene `Approval simulated in this take` or `Replayed from a recorded live run` instead, and do not show a phone.
3. **Delivery:** `Order placed · delivery visualized` (Reap confirms the merchant order; no parcel is filmed).
4. **Compressed time:** every speed-up carries `Wait shortened · real time [mm:ss]`.
5. **Prior work:** "Agent Arena is existing work; today we built the Reap purchasing, the gate, the record and the kitchen."

## Camera marks (`/ckcam go <mark>`)

| Mark | Frames | Auto-trigger event |
|---|---|---|
| `wide` | Whole kitchen from the camera wall (ck_player) | `brief`, `reset` |
| `line` | Chef and head stack on the counter line | `item_added`, `item_removed` |
| `budget` | Budget board and timer | `quote` |
| `gate` | The pass: ticket rail and gate bars | `gate_blocked`, `gate_passed`, `failed`, `expired` |
| `qr` | QR frame on the pass, close | `checkout` |
| `door` | Delivery door and bag drop | `completed` |
| `plating` | Tables A to C, plates per guest | follows `door` automatically |
| `bill` | Bill board / ledger | `record` |

The director holds each shot for its beat before the next glide (1.5 s glide, 2.5–7 s hold), so the camera never cuts mid-motion. If the run is faster than the camera, it skips the oldest queued shot rather than rushing.
