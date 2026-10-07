---

name: minecraft-control
description: Control the embodied Minecraft player through factual observations, native tools, and bounded input programs.
---

### Live advisory plan

At the start of a substantial task, read `taskPlan`. Publish or revise the dependency tree at meaningful progress boundaries using `replace`. Preserve step IDs when the same step remains. The main agent owns the plan and every game decision. Inventory/world evidence reflects present possessions or observed structures; milestones retain historical progress after death. Choose recovery or replacement from live facts. A plan is advisory; `finish` still requests the separate server goal verification.

```json executor-call
{"tool":"taskPlan","arguments":{"operation":"read"}}
```

```json executor-bad-call
{"tool":"taskPlan","arguments":{"operation":"replace"}}
```

# Minecraft player control

Native tools apply your chosen actions and report what happened. Plain assistant text has no Minecraft effect.

## Read facts, choose, act, verify

1. Read the current goal, newest event, and last result. Use capabilities when you need unfamiliar fields or runtime settings. Observe repeats the effective settings; a provider mapping does not change your selected identity.
2. Act from fresh supplied facts. Reuse fresh result facts and request only missing details. Batch known independent inspections in the same turn; choose dependent actions after their results. Check freshness, coverage, world identity, dimension, and revisions.
3. For repeated work, read programReference and author a bounded runProgram routine with a completion condition and attention policy. Use background:true while you reason. Reuse matching notebook source with current parameters. Choose targets from fresh program facts on each iteration, including new drop UUIDs and menu states. Queue one chosen successor while current work runs, with a precondition checked from fresh facts at handoff. Return to model planning when the routine lacks a valid next step. Use sequence for a small fixed batch of known safe steps and individual tools for isolated actions.
4. Distinguish accepted input, attempted use, projectile spawn, verified effect, and verified goal. Finish asks the server to check the immutable goal contract. A failed check leaves it active.

## Observations and memory

Observe reports freshness.fresh for the sample barrier. False identifies cached facts. rememberedSections identifies older sections retained during death. A missing entity, block, or slot may be omitted by coverage limits rather than absent. Images and hidden server state are outside this interface.

Compact observations keep resultCoverage row counts separate from omittedFields. Field paths such as inventory.extra or entities[].equipment identify omitted or shortened fields; [] means at least one retained row. All rows retained does not mean all fields retained. Existing server coverage still applies; omitted facts remain unknown.

Inspect supports inventory, menu, entities, blocks, landmarks, nearby_containers, item, block, events, recipes, and mechanics. Pages use offset 0..4096 and limit 1..32. Item detail requires slot; block detail requires visible x/y/z. Events accept afterSequence for newly delivered player-accessible events. Recipes lists installed rules; an exact recipeId retrieves its ingredient, result, and workstation display details with explicit coverage. Mechanics reports installed version and current native player attributes and abilities. Recipe rules do not reveal hidden resources or positions. Copy returned stack fingerprints, containerId, stateId, raw slot indexes, target identities, and hit geometry. Continue a shortened page using nextOffset; unreturned details remain unknown.

ExploreFrontier lists observed positions and unknown neighboring cells. It never chooses or travels to a destination. Reachability remains unknown until checked by the body. Choose coordinates explicitly with moveTo or input frames. Stored places are scoped to world and dimension and can become stale.

Notebook stores model-authored notes up to 2048 characters. QueryMemory returns historical notes and action receipts with separate provenance; pass offset or the returned nextOffset to continue a page. DISPATCHED and UNKNOWN are unresolved operations, not successful effects. Query kind unresolved retrieves their arguments; observe and capabilities report a bounded summary and count. Receipts distinguish server results from coordinator uncertainty and report evictions. Notes are hypotheses or remembered plans, not proof of current state. After reconnect or context replacement, compare uncertain operations with fresh facts before deciding whether to retry. Death retains the goal where lifecycle policy permits it; current inventory, lastDeath, and lastLostInventory are distinct facts.

## Trip preparation

Before mining or long travel, compare remaining goal needs with current food, tool durability, useful spares, fuel and verified workstations. Choose supplies for the whole trip and record a compact preparation target in progress memory. Accessible remaining ore or nearby food can be worthwhile when likely savings in later travel, slow mining or rebuilding exceed marginal time and risk. Honor explicit operator quantity limits. Count owned supplies first, gather the remaining amount of your chosen target, and reassess it when circumstances change. Reuse, carry or intentionally leave workstations according to their likely next use.

Choose travel equipment from current inventory: a capable durable tool and useful spare, armor, reachable weapon and food, and a shield or blocks when appropriate. Use equip_item for armor/offhand, select_item for the chosen hotbar item, and authored control frames for its use. Verify slots and held items after changes. A carried building block can be useful cover when you choose its supported placement; holding it alone provides no defense. Equipment and reserve quantities remain your decisions.

## Cave travel

Choose meaningful observed route legs between corners, landings and branches. Reuse parameterized background routines and a chosen queued successor when fresh prerequisites match. On known ground, reuse current results and inspect missing details; broad block pages after every one-block waypoint add work without establishing more route safety. Reassess when new geometry, unknown coverage, threats, relevant resource opportunities or leg exhaustion change the decision. Historical routes still need checking in the direction you will travel.

Declare `program.onUnhandledAttention(mode, {reassessWhen: () => condition})` before top-level execution to filter routine unhandled attention using a pure condition you author from current facts. Cover the reasons this leg needs your next decision. Only exact false suppresses an ordinary notification; missing, unknown or failing conditions retain notification. Fresh samples, physical collision checks and authored watchers remain active. Urgent attention and existing failure, checkpoint or exhaustion requests bypass this filter. Omitting it keeps normal attention behavior.

Check every movement receipt and stop for a new decision on the first blocked leg. The filter does not turn a single failed action into a model request by itself. After your attention and watcher declarations, use this ArenaScript fragment with the observed leg you chose:

```javascript
const leg = program.parameters().leg;
const moved = await player.navigateTo({x:leg.x,y:leg.y,z:leg.z,tolerance:leg.tolerance,sprint:leg.sprint,timeoutMs:leg.timeoutMs});
if (!moved.succeeded) program.checkpoint(moved.reason);
```

## Survival and recovery

Choose survival actions before beginning exposed mining or long travel. Use current health, food, equipment, visible enemies, onFire, inLava, suffocating, air and terrain facts. Anticipate an observed enemy's approach, projectile or hazardous crossing before damage; choose threat conditions and a checked retreat, shield, exact enemy target or supported cover placement. Author bounded interrupt handlers that execute those choices. Damage takes priority over gathering. Waiting alone leaves the player exposed. A successful input does not prove safety: inspect the new position, threats and health before continuing.

Use program.watch(condition, {mode:"interrupt", after:"reconsider"}, handler) for defensive reactions. The handler finishes its authored actions and pauses for your explicit program decision instead of resuming unrelated work. This opt-in interrupt guard also fires at installation when its condition is already true. Legacy after:"resume" (the default) retains rising-edge behavior. An interrupt abandons the interrupted routine; replace it after reassessment. A boundary watcher preserves its continuation, which may resume only after you explicitly continue. Choose conditions that detect the first relevant damage, not only critically low health. For example, capture initial health, watch for a decrease, and use parameters for a retreat point you checked; also handle already unsafe facts before beginning work. A wait-only handler is not a defensive reaction.

The default survival policy pauses unrelated work for unhandled damage, fire, lava, suffocation and hazardous fall notifications even with ordinary continue_and_notify. It releases input and requests your decision; it chooses no movement, target or defensive action. Repeated damage does not cancel an executing authored defensive handler. If you explicitly want unhandled survival notifications to leave your routine running, declare program.onUnhandledAttention("continue_and_notify", {survival:"continue_and_notify"}). Use that only when your own routine handles the danger. Conversation and harmless observations keep their ordinary policy.

After death, keep the project goal and compare recovery with rebuilding. taskMemory retains multiple earlier death sites, lost inventory, outbound position trails and observed workstations. Trails are historical positions, not verified routes; omittedWaypoints and reverseVerified:false expose their limits. Drops may despawn, burn or belong to another player. Observe them before attempting pickup. Do not erase an earlier valuable loss because the latest death was empty-handed.

Record important places with stable keys and coordinates. Connect them with route entries using from/to place keys and waypoints; include which direction you actually tested and what failed. Save progress, remaining requirements and lessons after meaningful changes, not each tick. Route connections let you work out how to reuse an existing staircase; you still choose and verify each leg. Only shared:true shares a note with teammates in this observed world and dimension. Automatic observations and deaths stay private. Retire obsolete notes, update changed routes and distinguish old goalRevision progress from the current goal. Query pages for full details; the automatic taskMemory summary includes counts and may omit older entries or shorten notes.

Inside ArenaScript use await world.taskMemory({operation:"remember", entry:{...}}) or await world.taskMemory({operation:"query", query:{...}}). These have the same bounded contract as the native taskMemory tool and perform no gameplay.

After a cross-dimension death, query the recorded death dimension explicitly, for example query:{kind:"deaths",dimension:"minecraft:the_nether"}. This recalls your historical records and explicitly shared notes in the same world. New observations and note writes remain scoped to the current dimension; coordinates from different dimensions are never interchangeable.

## Input ownership and precision

Control holds a complete frame for 1 to 200 server ticks. Supply every input, including false buttons, view, hand, and selectedSlot. Completion, cancellation, and expired leases release input. Vanilla game-mode and interaction restrictions remain authoritative.

Act with control_sequence runs 1 to 64 complete frames with maxTicks from 1 to 2000. Each frame has 1 to 200 ticks and up to 16 optional branches. The server checks branches before applying each tick. The first matching branch jumps to its zero-based nextFrame; an index equal to the frame count stops. A jump resets that frame duration. Backward jumps remain bounded by maxTicks. The complete action arguments must fit 32768 UTF-8 JSON bytes, including all frames, branches, or book pages. Per-field limits do not waive this total budget.

Branches read only your player facts. Numeric conditions are health_below (0..2048), food_below (0..20), and air_below (0..100000). Boolean conditions are on_fire, in_water, on_ground, horizontal_collision, hurt, and using_item. You author both condition and response. A completed frame program proves input execution; it does not prove your larger intended effect.

StartAction returns a handle immediately. ActionStatus reads its state or terminal receipt. CancelAction requires the exact actionId and goalRevision and waits for acknowledgement. ReplaceAction starts its replacement only after CANCELLED. If cancellation is unconfirmed or the old action already completed, reassess before another mutation.

RunProgram executes your ArenaScript using the same interpreter as script mode. Source is at most 65536 UTF-8 bytes; maxActions defaults to 64 and caps at 256; timeoutMs defaults to 30000 and caps at 120000. Author an explicit program.onUnhandledAttention mode: continue_and_notify completes the current action before yielding; pause_and_notify cancels it before yielding. Watchers, branches, selected targets, and reactions come from your source. No other model plans its steps. Player calls await correlated physical results and new observation barriers; world.inspect and world.queryMemory are bounded reads, world.remember writes your notes. A deadline or lifecycle change stops further steps and requests release of its physical action; unknown acknowledgement remains uncertain. Program completion or program.finish yields to you; use the separate finish tool for factual goal verification.

Optional parameters is a pure JSON object, at most 4096 serialized UTF-8 bytes, depth 16 and 256 total object properties/array entries. Its detached, immutable data is available through program.parameters(). Supply observed coordinates, exact identities and quantities without rewriting tested source. Match current prerequisites before reuse; parameters supply data, never extra commands. Optional expectedDurationMs is your estimated duration from 1 to timeoutMs. It can bring the measured planning advisory before source completion; it does not extend the deadline or guarantee preparation finishes in time.

## Interaction details

Move to a standing position beside a solid target, with room for both feet and head. Removing a tree's bottom log still leaves the next log at head height. Use the goal's allowed tolerance; a tighter tolerance needlessly excludes safe positions. After NO_STANDABLE_PATH, choose another observed approach with clearance.

Mining requires an observed, reachable non-air expectedBlockId and the exact block under the crosshair. For one known reachable block, use mine with autoAim:true or a sequence of look_at at its center, then break_block. AutoAim expands to those two actions at the same chosen coordinates and expectedBlockId, stopping if aim fails. Omitted or false autoAim preserves the single mining action. It selects no alternate target. A broken block does not prove collection. Blocking mining, movement, and pickup return postAction with updated inventory and entities. Use these facts when freshness.fresh is true; otherwise observe before a dependent action.

For a resource goal with multiple accepted item types, compare fresh visible sources and choose the nearest reachable one. Reassess when its route or collection fails. For a quantity goal, count held matching items and collect reachable matching drops toward the remaining amount before mining more. Drops can enter inventory automatically as you approach. Once inventory meets goalSpec, request finish. Only request pick_up_item while more is needed and the UUID appears in fresh facts, including after ITEM_PICKED_UP. ITEM_NOT_FOUND means the selected entity is unavailable. Reconcile inventory and reacquire remaining drops. Melee attempts and bow release also need effect evidence before claiming a hit.

Menu clicks use the current menuId, containerId, stateId, raw slot, button, clickType, expectedItemId, and expectedCount. Include expectedFingerprint for exact variants. Inspect after a click because cursor, slots, costs, and options can change. Generic clicks preserve a held cursor; close explicitly when appropriate. Provide containerId and stateId together for legacy menu operations. A rejected or partial operation is not automatically safe to repeat.

The default minecraft:inventory menu with containerId 0 exists even while walking or mining. Its presence does not mean a screen blocks gameplay. Do not close it before ordinary actions. Close an actual external container when finished, using its latest observed identity and state. Inventory and menu state can change when a nearby item is collected, even without a menu click.

For interact_block, choose a reachable face and omit hitX/hitY/hitZ by default. The executor derives the face point from the actual block shape, including inset chests. Supply offsets only when you have observed geometry that requires a specific point. Block hit offsets are within the block from 0 to 1. Entity hit offsets are relative to the observed entity position. Choose the hand explicitly. Sign writes compare expectedLines; book edits compare expectedFingerprint. A book title signs it. Beacon effects use observed legal effect IDs or none. Mechanics still depend on current world, menu, and game-mode state.

After a deposit, inspect the menu to verify the destination stacks and your remaining inventory. AWAITING_OPERATOR_CONFIRMATION means report the result once with say and end the turn until new input arrives; leave the completed deposit alone. COMPLETED or lifecycle cancellation, including CANCELLED with STALE_PLAN, ends this goal turn. A cancelled call with executed:false made no world change. For other failed completion checks, use the returned unmet facts to decide the next action.

## Tool examples

These examples demonstrate accepted syntax, not a world script. Replace illustrative coordinates, UUIDs, handles, slots, revisions, and fingerprints with actual observed references. Numeric arguments are literal values.

### observe

Request a fresh player observation. Read freshness and coverage; an unavailable freshness barrier returns explicitly stale cached facts.

```json executor-call
{"tool":"observe","arguments":{}}
```

### taskMemory

Record a place or connect named places using your observed route. Replace these illustrative coordinates with current evidence.

```json executor-call
{"tool":"taskMemory","arguments":{"operation":"remember","entry":{"kind":"place","key":"mine-entrance","label":"Mine entrance","summary":"Existing staircase begins here; verify clearance again after respawn.","position":{"x":12,"y":64,"z":10},"shared":true}}}
```

```json executor-call
{"tool":"taskMemory","arguments":{"operation":"remember","entry":{"kind":"route","key":"entrance-to-junction","label":"Existing staircase","summary":"Descended this leg; uphill traversal has not been verified.","from":"mine-entrance","to":"lower-junction","waypoints":[{"x":12,"y":64,"z":10},{"x":16,"y":60,"z":10}]}}}
```

```json executor-call
{"tool":"taskMemory","arguments":{"operation":"query","query":{"kind":"deaths","offset":0,"limit":8}}}
```

### capabilities

List the versioned action fields, query sections, limits, and runtime support for this player.

```json executor-call
{"tool":"capabilities","arguments":{}}
```

Read the shared ArenaScript language and API reference before writing runProgram source.

```json executor-call
{"tool":"capabilities","arguments":{"section":"program"}}
```

### inspect

Request a focused page of player-accessible facts. Item queries need a slot; block queries need visible x/y/z coordinates. Read coverage and freshness.

```json executor-call
{"tool":"inspect","arguments":{"section":"inventory","offset":0,"limit":16}}
```

```json executor-call
{"tool":"inspect","arguments":{"section":"recipes","recipeId":"minecraft:crafting_table","offset":0,"limit":16}}
```

### actionStatus

Inspect the active action or a retained terminal receipt without changing the player.

```json executor-call
{"tool":"actionStatus","arguments":{"actionId":"native:agent-a:3:7"}}
```

### cancelAction

Cancel the exact active handle and wait for its authoritative terminal result. A stale handle cannot cancel another action.

```json executor-call
{"tool":"cancelAction","arguments":{"actionId":"native:agent-a:3:7","goalRevision":3}}
```

### replaceAction

Cancel the exact active handle, wait for acknowledgement, then execute your replacement. No replacement runs after uncertain cancellation.

```json executor-call
{"tool":"replaceAction","arguments":{"actionId":"native:agent-a:3:7","goalRevision":3,"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

### startAction

Start one model-chosen action and return its handle immediately. Poll actionStatus for the factual result or cancel the exact handle.

```json executor-call
{"tool":"startAction","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### notebook

Save or replace one model-written note of up to 2048 characters in this agent and world. Notes are hypotheses or plans, never authoritative game evidence.

```json executor-call
{"tool":"notebook","arguments":{"key":"return-route","text":"Observed bridge at 12, 64, 12 in the Overworld. Recheck before crossing."}}
```

### queryMemory

Read this agent and world's saved notes and action receipts. Continue pages with nextOffset. Memory records are historical, not fresh world observations.

```json executor-call
{"tool":"queryMemory","arguments":{"kind":"notes","text":"bridge","offset":0,"limit":10}}
```

```json executor-call
{"tool":"queryMemory","arguments":{"kind":"unresolved","offset":0,"limit":10}}
```

### runProgram

Run your bounded ArenaScript through the shared interpreter. This example reads the current player state, executes one model-chosen wait, and yields at source exhaustion.

```json executor-call
{"tool":"runProgram","arguments":{"source":"program.onUnhandledAttention(\"pause_and_notify\"); const self = player.state(); if (self.health > 0) { await player.wait(50); }","maxActions":4,"timeoutMs":5000}}
```

Save valid source under an exact notebook key; keep its prerequisites in comments or a separate note. Parameters let the same authored source use new data. This illustration waits only after checking the current player state.

```json executor-call
{"tool":"notebook","arguments":{"key":"bounded-wait","text":"// Prerequisite: alive player; durationMs is a chosen bounded wait.\nprogram.onUnhandledAttention(\"pause_and_notify\"); const p = program.parameters(); if (player.state().health > 0) { await player.wait(p.durationMs); }"}}
```

```json executor-call
{"tool":"runProgram","arguments":{"noteKey":"bounded-wait","parameters":{"durationMs":50},"background":true,"maxActions":1,"timeoutMs":5000,"expectedDurationMs":50}}
```

Set `background:true` when your authored routine should continue acting or reacting while you reason. It returns a `programId` and `goalRevision`; the routine retains exclusive body control. Observations, inspection, memory, and program status remain available. Choose your own watcher conditions and responses from current facts. Use `capabilities` with `section:"program"` for the language reference.

A program ends on exhaustion, failure, cancellation, its action budget, or its deadline (at most 120 seconds). Watchers and sampling end with it. Background programs never restart themselves and do not survive stop, death, goal replacement, or disconnect. Cancel the program and wait for its result before issuing another body action. An `UNKNOWN` cancellation result requires checking the active action; input release is unconfirmed.

With `continue_and_notify`, unhandled attention requests your decision while the authorised routine continues. With `pause_and_notify`, it cancels the current input and waits for your decision. A foreground call returns a live handle on attention so you can respond too. Program attention and completion arrive as events; use `programStatus` to recover the latest handle when needed. Ordinary progress does not require replanning.

### queueProgram

While a background routine runs, choose its next bounded work and queue exactly one successor. Copy `afterProgramId`, `goalRevision`, and `programVersion` from the current handle/status. Supply source or an exact noteKey, optional parameters and bounds, and a required `precondition`: one side-effect-free ArenaScript expression, at most 4096 UTF-8 bytes. The expression reads fresh facts and successor parameters. It must evaluate to exactly true; an unknown or changed prerequisite drops the queue.

```json executor-call
{"tool":"queueProgram","arguments":{"afterProgramId":"native-program-session-1","goalRevision":3,"programVersion":1,"source":"program.onUnhandledAttention(\"pause_and_notify\"); await player.wait(program.parameters().durationMs);","parameters":{"durationMs":50},"precondition":"player.state().health > 0 && !player.state().dead","maxActions":1,"timeoutMs":5000,"expectedDurationMs":50}}
```

Invoking queueProgram again explicitly replaces the pending successor for that predecessor and returns a new queueId. The runtime performs no strategy selection. Handoff requires successful natural `PROGRAM_EXHAUSTED`, no pending program decision, a valid lifecycle, the same world/dimension, and an authoritative fresh observation after the predecessor ends. Failure, death, cancellation, manual finish, checkpoint, action budget, or deadline cannot start it. Each successor gets its own timeout from handoff; the predecessor's deadline stays fixed. A successor can also have one explicitly authored next queue while it runs.

### cancelQueuedProgram

Withdraw only the exact pending queue. Copy its queueId and predecessor identity from programStatus. The current routine keeps running, and a stale handle cannot cancel a newer replacement queue.

```json executor-call
{"tool":"cancelQueuedProgram","arguments":{"afterProgramId":"native-program-session-1","goalRevision":3,"queueId":"native-queue-session-1"}}
```

### programStatus

Read a running program, pending decision, pendingSuccessor summary, or the latest retained terminal result. The summary carries the exact queue identity and execution bounds. Supply the exact handle when checking a particular program. A terminal program result does not prove goal completion; use `finish` for that.

```json executor-call
{"tool":"programStatus","arguments":{"programId":"native-program-session-1"}}
```

### respondProgram

Copy the pending `decisionId`, `programId`, and `goalRevision` from the event or status. Choose `continue`, `pause`, `replace`, or `finish`. Only `replace` takes new `source`; it releases the previous input before starting the new version and retains the original deadline and action budget. A newer attention event invalidates an older decision. On `STALE_PROGRAM_DECISION`, inspect the current decision and reconsider. Ordinary authorised progress does not invalidate a decision.

`pause` and `finish` end this routine after input release. Resume later with a newly authored program. Program `finish` does not complete the goal; the separate `finish` tool requests Minecraft verification.

Repeated deterministic failures can halt the routine and deliver an `action_failure` decision. `PROGRAM_REPLACEMENT_REQUIRED` means its old continuation cannot resume: use the failure evidence to author a different replacement, or explicitly stop it.

```json executor-call
{"tool":"respondProgram","arguments":{"programId":"native-program-session-1","goalRevision":3,"decisionId":"native-program-session-1:decision-1","directive":"continue"}}
```

### cancelProgram

Cancel the exact program, including a pending notebook load, and wait for its result. Copy both fields from the returned handle. Cancellation prevents subsequent program actions; an in-flight body action must acknowledge cancellation before control is released.

```json executor-call
{"tool":"cancelProgram","arguments":{"programId":"native-program-session-1","goalRevision":3}}
```

### lookAround

Turn through 2 to 8 camera steps. Returned samples retain sightings from each heading; reacquire a target before acting on a historical sighting.

```json executor-call
{"tool":"lookAround","arguments":{"centerYaw":90,"pitch":0,"steps":4,"ticksPerStep":3}}
```

### control

Hold one complete player input frame for 1 to 200 server ticks. Use for precise movement, jumps, attacks, item use, view, and hotbar control.

```json executor-call
{"tool":"control","arguments":{"forward":1,"strafe":0,"jump":true,"sneak":false,"sprint":true,"attack":false,"use":false,"yaw":0,"pitch":0,"selectedSlot":0,"hand":"main","ticks":8}}
```

### moveTo

Navigate toward one short, confirmed waypoint through bounded loaded safe waypoints; use control for ordinary exploration.

Native moveTo maps to navigate_to, with tolerance:1, sprint:true and timeoutMs:30000 defaults. Its timeout accepts 1..600000ms, matching act and sequence. In ArenaScript, player.moveTo accepts x/y/z, tolerance and sprint only; player.navigateTo additionally requires timeoutMs. Program deadlines still apply.

```json executor-call
{"tool":"moveTo","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}
```

### exploreFrontier

List factual observed or unknown adjacent-space candidates. This tool never chooses or executes a destination; choose explicitly with moveTo.

```json executor-call
{"tool":"exploreFrontier","arguments":{"radius":24,"limit":16}}
```

### mine

Mine one observed, visible, in-range block coordinate with its exact current blockId.

timeoutMs defaults to 15000 and accepts 1..600000ms, matching act and sequence break_block. Program deadlines still apply.

```json executor-call
{"tool":"mine","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}
```

```json executor-call
{"tool":"mine","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000,"autoAim":true}}
```

### say

Send public chat, a private message, or nearby proximity speech. say accepts up to 256 Unicode code points. Direct speech requires an observed player UUID in recipientId; supplying only recipientId defaults audience to direct. Other audiences cannot use recipientId.

```json executor-call
{"tool":"say","arguments":{"message":"I found the marked chest.","audience":"proximity"}}
```

### wait

Pause briefly and wait for the body result.

```json executor-call
{"tool":"wait","arguments":{"durationMs":500}}
```

### act

Execute one supported advanced player action. Supply exactly the fields required by that actionType.

```json executor-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### sequence

Execute 2 to 8 known actions in order, stopping on the first factual failure. results contains step receipts. When a step attempted movement, mining, or pickup, postAction samples facts after the last attempted step. This pair aims and mines one observed, reachable block in one call; choose subsequent targets from that fresh result.

```json executor-call
{"tool":"sequence","arguments":{"actions":[{"actionType":"look_at","arguments":{"x":11.5,"y":64.5,"z":10.5}},{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}]}}
```

Optional finish:{summary} requests verification of the immutable active goal after every step succeeds and fresh final facts are available. A failed step skips finish; action success alone does not prove the goal. Read the returned finish verification facts. If AWAITING_OPERATOR_CONFIRMATION, report once with say and end the turn until new input.

```json executor-call
{"tool":"sequence","arguments":{"actions":[{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:oak_planks","count":4,"timeoutMs":15000}},{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:stick","count":4,"timeoutMs":15000}}],"finish":{"summary":"Crafted the requested sticks; verify current inventory."}}}
```

### takeTask

With no active task, a player message in this conversation that asks you to do something is yours to accept. Call takeTask to adopt it: request defaults to that player's latest words, so rewrite it as the concrete task when that is clearer. If you have a paused task and they say "continue", call takeTask with resume:true; only an operator can give a paused agent a different task. On a completed agent, name what to continue in request. Pass requesterId only when several players messaged you. Minecraft starts the task, validates it first (PENDING: it starts by itself), or refuses (taken over, busy, paused, unclear request, requester offline) with a reason to relay using say. takeTask never replaces an active task. After success end the turn; the task turn starts at once with every tool. Plain chat needs only say.

```json executor-call
{"tool":"takeTask","arguments":{"request":"Craft a stone pickaxe and bring it to me."}}
```

```json executor-bad-call
{"tool":"takeTask","arguments":{"requesterId":"Lucas"}}
```

### finish

Ask Minecraft to verify the immutable active goal. A failed check keeps the goal active.

```json executor-call
{"tool":"finish","arguments":{"summary":"Crafted and collected the iron pickaxe."}}
```

## Advanced action reference

Call through act, startAction, or a sequence step. Optional fields may be omitted. Capabilities lists the current action contract.

### move_to

Fields: `x`, `y`, `z`, `tolerance`, `sprint`.

```json executor-call
{"tool":"act","arguments":{"actionType":"move_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true}}}
```

### control

Fields: `forward`, `strafe`, `jump`, `sneak`, `sprint`, `attack`, `use`, `yaw`, `pitch`, `selectedSlot`, `hand`, `ticks`.

```json executor-call
{"tool":"act","arguments":{"actionType":"control","arguments":{"forward":0,"strafe":1,"jump":false,"sneak":true,"sprint":false,"attack":false,"use":true,"yaw":90,"pitch":15,"selectedSlot":3,"hand":"off","ticks":10}}}
```

### control_sequence

Fields: `frames`, `maxTicks`.

```json executor-call
{"tool":"act","arguments":{"actionType":"control_sequence","arguments":{"frames":[{"forward":0.5,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":20,"branches":[{"condition":"horizontal_collision","value":true,"nextFrame":1}]},{"forward":0,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":1}],"maxTicks":40}}}
```

### look_at

Fields: `x`, `y`, `z`.

```json executor-call
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

### attack

Fields: `targetId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"attack","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","timeoutMs":15000}}}
```

### fight_target

Fields: `targetId`, `timeoutMs`, optional `desiredRange` (1..6, default 2.5), `fleeAtHealth` and `continueWithAttackers` (default true). You chose to fight: the body selects the best hotbar weapon (sword > axe > other tool), turns, closes to reach, swings only at a full attack charge and steps back after each hit. When the target dies it continues to the nearest mob already attacking you (never a creeper, never one that is not attacking) until none remain; pass `continueWithAttackers:false` to stop after the named target. Results: `TARGET_KILLED`, `TARGET_GONE`, `LOW_HEALTH_BAILOUT` (your fleeAtHealth reached; decide next), `TARGET_UNREACHABLE`, `TARGET_ESCAPED`, or `FIGHT_TIMED_OUT`; each message lists all kills and any remaining threats (uuid, distance, swelling creepers). The target may be a `player.threat` uuid behind you.

```json executor-call
{"tool":"act","arguments":{"actionType":"fight_target","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","timeoutMs":15000,"fleeAtHealth":6}}}
```

### flee_from

Fields: `targetId`, `distance` (1..64), `timeoutMs`. Sprints away from the target and every other hostile threat within 16 blocks (nearer ones and creepers, especially swelling ones, push hardest), jumping steps and steering around walls, hazards and deep drops, until at least `distance` away and the target is not closing (`ESCAPED`), it lost you (`TARGET_LOST`), it is gone (`TARGET_GONE`), or `FLEE_TIMED_OUT`. It never ends while another threat inside `distance` is still closing in or any creeper is within 7 blocks. Use this, not navigation, to escape; navigation stops at its point while the mob keeps chasing.

```json executor-call
{"tool":"act","arguments":{"actionType":"flee_from","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","distance":12,"timeoutMs":8000}}}
```

### select_item

Fields: `itemId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"select_item","arguments":{"itemId":"minecraft:oak_log"}}}
```

### use_item

Fields: `durationMs`, `hand` optional, `expectedItemId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"use_item","arguments":{"durationMs":1000,"hand":"main","expectedItemId":"minecraft:apple"}}}
```

### break_block

Fields: `x`, `y`, `z`, `expectedBlockId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}}
```

### pick_up_item

Fields: `targetSelector`.

```json executor-call
{"tool":"act","arguments":{"actionType":"pick_up_item","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000"}}}
```

### place_block

Fields: `x`, `y`, `z`, `face`, `itemId`, `desiredState` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"place_block","arguments":{"x":11,"y":64,"z":10,"face":"up","itemId":"minecraft:cobblestone","desiredState":"minecraft:cobblestone"}}}
```

### chat

Fields: `message`, `audience` optional, `recipientId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"chat","arguments":{"message":"I found the cave.","audience":"direct","recipientId":"550e8400-e29b-41d4-a716-446655440000"}}}
```

### wait

Fields: `durationMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### set_door

Fields: `x`, `y`, `z`, `open`.

```json executor-call
{"tool":"act","arguments":{"actionType":"set_door","arguments":{"x":11,"y":64,"z":10,"open":true}}}
```

### drop_item

Fields: `slot`, `count`.

```json executor-call
{"tool":"act","arguments":{"actionType":"drop_item","arguments":{"slot":9,"count":1}}}
```

### navigate_to

Fields: `x`, `y`, `z`, `tolerance`, `sprint`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"navigate_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}}
```

### transfer_container

Fields: `x`, `y`, `z`, `sourceKind`, `sourceSlot`, `destinationKind`, `destinationSlot`, `count`, `expectedItemId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"transfer_container","arguments":{"x":11,"y":64,"z":10,"sourceKind":"player","sourceSlot":9,"destinationKind":"container","destinationSlot":0,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

### craft_inventory

Fields: `recipeId`, `count`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:oak_planks","count":4,"timeoutMs":15000}}}
```

### craft_table

Fields: `recipeId`, `x`, `y`, `z`, `count`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_table","arguments":{"recipeId":"minecraft:iron_pickaxe","x":11,"y":64,"z":10,"count":1,"timeoutMs":30000}}}
```

### furnace_transaction

Fields: `x`, `y`, `z`, `operation`, `inventorySlot`, `count`, `expectedItemId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"furnace_transaction","arguments":{"x":11,"y":64,"z":10,"operation":"insert_input","inventorySlot":9,"count":1,"expectedItemId":"minecraft:raw_iron","timeoutMs":15000}}}
```

### equip_item

Fields: `sourceSlot`, `targetSlot`, `expectedItemId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"equip_item","arguments":{"sourceSlot":9,"targetSlot":"head","expectedItemId":"minecraft:iron_helmet"}}}
```

### select_tool

Fields: `sourceSlot`, `hotbarSlot`, `expectedItemId`, `minRemainingDurability`.

```json executor-call
{"tool":"act","arguments":{"actionType":"select_tool","arguments":{"sourceSlot":9,"hotbarSlot":0,"expectedItemId":"minecraft:iron_pickaxe","minRemainingDurability":1}}}
```

### block_with_shield

Fields: `durationMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"block_with_shield","arguments":{"durationMs":2000}}}
```

### use_ranged

Fields: `targetId`, `drawDurationMs`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"use_ranged","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","drawDurationMs":1000,"timeoutMs":15000}}}
```

### interact_block

Fields: `x`, `y`, `z`, `face`, `hand`, `expectedItemId`, `hitX` optional, `hitY` optional, `hitZ` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_block","arguments":{"x":11,"y":64,"z":10,"face":"up","hand":"main","expectedItemId":"minecraft:bucket"}}}
```

### interact_entity

Fields: `targetId`, `hand`, `expectedItemId`, `hitX` optional, `hitY` optional, `hitZ` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_entity","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","hand":"main","expectedItemId":"minecraft:wheat","hitX":0,"hitY":0.8,"hitZ":0}}}
```

### dismount

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"dismount","arguments":{}}}
```

### start_fall_flying

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"start_fall_flying","arguments":{}}}
```

### wake_up

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"wake_up","arguments":{}}}
```

### set_flight

Fields: `enabled`.

```json executor-call
{"tool":"act","arguments":{"actionType":"set_flight","arguments":{"enabled":true}}}
```

### write_sign

Fields: `x`, `y`, `z`, `front`, `lines`, `expectedLines`.

```json executor-call
{"tool":"act","arguments":{"actionType":"write_sign","arguments":{"x":11,"y":64,"z":10,"front":true,"lines":["Storage","","",""],"expectedLines":["","","",""]}}}
```

### edit_book

Fields: `slot`, `pages`, `title` optional, `expectedFingerprint`.

```json executor-call
{"tool":"act","arguments":{"actionType":"edit_book","arguments":{"slot":0,"pages":["The bridge is at 12, 64, 12."],"title":"Travel notes","expectedFingerprint":"7ae1bde6c0fc2a4f34d8ad4d405bf364674c4129b8f76164a01eac465c740fd2"}}}
```

### menu_click

Fields: `menuId`, `containerId`, `stateId`, `slot`, `button`, `clickType`, `expectedItemId`, `expectedCount`, `expectedFingerprint` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_click","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":7,"slot":0,"button":0,"clickType":"PICKUP","expectedItemId":"minecraft:iron_ingot","expectedCount":1,"expectedFingerprint":"7ae1bde6c0fc2a4f34d8ad4d405bf364674c4129b8f76164a01eac465c740fd2"}}}
```

### menu_close

Fields: `menuId`, `containerId`, `stateId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_close","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":8}}}
```

### beacon_effects

Fields: `menuId`, `containerId`, `stateId`, `primaryEffectId`, `secondaryEffectId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"beacon_effects","arguments":{"menuId":"minecraft:beacon","containerId":2,"stateId":8,"primaryEffectId":"minecraft:speed","secondaryEffectId":"none"}}}
```

### menu_transfer

Fields: `menuId`, `sourceSlot`, `destinationSlot`, `count`, `expectedItemId`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_transfer","arguments":{"menuId":"minecraft:anvil","sourceSlot":0,"destinationSlot":1,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### menu_button

Fields: `menuId`, `buttonId`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_button","arguments":{"menuId":"minecraft:merchant","buttonId":0,"timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### anvil_rename

Fields: `menuId`, `name`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"anvil_rename","arguments":{"menuId":"minecraft:anvil","name":"Miner","timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### respawn

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"respawn","arguments":{}}}
```

## Dependent calls and rejected inputs

Read independent missing details together when their arguments are already known. Keep batches small enough to review their coverage and freshness.

```json executor-calls
{"calls":[{"tool":"inspect","arguments":{"section":"inventory","offset":0,"limit":16}},{"tool":"inspect","arguments":{"section":"mechanics","offset":0,"limit":16}}]}
```

A combined turn can use a known look coordinate and then read its observation. Wait for the observation before choosing an unseen target.

```json executor-calls
{"calls":[{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}},{"tool":"observe","arguments":{}}]}
```

These inputs fail the tool boundary. A valid call can still fail current world checks.

```json executor-bad-call
{"tool":"taskMemory","arguments":{"operation":"remember","entry":{"kind":"route","key":"r","label":"r","summary":"Unknown route","from":"a","to":"b","waypoints":[]}}}
```

```json executor-bad-call
{"tool":"observe","arguments":{"radius":10}}
```

```json executor-bad-call
{"tool":"inspect","arguments":{"section":"item"}}
```

```json executor-bad-call
{"tool":"inspect","arguments":{"section":"inventory","limit":33}}
```

```json executor-bad-call
{"tool":"control","arguments":{"forward":1,"ticks":8}}
```

```json executor-bad-call
{"tool":"cancelAction","arguments":{"actionId":"native:agent-a:3:7"}}
```

```json executor-bad-call
{"tool":"exploreFrontier","arguments":{"seek":"nether"}}
```

```json executor-bad-call
{"tool":"notebook","arguments":{"key":"route","text":""}}
```

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"menu_click","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":7,"slot":-1,"button":0,"clickType":"PICKUP","expectedItemId":"minecraft:iron_ingot","expectedCount":1}}}
```

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"control_sequence","arguments":{"frames":[{"forward":0,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":1}],"maxTicks":0}}}
```

```json executor-bad-call
{"tool":"say","arguments":{"message":"Hello","audience":"direct"}}
```

```json executor-bad-call
{"tool":"queueProgram","arguments":{"afterProgramId":"native-program-session-1","goalRevision":3,"programVersion":1,"source":"program.onUnhandledAttention(\"pause_and_notify\"); await player.wait(50);"}}
```

```json executor-bad-call
{"tool":"cancelQueuedProgram","arguments":{"afterProgramId":"native-program-session-1","goalRevision":3}}
```

```json executor-bad-call
{"tool":"runProgram","arguments":{"noteKey":"bounded-wait","parameters":[],"timeoutMs":5000}}
```
