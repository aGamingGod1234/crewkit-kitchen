# Arena command center and reliable reset design

## Context

Arena Agents currently opens a four-step showcase wizard before the operator can reach normal agent controls. At common Minecraft GUI scales, the roster instructions, count controls, editor, feedback, and footer overlap. A real Last Valley launch also fails after canonicalization because the reset aborts when its managed volume reaches an unloaded chunk.

The product register is an in-game operations console: dense enough for repeated use, conservative around destructive actions, and explicit about asynchronous server work.

## Approved operator flow

`G` opens the Agents command center. Agents and Arena are sibling tabs, so switching tasks does not require an introductory mode screen.

The Arena tab uses three steps:

1. Preset: choose and inspect the arena.
2. Roster: choose the contestant count and edit one or many contestants.
3. Launch: review exact settings and start preparation.

The setup panel reserves separate regions for the title/tabs, step rail, content, status, and footer. The roster uses a two-column list/editor layout when enough width is available and a compact stacked layout otherwise. Status text never shares the footer button baseline.

The agent screen uses the model catalogue rather than free text, keeps the visual anchor and command selection consistent, explains an empty selection accurately, enables lifecycle actions only when every selected agent supports them, and names/counts every destructive batch accurately.

## Arena reset lifecycle

After canonicalization, the reset derives a deterministic, deduplicated list of managed chunks. A new load phase adds transient loading tickets and loads those chunks under the existing per-tick work/time budget. Tickets remain held through apply and exhaustive verification, then are always released on completion, failure, or an exception.

The operator sees phase-specific progress: canonicalizing, loading chunks, applying blocks, and verifying. Internal failure codes are translated to actionable messages at the runtime boundary.

## Safety and recovery

- No persistent forced-chunk flags are written to the world.
- Reset work remains bounded per tick.
- A failed preparation clears the build job and releases every ticket.
- The existing server-authoritative command and permission paths remain authoritative.
- Removal remains confirmed before commands are sent.
- Launch acceptance means preparation started, not that the arena is already running.

## Verification

Regression coverage will lock chunk deduplication/order, the new load phase, selection movement, operation availability, batch-removal copy, three-step state flow, and responsive layout invariants. The full performance/reliability verifier and a Minecraft launch smoke will run after implementation.
