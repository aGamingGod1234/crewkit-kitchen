# Exact Agent Action Reliability Design

## Goal

Make Minecraft actions reflect the model's decisions quickly and accurately while preserving a hard boundary: the model chooses every gameplay outcome and the runtime only supplies facts and mechanically executes the chosen action.

## Evidence

The live 2026-08-14 run exposed three independent failures:

- Eight `place_block` actions reached `PLACEMENT_NOT_CONFIRMED` after five seconds.
- A planner turn failed with `Unknown field 'summary'` because the structured schema exposes every nullable action field while the trusted validator rejects fields that are irrelevant to the selected action type.
- A build plan contains at most four actions, forcing another model round-trip after only a few blocks.

The linked Purplers video uses a private Creative-mode plugin and includes human corrections, so it is not evidence of autonomous Survival control. It does demonstrate the throughput benefit of letting a model express a larger exact build before mechanically realizing it.

## Decision boundary

The model owns:

- the goal and strategy;
- every destination and target;
- the exact block item, destination, support face, and desired block-state properties;
- the ordered build program;
- whether to continue, cancel, replace, fight, flee, or stop.

The runtime may only:

- collect authoritative observations;
- validate that an instruction is legal and possible;
- select the already-specified inventory item;
- walk into legal interaction range as an intrinsic step of an accepted build program;
- aim at the specified support surface and issue the same interaction a client packet represents;
- repeat the same mechanical attempt when it misses;
- verify postconditions and return exact failure evidence.

It may not substitute materials, invent placements, repair a model-authored structure, choose a new target, or make a tactical decision.

## Planner contract

Structured actions are normalized according to their selected `type`. Known fields belonging to other action types are discarded before validation even when a model fills them with non-null values. Truly unknown fields remain rejected.

The maximum ordered action program grows from four to 32 actions. This is large enough for useful construction bursts without creating unbounded payloads or monopolizing an agent indefinitely. Every action remains individually acknowledged and a failed action stops the remaining program so the model sees the new state.

`place_block` gains an optional canonical desired-state string. Examples are `minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]` and `minecraft:repeater[delay=2,facing=south,locked=false,powered=false]`. When omitted, the postcondition remains block-ID based for ordinary blocks.

## Exact placement

The executor will stop relying on Carpet's camera raycast for placement. It will create an explicit `BlockHitResult` for the selected support block and call the server player's game-mode `useItemOn` path. This remains a normal player interaction: reach, replacement, protection, inventory, support, and item consumption are still enforced.

Placement succeeds only when:

- the destination contains the expected block type;
- the placement was owned by this player, or the target was already satisfied before the action;
- every requested stable block-state property matches.

The executor retries the identical interaction at the existing 250 ms cadence. It does not wait out the remainder of the five-second timeout after the bounded attempt budget is exhausted; it returns `PLACEMENT_NOT_CONFIRMED` immediately with the expected state, actual state, selected support, distance, and item count.

## Build throughput

The existing ordered action-program protocol grows to 32 actions for general gameplay. Construction also gains `build_sequence`, containing up to 32 exact placement entries selected and ordered by the model.

For each entry, the controller may find a safe standing position within interaction range and navigate there. This is an intrinsic mechanical step of the already-approved placement, equivalent to the offline player's body moving into reach; it may not reorder, omit, add, or substitute placements. If no safe bounded path exists, the sequence stops and returns the failed entry index and exact reason to the model.

Each placement is acknowledged internally, postcondition-checked, and attempted with normal Survival inventory consumption. One terminal action result summarizes how many entries completed, allowing a single model decision to become a useful burst of fast player actions.

## Presentation

`ACTION_CANCELLED` caused by a model `cancel` or `replace` directive remains recorded in observations, logs, and control state but is suppressed from Minecraft chat. Real action failures remain visible, with repeated identical failures eligible for aggregation rather than concealment.

## Verification

Focused tests must prove:

- irrelevant known structured fields are removed and unknown fields are rejected;
- 32 actions are accepted and 33 are rejected;
- desired block-state text survives Node and Java protocol validation;
- a 32-entry build sequence is accepted and a 33-entry sequence is rejected;
- build sequence navigation preserves model order and stops on the first impossible placement;
- exact placement postconditions reject wrong orientation/properties;
- the attempt budget terminates immediately when exhausted;
- intentional cancellation is absent from chat presentation;
- all coordinator tests and the project verifier pass.
