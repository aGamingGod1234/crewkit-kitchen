# CrewKit Kitchen: submission text

Paste each section into the submission form. Items marked `TODO` need a final value.

## Title

CrewKit Kitchen

## Tagline (<= 100 characters)

An AI chef buys your event supplies with Reap, and you watch every step in a Minecraft kitchen.

(94 characters.)

## Description (~150 words)

CrewKit Kitchen lets an AI agent buy an event's supplies through Reap's Agentic API while a human can see exactly what it does. You give the chef a brief: guests, a budget, what each person needs. It searches Reap's catalogue, builds a cart, and requests a quote. Our server checks the total against the budget and blocks the checkout if it is over, so the chef has to rework the cart. When it fits, a QR code appears. You scan it and approve on Reap's hosted page. When Reap reports the order as COMPLETED, a delivery bag arrives, the chef plates items for each named guest, and a bill board shows budget, quoted, charged, variance, and order id. Every checkout step is a physical action in a Minecraft kitchen, so you audit the agent by looking at the room. The API key and card details never reach the model.

## Problem

Teams are starting to let agents spend money on workshop kits, offsites, and office supplies. Placing the order is easy. Trusting it is hard. A chat log does not show at a glance what the agent bought, whether it stayed in budget, who approved the payment, or whether the order actually went through. Event ops and finance leads need spend that is visible, capped, and approved by a person.

## Solution

CrewKit turns an agent's purchase into a visible, step-by-step process with hard limits:

- A server-side budget gate that the model cannot bypass.
- Human approval on Reap's hosted page for every purchase.
- A delivery scene that fires only when Reap reports `COMPLETED`.
- A final record that compares budget, quoted total, and the amount actually charged.

The Minecraft kitchen is the interface. Each state (over budget, blocked, waiting for approval, delivered) has a distinct physical look, so anyone in the room can read the state of the spend without reading logs.

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

- Demo video: `TODO`
- Repository: `TODO` (public, MIT)
- Live demo / hosted page: `TODO`
- Plan: https://mwvetbk1qiwd.postplan.dev
- Team: `TODO`
