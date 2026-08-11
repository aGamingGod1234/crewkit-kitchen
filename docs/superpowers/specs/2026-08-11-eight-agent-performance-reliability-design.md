# Eight-Agent Performance and Reliability Design

## Objective

Optimize the summonable NPC mode for balanced responsiveness and reliability with up to eight simultaneous AI agents. Preserve the existing protocol, security boundaries, provider isolation, and legacy two-client compatibility. Verification must use automated and headless tooling only; no computer-use or interactive GUI automation is permitted.

## Scope

This work covers the complete summonable-agent control path:

1. Minecraft produces a bounded observation or significant event.
2. The multiplexed bridge delivers it to the coordinator.
3. The coordinator schedules a provider turn.
4. The provider returns one validated action.
5. Minecraft begins, progresses, and terminates that action.
6. Failures, stalls, disconnects, and stale results are reconciled without duplicating control.

The legacy two-client mode must continue compiling and passing its existing tests, but its architecture and behavior will not otherwise change. Provider model selection, security policy, action schemas, and unsupported fail-closed transactions are out of scope.

## Success Criteria

- Eight agents can operate concurrently without cross-agent state leakage, duplicate commands, stale commands controlling a newer goal, or starvation.
- An isolated headless Minecraft workload remains near the normal 20 TPS target and has no sustained tick backlog attributable to observation collection or agent control.
- The coordinator records bounded latency measurements for observation-to-plan, scheduler wait, provider duration, command-to-first-progress, and action completion.
- Redundant observations and low-value events do not cause redundant provider turns; terminal results, goal revisions, damage or threat events, and disconnects are never discarded.
- A progressing action is not cancelled merely for being long-running. A genuinely stalled action is cancelled once, followed by a fresh observation and bounded replanning.
- Bridge or provider failure either recovers automatically within bounded policy or moves only the affected agent into an explicit recoverable or error state.
- All queues, retries, telemetry buffers, child processes, threads, and observation payloads remain bounded.
- Existing automated verification remains green, and new deterministic stress tests cover the optimized behavior.

## Chosen Approach

Use an adaptive, measured control pipeline. Keep the default planning concurrency at four for eight-agent operation, but admit work based on real scheduler and provider pressure. Reduce redundant work before increasing concurrency. Retain deterministic local navigation, combat, and survival reflexes so urgent gameplay never waits for a model response.

This approach is preferred over aggressive planning concurrency, which can reduce isolated turn latency while degrading Minecraft TPS and provider stability. It is also preferred over serializing all planning turns, which maximizes predictability but creates unacceptable agent starvation and response delay.

## Architecture

### End-to-End Timing

Add monotonic timestamps at existing lifecycle boundaries rather than a parallel tracing system. Timing records use bounded numeric fields and stable agent, provider, model, operation, goal-revision, and action identifiers. Raw prompts, model output, bridge secrets, and full observations must not enter status telemetry.

The coordinator will measure:

- scheduler queue wait;
- provider initialization and decision duration;
- observation receipt to accepted plan;
- command publication to first progress;
- command publication to terminal result;
- reconnect detection to successful reconciliation.

Status snapshots expose bounded rolling counts plus p50 and p95 durations. The existing JSONL trace remains the detailed diagnostic record and retains its current redaction policy.

### Event Coalescing

Each agent keeps at most one pending replanning trigger while a turn or action is active. Triggers are classified by priority:

1. goal revision, stop, steer, removal, death, or disconnect;
2. terminal action result, damage, immediate threat, or explicit action failure;
3. changed observation;
4. ordinary significant event.

A higher-priority trigger replaces a lower-priority pending trigger. Equal-priority observation triggers coalesce by deterministic observation hash. Control events and terminal results retain their existing per-agent ordering and are never merged away. Coalescing never crosses agent boundaries.

### Fair Planning Admission

The existing global scheduler remains the single planning admission point. Its default is four active turns with capacity for all eight target agents. FIFO ordering remains within one pressure class, while an agent that just completed a turn cannot repeatedly jump ahead of agents already waiting.

Adaptive behavior is conservative:

- healthy state: admit up to four active provider turns;
- elevated provider failure or timeout pressure: stop new admission for the affected provider/model/operation circuit while unrelated circuits continue;
- sustained Minecraft-side backpressure: do not request speculative observations or redundant replans until pressure clears;
- recovery: reopen through the existing bounded half-open circuit probe.

No automatic policy may increase concurrency above the configured maximum or the global sixteen-turn safety cap.

### Observation Work Budget

Observation collection remains server-authoritative and bounded. Expensive world-derived sections receive deterministic cache keys based on agent dimension, block position, tick window, and relevant inventory or action revision. Reuse is allowed only for data whose cache key proves it unchanged. Health, hunger, position, current action, last result, nearby threats, and goal revision remain fresh.

Collection work is distributed across ticks when eight agents request observations together. Each agent receives a complete protocol-valid observation; partial observations are not sent. Urgent triggers may bypass ordinary batching but remain subject to existing payload limits.

### Action Progress Watchdog

Each accepted action owns one watchdog record containing its goal revision, action ID, action type, start time, last progress time, and last progress fingerprint. Progress fingerprints use action-specific evidence such as distance reduction, path waypoint advancement, target health change, block-breaking progress, or transaction phase advancement.

The watchdog has two distinct limits:

- hard action timeout: the existing command timeout remains authoritative;
- stall window: expires only when the progress fingerprint has not advanced for an action-specific bounded interval.

On a stall, Minecraft cancels the matching action once and emits a terminal result with a stable stall reason. The coordinator then requests one fresh observation and replans through the normal retry and circuit policy. Late progress or results from the cancelled action are rejected by action ID and goal revision.

### Bridge Recovery and Backpressure

The bridge keeps TCP no-delay, bounded connection and per-agent queues, monotonic message IDs, and authenticated loopback-only transport. Recovery adds explicit reconciliation timing and verifies that an outstanding action is either still authoritative or cleared before new work is admitted.

Backpressure behavior is fail-closed:

- terminal results and lifecycle controls take precedence over refresh-only observations;
- redundant refresh observations may be coalesced before enqueue;
- queue overflow never blocks the Minecraft server tick thread;
- an affected session reconnects and reconciles instead of silently losing authoritative control state.

### Deterministic Local Control

Navigation, combat, survival, and interaction controllers continue incrementally on Minecraft ticks. Model turns select intent and macro actions; they do not perform per-tick steering. Improvements to these controllers must preserve vanilla collision, attack cooldown, inventory, protection, and transaction semantics.

Local reflexes may interrupt a macro action only for existing high-priority conditions such as damage, death, an invalid target, stop, steer, or goal replacement. Every interruption produces an ordered, terminal outcome so the coordinator cannot hang waiting for completion.

## Error Handling

- Invalid provider output receives the existing single corrective retry.
- Transient provider or transport failures use bounded exponential backoff and existing circuit isolation.
- Minecraft action failures are never blindly replayed.
- Observation collection failure affects only that agent and produces an explicit bounded error.
- Metrics and status publication are non-authoritative; their failure cannot break control flow.
- Cleanup remains idempotent across timeout, interruption, disconnect, shutdown, and process-exit races.

## Testing and Measurement

All behavior changes follow test-first development. Tests must fail for the missing behavior before production code is changed.

Automated coverage will include:

- priority-preserving event coalescing and observation deduplication;
- fair scheduling under eight-agent contention;
- provider-circuit isolation while unrelated agents continue;
- advancing actions surviving the stall window;
- non-advancing actions cancelling exactly once and replanning from a fresh observation;
- stale progress and terminal results being rejected after cancellation or goal replacement;
- bounded observation caching and per-tick collection work;
- bridge backpressure, reconnect, and authoritative reconciliation;
- bounded latency telemetry with no private planner content;
- repeated eight-agent soak runs with injected slow providers, disconnects, malformed output, and action stalls.

The final headless validation will run the repository's clean automated verifier and isolated server tooling. It will capture TPS or tick-duration evidence, latency percentiles, scheduler pressure, recovery behavior, process cleanup, and queue bounds. If the available headless runtime cannot exercise a metric honestly, the report will mark that metric unverified rather than infer success.

## Rollout and Compatibility

Changes will be introduced behind existing defaults whenever possible. New thresholds will be constants with conservative validated bounds and deterministic test overrides. Wire-format changes are avoided; if an additional status field is necessary, it must be optional for older readers and covered by strict codec tests.

The implementation will be divided into independently verifiable slices: instrumentation, coalescing and fairness, watchdog recovery, observation budgeting, and headless stress validation. Each slice must pass focused tests before the full verifier runs.
