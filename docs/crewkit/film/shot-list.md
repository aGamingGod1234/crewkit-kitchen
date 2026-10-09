# CrewKit Kitchen: 3-minute film shot list

Track: **Most Worthwhile Problem**. Length: 3:00 exactly, pre-recorded, 1080p60.

**The user:** a student-club workshop organiser who has to turn an attendee list into a complete supply order inside a small approved budget, and then show the club treasurer where every dollar went. Lead with that problem. Minecraft is the shared visual interface everyone can read at a glance, not the point of the project.

**The footage is a replay.** We record the default replay of the real sandbox run (no new live take). Caption the whole demo: `Replayed from the recorded real sandbox run`.

**The run the film shows (every number on screen comes from it):**

- Brief: 6 guests, budget S$105. Each guest gets a badge, a notebook and a pen. Each pair shares a USB-C cable. Extras: sticky notes and markers.
- First quote S$196.85, OVER by S$91.85, checkout blocked by the server gate.
- Rework: sold-out swaps, notebooks 3+3, cables 1+1+1, optional sticky notes dropped.
- Ticker: S$196.85 -> S$137.25 -> S$131.65 -> S$102.75.
- A human approved with a passkey on Reap's hosted page (sandbox).
- Reap reported COMPLETED, order `ord_01M4G0J3JSEP31G7NASSA0K649`. Charged S$102.75, variance S$0.00, 154 Reap calls.

Never type an amount into a caption that differs from the board.

## Shot sources

| Code | Source | How it is captured |
|---|---|---|
| **IG** | In-game kitchen | OBS game capture of the replay. Camera marks from `/ckcam`. |
| **CODE** | Code, terminal or README | OBS window capture of VS Code / terminal, or a still screenshot. `.env` never on screen. |
| **TALK** | Organiser to camera, or Chef by voice | Phone or laptop webcam, or the 15 s voice conversation clip captured in game. |
| **PH** | Real approval footage | Only if real footage of the passkey approval exists. Otherwise no phone shot. |

## Standing labels (burn in during editing)

- Top-left, whole film: `Replayed from the recorded real sandbox run`
- Any shortened wait: `Wait shortened · real time ~4:30` (use the real figure for each cut)
- Delivery scene: `COMPLETED · Order placed · delivery visualized`
- No onchain, crypto or "agent pays" claims anywhere. The human approves; Reap places the order.

## Beat by beat

| Time | Picture, action and captions | Voiceover |
|---|---|---|
| 0:00–0:12 | Organiser clip, or fallback VO over a wide empty kitchen. | Organiser in their own words. Fallback: "Last term I ran a workshop for forty people. I bought supplies the night before, went over budget, and spent a week matching receipts for the treasurer." |
| 0:12–0:28 | Organiser talks to Chef by voice (or `/msg`). Ticket slides on, 6 guests sit. Chef: "Order in! Six guests, a hundred and five dollar budget." | "So we gave the job to an agent. The organiser tells Chef the brief: six guests, a hundred and five dollar budget. Each guest needs a badge, a notebook and a pen. Each pair shares a USB-C cable. Sticky notes and markers are extras." |
| 0:28–0:50 | Line shot: candidate fan, items onto Chef's head, crosshair hover on one real item. 3 s NDJSON insert. Chef sold-out line. | "Every search is a real call to Reap's Agentic API. The items on Chef's head are real listings at real prices. Some are sold out in the sandbox, and Chef says so." |
| 0:50–1:10 | Budget to gate: S$196.85 lands, ticket red, bars drop. 4 s `gate.mjs` insert. Caption: `OVER by S$91.85 · CHECKOUT BLOCKED (server rule)` | "Reap returns a quote with shipping: one hundred ninety-six dollars eighty-five. That is ninety-one eighty-five over budget. Checkout is blocked, and the block is our server code, not the model's judgement." |
| 1:10–1:30 | Speed-ramped rework. Caption: `Wait shortened · real time ~4:30` plus the ticker S$196.85 -> 137.25 -> 131.65 -> 102.75. | "Chef reworks the cart. Sold-out items are swapped, notebooks split three and three, cables one, one and one. The quote falls to one thirty-seven, one thirty-one, then one hundred two seventy-five. Only the optional sticky notes are dropped." |
| 1:30–1:52 | Gate lifts, ticket green, QR + hourglass. Approval caption: `Recorded from the real run: human passkey approval on Reap's hosted page (sandbox)`. Picture-in-picture only if real approval footage exists. Never film a phone scanning the replay QR: it encodes the GitHub URL. | "Both checks pass: under budget, every requirement covered. Now a human has to say yes. In the recorded run, a person approved with a passkey on Reap's own hosted page. The agent never sees the card." |
| 1:52–2:20 | Door to plating: bag arrives, ghost plates turn solid. Caption: `COMPLETED · Order placed · delivery visualized` | "Reap reports COMPLETED, so the merchant order is placed. The bag arrives and the ghost plates turn solid, one per guest. Delivery is visualized; the sandbox ships nothing." |
| 2:20–2:42 | Bill typewriter, receipt book, 3 s CSV insert. Board: charged S$102.75, variance S$0.00, order `ord_01M4G0J3JSEP31G7NASSA0K649`, 154 Reap calls. | "The bill is the treasurer's receipt: budget, charged amount, zero variance, the order reference, and a CSV." |
| 2:42–3:00 | Wide, end card: sandbox / approval / built on Agent Arena lines, repo URL, team "The Greek Warriors" + TODO names. | "An organiser writes a brief and approves one payment. The agent can't overspend and leaves a receipt anyone can read by looking at the room. Agent Arena is existing work; we built the Reap purchasing, the gate, the record and the kitchen. That's CrewKit Kitchen." |

Voiceover length: about 305 words (cap 330) at a calm pace. Cut the fallback VO in the first beat if the organiser clip is used.

## Disclosure lines (keep separate, never merge)

1. **Sandbox:** "Reap Agentic API sandbox, SGD. No real money moved."
2. **Replay:** "Replayed from the recorded real sandbox run" on the whole demo.
3. **Approval:** "Recorded from the real run: human passkey approval on Reap's hosted page (sandbox)". The simulate header was set in that run, but Reap still returned REQUIRES_ACTION and a human approved.
4. **Delivery:** `COMPLETED · Order placed · delivery visualized` (Reap confirms the merchant order; no parcel is filmed).
5. **Compressed time:** every speed-up carries `Wait shortened · real time <mm:ss>`.
6. **Prior work:** "Agent Arena is existing work; we built the Reap purchasing, the gate, the record and the kitchen."

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
