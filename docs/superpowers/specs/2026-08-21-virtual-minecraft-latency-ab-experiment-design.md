# Virtual Minecraft latency A/B experiment

## Status

Approved in chat on 2026-08-21.

## Goal

Measure how much a hybrid local-control architecture improves responsiveness, reliability, system load, and player-like execution compared with the current agent runtime. The experiment will compare two isolated copies created from the exact current working tree:

- `latency-baseline`: current runtime behavior plus behavior-neutral measurement code.
- `latency-optimized`: identical measurement code plus the approved runtime optimizations.

The experiment must distinguish provider delay, coordinator delay, simulated execution delay, host saturation, and correctness failures. It must not present simulated performance as live Minecraft evidence.

## Source isolation

The current working tree contains uncommitted product fixes that are absent from `HEAD`. A temporary Git index will create one exact snapshot commit without changing the user's index or working files. Two detached worktrees will be created from that snapshot under `.worktrees/`.

Generated build output, runtime state, logs, OAuth data, credentials, environment files, and existing worktrees are excluded. Every report records the snapshot ID, source-tree hash, optimization patch hash, effective configuration, Java and Node versions, provider executable versions, and benchmark version.

Both copies receive the same observational instrumentation. Only the optimized copy receives behavior changes.

## Experiment architecture

### Virtual Minecraft runtime

A reusable Node.js `VirtualMinecraftBridge` will replace the immediate callback-based fake bridge for performance scenarios. It preserves the existing protocol-v2, `AgentPlanner`, `PlanningScheduler`, `ProgramRuntimeManager`, ArenaScript, and provider-adapter boundaries.

The simulator runs an authoritative seeded 20 Hz world and models:

- continuous position, velocity, yaw, pitch, collision, jumping, and checkpoints;
- health, hunger, air, fire, falling, lava, damage, death, and respawn;
- blocks, obstacles, mobs, dropped items, inventory, crafting, mining, and placement;
- multi-tick action execution with progress, terminal result, and authoritative observation ordering;
- action stalls, unavailable paths, disappearing targets, provider failures, bridge pressure, and configurable tick load.

The first scenario set covers gathering stone tools, obstacle navigation, crafting, placement, hostile-mob combat, damage and lava reactions, death and checkpoint respawn, direct-message wake, stalled actions, and invalid provider output recovery.

### Benchmark modes

The runner supports three separate evidence classes:

1. **Instant-provider control.** Deterministic decisions isolate simulator and local-runtime performance.
2. **Recorded-decision replay.** Bounded decisions captured from real provider runs are replayed identically through both copies. This isolates local changes from provider output variance while preserving realistic decision shapes and measured delay distributions.
3. **Live-provider runs.** Production Codex and Kimi adapters make real external calls. Gemini is reported as unavailable unless its existing local authentication succeeds. The runner never substitutes another provider silently.

Live runs use strict turn and wall-clock budgets, isolated workspaces, redacted artifacts, and guaranteed child-process cleanup. Credentials remain in the providers' normal local login stores and never enter either repository or report.

### Raw timing records

The benchmark records raw events rather than relying only on bounded production percentile windows:

- world fact change;
- observation collection and publication;
- coordinator receipt and adaptation;
- scheduler admission, queue wait, and active/pending depth;
- provider session initialization, request start, first output when exposed, response completion, retries, and error code;
- decision parse and ArenaScript compile completion;
- command dispatch, bridge write, simulator acceptance, first physical displacement, progress, and completion;
- tick duration, missed 50 ms deadlines, event-loop delay, CPU, memory, process count, and child-provider count.

Each event includes run, trial, scenario, seed, arm, provider profile, agent, goal revision, and monotonic timestamps. Public reports contain no prompts, full provider responses, credentials, tokens, or bridge secrets.

## Optimized runtime

The optimized copy implements the following behavior changes as separate, testable slices:

1. **Bounded fair planning.** Default to four active provider turns with capacity for all sixteen agents to wait. Add provider-aware fair admission so one provider or agent cannot monopolize planning.
2. **Non-blocking observations.** Provider work no longer blocks current observation intake. Ordinary updates coalesce to the newest state per agent. Urgent events and operator instructions bypass ordinary debounce.
3. **Continued local action.** A non-urgent provider turn does not cancel or pause a valid local program or current action. Stale provider decisions cannot replace newer state.
4. **Event-driven observation publication.** Publish changed facts immediately and send bounded heartbeats instead of constructing a full observation for every agent on every tick. Correct the ineffective one-tick spatial cache without hiding urgent changes.
5. **Bounded navigation work.** Cap pathfinding work across agents per tick and schedule replans fairly so simultaneous failures cannot consume many server ticks.
6. **Player-like motor control.** Add acceleration, bounded yaw and pitch rates, useful strafing, jump timing, exact final interaction alignment, combat pursuit, line-of-sight handling, aim smoothing, and cooldown-aware attacks. Safety reactions remain local and tick-driven.

Provider prompts, decision validation, ArenaScript authority, protocol schemas, action postconditions, lifecycle semantics, and security boundaries remain unchanged unless a failing experiment proves a specific change is required.

## Fair comparison

The deterministic matrix uses 1, 4, 8, and 16 agents. Each paired trial uses the same simulator seed, roster, goals, event schedule, provider/model/settings, timeout, retry policy, and prompts. Baseline and optimized order is randomized and the arms never run concurrently.

Cold-start and warm-session measurements are separate. Live provider results remain separate by provider and load. The runner includes all timeouts and provider failures instead of dropping slow trials. Small samples are labelled provisional, especially p95 and p99.

Primary metrics are:

- first physical action latency;
- end-to-end task completion time;
- local hazard reaction latency;
- scheduler queue wait and provider turn duration;
- time idle with no local program while planning is pending;
- task, action, and postcondition success rates;
- simulator tick p50, p95, p99, and missed deadlines;
- movement smoothness, combat hit rate, stalls, replans, and fairness;
- CPU, memory, event-loop delay, child-process count, provider retries, and error taxonomy.

The final analysis reports baseline, optimized, paired difference, percentage change, sample count, and bootstrap confidence interval where the sample supports it.

## Testing and gates

Implementation follows test-driven slices. Required gates include:

- identical instrumentation produces identical command ordering with instrumentation enabled and disabled;
- seeded simulator runs are deterministic and enforce protocol/result ordering;
- 200 movement-only observations cause no provider turns;
- agents continue valid local work while a provider request is pending;
- urgent hazards and operator instructions do not wait behind ordinary observations;
- one agent and one provider cannot starve the rest;
- stale decisions never replace newer actions;
- terminal action results receive authoritative observation before dependent dispatch;
- provider failure remains isolated to the affected agent and provider lane;
- all spawned provider processes and benchmark listeners terminate after success, failure, and timeout;
- existing coordinator, Java, voice, packaging, and performance verification continue to pass.

The benchmark first runs deterministic controls in both copies. Live calls run only after both copies pass the same correctness gates.

## Error handling

Reports classify authentication/catalog rejection, process startup, session initialization, transport failure, timeout, empty response, invalid decision, ArenaScript compile failure, scheduler pressure, stale work, simulator failure, action failure, and cleanup failure separately. Unavailable providers are `SKIPPED` unless explicitly required. A skipped or failed provider is never counted as a zero-millisecond success.

Every run writes a bounded manifest, raw timing JSONL, redacted summary, system samples, assertions, cleanup status, and hashes. A failure preserves artifacts and returns a nonzero status.

## Limits

The simulator can prove coordinator scheduling, provider integration, ArenaScript execution, action sequencing, virtual physical progress, error behavior, and relative local performance. It cannot prove exact Minecraft collision, Carpet input behavior, rendering, live server TPS, or human-visible movement quality. Those remain separate live Minecraft acceptance checks after the experiment identifies a worthwhile candidate.
