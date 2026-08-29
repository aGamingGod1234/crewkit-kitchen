# Authoritative Minecraft goal verification

Date: 2026-08-26
Status: approved in conversation, awaiting written-spec review

## Purpose

Minecraft, not the selected model or coordinator, decides whether an agent has completed a player request. The model chooses actions and may request a completion check. The server-side mod owns the original objective, the immutable verification rule, live evaluation, persistence, and the final lifecycle transition.

This fixes two observed failures:

- Agents can currently satisfy a weak, model-authored condition for a small subtask and report the whole request complete.
- Once that false completion occurs, the coordinator stops supervising the goal. The agent can then stand idle until the player speaks again, including while taking damage or after respawning.

## Design principles

- The original player request remains attached to the goal for its entire lifetime.
- The model never owns, weakens, replaces, or proves the authoritative completion rule.
- Minecraft evaluates completion from server-owned facts.
- A provider turn ending is not a goal ending.
- An unfinished goal always has active work, an intentional bounded wait, recovery work, or a scheduled next decision.
- Ambiguity is shown to the player and clarified instead of guessed.
- Exact requests begin immediately after showing the inferred completion condition.

## Goal creation

The goal compiler translates a player request into a `GoalSpec` using a hybrid process.

1. Deterministic recognizers handle known items, coordinates, entity types, advancements, blocks, counts, and common phrasing.
2. A constrained model may propose allowlisted verifier rules for more complex requests.
3. The mod validates every identifier, argument, and rule against Minecraft registries and the verifier schema.
4. Every model-proposed rule requires player confirmation. Deterministically recognized exact requests do not.
5. If a deterministic request has multiple reasonable meanings, the mod asks the player to confirm what counts as done.
6. Once confirmed or deterministically accepted, the mod freezes the `GoalSpec` for that goal revision.

An exact request starts immediately and reports its interpreted condition:

```text
Goal set: obtain minecraft:iron_pickaxe x1
```

An ambiguous request such as "get a good pickaxe" asks the player to choose or state the exact requirement before starting.

## Goal representation

A durable goal contains:

```json
{
  "goalId": "server-generated identity",
  "goalRevision": 1,
  "agentId": "agent identity",
  "originalRequest": "Get an iron pickaxe",
  "status": "ACTIVE",
  "createdAtTick": 1200,
  "completion": {
    "type": "inventory_contains",
    "itemId": "minecraft:iron_pickaxe",
    "count": 1
  },
  "evidence": null
}
```

The original request and completion rule are immutable. Status and evidence change only through server-owned lifecycle operations.

## Verifier module

The verifier is a deep server-side module with a small interface:

```text
createGoal(agentId, originalRequest, verifiedGoalSpec)
evaluateGoal(agentId, goalRevision, currentMinecraftState)
cancelGoal(agentId, goalRevision, playerAuthority)
```

Its implementation owns registry checks, event subscriptions, periodic reconciliation, evidence capture, stable-position timing, kill attribution, advancement checks, compound predicates, persistence, and idempotent completion.

### Initial verifier rules

- `inventory_contains`: exact registered item ID and minimum count.
- `position_within`: coordinates, radius, and required stable ticks.
- `advancement_granted`: exact advancement for that agent.
- `entity_killed_by_agent`: exact entity type, kill credit for that agent, and an event after goal creation.
- `block_matches`: exact position, block ID, and optional block-state properties.
- `survive_duration`: required ticks alive after goal creation.
- `operator_confirmed`: player confirmation for subjective outcomes.
- `all_of`: every child predicate must pass.
- `any_of`: at least one child predicate must pass.

The schema is closed. Unknown predicate types and fields fail validation rather than being ignored.

### Examples

Iron pickaxe:

```json
{
  "type": "inventory_contains",
  "itemId": "minecraft:iron_pickaxe",
  "count": 1
}
```

Reach coordinates:

```json
{
  "type": "position_within",
  "x": 120,
  "y": 64,
  "z": -40,
  "radius": 2,
  "stableTicks": 20
}
```

Beat the game:

```json
{
  "type": "entity_killed_by_agent",
  "entityType": "minecraft:ender_dragon",
  "afterGoalStart": true
}
```

The dragon condition is agent-specific. A different player killing the dragon does not satisfy the goal.

## Verification flow

The mod evaluates active goals immediately after relevant server events:

- inventory changes;
- position changes;
- block changes;
- credited kills;
- advancement changes;
- death and respawn;
- player confirmation.

A periodic reconciliation runs every 20 ticks while a goal is active. It catches missed events and validates that the event-driven view still matches authoritative Minecraft state.

The model may call `finish`, but that call only requests evaluation. It does not change lifecycle state.

If the condition is false, the mod returns factual differences and keeps the goal active:

```json
{
  "verified": false,
  "status": "ACTIVE",
  "missing": [
    {
      "type": "inventory_contains",
      "required": "minecraft:iron_pickaxe x1",
      "observed": "minecraft:stone_pickaxe x1"
    }
  ]
}
```

If the condition is true, the mod atomically stores the satisfying evidence, transitions the goal to `SATISFIED`, stops current goal work, reports completion, and starts the next queued goal if one exists.

The verifier may complete a goal proactively. It does not wait for the model to call `finish`.

## Lifecycle and queue

Supported states are:

- `AWAITING_CLARIFICATION`
- `ACTIVE`
- `RECOVERING`
- `SATISFIED`
- `CANCELLED`

`SATISFIED` and `CANCELLED` are terminal for one goal revision. There is no model-selected terminal state.

While an agent has an active goal:

- follow-ups such as "continue", "watch out", or "try another route" steer the same goal;
- explicit stop requests cancel the goal;
- explicit replacement requests replace it after player intent is clear;
- an additional request with unclear intent asks `Replace / Queue / Cancel`;
- the current goal continues while that clarification is pending.

Clarification for an additional request is stored as a separate pending goal draft. It does not change the active goal's status or revision until the player chooses replace or queue.

## Liveness guarantee

An unfinished goal must always own one of these leases:

- provider turn in progress;
- Minecraft action in progress;
- intentional bounded wait;
- recovery operation in progress;
- next decision scheduled.

Every lease has a deadline. Provider, action, wait, recovery, and scheduled-decision leases expire or renew through observable progress. A hung provider or lost timer therefore cannot keep a goal falsely alive forever.

If an active or recovering goal owns no valid lease for 2 seconds, the coordinator requests a fresh observation and starts another selected-model turn. The same goal revision remains active.

When a provider turn ends, the coordinator checks the authoritative goal state. If Minecraft has not marked it `SATISFIED` or `CANCELLED`, the coordinator schedules the next turn. `native_turn_completed` therefore describes one model turn only.

If no factual world progress occurs for 30 seconds, the mod emits a stuck observation containing recent positions, repeated failures, inventory changes, active action state, and nearby hazards. The selected model must choose a materially different action or strategy. The stuck event cannot complete or pause the goal.

## Death and recovery

Death does not replace, cancel, or complete the root goal.

```text
ACTIVE -> RECOVERING -> ACTIVE
```

Recovery retains the goal revision, completion rule, queue, last known dropped-item location, death location, and attacker facts. Respawn automatically schedules a selected-model recovery turn. When recovery finishes, normal pursuit resumes without requiring a player message.

Conversation received during recovery steers the same goal unless the player explicitly cancels or replaces it.

## Immediate safety controller

A narrow server-owned safety controller protects the body while waiting for provider output. It may:

- swim upward while drowning;
- leave lava or fire;
- raise an equipped shield;
- move away from repeated incoming damage when a server-verified safe route exists;
- trigger respawn recovery after death.

It may not attack, consume scarce items, change goals, invent routes without server path validation, or claim completion. The selected model resumes strategic control as soon as its turn is ready.

## Persistence and concurrency

Minecraft world saved data stores active, queued, recovering, and clarification-pending goals. Restarting Minecraft or reconnecting the coordinator preserves the exact goal revision and verifier rule.

All lifecycle and evidence transitions execute on the Minecraft server thread. Every coordinator command, verifier result, and recovery action carries the goal revision. Stale revisions are ignored without altering newer work.

Completion is idempotent. Duplicate events or repeated finish requests preserve the first accepted evidence and emit one completion transition.

## Error handling

- Unknown item, block, entity, or advancement IDs reject goal creation and request clarification.
- Unsupported subjective requests use `operator_confirmed` after the player approves that completion method.
- Verifier exceptions leave the goal active, report a bounded diagnostic, and retry during periodic reconciliation.
- Provider and coordinator failures preserve the goal and schedule recovery after reconnection.
- A false finish request returns missing factual conditions and cannot disable supervision.
- A failed action becomes evidence for the next model turn and cannot pause or complete the goal.

## Player-visible reporting

Normal chat remains concise:

```text
Goal set: obtain minecraft:iron_pickaxe x1
Goal not complete: iron pickaxe 0/1. Continuing.
Recovering after death. Goal preserved.
Goal verified: obtained minecraft:iron_pickaxe x1.
```

Verbose mode may show verifier checks, liveness recovery, and stuck diagnoses in formatted summaries. It must not print raw transport messages or repeated lock status.

## Test strategy

The primary acceptance tests run against actual server-side Minecraft state.

1. Give an agent a stone pickaxe. An iron-pickaxe goal remains active and reports the exact mismatch.
2. Give the agent an iron pickaxe. The verifier completes the goal immediately without a model finish claim.
3. Have the model request finish while holding only a stone pickaxe. The goal stays active and another turn is scheduled.
4. End a provider turn after one successful action. The same goal automatically receives another turn.
5. Remove every work lease under a fake clock. Liveness recovery starts within 2 seconds.
6. Kill and respawn the agent. The same goal revision resumes automatically.
7. Let another player kill the Ender Dragon. The agent-specific goal remains active.
8. Let the assigned agent kill the Ender Dragon. The goal completes with credited-kill evidence.
9. Pass briefly through target coordinates. The goal completes only after the required stable ticks.
10. Save and reload the world. Active, queued, and clarification-pending goals retain their exact specifications.
11. Send an ambiguous request during active work. Minecraft asks `Replace / Queue / Cancel` while existing work continues.
12. Replay the captured Sol inactivity pattern. No unexplained action gap may exceed the configured liveness lease while the goal remains unfinished.

Focused unit tests cover schema validation and predicate evaluation. Headless Minecraft verification covers events, persistence, lifecycle, liveness, death recovery, and stale-revision fencing. One live selected-model run validates the full user experience after deterministic tests pass.

## Out of scope

- The verifier does not judge aesthetics without player confirmation.
- The safety controller does not choose combat or resource strategy.
- The first implementation does not attempt unrestricted natural-language theorem proving.
- The change does not redesign agent selection, groups, voice transcription, or unrelated scenario completion.
