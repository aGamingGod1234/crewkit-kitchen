# Task 7 report: factual attention deltas and local timing

## Delivered

- Server observations now carry a monotonic per-agent `eventSequence`, `attention`, and factual-only `changedFacts`.
- First and unchanged heartbeat publications remain `attention: false` with an empty delta. Material player, inventory, entity, block, world, and action changes are sent as factual paths only.
- The existing bounded eight-per-tick queue continues to coalesce each agent, with a 16-agent burst clearing in two drains.
- V2 validates the new ready-observation fields and rejects tactical labels such as `danger`, `fight`, and `flee`.
- Watcher-originated action provenance retains the exact triggering event sequence.
- The latency registry only accepts: `minecraft_change_to_publication`, `event_receipt_to_branch`, `branch_to_bridge_send`, `command_to_first_progress`, and `action_completion`. Program timing is injected into the existing coordinator status registry; provider inference is not recorded.

## Verification

- Focused Node suites: 34/34 passed, including the deterministic injected-clock 1,000 watcher-event benchmark with p95 receipt-to-branch and branch-to-send below 5 ms.
- Java 25 `verifyCore`: passed with 5,959 protocol and bridge assertions.
- No dependency files changed, so a clean archive verification was not required.

## Commit

- `0dc66a8 feat: stream factual attention events` (11 files, 346 insertions, 30 deletions).
