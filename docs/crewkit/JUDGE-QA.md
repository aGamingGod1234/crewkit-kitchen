# CrewKit Kitchen: judge Q&A

Short answers. Say only what is true today. Sandbox means no real money moves.

## What problem does this solve, and who is the user?

One user: a student-club workshop organiser. They have an attendee list and a small approved budget. Turning that into an order means comparing products, working out per-person versus shared quantities, adding shipping, and not forgetting anything. CrewKit does that legwork and makes the result checkable.

## Why is it worthwhile for business spend and event ops?

Workshop kits are small, repeated, and easy to get wrong: per-person items, shared items, a fixed budget. A mistake costs a second order or an over-budget cart. The same pattern applies to team offsites and office supplies, but we built and tested for the workshop case. CrewKit adds the controls an approver wants: a requirements check, a budget gate, human approval on Reap's page, and a record that compares what was budgeted, quoted, and charged.

## What happens if the budget is too small?

The chef reworks the cart by substituting cheaper products while keeping the requirements (every mandatory quantity). If no combination fits, the brief is reported infeasible. It does not drop required items to hit the number.

## Why Minecraft?

It is the shared visual interface, not the point. A chat log needs reading. A room can be read at a glance: a red ticket is over budget, dropped bars mean blocked, a bag at the door means Reap reported the order placed. It also gives a demo anyone can follow in 2 minutes. The engine does not depend on Minecraft; the kitchen is one front end for the same event stream.

## Do you have user feedback?

Not yet. We built and tested the workflow at the event; talking to organisers is the next step.

## Did it work against the real Reap sandbox?

Yes. A real run completed: order `ord_01M4G0J3JSEP31G7NASSA0K649` at Popular Bookstore (Singapore), 6 guests, budget S$105. The first real quote was S$196.85, so the server gate blocked checkout. Badges and notebooks were sold out at quantity 6 and no single cable listing had 3, so the agent swapped to in-stock listings and split quantities (cables 1+1+1, notebooks 3+3). Rework took the quote from S$196.85 to S$137.25, S$131.65, then S$102.75, dropping only the optional sticky notes. Both checks passed, a human approved on Reap's hosted page with a passkey, and Reap returned `COMPLETED`: charged S$102.75, variance S$0.00, 154 API calls. Reap returned several 503 responses and the backoff recovered. The demo replay is that recorded run with the approval URL redacted. It is the sandbox, so no real money moved, and delivery is visualized.

## Is the Minecraft part pre-built?

The Minecraft agent platform (Agent Arena) is existing work and is disclosed in the README. Built at the event: the Reap purchasing client, the chef and its tools, the budget gate, the run record, the `crewkit_state` bridge message, the kitchen set, and the choreography.

## What does COMPLETED mean?

Reap's docs: "The merchant order is placed." It is a terminal status with an `orderId`. This is sandbox, so no money moves. The docs do not say whether the card charge at that point is an authorization or a capture, so we do not claim either.

## Is approval the only human step?

The human writes the brief and approves on Reap's hosted page. Mandates (pre-approved spending) are not live on Reap yet, so every purchase goes through that page.

## What if the price changes?

Each checkout comes from one quote and needs its own approval. If the quote expires or Reap asks for a replacement quote, the chef re-quotes and a new approval QR appears. Reap's docs do not say what happens if the merchant changes the price after approval, so we do not claim a behavior there. The bill board reports the variance between quoted and charged.

## How is the budget enforced?

Enforcement is server code, not a prompt. Two checks run before `POST /agentic/checkouts`: a requirements check (every mandatory quantity is covered) and the budget gate (the shipping-inclusive quote is within budget). Over-budget quotes cannot create a checkout through our tool. The model can only change the cart and ask again. This is a property of our tool, not of Reap's API.

## Security: what does the model see?

The model sees product names, prices, the budget, and tool results. It never sees the Reap API key or card data. The key is in a gitignored `.env` read only by the engine and is not logged. Card entry and approval happen on Reap's hosted pages. The coordinator bridge binds to `127.0.0.1` and accepts schema-validated messages only.

## What stops a double purchase?

Enrollment, quote, and checkout calls carry an `Idempotency-Key`, generated per logical operation and stored before sending. A retry with the same key replays the first response. A checkout that reaches `COMPLETED`, `FAILED`, or `EXPIRED` is terminal and is never retried in place.

## Why not show the delivery scene when the checkout is created or approved?

Approval is not an order. The scene plays only when polling returns `COMPLETED`: order placed; delivery visualized. We do not claim goods were delivered or that everyone received supplies, because the sandbox ships nothing. `PROCESSING` shows the hourglass. `FAILED` and `EXPIRED` send the ticket back to the rail.

## Was the approval real in the demo?

Yes, in the recorded run. The simulate header was set, but Reap still returned `REQUIRES_ACTION` and a human approved with a passkey on Reap's sandbox hosted page. The demo replays that run and is captioned as a replay. We make no onchain claims.

## What is live and what is choreographed?

| Part | Status |
|---|---|
| Reap search, quote, checkout, polling | Live against the sandbox in `live` mode |
| Budget gate and totals | Real server code on real quote totals |
| Approval QR and page | Reap's real hosted page |
| Order status and `orderId` | From Reap's checkout response |
| Minecraft kitchen: bag arriving, plating, sounds | Choreographed. It plays on events from the engine, and only the `completed` event starts delivery |
| Over-budget moment in the demo | Real. In the recorded run the first real quote was S$196.85 against a S$105 budget and the gate blocked checkout |
| Demo replay | The recorded real sandbox run, approval URL redacted. Replay mode makes no network calls |
| Chef voice and spoken lines | OpenAI speech-to-text and text-to-speech through Simple Voice Chat. Spoken lines at key beats are scripted |
| Typewriter bill, written-book receipt, ghost plates turning solid | Kitchen visuals driven by the engine's events |
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

## Who wants this enough to pay?

Student-club workshop organisers are our target. We demonstrated the workflow; we have not yet validated willingness to pay or repeat demand.

## Why would an organiser install Minecraft to buy stationery?

They wouldn't need to. Minecraft is the shared display for this demo; the engine is a plain Node service and a simple web view is the obvious next step.

## Is this a shopping chatbot with a staged animation?

No. The recorded sandbox run handled sold-out substitutions and our server blocked an over-budget checkout before a human approved with a passkey; the delivery scene only visualizes COMPLETED.
