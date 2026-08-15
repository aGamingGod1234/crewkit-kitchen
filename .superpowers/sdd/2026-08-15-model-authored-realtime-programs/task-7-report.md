# Task 7 report: factual attention deltas and local timing

## Delivered

- Server observations now carry a monotonic per-agent `eventSequence`, `attention`, and factual-only `changedFacts`.
- First and unchanged heartbeat publications remain `attention: false` with an empty delta. Material player, inventory, entity, block, world, and action changes are sent as factual paths only.
- The existing bounded eight-per-tick queue continues to coalesce each agent, with a 16-agent burst clearing in two drains.
- V2 validates the new ready-observation fields and rejects tactical labels such as `danger`, `fight`, and `flee`.
- Watcher-originated action provenance retains the exact triggering event sequence.
- The latency registry only accepts: `minecraft_change_to_publication`, `event_receipt_to_branch`, `branch_to_bridge_send`, `command_to_first_progress`, and `action_completion`. Program timing is injected into the existing coordinator status registry; provider inference is not recorded.

## Review corrections

- World clock-only heartbeats no longer create attention. Entity and block churn coalesces to aggregate factual paths before the shared 256-path wire limit.
- The coordinator captures receipt time before per-agent queueing and passes it as monotonic timing context. Epoch timestamps are used only for Minecraft publication delay; skewed, regressing, and zero-duration samples are omitted.
- Completion is measured from bridge send to terminal result, including immediate terminal results; first progress remains first-only.
- The server uses a bridge-lifetime global observation sequence, so recreating an agent cannot restart its sequence. The manager ignores stale or duplicate server observations and only synthesizes event identities for sequence-less action events.
- The benchmark now uses `performance.now()`, validates exactly 1,000 watcher branches and commands, and keeps the p95 local timings below 5 ms.

## Verification

- Focused Node suites: 37/37 passed, including the real monotonic-clock 1,000 watcher-event benchmark with p95 receipt-to-branch and branch-to-send below 5 ms.
- Java 25 `verifyCore`: passed with 5,964 protocol and bridge assertions.
- No dependency files changed, so a clean archive verification was not required.

## Commit

- `0dc66a8 feat: stream factual attention events` (11 files, 346 insertions, 30 deletions).
- `9a29084 fix: correct factual delta timing` (10 files, 226 insertions, 42 deletions).
