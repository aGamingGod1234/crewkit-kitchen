# Code-driven agent control design

## Goal

Make up to 16 agents react quickly and reliably while preserving ordinary Minecraft rules. Models choose intent; the coordinator batches a few related intents; Java performs and verifies every physical input.

## Pipeline

1. Every active agent may hold one provider planning turn, up to the 16-agent cap.
2. One provider response may contain one to four ordered actions. Terminal decisions contain only `complete_goal`.
3. The coordinator sends one action at a time. A confirmed success advances immediately to the next action without another provider round trip.
4. A failure, rejection, interruption, goal revision, disconnect, or world conflict discards the remaining actions. The next fresh observation starts a replan.
5. Existing single-action provider responses remain valid and are normalized to a one-action program.

## Mechanical reliability

The model never receives shell or arbitrary-code execution. It selects only schema-validated actions. Java owns reach checks, item selection, support-face selection, aiming, player input, postconditions, timeouts, and retries.

Block placement is idempotent. If the requested block is already present, the intent is already satisfied and should not become an agent error. Otherwise Java chooses a usable adjacent support when the requested face is unsuitable, retries the real use input for a short bounded window, and succeeds only after the target world state confirms the requested block.

## Safety and load

Sixteen planning turns are permitted because every agent has an independent provider session. Existing provider health circuits, timeouts, cancellation, and per-agent serialization remain the backpressure and fault-isolation boundaries. No agent may hold overlapping planning turns or world actions.

## Verification

Focused tests cover parser compatibility and bounds, terminal-program rules, action-program sequencing and cancellation, 16-way scheduling/configuration, placement support choice, bounded retries, idempotence, and world confirmation. Existing coordinator and Java verification suites must remain green.
