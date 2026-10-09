# Brief and 2-minute script (draft)

The items and prices get swapped at 3:45 for what Reap's catalogue actually returns. Keep the shape.

**Merchant pick (from `reap-merchants.csv`):** one quote is one merchant request, so shop one Singapore merchant in SGD to keep it to one checkout and one QR. First choice `popular.com.sg` (stationery chain: notebooks, pens, badges). Backups: `boxgreen.co` (snacks, SGD), `ugreen.com.sg` / `anker.com.sg` (cables, hubs, SGD). The list holds one test product per merchant; search the merchant at 3:45 to see what else it returns. Write the budget in SGD (S$150), not $.

## Demo brief (the order ticket)

> **Order ticket #001 — Hackathon workshop kit**
> Guests: James, John, Sandy + 9 more (12 total)
> Budget: **$150**, all-in with shipping
> Everyone needs their own: name badge, notebook, pen, a drink
> Shared, one per pair: phone charging cable
> Shared, one for the room: speaker
> Deliver to: the venue

Why this brief: per-person items show plating to each named guest, shared-per-pair items show the chef reasoning about seats, and $150 is set so the first cart comes in **over** budget and the gate fires.

Pick the budget at 3:45 so the first cart is about 20–40% over: sum the first quote, then set budget ≈ 0.75 × that total.

## Script

| Time | On screen | Narration |
|---|---|---|
| 0–15s | Ticket slides onto the rail. Guests sit. Timer starts. | "Ordering is easy now. The hard part is seeing what your agent actually did with your money. So we put the agent in a kitchen." |
| 15–40s | Chef walks the line. Items pop onto the head stack, budget rolls down. Crosshair over one item shows its real name and price. | "Every item is a real product from Reap's catalogue, at its real price. That notebook is a real listing. Watch the budget." |
| 40–65s | Ticket goes red, counter goes negative, gate bars drop. Chef swaps per-person cables for shared-per-pair; items tumble off. Bars lift, ticket turns green. | "Over budget, so our server blocks the checkout. Not the model, our code. The chef shares cables between pairs, and everyone is still fed." |
| 65–95s | QR on the pass. Phone scans it, approve on Reap's page. Hourglass, then the bag lands at the door. | "Nothing is bought until a human approves on Reap's own page. The agent never sees the card. When Reap says the order is placed, the bag arrives." |
| 95–120s | Chef unpacks and plates for each guest by name. Bill board stamps budget, quoted, charged, variance, order id. | "Every guest got what the brief said, and every dollar is on the board. You can audit the agent by looking at the room. That's CrewKit Kitchen." |

## Lines ready for judge questions

- **What does COMPLETED mean?** Reap's docs: "The merchant order is placed." This is sandbox, so no money moves.
- **Is approval the only human step?** The human writes the brief and approves on Reap's hosted page. Mandates (pre-approved spending) aren't live on Reap yet, so every purchase goes through that page.
- **What if the price changes?** Each checkout comes from one quote and needs its own approval. If the quote expires or Reap asks for a replacement quote, the chef re-quotes and a new approval QR appears. What Reap does if the merchant changes the price after approval isn't in the docs, so ask the mentors at kickoff before saying more.
- **Is the Minecraft part pre-built?** "The Minecraft agent platform is existing work. Today we built the Reap purchasing, the gate, the record and the kitchen."
