# Task 3 implementation report: warm provider sessions and supplemental context deltas

## Outcome

Task 3 now keeps exact provider profile/session identity observable, adds bounded fact and conversation cursors, and renders supplemental full/delta context while carrying the complete authoritative planner state on every prompt. The catalog cache remains capability-only and does not retain decisions, programs, completion claims, or provider output.

## TDD evidence

Focused tests were written before each production change.

### RED

Against the Task 3 starting commit, the new tests failed for the intended missing behavior:

```text
npm test -- --test-name-pattern="projects keyed|expiry tombstones|projects only new|full baseline after ring|planner input always|supplemental context|decision-like"
```

Relevant failures included `ledger.delta is not a function`, `memory.delta is not a function`, and the missing `buildSupplementalContext` export. Provider metadata tests initially failed with the missing `provider-session.mjs` module. The telemetry regression then failed because session fields were not present.

### GREEN

Focused coordinator coverage:

```text
node --test test/codex-service.test.mjs test/acp-service.test.mjs test/antigravity-service.test.mjs test/agent-planner.test.mjs test/fact-ledger.test.mjs test/conversation-memory.test.mjs test/prompts.test.mjs test/model-catalog-cache.test.mjs test/provider-health-registry.test.mjs
```

Result:

```text
tests 88
pass 88
fail 0
cancelled 0
```

## Session and cursor schema

The canonical profile fingerprint is `sha256:<64 lowercase hex characters>` over the exact tuple `{provider, model, reasoningEffort, serviceTier}`. Provider agents expose `profileFingerprint`, `sessionGeneration`, and `sessionMetadata()` with `sessionState`, `sessionReuse`, `continuation`, `durability`, and bounded `resetReason`. Codex and ACP report durable continuation; Antigravity reports explicit best-effort, unverified continuation.

The context cursor is:

```json
{
  "agentId": "...",
  "profileFingerprint": "sha256:...",
  "sessionGeneration": 1,
  "goalRevision": 0,
  "serverInstanceId": "...",
  "factRevision": 0,
  "conversationSequence": -1
}
```

`advanceContextCursor` returns the prior cursor unless `providerAccepted === true`, and rejects binding changes before advancing.

Fact projections use `{fullBaseline, baseRevision, nextRevision, upserts, removals}` with keyed replacement and expiry tombstones. Evicted/unknown bases return a bounded full baseline. Conversation projections use `{fullBaseline, baseSequence, nextSequence, entries}` and fall back after ring eviction or reset.

## Prompt behavior

`buildPlannerInput` always serializes the full `Minecraft planner state (authoritative JSON)` section. Only the untrusted fact ledger and conversation sections can be delta projections. Deltas retain the original untrusted JSON labels and add an explicit `mode` (`delta` or `full_baseline`). Stale profile/session/goal/server bindings or hash mismatch force full supplemental baselines. No decision-like fields are accepted by the model catalog cache.

## Files changed

- `coordinator/src/provider-session.mjs`: canonical profile fingerprint and redacted session metadata.
- `coordinator/src/codex-service.mjs`, `acp-service.mjs`, `antigravity-service.mjs`: generation, fingerprint, warm/cold, continuation, and replacement metadata.
- `coordinator/src/agent-planner.mjs`, `provider-turn-telemetry.mjs`: session metadata in bounded telemetry.
- `coordinator/src/fact-ledger.mjs`: bounded revision journal, keyed upserts, removals, tombstones, reset, and full fallback.
- `coordinator/src/conversation-memory.mjs`: sequence deltas, eviction/reset fallback, and reset.
- `coordinator/src/prompts.mjs`: supplemental projection rendering, cursor binding, acceptance-gated advancement, and ledger/memory integration.
- Focused provider, planner, ledger, memory, prompt, and catalog tests.

## Prompt-size measurements

No live provider/device or expensive performance fixture was run in this focused Task 3 worktree. The deterministic tests verify that unchanged warm context emits empty labeled supplemental deltas while authoritative state remains present; byte p50/p95 fixture reporting remains a release-gate follow-up.

## Self-review and concerns

- No decision, tactic, ArenaScript source, watcher response, completion claim, reaction, goal, or speech is cached or synthesized.
- Existing full-snapshot prompt callers remain compatible.
- Antigravity continuation is deliberately marked best-effort/unverified because its CLI continuation has no durable session identifier.
- Changed-server ledger clearing and dynamic reconnect wiring remain outside this owned provider/prompt/ledger patch and must be completed by the coordinator/reconnect owner before release.
- `git diff --check` is clean; the full repository verification script was not run because this handoff was restricted to focused coordinator tests and no live providers/devices.
