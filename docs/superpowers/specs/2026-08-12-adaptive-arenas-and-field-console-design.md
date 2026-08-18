# Adaptive Arenas and Field Console Design

## Status

Approved for implementation by the operator on 2026-08-12. This specification extends the existing Arena Command Center design without replacing its runtime-truth and accessibility requirements.

## Outcomes

1. Arena construction reaches a real terminal state without treating `COMPLETE` or `FAILED` as in-progress work.
2. Each preset builds only the playable lanes, plots, sectors, or starts required by the configured roster, from one through sixteen participants where the preset allows it.
3. Parkour is a four-stage course—easy, medium, hard, extreme—with visible checkpoint blocks, aligned starts, deterministic recovery, healing, and Adventure mode enforcement.
4. The observation position is an obvious connected command gantry, not an unexplained detached platform. Its beacon uses a canonical 3-by-3 base.
5. The Field Console is a custom-rendered product surface with one persistent Agents, Group, Live, Build navigation system, comfortable header spacing, no ornamental label shadows, and no vanilla button textures.
6. Provider-specific speed values are hidden behind the player-facing choices Normal and Fast mode. Wire values remain provider-owned; the current Codex catalog presents `priority` as Normal and `fast` as Fast mode.
7. Kimi ACP sessions use only capabilities negotiated by that session and fall back cleanly when an optional mode is unavailable.
8. Agent chat reports meaningful intent, current action, recovery, and blockers. It omits low-value lifecycle labels such as Planning, Decision, Acting, Succeeded, and Failed.
9. Generated agent names are readable, provider-aware identities and remain stable for that agent. Skin variants are deterministic, visibly distinct, and paired with identity.
10. Crafting failures do not create an unbounded plan/action loop. A restored transaction produces a concise recoverable result with retry guidance and a bounded repeated-failure circuit breaker.

## Arena Architecture

`ScenarioArenaProfile` derives bounded arena dimensions and slot geometry from preset category and participant count. `ScenarioSpawnLayout.slots(category, count)` is the single source of truth used by both the blueprint and allocator, so rendered pads cannot disagree with actual spawns.

- Survival uses a participant-scaled radial start ring while keeping the authored valley landmarks.
- Building creates the smallest centered grid that contains every participant, with one aligned plot per participant.
- PvP uses a participant-scaled symmetric ring and preserves team-readable opposing positions.
- Parkour creates exactly one centered lane per participant, and each lane's spawn pad is its first platform.

The arena bounds include the command gantry and a connected, readable path. Construction remains incremental and visible. Only actual world mutations contribute to the changed-block count.

## Parkour Rules

Every lane shares the same fair longitudinal route but progresses through four distinct movement grammars:

- Easy: short straight gaps and occasional one-block rises.
- Medium: alternating lateral offsets, longer gaps, and rhythm changes.
- Hard: head-hitter-safe elevation changes, narrower landings, and chained offsets.
- Extreme: the longest legal jumps, descending recovery beats, and a final precision sequence.

Checkpoint platforms use a dedicated high-contrast decorative block and occur at the boundary of each stage. Runtime checkpoint state advances only forward. A participant below the recovery plane is returned to the center of their latest checkpoint, receives full health and food, has fall distance cleared, and remains in Adventure mode. Fall distance is cleared while the run is active so touching the reset floor cannot kill the participant before recovery.

## Provider and Agent Contract

The UI exposes `normal` and `fast`. Provider adapters translate those stable values after model/session capability discovery. Unsupported optional ACP fields are omitted; they never abort agent creation. The coordinator records the effective capability set and returns a human-readable fallback notice only when behavior materially changes.

Default names follow a provider-themed identity pool rather than opaque IDs or model slugs. Explicit operator names always win. Skin selection is deterministic from the persisted identity and provider, preventing names and appearance from changing across reloads.

## Console Design

Fabric's `Screen`, custom widgets, render-state extraction, shape drawing, texture drawing, clipping, focus, and narration APIs permit a fully custom visual surface while retaining Minecraft's supported input/render pipeline. The redesign uses:

- a stable product header with breathing room;
- one persistent four-destination navigation row on every console screen;
- custom stepped panels, segmented controls, selection rows, progress tracks, and action buttons;
- sentence-case labels and shadow-free control text;
- amber only for selection/current primary action, mint for confirmed state, and coral for blockers;
- visible focus, hover, pressed, disabled, selected, loading, error, and empty states;
- full-row agent selection with explicit Selected text and readable details;
- narration labels and keyboard traversal for every custom control.

The implementation intentionally retains Fabric/Minecraft rendering abstractions for compatibility, but no visible control uses the vanilla button texture or the vanilla options-menu layout.

## Verification

Headless verification must prove:

- terminal reset ticks become Ready/Failed without exceptions;
- slot count, uniqueness, centering, symmetry, and blueprint alignment for every valid roster size;
- parkour stage variety, reachable transitions, checkpoints, recovery decisions, and game-mode policy;
- 3-by-3 beacon base and connected command gantry;
- consistent four-tab layout and shadow-free custom button state;
- Normal/Fast UI mapping and provider-specific internal translation;
- ACP optional-capability fallback;
- readable identity/skin stability;
- bounded crafting-failure retry behavior;
- all existing core, coordinator, reliability, and packaging checks remain green.

No Computer Use or visible Minecraft automation is part of this pass.
