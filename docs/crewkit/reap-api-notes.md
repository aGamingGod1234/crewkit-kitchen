# Reap Agentic Payments API: hackathon notes

Checked 2026-10-08 against docs.reap.global (Mintlify). Every page below also exists as raw markdown: append `.md` to the URL. Index: https://docs.reap.global/llms.txt

Key pages:
- Overview: https://docs.reap.global/agentic-payments/overview
- How it works: https://docs.reap.global/agentic-payments/how-it-works
- Setup: https://docs.reap.global/agentic-payments/setup
- One-time purchases: https://docs.reap.global/agentic-payments/one-time-purchases
- Lifecycle: https://docs.reap.global/agentic-payments/lifecycle
- FAQ: https://docs.reap.global/agentic-payments/faq
- API ref: https://docs.reap.global/api-reference/agentic/create-checkout (and siblings)

The docs confirm the flow we expected, with three surprises:
- **Mandates are "coming soon"** and are not live in sandbox or prod (`MANDATES_AVAILABLE = false` on every guide page). So every checkout goes through the hosted approval page.
- **Only `EXTERNAL` enrollment works today.** The create-enrollment API ref marks `REAP_CARD` and `BIN_SPONSOR` as "Coming soon", even though the Setup guide shows examples for them.
- **Amounts are decimal numbers in major units, not minor units** (`129` means USD 129.00).

---

## 1. Base URLs and headers

Source: https://docs.reap.global/api-reference/overview, https://docs.reap.global/api-reference/authentication

| Env | Base URL |
|---|---|
| Singapore sandbox | `https://sg.sandbox.api.reap.global` (alias `https://sandbox.api.reap.global`, used in all agentic examples) |
| Singapore prod | `https://sg.prod.api.reap.global` (alias `https://prod.api.reap.global`) |
| Mexico sandbox / prod | `https://mx.sandbox.api.reap.global` / `https://mx.prod.api.reap.global` |

Keys are project-scoped and environment-scoped, so a sandbox key fails on prod with `401 INVALID_API_KEY`. Keys can carry an optional IP allowlist; a request from outside it gets `403 API_KEY_IP_NOT_ALLOWED`. To get keys: "Contact the Reap team".

| Header | When | Value |
|---|---|---|
| `Authorization` | every request | `Bearer <API_KEY>` |
| `Reap-Version` | every request, or `400 API_VERSION_HEADER_MISSING` | `2025-02-14`. This is the only enum value in the agentic OpenAPI. The idempotency page example shows `2026-01-01`; ignore it. |
| `Content-Type` | requests with a body | `application/json` |
| `Idempotency-Key` | **required** on `POST /agentic/enrollments`, `/agentic/quotes`, `/agentic/checkouts` | 1 to 255 chars, UUIDv4 recommended. ([how-it-works](https://docs.reap.global/agentic-payments/how-it-works)) |
| `X-Simulate-Checkout` | optional, `POST /agentic/checkouts`, sandbox only | Only value: `COMPLETED`. Rejected in prod. ([create-checkout](https://docs.reap.global/api-reference/agentic/create-checkout)) |

The docs show no simulation header for `FAILED` or `EXPIRED`, and no agentic webhooks, so polling is the only way to see status changes.

---

## 2. Call order for a one-time purchase

Flow source: https://docs.reap.global/agentic-payments/one-time-purchases. Field details come from each API reference page. All calls below are VERIFIED against the docs unless marked UNVERIFIED.

### 2.1 Create enrollment: `POST /agentic/enrollments` (Idempotency-Key required)
Ref: https://docs.reap.global/api-reference/agentic/create-enrollment

```json
{
  "source": "EXTERNAL",
  "owner": { "type": "CLIENT_REFERENCE", "id": "<your-customer-id>", "email": "jsmith@example.com" },
  "presentation": { "type": "REDIRECT", "returnUrl": "https://example.com/cards/added" }
}
```
- Required fields: `source`, `owner` (`type`, `id`, `email`), and `presentation` (`type`, `returnUrl`, which must be HTTPS).
- Reusing an idempotency key with a changed returnUrl is rejected.
- Response: `id`, `status: "REQUIRES_ACTION"`, `source`, `owner`, `nextAction { type: "REDIRECT", url, expiresAt }`.
- Send the user to `nextAction.url` (hosted card entry) before `expiresAt`. Reap then redirects them to `returnUrl`.
- Errors: `403 AGENTIC_PAYMENTS_NOT_ENABLED`, `404 AGENTIC_CARD_NOT_FOUND`, `503 AGENTIC_SERVICE_UNAVAILABLE`.

### 2.2 Confirm ACTIVE: `GET /agentic/enrollments/:id`
Ref: https://docs.reap.global/api-reference/agentic/get-enrollment
- Response: `id`, `status` (`REQUIRES_ACTION | ACTIVE | FAILED | EXPIRED | REVOKED`), `owner`, `paymentMethod { type: "CARD", network, last4, expiryMonth, expiryYear }`, `nextAction`, `createdAt`, `updatedAt`.
- Coming back to the returnUrl "does not prove the card is ready". Always read the status.
- To list stored cards: `GET /agentic/enrollments?ownerType=...&ownerId=...&limit=20`. `ownerId` is required and each call covers one owner. Pagination uses `nextCursor`.

### 2.3 Search products: `POST /agentic/products/search` (no idempotency key)
Ref: https://docs.reap.global/api-reference/agentic/search-products
```json
{
  "query": "Sony WH 1000XM5 headphones",
  "context": { "country": "US", "currency": "USD" },
  "filters": { "price": { "min": "100", "max": "200" }, "availability": "AVAILABLE_ONLY" },
  "pagination": { "limit": 20 }
}
```
- Only `query` is required. Optional `merchantPreference { mode: "PREFER" | "ONLY", merchantName }`. `limit` ranges 1 to 50. Price filters are **strings**.
- Response: `id`, `products[] { id, merchant { name }, name, imageUrl, priceRange { min, max }, available, previewVariant { id, name, price, available } }`, `pagination { nextCursor, hasNextPage, returnedCount }`, `warnings[]`.
- Error: `400 MERCHANT_NOT_RESOLVED`.

### 2.4 Product details: `POST /agentic/products/details`
Ref: https://docs.reap.global/api-reference/agentic/get-product-details
```json
{ "productIds": ["<product-id>"] }
```
- Response: `products[] { id, name, options[] { name, values[] { optionId, label, available } }, defaultVariant { id, price, available, requiresShipping } }`, `errors[]`.
- An ID that fails to resolve lands in `errors[]` and does not fail the call.
- If the user accepts `defaultVariant`, skip step 2.5.

### 2.5 Resolve variant: `POST /agentic/products/variant`
Ref: https://docs.reap.global/api-reference/agentic/resolve-variant
```json
{ "productId": "<product-id>", "optionIds": ["<option-id>"] }
```
- Response: `id` (the **variant id**, "the only id accepted by POST /agentic/quotes"), `name`, `options[]`, `price`, `available`, `requiresShipping`.
- Stop if `available` is false.
- Error: `400 VARIANT_RESOLUTION_FAILED`.

### 2.6 Create quote: `POST /agentic/quotes` (Idempotency-Key required)
Ref: https://docs.reap.global/api-reference/agentic/create-quote

Send exactly one of `items` or `externalCheckout`. `email` is required. `shippingAddress` is required when any item has `requiresShipping` and always for `externalCheckout`.

```json
{
  "items": [{ "variantId": "var_123", "quantity": 1 }],
  "email": "avery.tan@reap.hk",
  "shippingAddress": {
    "firstName": "Avery", "lastName": "Tan", "phone": "+85200000000",
    "addressLine1": "123 Example Street", "city": "Example City",
    "postalCode": "000000", "country": "HK"
  },
  "offerCode": "SAVE10"
}
```
- Address required fields: `firstName`, `lastName`, `phone` (E.164, regex `^\+[1-9]\d{6,14}$`), `addressLine1`, `city`, `country`.
- Address optional fields: `addressLine2`, `region`, `postalCode`. `offerCode` is optional (max 128).
- `externalCheckout { merchantDomain, checkoutUrl }` only works for merchant domains Reap has allowlisted. The FAQ says the custom checkout URL flow is enabled per account, so treat it as unavailable unless a mentor confirms.
- Response: `id`, `shippingOptions[] { id, name, selected, price, details[] }`, `amountBreakdown { itemsSubtotal, shipping, tax { amount, includedInPrices }, discounts[], additionalCharges[], finalAmount }`, `expiresAt`.
- One shipping option comes preselected.
- Errors:
  - 400: `CHECKOUT_URL_INVALID`, `CARD_PAYMENT_UNAVAILABLE` (do not retry unchanged), `OFFER_CODE_INVALID`, `OFFER_CODE_EXPIRED`, `QUOTE_UNFULFILLABLE` (reasons `INVALID_PHONE`, `STATE_OR_PROVINCE_REQUIRED`, `ITEMS_UNSHIPPABLE`, `ADDRESS_LINE_2_REQUIRED`)
  - 409: `QUOTE_EXPIRED`, `VARIANT_UNAVAILABLE`
  - 503: `QUOTE_TEMPORARILY_UNAVAILABLE` (comes with a `Retry-After` header)
- "Reap makes one merchant request for each quote attempt. It does not retry a failed request."

### 2.7 Change shipping (optional): `POST /agentic/quotes/:id/shipping-option`
Ref: https://docs.reap.global/api-reference/agentic/select-shipping-option
```json
{ "shippingOptionId": "<standard-option-id>" }
```
- Response: the updated quote with the same shape as 2.6 and a fresh `amountBreakdown`.
- No Idempotency-Key parameter is listed.
- Errors: 400 `SHIPPING_OPTION_INVALID` and offer-code errors; 409 `QUOTE_EXPIRED`, `QUOTE_REPLACEMENT_REQUIRED` (create a new quote), `QUOTE_NOT_MUTABLE`.
- `GET /agentic/quotes/:id` re-reads the current total without changing anything ([get-quote](https://docs.reap.global/api-reference/agentic/get-quote)).

### 2.8 Create checkout: `POST /agentic/checkouts` (Idempotency-Key required)
Ref: https://docs.reap.global/api-reference/agentic/create-checkout
```json
{
  "quoteId": "<quote-uuid>",
  "enrollmentId": "<enrollment-uuid>",
  "presentation": { "type": "REDIRECT", "returnUrl": "https://example.com/orders/done" }
}
```
- Optional sandbox header: `X-Simulate-Checkout: COMPLETED`. IDs are UUIDs.
- Response: `id`, `status` (normally `REQUIRES_ACTION`), `quoteId`, `enrollmentId`, `amount { amount, currency }`, `nextAction { type: "REDIRECT", url, expiresAt }`.
- If `nextAction` is present, send the user to the hosted approval URL. If it is null, the charge ran under earlier approval, which only applies to mandates and so does not apply today.
- Errors:
  - 400: `AGENTIC_REQUEST_REJECTED` (detail.field `quoteId` or `enrollmentId`)
  - 404: `ENROLLMENT_NOT_FOUND`, `QUOTE_NOT_FOUND`
  - 409: `ENROLLMENT_NOT_ACTIVE` (detail.reason `CARD_NOT_CAPTURED`), `QUOTE_EXPIRED`
  - 503: `CHECKOUT_TEMPORARILY_UNAVAILABLE`

### 2.9 Poll: `GET /agentic/checkouts/:id`
Ref: https://docs.reap.global/api-reference/agentic/get-checkout
- Response: `id`, `status`, `quoteId`, `enrollmentId`, `orderId` (nullable), `finalAmount { amount, currency }`, `nextAction`, `createdAt`, `updatedAt`.
- Poll until the status is `COMPLETED`, `FAILED`, or `EXPIRED`. Store `orderId` and reconcile against `finalAmount`, not the quote total.
- The GET response enum lists `PROCESSING | COMPLETED | FAILED | EXPIRED` and omits `REQUIRES_ACTION`, while the lifecycle page includes it. Handle `REQUIRES_ACTION` on GET anyway.
- No polling interval is documented. UNVERIFIED suggestion: poll every 2 to 3 s, which stays well under the sandbox rate limit.
- Error: `404 CHECKOUT_NOT_FOUND`.

---

## 3. Status lifecycle

Source: https://docs.reap.global/agentic-payments/lifecycle

**Enrollment:** `REQUIRES_ACTION` -> `ACTIVE` -> `REVOKED`. `FAILED` means Reap could not store the card. `EXPIRED` means the hosted step was not finished before `expiresAt`. For both, create a new enrollment. Revoke with `POST /agentic/enrollments/:id/revoke`; revoking is final.

**Quote:** has no status, only `expiresAt`. Once it passes, create a new quote, "because merchant prices can move in between".

**Checkout:**

| Status | Docs meaning |
|---|---|
| `REQUIRES_ACTION` | "The charge is waiting on user approval" |
| `PROCESSING` | "The charge is approved and the order is being placed" |
| `COMPLETED` | "The merchant order is placed" |
| `FAILED` | "The charge or the merchant order did not go through" (needs a new quote and a new checkout) |
| `EXPIRED` | "The user did not approve before `expiresAt`" (needs a new quote and a new checkout) |

`COMPLETED`, `FAILED`, and `EXPIRED` are terminal and cannot be retried in place. The state diagram labels the PROCESSING -> COMPLETED transition "the merchant places the order".

**What COMPLETED means:** the docs define it as **order confirmed** ("The merchant order is placed", with `orderId` present). The docs do **not say** whether the card charge at that point is an authorization or a capture. `finalAmount` is described as "the amount actually charged", which suggests the charge went through, but the auth versus capture stage is never named. Ask the mentors (see section 6).

---

## 4. Money, limits, expiry, idempotency, re-approval

- **Money format:** `{ "amount": <number>, "currency": "<ISO 4217, 3 chars>" }`. Fiat amounts are JSON numbers "at the currency's native precision (2 decimal places for most fiat)", so they are **major units, not minor units** ([overview](https://docs.reap.global/api-reference/overview)). Example: `{ "amount": 134, "currency": "USD" }` is $134.00. Search price filters are strings (`"100"`).
- **Rate limits** ([rate-limiting](https://docs.reap.global/api-reference/rate-limiting)): counted per project, not per key. **Sandbox default: 10/s, 150/min, 10,000/day** (confirms our 10/s and 150/min). Prod default is 20/s, 600/min, 500k/day. Agentic endpoints are not in any tighter tier, so they fall under default.
  - Responses carry `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, and `RateLimit-Policy` headers.
  - Over the limit you get `429 RATE_LIMIT_EXCEEDED` plus `Retry-After` (seconds).
  - Watch the **10,000/day sandbox cap** if an agent loop polls.
- **Quote expiry:** each quote returns `expiresAt`, but **the duration is not stated** ("short-lived", "a short window"). Checkout `nextAction.expiresAt` and enrollment `nextAction.expiresAt` durations are not stated either.
- **Idempotency** ([idempotency](https://docs.reap.global/api-reference/idempotency)):
  - Keys last 24 h. A retry with the same key and same body replays the cached response and adds the header `Idempotent-Replayed: true`.
  - Same key with a different body: `400 IDEMPOTENT_PARAMETER_MISMATCH`.
  - Concurrent requests with the same key: `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`.
  - **All outcomes are cached, including 4xx and 5xx**, except 401, 422, and 429. After a business error, fix the input and use a new key.
  - Retry network errors and 429 with the same key. Generate one key per logical operation and persist it before sending.
- **Retries in general** ([errors](https://docs.reap.global/api-reference/errors)): use exponential backoff for 5xx and honor `Retry-After` for 429. For other 4xx, fix the request. Branch on `error.code`, not on the HTTP status. Error shape: `{ "error": { "code", "message", "detail" } }`.
- **Re-approval on price change:** every checkout in the current non-mandate flow needs its own hosted approval of `amount`. If a quote expires or a shipping change returns `QUOTE_REPLACEMENT_REQUIRED`, you need a new quote and a new checkout, which means a new approval. The docs do **not say** what happens if the merchant total changes between approval and placing the order: whether the order fails, re-prompts, or charges a different `finalAmount`. For mandates, which are not live, the mandate `amount` is a ceiling and "the final quote total has to stay at or below that amount for the charge to run under the same approval" ([how-it-works](https://docs.reap.global/agentic-payments/how-it-works)). One search result summary claimed Reap "reconciles the live total against the quote before any charge". I could not find that sentence on the current pages: UNVERIFIED.

---

## 5. Setup prerequisites

Source: https://docs.reap.global/agentic-payments/setup
- A Reap API key for sandbox, obtained from the Reap team.
- **Agentic Payments enabled on the project.** Without it, every agentic endpoint returns `403 AGENTIC_PAYMENTS_NOT_ENABLED`.
- An **HTTPS return URL** for both hosted pages (card entry and approval). For local dev we need an HTTPS tunnel or a deployed URL. Whether `localhost` is accepted is UNVERIFIED.
- Cards must support **Visa Cloud Token Framework / Visa Token Service tokenization** (relevant for real cards).
- **Sandbox test cards** (they only work through the Agentic API):

  | Card | CVC | Expiry |
  |---|---|---|
  | 4622 9431 2313 7797 | 640 | 12/27 |
  | 4622 9431 2313 7805 | 304 | 12/27 |
  | 4622 9431 2313 7847 | 698 | 12/27 |

- **Sandbox OTP** for card verification: `456789` (works for both email and SMS).
- Merchant coverage changes over time; ask Reap which merchants are live ([FAQ](https://docs.reap.global/agentic-payments/faq)).
- MCP or CLI agent builds stay sandbox-only for now; prod needs Visa and Reap approval ([FAQ](https://docs.reap.global/agentic-payments/faq)).

---

## 6. Questions for Reap mentors at kickoff

1. Is Agentic Payments already enabled on our hackathon project and key? Which region host should we use (`sandbox.api.reap.global` vs `sg.sandbox...`)?
2. Does COMPLETED mean the card was authorized only, or captured? Can a COMPLETED order later be reversed or refunded, and is there an endpoint for that?
3. How long do quotes, checkout approval links, and enrollment card-entry links stay valid (`expiresAt` durations)?
4. If the merchant price changes after the user approves, what happens: FAILED, a re-approval, or a different `finalAmount`? Is there a tolerance?
5. Which merchants and catalogs return results in sandbox? Can you suggest a search query that reliably works for a demo?
6. With `X-Simulate-Checkout: COMPLETED`, do we still need to open the hosted approval page, or does the checkout jump straight to COMPLETED? Can we simulate FAILED or EXPIRED?
7. Can sandbox enrollments skip the hosted card-entry page, or must a human enter a test card each time?
8. Does `returnUrl` accept `http://localhost` in sandbox, or must it be public HTTPS?
9. Are there agentic webhooks (checkout or enrollment status) planned or available, or is polling the only option? What poll interval do you recommend given the 10/s, 150/min, 10k/day sandbox limits?
10. Are the hackathon project's rate limits raised above the default sandbox tier?
11. Are mandates (recurring or pre-approved charges) usable at the buildathon, or still unavailable?
12. Is the `externalCheckout` (custom checkout URL) flow enabled for us, and for which merchant domains?
13. The GET checkout enum omits `REQUIRES_ACTION`. Can GET return it before the user approves?
14. Which `Reap-Version` should we send? Is it `2025-02-14`?
15. Are `REAP_CARD` and `BIN_SPONSOR` enrollment sources live in sandbox, or only `EXTERNAL`? Can we issue a Reap card ourselves during the event?
