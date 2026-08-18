# Model-Owned Minecraft Responsiveness Design

## Purpose

Make Minecraft agents receive fresh, accurate world information while an action is running without allowing the server, coordinator, or controller to choose a gameplay response. The AI model remains the only component allowed to decide whether the player continues, cancels, or replaces an action.

This design is pre-approved by the operator's instruction to audit, fix, verify, and repeat without waiting for additional approval.

## Autonomy Boundary

The information layer may:

- sample raw Minecraft state;
- report raw state changes and action progress;
- schedule a model decision when new information is available;
- coalesce newer observations while one model turn is in flight;
- reject stale revisions or action identities;
- report that an explicit action succeeded, failed, timed out, was cancelled, or became mechanically impossible.

The information and execution layers may not:

- label a health decrease as a reason to stop or replan;
- choose fight, flight, recovery, retaliation, food use, or another survival response;
- cancel or replace an action because a gameplay fact changed;
- stop a goal because the model repeated a failed action;
- silently alter the model's requested strategy.

Death, operator stop/steer commands, bridge disconnection, protocol invalidity, action validation, cleanup, and action timeout remain runtime concerns. They describe whether an explicit command can be executed safely and consistently; they do not select the next gameplay action.

## Architecture

### Server observation publisher

The Java bridge will publish observations at action start and completion, on bounded action heartbeats, and immediately when a compact raw player-state fingerprint changes. The fingerprint contains factual fields such as health, food, fire, water, air, suffocation, on-ground state, fall distance, and last-attacker identity. It does not contain a danger classification or response recommendation.

Damage will no longer return `FAILED / DAMAGE_OBSERVED`. The current action continues until it reaches its normal result or the model explicitly cancels or replaces it.

Each observation includes its collection time and the current action identity. Existing queue deduplication and per-tick limits remain the backpressure boundary.

### Model decision protocol

Planner output gains a required directive:

- `continue`: keep the current action; `actions` must be empty.
- `cancel`: explicitly cancel the current action; `actions` must be empty.
- `replace`: cancel the current action when needed and execute one to four supplied actions.

When no action is active, only `replace` is valid. Terminal goal decisions remain `replace` with exactly one `complete_goal` action.

The coordinator associates a reactive decision with the observed goal revision and action ID. A decision for a different or completed action is discarded. New observations are coalesced while a model turn is in flight, then the newest state is considered next.

### Explicit cancellation and replacement

Protocol v2 gains an action-cancel message carrying the goal revision and action ID. The server accepts it only when both identify the current action. Cancellation produces the ordinary terminal `CANCELLED` result. For replacement, the coordinator waits for that result before sending the model's replacement command, so overlapping controls never reach the offline player.

### Failure evidence

Repeated failures remain factual ledger entries visible to the model. The coordinator never promotes them to `ERROR` or stops the goal. Provider failures, malformed decisions, stale revisions, and broken transport remain system errors because they prevent the model-control contract from functioning.

### Human-like information

Raw state replaces derived `dangerousFall` and damage-replan concepts. Perception must not claim human-like visibility while exposing hidden exact entities through walls. This iteration adds explicit visibility and line-of-sight facts and stops treating hidden entity details as visible facts; further sensory fidelity is verified in the repeated audit.

## Responsiveness and Backpressure

Minecraft sampling runs on the 20 Hz server tick. State-change observations are queued immediately and drained with the existing bounded per-tick budget. Only one model turn per agent is active at a time. Additional observations replace a single pending snapshot instead of creating an unbounded provider queue.

No local design can make a remote model react faster than provider inference latency. The implementation therefore measures these separately:

- Minecraft change to observation publication;
- observation receipt to model decision;
- model directive to cancel acknowledgement or first action progress.

## Verification

Focused tests must prove:

- health loss does not terminate an active action;
- a raw observation is published while an action remains active;
- active-action observations reach the planner;
- `continue` sends no motor or cancellation command;
- `cancel` cancels only the referenced action;
- `replace` cancels, waits for acknowledgement, then dispatches the replacement;
- stale action decisions cannot affect a newer action;
- three identical failures remain planner evidence and never set `ERROR`;
- the full protocol/Java/Fabric/coordinator/fake-E2E verifier and 50-run soak pass;
- a live client exercise records actual observation and control latency when a usable local world is available.
