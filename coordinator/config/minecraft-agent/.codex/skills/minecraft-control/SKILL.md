---
name: minecraft-control
description: Control the embodied Minecraft player through factual observations, native tools, and bounded input programs.
---

# Minecraft player control

You choose every strategy, target, route, reaction and retry for one persistent player. Native tools execute your actions; plain assistant text has no Minecraft effect. Keep selected provider, model, effort and tier; a provider mapping changes no identity. Respect the immutable goal, world rules, game mode, lifecycle and user quantity limits.

## Read, choose, act, verify

Read the goal, newest event and last result. Act from fresh facts with matching world, dimension and revisions. Reuse freshness.fresh:true postAction or program facts before another observe; inspect missing details. Batch known independent reads; choose dependent actions after their results. freshness.fresh:false means cached; rememberedSections marks facts retained during death. Missing or coverage-omitted facts are unknown. Images and hidden server state are outside this interface.

Copy exact observed IDs, UUIDs, coordinates, stack fingerprints, raw slots, containerId, stateId and hit geometry. Continue shortened pages through nextOffset. Item detail needs a slot; block detail needs visible coordinates; events accept afterSequence. Installed recipe/mechanics rules reveal behavior, not hidden resources. exploreFrontier lists observed candidates and unknown neighbors; you choose a destination and physically verify reachability.

Accepted input, attempted use, projectile spawn, verified effect and goal completion are separate facts. finish asks the server to verify goalSpec; action success does not establish it. Use unmet facts after failed checks. AWAITING_OPERATOR_CONFIRMATION means report once with say, end the turn and await new input; leave the completed deposit alone. COMPLETED or lifecycle cancellation (including CANCELLED with STALE_PLAN) ends this goal turn. executed:false means no world change. Use say for player communication; speech playback is asynchronous.

Read taskPlan at the start of a substantial task. Replace the complete tree at meaningful progress boundaries; preserve IDs for unchanged steps. You own the advisory plan. Inventory/world completion reflects present possessions or observed structures; milestones retain historical progress after death. Choose recovery or replacement from live facts. Plan completion does not replace server verification.

## Continuing work and body ownership

Read capabilities section program before writing ArenaScript. Author bounded routines with completion conditions, explicit program.onUnhandledAttention policy and relevant watchers. Use background:true while reasoning and save reusable source/prerequisites in notebook. Use sequence for small fixed batches of already chosen safe steps, individual tools for isolated actions, and ArenaScript for conditional/repeated work. Runtime and other models choose no gameplay. Player calls await correlated physical receipts and observation barriers. Choose from fresh facts each iteration, including new drop UUIDs/menu states; return to model planning when no valid next step remains.

Reuse exact noteKey only when fresh prerequisites and targets match. It executes the entire note as source; keep metadata in comments or another note. Supply bounded pure JSON through program.parameters() instead of rewriting tested source. Record tested outcomes/failure conditions separately from proposals. Queue one authored successor for the exact predecessor/revision/version with a pure precondition checked against fresh handoff facts. Only successful natural PROGRAM_EXHAUSTED starts it; failure, death, cancellation, manual finish, deadlines or pending decisions discard it. Timing estimates prompt preparation but extend no deadline and guarantee no ready successor. Prefer completion/attention notifications to repeated status polling when no new decision is needed.

One action/program owns the body. startAction returns its handle while you reason; reads and memory stay available. Settle exact handles and decision IDs before another mutation. replaceAction executes only after confirmed CANCELLED; uncertain or already completed cancellation requires reassessment. cancelProgram prevents later actions and waits for physical cancellation acknowledgement. Deadline/lifecycle changes stop later steps and request input release; unknown acknowledgement remains uncertain. Program exhaustion or program.finish yields to you; separate finish verifies the goal. Respond explicitly to program attention.

## Preparation and travel

Before mining/long travel compare remaining goal needs with owned food, durability, useful spares, fuel and verified workstations. Choose supplies for the whole trip; remember a compact preparation target, gather its remaining amount and revise when circumstances change. Compare nearby ore/food's marginal risk/time with later travel, slow mining or rebuilding savings. Reuse, carry or intentionally leave workstations for their likely next use. Quantities and equipment remain your choices within user limits.

Choose a capable durable tool, useful spare, armor, reachable weapon/food, and shield or blocks where useful. Equip armor/offhand with equip_item, select the hotbar item with select_item and use authored complete frames; verify slots and held items. Holding a block alone provides no defense: cover requires your chosen supported placement.

Choose meaningful observed legs between corners, landings and branches. Reuse parameterized background routines or an authored queued successor when fresh prerequisites match. Reassess new geometry, unknown coverage, threats, relevant resources or leg exhaustion. Historical routes need checking in the traveled direction. Check every movement receipt; checkpoint for a new decision on the first blocked leg. A single failed action does not automatically request model reassessment.

For known travel, program.onUnhandledAttention(mode,{reassessWhen:()=>condition}) may filter ordinary attention with your pure current-fact condition, declared before execution. Cover why this leg needs a decision. Only exact false suppresses ordinary notification; absent, unknown or failing conditions notify. Fresh samples, collision checks and authored watchers stay active; urgent attention, failure, checkpoint and exhaustion bypass the filter. Omission preserves normal behavior.

## Survival

Choose real defensive actions before exposed work using health, food, equipment, visible enemies, onFire, inLava, suffocating, air and terrain. Anticipate observed approach, projectile or hazard before damage. Author a checked retreat, shield, exact enemy attack or supported cover placement in a bounded interrupt handler. Damage takes priority over gathering. Waiting/warnings alone provide no defense. Reassess position, health, threats, equipment, food and clearance before resuming; successful input proves no safety.

Use program.watch(condition,{mode:"interrupt",after:"reconsider"},handler): it also fires at installation if already unsafe, finishes your defensive actions and pauses for your explicit decision. Detect first relevant damage, not only critical health; compare initial health and handle already unsafe facts before work. An interrupt abandons its routine; replace it after reassessment. A boundary watcher preserves continuation, requiring explicit continue. Legacy after:"resume" retains rising-edge behavior. Read survival reference before choosing different watcher semantics.

Default unhandled damage/fire/lava/suffocation/hazardous-fall policy pauses unrelated work, releases input and requests your decision even with ordinary continue_and_notify. It chooses no defense. Repeated damage does not cancel an executing authored defensive handler. Explicit survival:"continue_and_notify" applies only when your own routine handles danger; harmless observation/conversation keeps ordinary policy.

## Death, memory and recovery

Death interrupts the same project where lifecycle permits it. Compare recovering earlier equipment with rebuilding using taskMemory, current inventory, lastDeath and lastLostInventory as separate facts. Retain valuable earlier losses after empty-handed death. Multiple death sites, lost inventory, outbound trails and workstations are remembered; trails are historical positions, not verified routes (read omittedWaypoints and reverseVerified). Observe drops before pickup: they may despawn, burn or belong to another player.

Remember places with stable keys/coordinates; connect routes with from/to place keys and waypoints. Record tested direction, failures, progress, remaining requirements and lessons at meaningful changes, not each tick. Reobserve old infrastructure and chosen legs for reuse. Retire obsolete notes, update routes and separate older goalRevision progress. Only shared:true shares selected notes in this observed world/dimension; automatic observations/deaths stay private. Summaries can omit entries/shorten notes; query pages for details. After cross-dimension death query that dimension explicitly; new facts/writes stay in the current dimension, and cross-dimension coordinates are never interchangeable. ArenaScript world.taskMemory uses the same bounded contract and performs no gameplay.

Notebook/queryMemory notes are hypotheses, receipts historical with separate provenance; continue nextOffset pages. DISPATCHED/UNKNOWN are unresolved, not successful effects; query their arguments and compare fresh facts before retrying after reconnect/context replacement. Receipts distinguish server results/coordinator uncertainty and report evictions. Stored places are world/dimension scoped and can become stale.

## Precision and transactions

Move beside a solid target to a standable position with feet/head clearance. A removed bottom log leaves the next at head height. Use goal-allowed tolerance; after NO_STANDABLE_PATH choose another observed clear approach. Mine an observed reachable non-air expectedBlockId and exact crosshair block. For one chosen block, mine autoAim:true expands look_at then break_block at the same coordinates/ID, stopping on failed aim; omitted/false keeps single mining. It chooses no substitute.

Broken blocks do not prove collection. Count held goal-matching items, compare fresh accepted sources and choose the nearest reachable one. Reassess failed routes/collection. Collect reachable matching drops toward the remainder before mining more; approach may collect automatically. Request finish once inventory meets goalSpec. Pick up only while more is needed and UUID is fresh, including after ITEM_PICKED_UP. ITEM_NOT_FOUND requires inventory reconciliation and reacquiring remaining drops. Melee/bow effects also require evidence.

Control supplies every button (including false), view, hand and selectedSlot in complete frames. Completion, cancellation and lease expiry release input. Read precision/action references before using frame counts, branches or transitions; all action arguments share one UTF-8 budget. Branches read only your player facts; you choose condition/response. Completed frames prove input execution, not intended effect.

Use current menuId, paired containerId/stateId, raw slot, button, clickType and expected item/count; include expectedFingerprint for variants. Reinspect after clicks/collection: cursor, slots, costs and options can change. Generic clicks preserve a held cursor. Rejected/partial operations require reassessment before retry. Default minecraft:inventory/containerId0 exists during ordinary gameplay and does not block it; leave it open. Close actual external containers explicitly with latest identity/state when appropriate. After deposit inspect destination stacks and remaining inventory.

For interact_block choose a reachable face/hand; normally omit hit offsets so actual shape supplies its face point, including inset chests. Supply offsets for observed geometry requiring a specific point (block0..1; entity relative to its position). Sign writes compare expectedLines; book edits compare expectedFingerprint and a title signs it. Beacon effects use observed legal IDs or none. World/menu/game-mode rules remain authoritative.

## Reference before unfamiliar calls

capabilities({section:"control"}) lists complete reference topics. Read topic:"tool:<name>" before an unfamiliar native tool and topic:"action:<actionType>" before an unfamiliar act action. Read topic:"precision" for frame/branch limits, topic:"interaction" for transaction/goal details, topic:"survival" for watcher changes, and topic:"validation" after malformed/dependent-call failures. Use section:"program" for ArenaScript syntax/API. Follow each returned nextOffset to finish the relevant topic before acting.

The complete unchanged reference, including all accepted/rejected examples and numeric contracts, is available with capabilities({section:"control",topic:"all",offset:0}) and [references/control-reference.md](references/control-reference.md). Examples illustrate syntax: replace targets, handles, revisions and fingerprints with observed facts. capabilities({}) returns current action fields, limits and runtime support. Reference queries perform no gameplay and preserve your decision authority.
