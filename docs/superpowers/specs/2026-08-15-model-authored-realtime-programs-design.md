# Model-Authored Real-Time Minecraft Programs

## Status and relationship to earlier designs

This design extends `2026-08-14-model-owned-minecraft-responsiveness-design.md` and supersedes the linear decision format in `2026-08-14-code-driven-agent-control-design.md`. Existing observation fidelity, stale-decision protection, physical action verification, and provider-session work remain valid. The selected model's output changes from a linear JSON action list into a bounded Minecraft program.

## Purpose

Make agents act as quickly as the local Minecraft runtime permits without moving any gameplay decision away from the exact provider, model, and reasoning setting selected by the user.

The selected model authors:

- the current intention and targets;
- ordered actions;
- conditions and loops;
- watched facts and their reaction branches;
- whether a watched reaction interrupts the current motor action or waits for its boundary;
- the default response to an unhandled attention event;
- retries, fallbacks, checkpoints, completion, and respawn.

The coordinator and mod supply accurate facts, validate the program, execute its requested mechanics, and report results. They never invent fight, flight, healing, collection, building, recovery, or respawn decisions.

## Authority contract

Only the user-selected model may create or replace gameplay logic. There is no tactical model, fallback model, heuristic survival controller, or runtime-authored alternative action. Provider failure leaves the agent waiting for the selected model; it never silently routes the decision elsewhere.

Every Minecraft command must carry:

- the agent, goal revision, and selected model identity;
- a program ID and version;
- the source step or watcher that authorized it;
- the observation or event sequence on which that step operated.

A command without valid model-program provenance is rejected before reaching the offline player.

Runtime-only stops remain allowed for death, operator stop, disconnection, invalid protocol, invalid program, impossible mechanics, timeout, cleanup, or exhausted interpreter limits. These stops describe whether execution is possible. They do not select the next gameplay action.

## Architecture

The coordinator owns a per-agent `ArenaScriptEngine` between the provider session and the existing bridge:

1. Minecraft publishes an authoritative snapshot and immediate factual deltas.
2. The selected model returns one ArenaScript program plus a concise public summary.
3. The coordinator parses the source into an allowlisted syntax tree and validates its bounds and API calls.
4. The engine runs until it reaches a motor primitive, watcher branch, checkpoint, terminal result, or mechanical stop.
5. A motor primitive is sent through the bridge with its program provenance.
6. Minecraft performs the action through the offline player's ordinary controls and returns progress and a typed result.
7. The engine resumes with fresh facts and the result rather than assuming success.
8. Immediate factual deltas evaluate the model-authored watchers while the program is active.

Each agent has one active program version, one physical action, and at most one selected-model turn. Agents remain independent and may plan concurrently up to the configured agent cap.

## Provider output contract

Provider output uses a compact discriminated envelope instead of requiring every possible action field:

- create or replace: `{ "summary": "...", "directive": "replace", "source": "...ArenaScript..." }`;
- retain the current program: `{ "summary": "...", "directive": "continue" }`;
- pause the current program: `{ "summary": "...", "directive": "pause" }`;
- end the goal: `{ "summary": "...", "directive": "finish", "status": "completed|impossible" }`.

Only `replace` contains source. When no program is active, only `replace` or terminal `finish` is valid. The response is associated with the exact goal revision, program version, and triggering observation or event before it can affect Minecraft.

## ArenaScript

ArenaScript is JavaScript-like source designed for models, but it is not executed by Node.js, `eval`, `Function`, or `vm`. It is parsed into an allowlisted syntax tree and interpreted by project-owned code.

The first version supports:

- literals, local variables, assignment, comparisons, and boolean arithmetic;
- `if` and `else`;
- bounded `for` and `repeatUntil` control flow;
- non-recursive functions and watcher callbacks;
- `await` calls to the Minecraft API;
- `tryResult` handling for typed motor failures;
- explicit `checkpoint`, `finish`, and unhandled-event policy declarations.

It does not support imports, filesystem or network access, processes, Node globals, prototypes, constructors, dynamic code generation, reflection, recursion, unbounded timers, or access outside the agent's ArenaScript environment.

Default program bounds are:

- 64 KiB of source;
- 4,096 syntax-tree nodes;
- 16 active watchers;
- 1,024 interpreter operations per resume;
- 128 loop iterations before yielding for fresh facts;
- 256 motor primitives per program version before a mandatory model checkpoint.

Exhausting a bound pauses at a checkpoint with exact diagnostics. It does not fail or end the Minecraft challenge.

A representative model-authored program is:

```ts
program.onUnhandledAttention("continue_and_notify");

const startingHealth = player.state().health;
program.watch(
  () => player.state().health < startingHealth,
  { mode: "interrupt" },
  async () => {
    await player.moveTo(world.relativePosition({ back: 4 }));
    program.checkpoint("I was damaged while gathering logs");
  }
);

await program.repeatUntil(
  () => inventory.countTag("#minecraft:logs") >= 8,
  { maxIterations: 16 },
  async () => {
    const drop = world.nearest(world.items({ tag: "#minecraft:logs", reachable: true }));
    if (drop !== null) {
      const pickup = await tryResult(player.moveTo(drop.position));
      if (!pickup.succeeded) program.checkpoint(pickup.reason);
      return;
    }

    const log = world.nearest(world.blocks({ tag: "#minecraft:logs", reachable: true }));
    if (log === null) program.checkpoint("No reachable tree is currently observed");
    const mined = await tryResult(player.mine(log.position));
    if (!mined.succeeded) program.checkpoint(mined.reason);
  }
);

program.finish("Collected at least eight logs");
```

The model selected the target criteria, nearest-candidate rule, damage response, retry bound, failure checkpoints, and completion condition. The runtime only supplies current facts and executes the requested movement and mining.

## Model-facing Minecraft API

The API separates factual queries from physical primitives.

Factual APIs expose the current authoritative state:

- player position, rotation, health, hunger, air, fire, effects, equipment, inventory, and death state;
- visible entities and item entities with stable identity, type, item ID, count, distance, visibility, and current reachability facts;
- observed blocks with exact state, distance, mining reach, and currently valid placement faces;
- current motor action, action progress, results, damage facts, death facts, and world changes;
- factual helpers for distance, nearest matching observation, inventory count, tool suitability, line of sight, and path availability.

The model supplies the filtering criteria. A helper may calculate a distance or select the nearest member of the model's explicit candidate set; it cannot broaden the set, replace the target, or choose a new objective.

Physical primitives include:

- move or navigate to the exact model-selected position or entity within a supplied tolerance;
- look or aim at an exact target;
- select an exact inventory item or slot;
- press, hold, or release use, attack, jump, sneak, or sprint through bounded calls;
- mine one specified block;
- place one specified item at a specified position, face, and desired state;
- craft a specified recipe and count;
- perform a specified container transfer, furnace operation, equipment change, drop, or chat action;
- wait for a factual condition or bounded duration;
- checkpoint, finish, and respawn.

Navigation may calculate a local path to the selected destination because pathfinding is motor execution. It may step around obstacles but may not change the destination or objective. Mining and placement verify actual reach, inventory, world state, and vanilla postconditions. An already-satisfied postcondition is success, not an error.

High-level controllers that independently decide ongoing combat, flight, following, collection, or building strategy are not exposed to ArenaScript. Their mechanical pieces may be reused behind smaller primitives. For example, the model writes the loop and target choice for combat; `attackOnce` performs only the requested movement input against that target.

## Objective loops and pickup range

Programs operate on measured state rather than assumed yields. A request for eight logs checks the real inventory count, may select multiple trees, and continues only according to the model's code.

Picking up an item is ordinary vanilla proximity behaviour. The model must select the item and request movement into pickup range. The runtime may navigate to that model-selected entity but may not choose another drop. If the entity disappears, becomes unreachable, or is not collected, the primitive returns a typed factual result and the model-authored code chooses its fallback.

## Watchers and attention events

A watcher contains a factual condition, an interrupt mode, and a model-authored handler. Interrupt mode is either:

- `boundary`: let the active primitive reach its result, then run the handler;
- `interrupt`: request cancellation of the active primitive and run the handler after cancellation is acknowledged.

Watcher evaluation is local and deterministic. When its condition becomes true, the exact handler already written by the selected model runs without a new provider round trip.

Watchers are edge-triggered: a handler runs when its condition changes from false to true and rearms only after the condition becomes false. This prevents an unchanged fact from repeatedly firing the same reaction every server tick.

Minecraft also publishes an attention stream containing factual changes such as damage, health or air changes, fire or fluid state, fall distance, target disappearance, inventory changes, action precondition loss, action results, and death. These are facts, not danger labels or recommendations.

Every program must declare one unhandled-attention policy:

- `continue_and_notify`: continue the pre-authorized program while immediately starting a reactive turn with the same selected model;
- `pause_and_notify`: cancel the active primitive, suspend the program after acknowledgement, and immediately start a reactive turn with the same selected model.

This default is authored by the model, not selected by the runtime. A reactive response may continue, pause, replace the program, or finish. `pause` requests cancellation of the current primitive and suspends after acknowledgement. It applies only if its goal revision, program version, and event sequence are still current. Later facts are coalesced into the newest pending observation, and stale responses cannot affect a newer program or action.

If a current primitive becomes mechanically impossible, its typed failure still stops that primitive. With `continue_and_notify`, the interpreter may proceed only where the model's code explicitly handles that result; it cannot pretend the failed step succeeded.

## Death and respawn

Death ends the current physical action and suspends ArenaScript. It does not remove the logical agent, provider session, memory, goal, or program history.

The selected model receives a dead-state observation containing the vanilla death cause, death position, available respawn point, dropped-inventory facts that are legitimately observable, and relevant gamerules. The model may issue `respawn()` from dead state. Only that command recreates the offline player.

Respawn follows vanilla rules: bed or world spawn, inventory and experience loss, item drops, and `keepInventory` behaviour are unchanged. The user may change gamerules in Minecraft. The mod does not restore inventory or bypass the respawn decision.

## Failure and presentation policy

Motor operations return structured states such as `SUCCEEDED`, `FAILED`, `CANCELLED`, and `TIMED_OUT`, with stable reason codes and current facts. Expected recoverable results go to ArenaScript and the selected model, not to public red chat.

Unhandled program failures pause at a checkpoint and provide compiler location, API call, mechanical reason, and relevant observation to the same model. Provider, bridge, protocol, sandbox, and coordinator failures remain operator-visible system errors because they prevent the control contract from operating.

The field console and trace retain detailed diagnostics. Minecraft chat shows concise agent activity only when enabled and does not print routine cancellation, retry, or placement-confirmation noise.

## Responsiveness

Minecraft publishes watcher-relevant deltas on the 20 Hz server tick without waiting for the periodic full snapshot. The coordinator evaluates ArenaScript immediately and sends an authorized reaction over the loopback bridge. Physical execution begins on the earliest server tick that can safely accept the command.

The initial local targets, measured without provider inference or physical action duration, are:

- event receipt to watcher branch selection: p95 below 5 ms;
- branch selection to bridge send: p95 below 5 ms;
- Minecraft fact change to reaction command acceptance: median within one server tick and p95 within two server ticks on the supported local test machine.

Telemetry reports provider inference, event publication, interpreter, bridge, command acceptance, and physical completion separately. No combined number may attribute remote model latency to Minecraft execution or hide local overhead inside provider time.

## Security and isolation

In-world text and observations are untrusted data and can never become ArenaScript source or instructions except through the selected model's validated response. Source is stored and traced separately from world facts.

The interpreter has no ambient authority. It receives immutable factual values and capability-scoped functions for one agent. CPU, memory, output, watcher, command, and recursion limits are enforced before or during execution. A sandbox violation pauses the program and reports the exact violation; it never falls back to unrestricted execution.

## Migration

Implementation proceeds without changing the selected-model contract:

1. Add the ArenaScript parser, validator, interpreter, provenance, and simulated Minecraft API alongside the current linear planner.
2. Add immediate factual deltas and smaller physical primitives while retaining existing postcondition verification.
3. Add watcher evaluation, unhandled-attention policies, reactive same-model replacement, and stale-version guards.
4. Add death persistence and the model-only `respawn()` primitive.
5. Switch the planner prompt and provider schemas to ArenaScript after focused and full verification.
6. Retire the linear action-list path and high-level decision-making controllers from normal agent control. They are not retained as a silent fallback.

If the selected model emits invalid source, the same model receives concise compiler diagnostics and may correct it within the normal provider retry policy. No other model or heuristic produces a substitute plan.

## Verification and acceptance

Focused parser and interpreter tests cover accepted syntax, rejected syntax, bounds, deterministic execution, sandbox escapes, program provenance, stale program versions, watcher ordering, interrupt acknowledgement, and unhandled-event policies.

Simulated Minecraft tests cover:

- collecting eight logs when one tree yields only four or five;
- selecting and walking to drops outside pickup range;
- unreachable, moved, and disappearing item entities;
- damage during mining with a matching watcher;
- damage during mining without a matching watcher under both unhandled policies;
- a pre-authored falling or lava response without a provider round trip;
- target and placement-support disappearance;
- pathfinding and motor timeout failures;
- death, retained logical state, vanilla consequences, and model-commanded respawn;
- invalid model source being returned to the same model without fallback behaviour;
- rejection of any Minecraft command without valid model-program provenance.

Performance tests measure the three local latency targets under one and sixteen active agents while separately recording provider latency. Existing protocol, coordinator, Java/Fabric, fake-player, scenario, and soak verification must remain green.

Live acceptance requires a restarted client using the newly built mod and coordinator. It exercises gathering, building, combat, abnormal events, and death/respawn, and records program source, event sequence, command provenance, local latency, physical results, and model latency. Headless verification alone cannot establish live Minecraft behaviour.

## Success criteria

The work is complete when:

- every gameplay command is traceable to code authored by the exact user-selected model;
- the runtime makes no target, strategy, fallback, interruption, or respawn decision;
- pre-authored reactions run at local runtime speed without another model call;
- unhandled attention events follow the model-authored default and notify the same model;
- physical primitives operate through vanilla-compatible offline-player controls and verify real results;
- death preserves the agent and requires the model's `respawn()` command;
- local and provider latency are measured independently;
- focused, full, soak, and live acceptance gates pass.
