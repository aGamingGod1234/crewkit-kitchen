# CrewKit Kitchen: judge Q&A

Short answers. Say only what is true today. Sandbox means no real money moves.

## What problem does this solve, and who is the user?

Teams that let an AI agent buy things need to see and control what it does. The user is the person who owns the spend: an event organizer, an office or ops manager, a club treasurer. They write the brief and approve the purchase. Other people in the room, such as finance or the guests, can read the state of the order from the kitchen without opening logs.

## Why is it worthwhile for business spend and event ops?

Event kits are small, repeated, and error-prone: per-person items, shared items, a fixed budget, a delivery date. They are a good first place to hand an agent real purchasing because the cost of a mistake is capped. CrewKit adds the three controls finance asks for: a hard budget, a named human approval, and a record that compares what was budgeted, quoted, and charged.

## Why Minecraft?

Because trust in an agent is a visibility problem. A chat log needs reading. A room can be read at a glance: a red ticket is over budget, dropped bars mean blocked, a bag at the door means the order is placed. It also gives the demo a physical story anyone can follow in 2 minutes. The engine does not depend on Minecraft; the kitchen is one front end for the same event stream.

## Is the Minecraft part pre-built?

The Minecraft agent platform (Agent Arena) is existing work and is disclosed in the README. Built at the event: the Reap purchasing client, the chef and its tools, the budget gate, the run record, the `crewkit_state` bridge message, the kitchen set, and the choreography.

## What does COMPLETED mean?

Reap's docs: "The merchant order is placed." It is a terminal status with an `orderId`. This is sandbox, so no money moves. The docs do not say whether the card charge at that point is an authorization or a capture, so we do not claim either.

## Is approval the only human step?

The human writes the brief and approves on Reap's hosted page. Mandates (pre-approved spending) are not live on Reap yet, so every purchase goes through that page.

## What if the price changes?

Each checkout comes from one quote and needs its own approval. If the quote expires or Reap asks for a replacement quote, the chef re-quotes and a new approval QR appears. Reap's docs do not say what happens if the merchant changes the price after approval, so we do not claim a behavior there. The bill board reports the variance between quoted and charged.

## How is the budget enforced? Can the model overspend?

Enforcement is server code, not a prompt. The engine compares the Reap quote total (items, shipping, tax) with the budget before it calls `POST /agentic/checkouts`. If the total is over, no checkout exists. The model can only change the cart and ask again.

## Security: what does the model see?

The model sees product names, prices, the budget, and tool results. It never sees the Reap API key or card data. The key is in a gitignored `.env` read only by the engine and is not logged. Card entry and approval happen on Reap's hosted pages. The coordinator bridge binds to `127.0.0.1` and accepts schema-validated messages only.

## What stops a double purchase?

Enrollment, quote, and checkout calls carry an `Idempotency-Key`, generated per logical operation and stored before sending. A retry with the same key replays the first response. A checkout that reaches `COMPLETED`, `FAILED`, or `EXPIRED` is terminal and is never retried in place.

## Why not celebrate when the checkout is created or approved?

Approval is not delivery. The delivery scene fires only when polling returns `COMPLETED`. `PROCESSING` shows the hourglass. `FAILED` and `EXPIRED` send the ticket back to the rail.

## What is live and what is choreographed?

| Part | Status |
|---|---|
| Reap search, quote, checkout, polling | Live against the sandbox in `live` mode |
| Budget gate and totals | Real server code on real quote totals |
| Approval QR and page | Reap's real hosted page |
| Order status and `orderId` | From Reap's checkout response |
| Minecraft kitchen: bag arriving, plating, sounds | Choreographed. It plays on events from the engine, and only the `completed` event starts delivery |
| Over-budget moment in the demo | The budget is set so the first cart comes in over. The gate is real, the budget is chosen to show it |
| Guest names and seating | From the brief |
| `replay` and `simulate` modes | Recorded or simulated, and labeled as such. `simulate` uses `X-Simulate-Checkout` |

Demo item names and prices come from what Reap's catalogue returns in sandbox.

## What are the sandbox limits?

- No real money moves, and no real delivery happens.
- Mandates are not live, and only `EXTERNAL` card enrollment works today.
- Rate limits: 10 requests per second, 150 per minute, 10,000 per day. The engine polls every few seconds to stay under them.
- Merchant coverage in sandbox is limited. We shop one Singapore merchant in SGD, so one quote is one merchant request, with one checkout and one QR.
- Quote and approval expiry durations are not documented, so the engine reads `expiresAt` rather than assuming a time.
- No agentic webhooks are documented, so polling is the only way to see a status change.

## What would it take to go to production?

Reap and Visa approval for production use, production keys, real merchants, and (once live) mandates so approved budgets can be spent without a page visit per order. The gate, idempotency, and record logic carry over unchanged.

## What is next?

Mandates with the budget as the ceiling, recurring team orders, finance-lead approval with per-person limits, a web view of the same state, and polished failure and expiry scenes.
