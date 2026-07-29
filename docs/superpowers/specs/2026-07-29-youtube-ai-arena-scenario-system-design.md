# YouTube AI Arena Scenario System Design

Date: 2026-07-29

Status: Approved design direction
Project: Arena Agents

## Outcome

Arena Agents will support two equally visible workflows:

1. Spawn one or more agents into the current world exactly as the mod does today.
2. Launch a repeatable showcase arena containing one to sixteen independently configured agents.

The first showcase collection contains four presets:

- A — Survival: **The Last Valley**
- B — Building: **The Impossible Brief**
- C — PvP: **Citadel Collapse**
- D — Parkour: **The Thinking Tower**

The system is designed for entertaining, fair YouTube comparisons across Codex, Gemini/Antigravity, Kimi, and future local providers. It must improve mechanical reliability without replacing a model's strategy, personality, risk tolerance, creativity, or social decisions.

## Success Criteria

The feature is successful when:

- A user can press `G`, choose normal spawning or a preset arena, configure one to sixteen agents, review the setup, and launch it without using commands.
- Each roster row exposes provider, exact model, thinking level, display name, team, and game mode.
- Provider/model/thinking settings are validated before the arena modifies the world.
- Every arena can be reset and rerun from a pristine state with a recorded seed and event sequence.
- All agents receive the same controller capabilities, observation limits, system rules, and scenario information.
- Models choose high-level behavior while a shared controller reliably performs low-level Minecraft mechanics.
- Important decisions and actions are immediately legible in formatted chat and a spectator HUD without exposing private chain-of-thought.
- A run produces an auditable result containing configuration, timing, events, scores, failures, and model-visible summaries.
- Normal free-world spawning remains compatible and does not require arena configuration.

## Product Principles

### Preserve model agency

The model decides:

- Which objective to pursue.
- Where to go.
- Which resources to prioritize.
- Whether to prepare, rush, explore, cooperate, negotiate, betray, fight, flee, or wait.
- Which opponent or route to choose.
- What to build and how to revise it.
- When its goal is complete or impossible.

The deterministic controller performs:

- Path following, obstacle traversal, gradual facing, sprinting, jumping, and safe short drops.
- Attack cooldown timing, target tracking, reach maintenance, and continuous block breaking.
- Ordinary inventory slot selection, item use timing, and bounded interaction mechanics explicitly requested by the model.
- Emergency motor responses while a new model decision is pending.

The controller must not invent a strategic goal, silently select a better target, grant resources, teleport around ordinary obstacles, or substitute a fallback model.

### Make intelligence visible

Every arena presents multiple valid strategies with observable tradeoffs. Scenarios must reward adaptation and coherent execution rather than one hidden solution or pure mechanical reaction speed.

### Fairness is measurable

Shared controller code, action schemas, observations, prompts, timeouts, and scenario rules are identical for every model. Randomness is seeded and recorded. Provider latency and failures are shown rather than hidden.

### Failure should create a story

An agent that gets stuck, changes its plan, recovers from an attack, misses a shortcut, or makes a poor alliance should remain understandable on camera. Failures should terminate only the affected action or agent whenever possible.

## User Experience

### Step 1 — Mode

The control center opens with two large choices:

- **Spawn agents normally** — preserves the current free-world workflow.
- **Run a preset arena** — opens the arena library.

The previous choice is remembered but never automatically launched.

### Step 2 — Arena

The arena library shows the four lettered presets as visual cards with:

- Preview art.
- Scenario name and category.
- Estimated duration.
- Supported agent count.
- Competitive, cooperative, or individual-run tags.
- Short description of the abilities the scenario reveals.

The user can move with Previous/Next, click a card directly, or press A/B/C/D.

### Step 3 — Roster

The roster editor supports:

- Exact `-` and `+` controls from 1 through 16 agents.
- Quick counts for 2, 4, 8, and 16.
- One row per agent containing head preview, readable name, provider, model, thinking level, team, and game mode.
- Left-click multi-selection.
- Bulk model, thinking, team, duplicate, and remove operations.
- Per-provider family color independent from team color.
- Exact provider readiness and authentication status.
- Automatic duplicate naming: the first instance has no suffix, followed by `(1)`, `(2)`, and so on.
- Saving and loading reusable roster presets.

Normal spawning uses the same roster editor, but hides arena teams, spawn lanes, seed, and scenario controls.

The count selector follows the selected scenario's declared minimum and maximum. Survival, Building, and Parkour support 1–16 agents; PvP requires 2–16. Scenario-required game modes are enforced visibly—for example, a Survival round cannot silently launch a Creative agent.

### Step 4 — Review and Launch

The review screen shows:

- Arena, mode, duration, map version, and scoring rules.
- Every agent's exact provider/model/thinking configuration.
- Teams and spawn assignments.
- World seed and event seed mode.
- Deterministic or randomized event selection.
- Reset behavior.
- Provider preflight results.
- Any unavailable provider/model as a blocking error; no silent fallback.

Controls are Previous, Save Preset, Launch, and Cancel. Launch remains disabled until all selected agents and the scenario pack validate.

## Visual Language

- Codex uses an emerald/neutral family treatment.
- Gemini uses a blue-violet-magenta family treatment.
- Kimi uses amber/orange.
- Future providers receive a stable, accessible family color.
- Team color appears as a separate badge so provider and team identity cannot be confused.
- Success is green, planning is blue, danger is red, recovery is amber, stopped is gray, and provider failure is magenta.
- Screens use a dark Minecraft-compatible panel style, strong spacing, restrained animation, and large readable status text.
- UUIDs never appear in normal UI.

## Scenario Pack Architecture

A scenario pack is data-driven and can be built into the mod or installed without changing coordinator code.

Each pack contains:

- `scenario.json` — identity, version, category, supported counts, durations, game rules, phases, scoring, and presentation metadata.
- `template.nbt` — compressed block/entity snapshot for the resettable arena region.
- `spawns.json` — player, spectator, team, camera, item, and event anchors.
- `events.json` — seeded director events and their eligibility rules.
- `objectives.json` — machine-readable goals, milestones, and terminal conditions.
- `preview.png` — arena-library art.
- Optional loot tables, building briefs, parkour routes, and scoring modules.

Installed packs live beneath:

`config/arena-agents/scenarios/<scenario-id>/`

Built-in packs are copied to that directory only when absent, preserving user-created variants.

## Arena Dimension and Instances

The mod registers a dedicated arena dimension at world startup. Arena sessions use isolated bounded regions inside that dimension rather than altering the user's ordinary overworld.

An instance lifecycle is:

1. Reserve a free region.
2. Freeze entry and show reset progress.
3. Restore the compressed pristine snapshot in bounded per-tick batches.
4. Remove leftover entities and dropped items.
5. Apply game rules, time, weather, borders, teams, inventories, and spawn assignments.
6. Teleport participants and spectators.
7. Run provider preflight again immediately before countdown.
8. Start the synchronized countdown and event director.
9. Score until a terminal condition or manual stop.
10. Freeze results, preserve evidence, and offer Rerun, Reset, or Exit.

Only one showcase session is required initially. The allocator remains an explicit component so multiple simultaneous instances can be added later without changing scenario definitions.

Large custom full-world survival packs may use a separately cloned save in a later extension. The first implementation stays within bounded arena regions so launch and reset work from the in-game menu without reconnecting.

## Runtime Components

### ScenarioRegistry

Loads built-in and user packs, validates schemas and resources, rejects duplicate IDs or unsupported versions, and exposes the arena library.

### ScenarioSession

Owns one run's immutable configuration and mutable lifecycle:

`PREPARING → READY → COUNTDOWN → RUNNING → PAUSED → FINISHED`

Failures can move the session to `FAILED`. Stop and reset are idempotent.

### ArenaResetService

Restores blocks and entities incrementally with a configurable tick budget. It emits reset percentage and never places agents into a partially restored arena.

### SpawnAllocator

Assigns symmetric spawn sectors or lanes deterministically. Counts that do not divide evenly use the scenario's declared fallback ordering rather than random placement.

### EventDirector

Uses a recorded PRNG seed. It evaluates declared events against phase, time, score, location, and cooldown conditions. It never inspects provider identity or private reasoning.

### ScoreEngine

Consumes server-authoritative events, maintains per-agent and per-team scores, and emits a complete reason for every score change.

### EvidenceRecorder

Writes a bounded JSONL timeline plus a final result document containing:

- Scenario and map versions.
- Agent profiles.
- Seeds.
- Goals and visible decision summaries.
- Action start/result/progress.
- Health, hunger, death, checkpoint, score, and phase events.
- Provider timing, retries, and failures.
- Final placements and score breakdowns.

Authentication material and private provider reasoning are never recorded.

## Agency-Preserving Controller

The current straight-line movement implementation is replaced by a layered controller.

### Local navigation

- Bounded A* over walkable block positions.
- Cardinal and diagonal movement.
- One-block jumps.
- Safe bounded drops.
- Door interaction when permitted.
- Water and hazardous-block cost penalties.
- Collision detection and local replanning.
- Frontier selection only when the model explicitly asks to explore an unknown area.
- Path length, explored nodes, and per-tick work budgets are capped.

The model still selects the destination or exploration intent.

### Combat

The model selects fight, flee, defend, or a target. The combat controller:

- Tracks the chosen target.
- Faces and approaches it.
- Maintains attack reach.
- Respects vanilla cooldowns.
- Uses the requested weapon or shield.
- Stops on target death, invalid target, retreat distance, timeout, goal revision, or user stop.

It cannot switch targets strategically without a new model decision.

### Survival reflex

Immediate damage, fire, drowning, suffocation, dangerous falling, or a nearby hostile interrupts the current action and requests a new decision. While the provider responds, a bounded reflex may:

- Stop walking into a hazard.
- Move out of fire or suffocation.
- Surface when drowning.
- Back away briefly from a damaging attacker.
- Cancel unsafe item use or block breaking.

The reflex does not choose food sources, hunt mobs, craft equipment, or abandon the goal. Those remain model decisions.

### Action vocabulary

Existing actions remain compatible. The controller adds bounded macro actions needed for complete play:

- `navigate_to`
- `explore_area`
- `fight_target`
- `flee_from`
- `eat_item`
- `equip_item`
- `craft_item`
- `interact_block`
- `open_container`
- `transfer_item`
- `follow_entity`

Every macro exposes progress and machine-readable failure reasons. No macro is retried blindly.

## Model Planning and Responsiveness

- A model returns one visible decision summary, goal status, and one bounded macro action.
- The summary describes the decision without private chain-of-thought.
- Action progress streams independently while a macro executes.
- Significant events can interrupt or coalesce into the next planner input.
- A round-robin scheduler prevents one provider from starving others.
- Provider-specific concurrency caps and backoff prevent account throttling from freezing unrelated agents.
- One bounded retry handles malformed output and transient provider startup/timeout failures.
- Startup preflight verifies exact model and thinking support before map reset.
- An agent with no goal remains idle and receives no forced autonomous objective.

## Spectator and YouTube Presentation

### Spectator HUD

The spectator overlay shows:

- Compact roster with head icons and provider-family colors.
- Health, hunger, armor, current score, state, and team.
- Current visible decision summary.
- Current macro action and progress.
- Goal and phase timer.
- Important event feed.

The user can filter to all agents, one team, selected agents, or one focused agent.

### Camera Director

An optional camera assistant ranks public events such as combat, near-death states, discoveries, score swings, checkpoint finishes, and building milestones. It recommends or performs smooth focus changes depending on user settings.

Manual camera control always overrides the director immediately. The director never teleports agents or changes gameplay.

### Chat Presentation

Chat messages use consistent formatted blocks:

- Agent head/family color and readable name.
- `THINKING`, `DECISION`, `ACTION`, `RECOVERY`, `SCORE`, or `ERROR` label.
- Concise wrapped content.
- Hover details for provider/model/thinking, action ID, and elapsed time.

Automatic progress can be enabled per agent or in bulk. Critical errors and terminal results always remain visible.

### Results Screen

The final screen includes:

- Placement and score totals.
- Score breakdown by category.
- Deaths, retries, recoveries, and provider failures.
- Key timeline events.
- Rerun Same Seeds.
- Rerun New Events.
- Reset and Edit Roster.
- Exit Arena.

## Arena A — The Last Valley

Category: dynamic survival

Duration: 25–40 minutes

Agents: 1–16

### Layout

A circular valley contains symmetric outer spawn sectors and an asymmetric but equally reachable central ruined camp. Major landmarks are visually distinct:

- Forest and exposed food sources.
- Stone ridge with coal and iron.
- Animal meadow.
- Cave entrance with higher risk and reward.
- Central ruin containing limited shelter and contested supplies.
- Water route and high-ground lookout.

Spawn rotation and mirrored resource budgets prevent one fixed slot from being consistently superior.

### Phases

1. **Dawn — Establish:** gather, scout, craft, cooperate, or contest the ruin.
2. **Forecast — Decide:** visible clouds and messages warn of an approaching storm.
3. **Storm — Adapt:** rain, lightning risk, reduced visibility, and displaced mobs.
4. **Night — Survive:** hostile pressure increases within bounded caps.
5. **Sunrise — Extract:** agents must reach a marked extraction zone or choose to continue collecting score.

### Dynamic events

- Tree fall blocking a common route.
- Limited supply crate announced to everyone.
- Wandering trader with constrained offers.
- Localized cave-in or flooded passage.
- Injured neutral mob or stranded villager as an optional moral/resource choice.

### Scoring

- Survival and extraction.
- Health, hunger, and avoidable damage.
- Advancements and tool progression.
- Food security.
- Shelter safety and lighting.
- Resources retained.
- Optional rescue/exploration objectives.
- Recovery after significant setbacks.

The scoring never requires one exact survival strategy.

## Arena B — The Impossible Brief

Category: building

Duration: 15–30 minutes

Agents: 1–16

### Layout

Agents receive equal plots around a central review pavilion. Each plot has the same dimensions, orientation options, block access, test fixtures, and camera coverage.

### Brief generator

A seeded brief combines:

- Theme: rustic, futuristic, ruined, aquatic, magical, industrial, minimalist, or another installed theme.
- Purpose: home, bridge, tower, market, defense, shrine, farm, or public space.
- Required materials.
- Functional requirement.
- Spatial constraint.
- Surprise requirement revealed at the midpoint.

Example:

> Build a defensible treetop home for two villagers using stone, spruce, and one working redstone feature. At midpoint, add safe access for a horse.

### Modes

- Creative Showcase — unlimited approved palette.
- Survival Build — equal resource crates and ordinary survival mechanics.
- Team Studio — multiple agents share one plot and can divide work.

### Scoring

Automated:

- Required features.
- Completion and enclosed usable volume.
- Traversability and safety.
- Lighting.
- Functional redstone or interaction tests.
- Material constraints.
- Block diversity and deliberate pattern metrics.
- Efficiency and cleanup.

Human optional:

- Visual appeal.
- Originality.
- Theme coherence.
- Storytelling.

Each agent gets a short post-build chat explanation for the edit.

## Arena C — Citadel Collapse

Category: PvP

Duration: 8–15 minutes

Agents: 2–16

### Layout

A hexagonal ruined citadel has symmetric outer starts and multiple risk paths:

- Safe outer loot with slower equipment progression.
- Exposed central vault with strong gear.
- High-ground bow route.
- Potion undercroft.
- Breakable barricades and usable doors.
- Limited fortification materials.
- Spectator ring and camera anchors.

### Phases

1. **Scouting grace period:** combat disabled; movement and looting allowed.
2. **Open conflict:** PvP enabled and first objectives activate.
3. **Supply reveal:** a contested vault or supply point is announced.
4. **Collapse:** outer sectors become unsafe in recorded phases.
5. **Final citadel:** remaining agents converge without forced target selection.

### Strategy

Models may rush, loot, fortify, stalk, hide, negotiate, form temporary teams, betray, hunt a leader, avoid stronger opponents, or contest objectives.

Creative-mode human spectators are never valid combat targets.

### Scoring

- Final placement.
- Kills and assists.
- Damage efficiency.
- Objective control.
- Valuable equipment secured.
- Successful disengagement while low.
- Survival time.
- Penalties for environmental self-elimination or attacking invalid targets.

## Arena D — The Thinking Tower

Category: parkour and route reasoning

Duration: 6–12 minutes

Agents: 1–16

### Layout

Each competitor receives an identical parallel lane to prevent collisions. Routes remain visually connected so the audience can compare progress.

Every major section offers:

- A safe but longer route.
- A medium route containing an observation or interaction puzzle.
- A short high-risk route requiring precise movement.

### Phases

1. **Calibration:** simple jumps establish movement behavior.
2. **Branching ascent:** route choice becomes meaningful.
3. **Observation puzzle:** agents must notice and manipulate the environment.
4. **Adaptive section:** one seeded change invalidates the obvious route.
5. **Timed escape:** agents descend or exit using learned mechanics.

### Checkpoints

Checkpoints preserve the story after failure. A fall returns the agent to its latest checkpoint with a score/time penalty and a significant event prompting reconsideration.

### Scoring

- Completion time.
- Highest checkpoint.
- Route difficulty.
- Falls and retries.
- Shortcut discovery.
- Puzzle completion.
- Improvement after a failed attempt.

Pure reaction-time obstacles are avoided; the course tests perception, planning, learning, and risk selection.

## Custom Scenario Creation

The system supports later user-created scenarios through:

- Importing a validated scenario-pack directory.
- Capturing a bounded region into `template.nbt`.
- Marking spawns, camera anchors, objectives, and event anchors with an operator-only editor.
- Validating reachability, bounds, required files, and agent-count compatibility.
- Exporting a portable pack without worlds, credentials, logs, or provider state.

Custom packs cannot introduce arbitrary JavaScript, shell commands, or Java code. Behavior is selected from registered scenario, event, objective, and score types.

## Persistence and Compatibility

- Existing agents and normal spawning continue to load.
- Scenario state is stored separately from the agent registry.
- An interrupted `PREPARING` or `RUNNING` session is reconciled safely on world load.
- Agents still alive after a crash are stopped before reset or resume.
- Scenario schema versions support explicit migrations.
- Missing custom packs produce a visible recoverable error; they do not delete session evidence.

## Verification

### Pure and automated

- Scenario manifest parsing and version validation.
- Snapshot bounds and incremental reset budgets.
- Deterministic spawn allocation for counts 1–16.
- Seeded event sequence reproducibility.
- Score calculation and reason audit.
- Session lifecycle and crash reconciliation.
- Model/provider preflight and no-fallback behavior.
- Multi-selection and wizard state reducers.
- Planner macro schema and retry behavior.
- A* path graph, hazards, jump/drop edges, caps, and replanning.
- Combat cooldown, target validity, user-stop, and goal-revision cancellation.
- Survival-reflex bounds.
- Protocol round trips and backward compatibility.
- Fake-bridge end-to-end arena launch, events, finish, reset, and rerun.

### Headless server

- Dedicated arena dimension loads.
- Each built-in snapshot restores.
- Agents spawn at valid assignments for representative counts.
- Server-authoritative score events match expected actions.
- Stop/reset removes action state and leftover entities.

### Visual and gameplay

Visual testing is a separate final phase:

- Wizard layout, scaling, focus, keyboard navigation, and text wrapping.
- Provider colors, head icons, duplicates, and multi-select.
- Map sightlines and camera anchors.
- Reset progress and results presentation.
- Representative real provider runs for navigation, combat, survival, building, and parkour.

No visual success is claimed from automated tests alone.

## Implementation Sequence

1. Agency-preserving navigation, combat, survival-reflex, and macro-action foundation.
2. Scenario schemas, registry, lifecycle, arena dimension, reset, spawn allocation, events, scoring, and evidence.
3. Guided control-center wizard and roster editor.
4. The four built-in scenario packs.
5. Spectator HUD, formatted chat, camera assistant, and results screen.
6. Automated, headless, and visual validation.
7. Rebuild, distribution synchronization, and installation.

Each stage must preserve the current normal-spawn workflow and keep focused regression tests green before the next stage begins.

## Explicit Non-Goals for the First Release

- Arbitrary downloadable executable scenario code.
- Silent provider or model substitution.
- Exposing private chain-of-thought.
- Teleporting agents to compensate for ordinary navigation failures.
- More than one simultaneous arena session.
- Full-world hot swapping without leaving the current world.
- Claiming aesthetic building quality can be judged entirely by deterministic metrics.
