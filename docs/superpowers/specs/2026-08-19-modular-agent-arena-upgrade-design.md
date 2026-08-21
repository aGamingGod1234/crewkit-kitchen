# Modular Agent Arena Upgrade Design

**Date:** 2026-08-19  
**Status:** Approved direction, awaiting written-spec review  
**Selected control concept:** A, roster grid with focused inspector  

## Purpose

Improve four connected parts of Arena Agents without weakening deterministic control or provider ownership:

1. Make every provider, model family, and individual agent readable at a glance.
2. Replace generic code-shaped arenas with licensed, adapted, modular level design.
3. Make one to sixteen agents fast to select and safe to control, both normally and during event setup.
4. Reduce model latency and unnecessary work while preserving or improving measured task success.

The work is split into four independently testable workstreams. They share stable identity and scenario metadata, but no workstream may require an all-at-once release.

## Product and Visual Direction

The operator uses the Field Console over a bright, moving Minecraft world. The interface therefore remains dark and opaque enough for contrast, but the approved revision replaces the old hard-edged command-panel treatment with a quieter product surface:

- six to ten pixel corner radii on major controls and panels;
- one subtle boundary per region, never stacked double frames;
- restrained amber for focus, selection, and primary action only;
- sentence case for ordinary labels;
- medium weights for data and supporting text;
- fewer helper sentences, with detail revealed in the focused inspector;
- 150 to 200 millisecond state transitions using opacity or transform only;
- no decorative animation, glass blur, gradients, glow, or nested card grids.

Focus and selection remain visually different. Focus uses a neutral outline. Selection uses an amber check and a quiet selected surface. Provider identity never depends on color alone.

## Workstream 1: Agent Identity, Skins, and World Names

### Identity hierarchy

Identity is read in this order:

1. **Provider chassis:** Codex, Gemini, or Kimi.
2. **Model family:** the exact strategic model family selected by the operator.
3. **Individual signature:** a stable variant derived from the agent ID.
4. **Friendly name:** the operator-supplied name shown in the world and UI.
5. **Runtime state:** working, idle, blocked, dead, or disconnected.

Provider and model identity must remain stable if a friendly name changes. Team or event color must use a small outline or marker and must not recolor the body.

### Visual manifest

Introduce one shared `AgentVisualIdentity` manifest used by skin selection, GUI portraits, generated fake-player fallback names, and tests. It resolves:

```text
provider + exact model slug + agent ID
    -> provider chassis
    -> model family key
    -> individual variant 0..3
    -> texture identifier
    -> short model label
    -> provider glyph
```

Each provider receives an invariant large-scale read:

- **Codex:** squared helmet, horizontal visor, open-knot torso structure.
- **Gemini:** lighter upper body, four-point head/torso star, asymmetric shoulder panel.
- **Kimi:** hooded head edge, crescent torso opening, dark-light half pattern.

Within a provider, model families change a large upper-body motif and dominant value block. Four individual variants change a secondary shoulder, back, and lower-leg signature. This produces up to sixteen distinguishable combinations per provider without relying on tiny decorative pixels.

The generator remains project-owned original art. External resources are format and readability references only. The Microsoft MIT sample skin pack and CC0 64 by 64 templates may be retained in the asset ledger as scaffolding references, not copied character art.

Unknown newly discovered model slugs receive a deterministic fallback family instead of a missing texture. Variant counts and asset ordering live in one manifest rather than being duplicated across the entity renderer, player mixin, identity helper, and generator.

### Friendly world names

Only agents with an explicit friendly name receive a persistent world tag. The tag is concise:

```text
[provider glyph] Friendly Name · Short Model
```

The friendly name is primary. The model is secondary and may fade earlier with distance. Unnamed agents remain unlabelled in the world to avoid sixteen overlapping technical tags; their identity remains available through skin, roster, HUD, and focused inspector.

The active fake players are `ServerPlayer` instances, so world tags are implemented in the client avatar render-state path, not through the legacy custom mob renderer. A pure name-tag policy resolves snapshot-known agent identities and never changes ordinary player labels. Server scoreboard suppression may remain as a privacy baseline because the client supplies the approved agent-only tag.

### Naming consistency

Model display names move to one canonical catalogue formatter shared by:

- world tags;
- command and chat output;
- control snapshots;
- roster portraits and inspector;
- generated fallback player names.

Default UI names must be stable and unique. Explicit friendly names remain case-insensitively unique. Generated player usernames remain technical transport identifiers and are not presented to the operator.

### Identity acceptance criteria

- Provider remains identifiable in grayscale at normal gameplay distance.
- Model family remains identifiable from the upper half of the skin.
- Four same-model agents remain distinguishable by individual signature.
- Every provider, supported model family, and variant maps to an existing 64 by 64 RGBA texture.
- Explicit friendly names render above fake players; unnamed agents and ordinary players do not gain agent tags.
- A generated fallback username round-trips to its visual identity for Codex, Gemini, and Kimi, including digit-bearing model names.
- Save and reload preserve service tier, visual identity, and friendly name.

## Workstream 2: Licensed Modular Arenas

### Source policy

Only sources with explicit permission to modify and redistribute are eligible for bundled derived geometry. The first research set is:

| Source | Intended use | Declared license |
|---|---|---|
| Parkour Masters | escalating movement rooms | MIT |
| Re-Structured | modular puzzle, maze, and trap rooms | MIT |
| BigYous' MineGPT Worlds | compact sandbox and deathmatch shells | MIT |
| PVP Arena | environmental combat mechanics | MIT |
| Bunker Survival | constrained resource and building progression | MIT |

Each downloaded archive is kept outside source control until it passes review. The repository stores a source ledger containing URL, author, exact file version, retrieval date, SHA-256 checksum, declared license, retained license text, original Minecraft version, and the derived modules that use it.

All Rights Reserved, ambiguous Creative Commons, NonCommercial, NoDerivatives, modded-block, and dependency-unclear sources are research references only.

### Offline adaptation pipeline

Downloaded worlds are never loaded directly by the Arena Agents runtime. Adaptation is an offline process:

1. Download to an ignored research directory.
2. Verify checksum and unpack into a disposable copy.
3. Upgrade the disposable world through Minecraft 26.1.2 when required.
4. Inspect block IDs, states, entities, block entities, datapacks, bounds, and DataVersion.
5. Select only bounded rooms or shells with clear gameplay purpose.
6. Strip entities, commands, inventories, loot, scheduled ticks, and unsupported block entities.
7. Convert vanilla block geometry to a project-owned deterministic module resource.
8. Add project-owned semantic anchors for spawn, checkpoint, goal, loot, camera, and connection points.
9. Validate safety budgets and controller reachability.
10. Record attribution and the transformation in the source ledger.

The converter emits relative-coordinate module resources containing block IDs and explicitly authored state properties. Runtime loading resolves every ID against the 26.1.2 registry and fails closed on unknown blocks or states. The module format contains no arbitrary commands or executable datapack content.

### Module model

Each module declares:

```text
id
version
source attribution key
difficulty 1..5
bounds and placement count
connection anchors
spawn/checkpoint/goal anchors
allowed transforms
container policy
spectator policy
canonical geometry hash
```

Scenario geometry dispatch changes from category-only to stable preset or map ID. A scenario composes a deterministic ordered list of modules. Difficulty increases through that ordered module curriculum rather than a global enemy-stat multiplier.

Examples:

- Thinking Tower: movement fundamentals, route reading, precision, environmental puzzle, adaptive escape.
- Citadel Collapse: open duel, multi-route pressure, environmental hazard, resource gate, convergence.
- Last Valley: basic harvesting, constrained shelter, cave routing, resource scarcity, extraction.
- Impossible Brief: material study, traversability, functional constraint, resource-limited build, midpoint adaptation.

Functional systems remain project-owned. Checkpoints, scoring, loot, spawn allocation, event timing, reset, and match completion do not depend on imported command blocks.

### Reset safety

Before any module reaches runtime it must satisfy exact limits for:

- X/Z footprint and Y span;
- placements and clear volume;
- loaded chunks;
- fluids and neighbor-sensitive blocks;
- containers;
- spawn clearance;
- spectator deck separation.

The build preview reports exact destructive bounds before launch. Geometry hashes are bound to the module version, and CI fails if geometry changes without a version bump.

Rebuilding a smaller roster at the same origin clears the maximum declared footprint for that scenario family, preventing stale sixteen-player geometry from surviving a later smaller build.

### Map acceptance criteria

- Every bundled derived module has a complete source and license ledger entry.
- No runtime module contains commands, entities, inventories, or unapproved block-entity NBT.
- All modules load on Minecraft 26.1.2 and pass exact registry validation.
- Module composition is deterministic for a fixed preset, roster, and seed.
- Every supported roster size has safe spawn, head clearance, reachable objectives, and separated audience space.
- Each scenario has a visible five-stage difficulty progression based on mechanics, not decoration alone.
- Reset, repair, and verification converge after interruption and after a sixteen-to-one roster rebuild.

## Workstream 3: Roster Grid Control UI

### Shared roster primitive

Concept A becomes a reusable `AgentRosterGrid` model and renderer shared by normal control and scenario roster setup.

Each tile contains only:

- the agent head portrait using the same resolved texture as the world skin;
- the friendly or canonical short name;
- one short model label;
- one text-independent state marker;
- a check only when included in the current multi-selection.

Detailed goal, errors, effort, service tier, health, queue, and supported actions live in the focused inspector. Tiles do not become miniature status cards.

### Focus and selection

Focus and selection are independent state:

- arrows or D-pad move focus;
- Enter opens or activates the focused agent;
- Space toggles group or event membership;
- mouse click focuses in single-agent mode and toggles in explicit multi-select mode;
- Shift-click selects a range where a pointer is available;
- Ctrl+A selects the current filtered result set;
- Escape clears a transient selection before leaving the screen.

The interface always prints the current scope near the primary action, such as `Rook`, `3 selected`, or `Event team 6 / 8`.

### Scaling

- **One agent:** one larger focused tile, no search, filters, group shortcuts, or batch terminology.
- **Two to eight agents:** four-column wide grid; compact view uses two columns and paging.
- **Nine to sixteen agents:** search and state/provider filters appear; wide view uses a four by four grid when height permits; compact view uses explicit range and page controls.

Selections survive filtering. If selected agents are hidden, the footer says how many and offers Show selected or Clear. No roster changes when the pointer wheel is outside the roster viewport.

### Normal control

The default Agents workspace uses the roster grid plus one focused inspector. A contextual footer exposes only actions supported by the active scope. If selected agents have mixed compatibility, the interface names the blocking agent instead of silently disabling the whole batch.

Single-agent configuration remains separate from group messaging. The grid improves navigation but does not merge those workflows.

### Event setup

The scenario roster step reuses the grid. The right inspector becomes a concise event lineup with capacity and slot order. Unavailable agents remain visible with a short reason. Additions and removals edit a draft until Confirm lineup. Cancel discards the draft.

Event setup does not imply live event mutation. The Live workspace remains read-only except for existing spectator focus behavior unless a later backend design explicitly adds director commands.

### Accessibility and presentation

- Focus, selection, status, and provider each have non-color cues.
- Every tile narrates position, name, model, state, and selection membership.
- Live standings, selected-agent details, build progress, and review summaries become narratable content.
- Compact Group always shows selected count and scope.
- Scenario Left/Right handling no longer intercepts focused cycle controls.
- Provider and preset accent text meets at least 4.5:1 contrast on its actual surface.

### UI acceptance criteria

- One, eight, and sixteen-agent layouts remain usable at 960 by 540, 700 by 360, and 320 by 240 GUI sizes.
- Sixteen agents are discoverable without invisible overflow.
- Focus never looks identical to selection.
- Search and filters never discard selection.
- Single, group, and event scopes are explicit before mutation.
- Keyboard, mouse, controller-style directional input, and narration expose the same essential actions and information.
- No production surface reintroduces double frames, heavy all-caps copy, or decorative card chrome.

## Workstream 4: Model and Runtime Performance

### Measurement before policy

Create a provider-neutral evaluation matrix for one, eight, and sixteen concurrent agents. Outcomes are graded from authoritative Minecraft state, not response prose.

Measure:

- task completion or correct impossibility rejection;
- first-pass planner envelope validity;
- ArenaScript compile success and corrective retries;
- primitive success and postcondition rate;
- stale decision and provenance rejection;
- provider queue and inference p50, p95, and p99;
- observation collection and encoding time;
- action-result-to-next-authoritative-observation latency;
- input, output, reasoning, cached, and cache-write tokens where available;
- context compactions, transport retries, rate limits, process count, RSS, and Minecraft MSPT.

No model, effort, prompt, or concurrency change ships solely because it is theoretically faster.

### Observation work

Restore bounded rotating full observations, beginning at two per server tick. Cheap player vitals and lifecycle facts remain fresh. Idle and completed agents move to a heartbeat cadence.

Spatial candidates use a ten-tick raw geometry cache with explicit invalidation after world-changing actions. The cache excludes exact view direction so current facing can filter cached candidates without rescanning blocks.

Add a complete server observation wire budget before bridge encoding. It preserves high-priority threats, current action, nearby targets, and inventory essentials while progressively reducing low-priority tags and distant blocks until the full authenticated envelope fits.

### Attention gating

Fresh facts and model attention become separate concepts. Ordinary position, velocity, view, and distance movement updates refresh watcher facts but do not request a provider turn. High-signal unhandled changes may request attention:

- damage, fire, death, or critical vitals;
- inventory identity or count change;
- relevant target appearance or disappearance;
- terminal action failure;
- watcher event without an authored branch;
- goal revision or explicit operator control.

Local watchers continue to trigger already-authored branches from fresh facts.

### Authoritative sequencing

Action progress never fabricates a factual event sequence. A terminal action result remains pending until the next authoritative observation arrives. The program cannot dispatch its next physical action from a cached pre-action observation relabelled as newer state.

The fake bridge test fixture changes to match the real result-then-observation order.

### Provider admission and retry

Use provider-specific concurrency lanes and a bounded total queue rather than sending all sixteen turns to one backend or silently switching models. Start from measured conservative limits and raise them only when p95 latency, error rate, and Minecraft MSPT remain within target.

Exactly one layer owns transport retries. Retry transient timeouts, 408, 429, and eligible 5xx responses with jitter and `Retry-After` support. Authentication, schema, semantic, stale, cancellation, and invalid-request failures are not transport-retried.

Provider, model, and effort remain fixed for a goal. Fast, balanced, and quality profiles are explicit operator choices resolved to exact provider settings; there is no invisible mid-goal fallback.

### Prompt and context work

Preserve the one-response ArenaScript architecture and local watchers. Keep stable instructions, schemas, and examples first, with changing observation and correction facts last for cache-friendly prefixes.

Benchmark sending the full static system prompt once per persistent Gemini or Kimi session where the transport supports it. Add explicit context turn and byte budgets plus safe compaction or session recreation. A recreated session receives the current authority contract and recoverable program source or hash.

Prompt ablations remove repetition one group at a time and must pass the frozen evaluation matrix before adoption.

### Performance acceptance criteria

- Two hundred movement-only observations cause no provider turn while local distance and health watchers still execute.
- No second program action is dispatched between a terminal result and the next authoritative observation.
- Worst-case bounded server observations fit the complete bridge envelope.
- One, eight, and sixteen-agent concurrency runs publish queue, inference, observation, MSPT, retry, and process evidence.
- The chosen production profile improves latency or resource use without reducing scenario success beyond the approved threshold.
- Provider/model ownership, provenance, cancellation, and postconditions remain intact.

## Error Handling and Failure Policy

- Missing identity assets fail to a known provider fallback and emit a bounded diagnostic.
- Ordinary players never inherit agent skins or world tags from an ambiguous name alone.
- Invalid map licenses or incomplete ledger entries block import before conversion.
- Unknown block IDs, unsupported states, excessive bounds, or hash mismatches block module loading before world mutation.
- A failed module build leaves the previous verified scenario available and reports the exact failed phase.
- A selection operation that only partially reaches agents reports each accepted and rejected agent.
- Provider performance changes fail closed to the previous measured configuration, never to a different strategic model.

## Testing Strategy

Tests remain focused on high-risk boundaries:

1. Pure identity manifest, naming, fallback, and asset consistency tests.
2. Pure roster viewport, focus, selection, filtering, and narration tests at one, eight, and sixteen agents.
3. Converter and module validation fixtures using small licensed or project-owned crops.
4. Blueprint composition, bounds, hash, spawn, reachability, and reset convergence tests.
5. Attention gating, sequencing, wire-budget, provider-lane, retry, and context-budget coordinator tests.
6. One integrated automated verifier and the existing repeated performance soak.
7. One live visual acceptance pass for world skins, name tags, roster density, compact layout, and imported arena composition.

The project does not add broad snapshot suites or repeated smoke tests that do not establish a specific contract.

## Delivery Order and Shared-Worktree Safety

1. Identity manifest and roster-grid pure models can begin in new files.
2. Licensed source ledger and offline converter can proceed without runtime mutation.
3. Production UI integrates only after the approved A mock is translated into Minecraft geometry.
4. Map runtime composition follows after converted modules and safety tests exist.
5. Performance changes begin only after the other active thread releases its modified coordinator and bridge files.

Subagents receive explicit, non-overlapping file ownership. No task stages, commits, resets, or overwrites changes from the other active thread.

## Research References

- Valve, *Illustrative Rendering in Team Fortress 2*: distance recognition, silhouette, and value grouping.
- Riot Games, *Clarity in League*: invariant primary recognition traits.
- IBM Carbon data-table guidance: batch actions and selected-count context.
- W3C listbox pattern: keyboard focus and multi-selection semantics.
- Minecraft Legends Banner View: individual and category unit control.
- OpenAI model selection, agent evaluations, prompt caching, and latency optimization guidance.
- Google Gemini context caching, structured outputs, and agent evaluation guidance.
- Kimi CLI session, model, context, and retry documentation.

Exact URLs and retrieved asset metadata belong in the source ledger and implementation plan so release attribution travels with the derived files.
