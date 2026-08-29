# Coordinator recovery design

## Goal

The Arena Agents coordinator must recover automatically from every transient failure without silently changing the selected AI profile or abandoning unfinished Minecraft work.

No software process can be made incapable of failing. This design instead guarantees that every supported failure has an explicit classification, a bounded fallback, a probe for the preferred path, and a tested transition back to normal operation. When external state makes recovery impossible, the mod preserves the active goal and reports a blocked recovery state instead of entering a silent or permanent failure state.

## User-visible guarantee

For an unfinished active goal, one of these conditions must always be true:

- provider work is queued or running;
- a Minecraft action is running;
- completion verification is running;
- a fresh observation is pending;
- a recovery attempt has a scheduled deadline;
- recovery is blocked by a named external requirement and the coordinator is periodically rechecking it.

The mod never changes the selected provider, model, reasoning effort, or service tier during recovery. Explicit player pause is the only ordinary path into `PAUSED`. Recoverable infrastructure failures do not convert active work into `PAUSED`, `ERROR`, or completed work.

## Evidence and current dead ends

The current implementation already has work leases, bridge reconnect backoff, provider circuit half-open probes, stale-work fencing, and persisted goal reconciliation. Four boundaries still permit permanent failure:

1. `CoordinatorLaunchPolicy.RestartBudget` rejects the fourth unexpected coordinator exit.
2. `CoordinatorProcessSupervisor` latches `failureCode`, and every later tick returns without revalidating or restarting.
3. `DynamicCoordinator.start()` waits for the Codex service before starting the Minecraft bridge. A Codex initialization failure therefore prevents otherwise healthy providers and core reconciliation from starting.
4. `CombinedProviderCatalog.refresh()` uses fail-fast aggregation. One provider catalog failure can reject the entire catalog and roster reconciliation.

These are ownership problems. Recovery policy is split between callers, and several callers own terminal decisions they should not own.

## Chosen architecture

### Java process supervisor

Java owns coordinator process availability through one state machine:

```text
STARTING -> AUTHENTICATING -> HEALTHY
    ^            |             |
    |            v             v
    +--------- BACKOFF <- DEGRADED

BLOCKED_RETRYABLE -> BACKOFF
STOPPED
```

`STOPPED` is entered only for an explicit Minecraft server shutdown or disabled coordinator autostart. Runtime failures never enter a permanent terminal state.

The supervisor records:

- state and generation;
- consecutive failures;
- last stable time;
- next retry time;
- last bounded failure code and message;
- owned process identity and start time;
- authentication and bridge-health deadlines;
- active runtime generation.

Unexpected exits restart indefinitely with delays of 1, 2, 5, 15, and then at most 30 seconds. A healthy authenticated interval resets the failure count. Merely authenticating for one tick does not reset crash-loop history.

An owned process that misses its authentication deadline is treated as hung. The supervisor terminates its process tree, records the failure, and schedules a clean restart. An authenticated owned process that loses the bridge receives a reconnect window. If it remains alive but disconnected beyond that window, the supervisor replaces it.

Startup dependency failures enter `BLOCKED_RETRYABLE`. The supervisor periodically revalidates the runtime and Node executable. It also retries immediately when the runtime manifest or relevant configuration fingerprint changes. The state remains visible, but it is not latched.

### Runtime generations

The installed coordinator uses verified runtime generations:

- the candidate generation is staged and validated before activation;
- the previous verified generation remains available as last known good;
- activation is an atomic pointer or directory swap;
- a candidate that fails validation or repeated authentication falls back to last known good;
- the supervisor continues trying to repair and promote the preferred generation in the background;
- the bridge secret and user credentials remain outside runtime generations and are never rolled back or copied into logs.

Every launch records the chosen runtime generation. Orphan cleanup remains ownership-based and idempotent.

### Coordinator core and optional subsystems

The coordinator core consists only of configuration validation, persisted registry access, the Minecraft bridge, lifecycle reconciliation, and work supervision. It starts before any provider process, provider catalog, voice worker, or diagnostics sink.

Providers are isolated services. A service may be `READY`, `DEGRADED`, `BACKOFF`, or `BLOCKED_RETRYABLE` without taking down the bridge or another provider. Provider startup becomes lazy for the exact profiles present in the reconciled roster.

Catalog aggregation uses settled results. A failed provider contributes its last valid snapshot or deterministic built-in fallback while marking that provider stale. A healthy provider continues serving profiles. Background refresh probes stale providers and promotes fresh snapshots atomically.

Voice remains optional to coordinator control. Its preferred transport may fall back to another configured voice transport or text delivery. Each fallback keeps a retry deadline and probes the preferred transport. Voice failure cannot exit the coordinator process.

Diagnostics remain observational. Log, audit, trace, and verbose-output failures cannot reject control work. Diagnostic failures are aggregated and bounded rather than silently swallowed forever.

## Failure classification

Every boundary translates raw errors into one of four recovery classes:

| Class | Examples | Coordinator behavior |
|---|---|---|
| `TRANSIENT` | timeout, EOF, refused socket, temporary provider error, rate limit | Keep the goal active, apply capped backoff, retry the same operation or reconstruct it from fresh facts. |
| `DEGRADED` | stale catalog, failed voice transport, unavailable diagnostics sink | Use the component's bounded fallback and probe the preferred path. |
| `BLOCKED_RETRYABLE` | missing Node executable, invalid runtime generation, absent credential for the selected provider, port owned by another process | Preserve goals, report the external requirement, periodically revalidate, and resume automatically when it changes. |
| `TERMINAL_DOMAIN` | explicit stop, verified completion, confirmed impossible goal, removed agent | End only the affected goal or coordinator because the domain requested it. |

Raw provider, filesystem, process, socket, and protocol errors are classified once at their boundary. Internal lifecycle code consumes the recovery class and does not repeat string-based error decisions.

## Exact-profile provider recovery

An agent profile is immutable across recovery:

```text
provider + model + reasoning effort + service tier
```

When a provider session fails:

1. Fence and dispose the failed session generation.
2. Retain the active goal, conversation memory, accepted factual cursor, and exact profile fingerprint.
3. Open or update the provider circuit using structured telemetry.
4. Schedule one recovery lease.
5. When the circuit permits a half-open probe, create a fresh session with the same profile.
6. Give the session a current Minecraft observation and a bounded recovery summary.
7. Promote it only after a valid provider turn begins or completes according to the operation contract.

No recovery path silently selects another model. A profile that is no longer advertised becomes `BLOCKED_RETRYABLE`; catalog refresh continues until the exact profile returns or the player explicitly changes it.

## Work continuity

Minecraft remains authoritative for goals, physical facts, completion, death, and explicit pause. The coordinator may restart at any point without becoming the source of truth for these facts.

On bridge authentication:

1. Fence all work from a prior server instance or lifecycle generation.
2. Reconcile the server roster and immutable profiles.
3. Restore active goals as `STARTING`, never `PAUSED`.
4. Request fresh observations.
5. Recreate one supervision entry per active goal revision.
6. Resume provider work only from current facts.

Action acceptance, action completion, and goal completion retain their existing revision and generation fences. Retried recovery operations are idempotent. Repeating reconciliation or receiving duplicate recovery callbacks converges on one active session and one scheduled unit of work.

## Recovery promotion rules

Every fallback records:

- primary component;
- fallback component or waiting mode;
- activation reason;
- activation generation;
- next probe time;
- consecutive probe failures;
- promotion condition.

A successful health probe is necessary but not always sufficient. Process recovery requires authenticated bridge stability. Provider recovery requires an accepted exact-profile operation. Catalog recovery requires a valid complete snapshot. Voice recovery requires a successful bounded synthesis or transcription request.

Only the current generation may promote a component. A stale success is discarded. Promotion clears the fallback reason, resets backoff after the component's stability condition, and emits one recovery transition.

## Status and logging

The coordinator status snapshot adds component recovery records containing only bounded operational data:

- component name;
- state;
- fallback mode;
- failure code;
- consecutive failure count;
- next probe time;
- generation;
- last recovery time.

Operator chat reports state transitions, not every retry. Logs include full unexpected stacks once per aggregated incident and a concise recovery summary when the component returns. Credentials, prompts, model reasoning, bridge secrets, and access tokens remain excluded.

The existing message that always calls a disconnect a startup failure is replaced with state-aware guidance. Startup, crash-loop, authentication, bridge, provider, and blocked-dependency failures identify their actual boundary.

## Fault-injection verification

The implementation is not complete until automated tests inject failures at the real lifecycle seams.

### Process and runtime

- kill the coordinator before bridge startup, during authentication, after authentication, and during active work;
- crash it more than three times and prove later recovery still succeeds;
- leave the process alive but prevent authentication and prove the supervisor replaces it;
- disconnect an authenticated live process and prove it reconnects or is replaced after its deadline;
- corrupt the candidate runtime and prove last known good starts;
- remove Node, restore it, and prove `BLOCKED_RETRYABLE` resumes without restarting Minecraft;
- occupy the bridge or voice port, release it, and prove recovery;
- run startup and reconciliation twice and prove one owned process and one active session remain.

### Providers and catalogs

- fail each provider independently during startup, catalog refresh, session creation, and an active turn;
- prove another provider's failure does not block the selected healthy provider;
- prove the exact selected profile survives every restart;
- open a circuit, advance through cooldown, fail a half-open probe, then pass one and prove promotion;
- fail one catalog source and prove cached or built-in data remains available;
- restore that source and prove the live catalog replaces the fallback atomically.

### Active goals

- fail between every action acceptance and result boundary;
- lose the bridge during planning, action, and completion verification;
- restart the coordinator after one successful action in a multi-step goal;
- emit duplicate and stale callbacks after recovery;
- prove active work never becomes paused, completed, or terminal from infrastructure failure;
- prove explicit pause and stop remain authoritative;
- prove no duplicate physical action executes after recovery;
- prove factual completion still stops work exactly once.

### Optional subsystems

- terminate the voice worker and prove agent control remains healthy;
- fail preferred voice, use fallback, restore preferred voice, and prove promotion;
- fail trace, audit, verbose, and error-reporting sinks and prove control work continues;
- repeat each recoverable fault in a bounded soak and prove logs, timers, listeners, queues, and child processes remain bounded.

## Acceptance criteria

- No transient or degraded failure creates a permanent coordinator failure latch.
- Every fallback has an automated and tested promotion path.
- The selected AI profile never changes without an explicit player operation.
- One component failure cannot stop unrelated healthy components.
- Every active unfinished goal owns live, scheduled, or explicitly blocked recovery work.
- Coordinator and provider process restarts are idempotent and leave no orphaned children.
- Reconciliation cannot duplicate sessions, actions, messages, or completion transitions.
- Operator status identifies the current failing boundary and next recovery action.
- The complete Java, coordinator, voice, protocol, simulator, and headless suites pass.
- A fault-injection soak proves recovery after repeated process, bridge, provider, catalog, and voice failures.

## Limits

The mod cannot keep agents acting while Minecraft itself or Windows is not running. After a JVM, GPU, or operating-system failure, the next Minecraft launch must reap stale owned processes, validate the runtime, reconcile persisted goals, and resume them automatically. Surviving an absent Minecraft process would require a separately installed operating-system service and does not improve in-game availability while the server is down.

The design does not hide permanent external requirements. Missing executables, invalid credentials, or an unavailable selected model remain visible as `BLOCKED_RETRYABLE` until repaired or explicitly changed by the player.

## Scope

This work changes coordinator process supervision, runtime installation generations, bridge health deadlines, provider startup isolation, catalog aggregation, recovery classification, component status, and fault-injection tests. It does not change model selection semantics, expose hidden reasoning, add a new external service, or let fallback code claim goal completion.
