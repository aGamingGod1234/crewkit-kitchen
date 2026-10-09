# CrewKit Kitchen: submission text

Paste each section into the submission form. Items marked `TODO` need a final value.

## Title

CrewKit Kitchen

## Tagline (<= 100 characters)

An AI chef turns a workshop guest list into a budget-checked Reap order, shown in Minecraft.

(92 characters.)

## Description (~150 words)

CrewKit Kitchen helps a student-club workshop organiser turn an attendee list into a complete supply order within a small approved budget. The organiser writes a brief: guests, budget, what each person needs. The AI chef searches Reap's catalogue, works out per-person and shared quantities, and requests a quote. Two server checks run before checkout: every mandatory quantity is covered, and the shipping-inclusive quote is within budget. If not, the chef swaps in cheaper products and keeps the requirements, or the brief is reported infeasible. When both pass, a QR code opens Reap's hosted approval page. When Reap reports the order COMPLETED, the Minecraft kitchen shows the order placed and plays the delivery and plating scene for each named guest. Minecraft is the shared display, so everyone can read the order's state. The API key and card details never reach the model.

## Problem

The user is a student-club workshop organiser with an attendee list and a small approved budget. Turning that list into an order is tedious and error-prone: comparing products across listings, working out which items are per person and which are shared, adding shipping to the total, and forgetting items. A mistake means a second order or an over-budget one. An agent can do the legwork, but the organiser needs to check what it did without reading a chat log.

> TODO: organiser testimonial (quote, name, club)

## Proof: a real sandbox run

A real Reap sandbox run completed: order `ord_01M4G0J3JSEP31G7NASSA0K649` at Popular Bookstore (Singapore), 6 guests, budget S$105.

- The first real quote was S$196.85, so the server gate blocked checkout.
- Sandbox stock was limited. Badges and notebooks were sold out at quantity 6, and no single cable listing had 3. The agent swapped to in-stock listings and split quantities (cables 1+1+1, notebooks 3+3).
- Rework took the quote from S$196.85 to S$137.25, S$131.65, then S$102.75, and dropped only the optional sticky notes.
- The requirements check and the gate passed. A human approved on Reap's hosted page with a passkey.
- Reap reported `COMPLETED`. Charged S$102.75, variance S$0.00, 154 Reap API calls. Reap returned several 503 responses and the backoff recovered.
- The demo replay is that recorded run, with the approval URL redacted. This is the sandbox: no real money moved, and delivery is visualized.

In the kitchen: a fan of candidate products from real search results, ghost plates for the cart that turn solid when paid, a typewriter bill, and a written-book receipt. The chef answers by voice (OpenAI speech-to-text and text-to-speech through Simple Voice Chat) and speaks lines at key beats. It is summoned and placed automatically and cannot mine or wander.

## Solution

CrewKit makes the agent's purchase a visible, step-by-step process with checks the model cannot skip:

- A requirements check: every mandatory quantity is covered.
- A budget gate: the shipping-inclusive quote is within budget. Over-budget quotes cannot create a checkout through our tool.
- Rework: the chef substitutes cheaper products and keeps the requirements. If nothing fits, the brief is reported infeasible.
- Human approval on Reap's hosted page for every purchase.
- A delivery scene that appears only when Reap reports `COMPLETED`. Order placed; delivery visualized. The sandbox ships nothing.
- A final record that compares budget, quoted total, and the amount charged.

The Minecraft kitchen is the shared visual interface. Each state (over budget, blocked, waiting for approval, order placed) has a distinct physical look.

Demo honesty: hosted approval is a real human step on Reap's sandbox page. Simulated completion (`X-Simulate-Checkout: COMPLETED`) is a separate mode that skips approval and is labeled as simulated. We make no onchain claims.

## How Reap is used

Reap Agentic API, sandbox (`https://sg.sandbox.api.reap.global`, `Reap-Version: 2025-02-14`).

| Step | Endpoint |
|---|---|
| Card on file | `POST /agentic/enrollments`, `GET /agentic/enrollments/:id` (hosted card entry, confirm `ACTIVE`) |
| Find products | `POST /agentic/products/search`, `POST /agentic/products/details`, `POST /agentic/products/variant` |
| Price the cart | `POST /agentic/quotes` (items, email, shipping address), returns `amountBreakdown` and `expiresAt` |
| Create the purchase | `POST /agentic/checkouts` (returns the hosted approval URL, shown as a QR) |
| Track the result | `GET /agentic/checkouts/:id`, polled until `COMPLETED`, `FAILED`, or `EXPIRED` |

Enrollment, quote, and checkout calls send an `Idempotency-Key`. The bill board reconciles against the checkout's `finalAmount`. Sandbox runs can also use `X-Simulate-Checkout: COMPLETED`.

## Tech stack

- Reap Agentic API (sandbox)
- Node.js: CrewKit engine, Reap client, budget gate, run record (`coordinator/src/crewkit/`)
- LLM chef agent driven through the Agent Arena coordinator and provider CLIs
- Java 25, Fabric, Carpet: Minecraft 26.1.2 mod for the kitchen set, display entities, and choreography
- `crewkit_state` event bridge between the coordinator and the mod
- Mermaid and Markdown docs

## What is next

- Pre-approved spending with Reap mandates once they go live, with the budget as the ceiling.
- Real merchants and cards in production after Reap and Visa approval.
- Recurring orders for teams, such as monthly office supplies.
- Approval by finance leads instead of the requester, with per-person limits.
- A web view of the same state for people not in the game.
- Failure and expiry scenes (re-quote, new approval QR) polished to the same level as the happy path.

## Links

- Demo video: TODO (link)
- Repository: https://github.com/aGamingGod1234/crewkit-kitchen (public, MIT)
- Plan: https://mwvetbk1qiwd.postplan.dev
- Team: The Greek Warriors. `TODO: member names`
