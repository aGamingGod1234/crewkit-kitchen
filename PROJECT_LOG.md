## 2026-07-13 — Task 4 bounded observation review fixes

### What Was Implemented
- Replaced `ClientLevel.hasChunk` with a true non-loading `ClientChunkCache.getChunk(..., ChunkStatus.FULL, false)` cache probe before any block-state, fluid-state, or collision-shape access. Temurin JDK 25 `javap -c` confirmed Minecraft 26.1.2's `ClientLevel.hasChunk(int, int)` is only `iconst_1; ireturn`, while the false-return branch of the cache lookup reaches `aconst_null; areturn` when no cached full chunk exists.
- Filtered queried entities by a finite spherical radius after the broad-phase AABB query, computing squared player distance exactly once per entity and retaining entities exactly on the radius boundary.
- Added deterministic observation fitting against the exact `ProtocolCodec` event envelope and UTF-8 byte encoding, reserving the maximum valid generated message-ID length so every published observation remains within the 65,536-byte JSONL limit.
- Added bounded text validation across observation snapshots and action/result placeholders so worst-case valid strings remain finite inputs to the fitter.
- Added RED-first regressions for absent client chunks, diagonal/out-of-radius entities, exact-boundary entities, invalid distances, maximum-cap escaped Unicode payloads, stable-prefix fitting, deterministic fitting, reserved-envelope measurement, and overlong snapshot text.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — fits each collected observation before publishing its payload.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeServer.java` — exposes exact reserved-envelope byte measurement through the production session codec path.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — shares event construction between real sends and maximum-ID envelope measurement.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationCollector.java` — uses non-loading chunk-cache probes and finite single-computation spherical entity filtering.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationWireBudget.java` — deterministically fits observations to the wire limit while preserving stable prefixes and supplies a minimal unavailable fallback.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/BlockSnapshot.java` — bounds serialized block identifiers.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/EntitySnapshot.java` — bounds serialized entity identifiers and names.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/InventorySnapshot.java` — bounds selected and summarized item identifiers.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/Observation.java` — bounds status, dimension, effect, action, and result text fields.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java` — exposes the exact versioned JSON object used by bounded line encoding.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolConstants.java` — exposes the protocol-version field name to the shared fitter path.
- `src/test/java/dev/agaminggod/arenaagents/client/perception/ObservationCollectorVerification.java` — verifies cache-before-snapshot behavior and spherical distance edge cases.
- `src/test/java/dev/agaminggod/arenaagents/client/perception/ObservationWireBudgetVerification.java` — verifies worst-case exact-envelope fitting, determinism, stable prefixes, and string bounds.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the new review-fix fixtures.
- `PROJECT_LOG.md` — records review evidence, fitting decisions, and deferred integration work.

### Assumptions Made (flag these for review)
- Deterministic retention priority is fixed as block tail, inventory-summary tail, entity tail, then effect tail; core player/world/action/result state and the selected item remain present, and each retained list is an unchanged stable prefix.
- Envelope budgeting reserves a 128-byte ASCII generated message ID, the protocol maximum, even though current monotonic `server-N` IDs are normally shorter.
- A cached chunk at `ChunkStatus.FULL` is the required safe boundary for block snapshot reads; an absent cache entry is skipped without requesting or loading it.

### Known Issues / Deferred
- Task 9's Node-side observation validator must mirror the new bounded string fields and existing collection caps.
- No periodic observation scheduler was added; publication remains request-driven as assigned.
- Official-launcher verification against the copied live world remains deferred to the later integration task.

### Suggested Next Steps
- Preserve the exact production envelope measurement path if event IDs or envelope fields change.
- Keep future observation fields individually bounded and include their maximum-cap forms in the wire-budget regression.
- Exercise cache misses and maximum-cap observation publication during official-launcher integration testing.

## 2026-07-13 — Nonblocking callback-failure shutdown

### What Was Implemented
- Changed fatal action-callback handling to close the bridge socket immediately without first waiting for error-frame delivery.
- Added a deterministic blocked-output regression that holds the writer's output lock, fails an action callback, and proves the callback can still finish while shutdown releases the blocked writer.
- Preserved generated error-ID coverage through an ordinary malformed control message, where synchronous protocol-error delivery remains safe.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — removes network output from the fatal callback-failure path and begins socket shutdown immediately.
- `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeConcurrencyVerification.java` — adds the controlled socket/output fixture and blocked-writer callback-failure regression.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the new regression and updates callback-failure expectations to immediate connection closure.
- `PROJECT_LOG.md` — records the callback shutdown fix, approved tradeoff, and verification scope.

### Assumptions Made (flag these for review)
- Fatal callback safety takes priority over delivering a `CALLBACK_FAILED` frame; the coordinator observes connection closure instead, as explicitly approved for this fix.

### Known Issues / Deferred
- Because the socket is closed immediately, a coordinator cannot distinguish a fatal application callback from another abrupt session failure using an in-band error frame.
- Action execution, cancellation effects, observation collection, and coordinator behavior remain in their previously assigned later tasks.

### Suggested Next Steps
- Keep fatal callback paths free of outbound network I/O as later action execution is added.
- Preserve the blocked-output regression when evolving bridge shutdown or writer ownership.

## 2026-07-13 — Task 3 bridge lifecycle review fixes

### What Was Implemented
- Linearized bridge start, session admission, and shutdown with explicit guarded server states so a candidate accepted during close can never publish or start afterward.
- Made `hello_ack` queue insertion and authentication publication one ordered session transition, and invalidated queued action callbacks when their originating session closes.
- Added a one-second pre-authentication timeout that reports `AUTHENTICATION_TIMEOUT` and releases the single-session slot for reconnect.
- Replaced caller-controlled outbound IDs and unbounded retention with one monotonic per-session generator shared by acknowledgements, events, and errors; `sendEvent` returns the generated ID for correlation without retaining prior IDs.
- Hardened authenticated test retries to close every failed socket and retry only transport failures or transient `SESSION_ACTIVE` responses.
- Moved queue-overflow shutdown and callback-error I/O outside conflicting locks, made concurrent close calls wait for teardown completion, and closed sockets before waiting for in-flight callbacks.
- Added live socket and deterministic concurrency regressions for the review findings, expanding `verifyCore` from 142 to 166 assertions.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeServer.java` — adds guarded lifecycle and admission state plus a package-private session-construction boundary for deterministic verification.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — orders authentication, generates outbound IDs, times out silent peers, contains callback failures, and completion-linearizes close.
- `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeConcurrencyVerification.java` — forces close-versus-admission, acknowledgement-order, and queue-overflow lock interleavings with real loopback sockets and controlled production boundaries.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — adds timeout, retry, sustained generated-ID, stale-callback, acknowledgement-order, queue-overflow, and shutdown-race regressions.
- `PROJECT_LOG.md` — records the Task 3 review fixes, assumptions, and remaining scope.

### Assumptions Made (flag these for review)
- A silent coordinator receives a one-second hello deadline; this is long enough for a local loopback handshake while preventing indefinite ownership of the only session slot.
- All server-originated frames use generated IDs in the form `server-N`; callers receive the generated event ID as the return value and may not replace envelope fields through payload data.

### Known Issues / Deferred
- The existing 4,096-message inbound session limit remains unchanged; outbound sessions have no event-count cap and retain no historical ID set.
- Action execution, cancellation effects, observation collection, and coordinator behavior remain in their previously assigned later tasks.

### Suggested Next Steps
- Implement Task 4 bounded observations through the hardened `BridgeServer.sendEvent` path.
- Preserve the new session lifecycle and acknowledgement-order invariants when Task 5 adds action execution.

## 2026-07-13 — Task 3 client configuration and loopback bridge

### What Was Implemented
- Added a strict four-field client config with named safe defaults, bounded agent identity/port/radius validation, optional loopback-host validation, and Fabric's conventional config directory.
- Added idempotent config creation using create-new semantics so an existing user config is parsed but never overwritten; malformed, unknown, and incorrectly typed fields fail with explicit protocol codes.
- Added bounded UTF-8 JSONL framing to the shared codec, including strict decoding, a 64 KiB byte limit, and explicit incomplete-frame errors.
- Added a `127.0.0.1`-only bridge with first-message `hello` authentication, protocol/agent identity checks, unique bounded message IDs, strict message allowlists, one live coordinator session, and reconnect support.
- Added daemon accept/reader/writer lifecycle management, a bounded outbound queue with explicit overflow failure, client-executor action dispatch, and clean close wiring for Minecraft shutdown.
- Expanded dependency-free verification with live loopback authentication, single-session rejection, action dispatch, reconnect, outbound-event, framing, config idempotency, and daemon-thread assertions.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/config/AgentConfig.java` — defines the exact four-field validated client configuration and named defaults.
- `src/client/java/dev/agaminggod/arenaagents/client/config/AgentConfigLoader.java` — parses, creates, and loads the Fabric client config without overwriting existing data.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeEventSink.java` — defines the parsed action callback boundary.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — implements authenticated JSONL session framing, validation, queues, callbacks, and daemon reader/writer lifecycle.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeServer.java` — binds the single-session acceptor explicitly to `127.0.0.1` and owns reconnect/shutdown behavior.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — loads the Fabric config, starts enabled bridges, dispatches callbacks through the Minecraft executor, and closes on client shutdown.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java` — adds bounded strict UTF-8 line read/write helpers.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — adds Task 3 config, framing, socket, reconnect, and thread assertions.
- `PROJECT_LOG.md` — records Task 3 behavior, decisions, verification, and deferrals.

### Assumptions Made (flag these for review)
- The generated example config is disabled by default (`enabled=false`) so installing the JAR does not open a listener until the user or runtime packaging opts in.
- The named safe observation-radius range is 1–32 blocks, with the approved example default of 12; the named non-privileged bridge-port range is 1024–65535.
- `action_command` carries the existing Task 2 command object in a nested `command` field because the outer envelope's `type` is reserved for `action_command`.
- Numeric IPv4 addresses in `127.0.0.0/8`, `localhost`, and IPv6 loopback literals are accepted as loopback config hints, while the actual server bind remains fixed to `127.0.0.1`.

### Known Issues / Deferred
- Action execution, cancellation effects, observation collection, and request-observation responses remain in Tasks 4 and 5; Task 3 only validates controls and dispatches parsed action commands.
- There is intentionally no shared secret or model credential in the config; authentication at this stage is the approved loopback plus first-message agent/protocol handshake.

### Suggested Next Steps
- Implement Task 4 bounded observations and publish them through `BridgeServer.sendEvent`.
- Implement Task 5's single-action lifecycle behind `BridgeEventSink` while preserving Minecraft-client-thread execution.

## 2026-07-13 — Task 2 protocol review fixes

### What Was Implemented
- Replaced double-rounded block-coordinate integrality checks with exact decimal-to-32-bit-integer validation for `break_block` and `place_block`.
- Reused one action-schema validator from both decoding and the public `ActionCommand` constructor so directly constructed commands cannot bypass required-field, unknown-field, type, or range checks.
- Made a missing compiled `VerificationMain` a hard `verifyCore` failure instead of a skipped verification task.
- Added regressions for sub-ULP fractional block coordinates, valid and invalid direct command construction, and the required verification-main wiring.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java` — adds exact block-coordinate validation and the shared action-argument validator.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionCommand.java` — validates every public construction through the shared action schema.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — expands dependency-free verification from 90 to 101 assertions.
- `build.gradle` — replaces the optional `verifyCore` predicate with an explicit required-class failure.
- `PROJECT_LOG.md` — records the review fixes and verification scope.

### Assumptions Made (flag these for review)
- JSON numbers with a mathematically zero fractional component, such as `1.0`, remain valid block coordinates; any nonzero fractional component is rejected exactly even when conversion to `double` would round it away.
- The existing signed 32-bit block-coordinate range remains the approved bound.

### Known Issues / Deferred
- Bridge message envelopes, JSONL socket framing, and coordinator-side validator parity remain in their planned later tasks.

### Suggested Next Steps
- Continue with Task 3 only after this review-fix commit is accepted.

## 2026-07-13 — Shared protocol and validation core

### What Was Implemented
- Added the exhaustive ten-action protocol vocabulary and explicit running/terminal action states.
- Added immutable action command and terminal result records with defensive JSON copying and coded validation failures.
- Added a strict Gson codec for flat action-command JSON, protocol version `1`, UTF-8 line limits, required and unknown fields, finite coordinates, bounded durations, bounded text, and exact per-action argument schemas.
- Added dependency-free Java verification covering all action types, malformed and oversized input, version mismatch, required fields, numeric and text bounds, immutability, coded failures, and encoding round trips.
- Updated Gradle's test lifecycle so the dependency-free verification main coexists with Gradle 9's no-discovered-tests check.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolConstants.java` — defines protocol version, validation bounds, and stable error codes.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionType.java` — defines the exact snake_case action union and wire lookup.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionState.java` — distinguishes running state from the four terminal states.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionCommand.java` — adds the immutable validated command record.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ActionResult.java` — adds the immutable terminal result record and machine-readable reason code.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolException.java` — carries explicit stable error codes and messages.
- `src/main/java/dev/agaminggod/arenaagents/protocol/ProtocolCodec.java` — parses, validates, and encodes bounded protocol JSON objects.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs 90 dependency-free protocol assertions through `verifyCore`.
- `build.gradle` — allows the intentionally framework-free test source while retaining `verifyCore` as the assertion runner.
- `PROJECT_LOG.md` — records Task 2 implementation, decisions, deferred work, and verification scope.

### Assumptions Made (flag these for review)
- Action-specific fields are flat beside `protocolVersion`, `commandId`, `type`, and `issuedAtEpochMs`, matching the approved Task 2 examples; `ActionCommand.arguments()` stores only the action-specific fields.
- Conservative named bounds are 128 characters for command IDs, 256 for chat/identifiers/selectors, 2,048 for summaries/result messages, 1–600,000 milliseconds for durations, and 0.01–16 blocks for movement tolerance.
- Block-action coordinates must be integral 32-bit values and placement faces are limited to the six Minecraft direction names.
- `ActionResult` contains command ID, terminal state, reason code, human-readable message, and completion epoch time, which is the smallest result shape that satisfies the approved terminal-result contract.

### Known Issues / Deferred
- Bridge message envelopes (`agentId`, `messageId`, bridge message type), loopback framing, and socket lifecycle remain Task 3 work.
- Coordinator-side parity validation remains Task 9 work.

### Suggested Next Steps
- Implement Task 3 client configuration and the loopback-only JSONL bridge against this codec.

## 2026-07-13 — Reproducible Fabric and Node baseline

### What Was Implemented
- Added a reproducible Fabric 26.1.2 project using Gradle 9.5.1 and Java 25.
- Added common and client Fabric entrypoints with build-time entrypoint verification.
- Added a dependency-free Node.js coordinator package with built-in test and start scripts.
- Added a forward-compatible `verifyCore` lifecycle task for the Task 2 verification main.
- Added proprietary licensing and exclusions for generated runtime data, logs, credentials, and local agent state.

### Files Modified
- `.gitignore` — excludes generated build/runtime data, credentials, logs, and local tool state.
- `settings.gradle` — configures Fabric plugin resolution and the project name.
- `build.gradle` — configures Loom, Java 25, dependencies, packaging, publishing, and verification tasks.
- `gradle.properties` — pins the approved Minecraft, Fabric, Loom, API, and project versions.
- `gradle/wrapper/*`, `gradlew`, `gradlew.bat` — adds the official Fabric 26.1.2 Gradle 9.5.1 wrapper.
- `LICENSE` — records the project as proprietary and all rights reserved.
- `src/main/resources/fabric.mod.json` — defines mod metadata, entrypoints, and dependency requirements.
- `src/main/java/dev/agaminggod/arenaagents/ArenaAgents.java` — adds the common Fabric entrypoint.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — adds the client Fabric entrypoint.
- `coordinator/package.json` — adds dependency-free Node.js lifecycle scripts.
- `PROJECT_LOG.md` — records this implementation and its deferred work.

### Assumptions Made (flag these for review)
- None. Licensing, ignore rules, entrypoint behavior, and the no-test `verifyCore` behavior were explicitly confirmed before implementation.

### Known Issues / Deferred
- `verifyCore` intentionally skips with a clear message until Task 2 adds `VerificationMain`.
- `coordinator/src/main.mjs` is deferred to the coordinator implementation task; the baseline test entrypoint already succeeds with zero tests.

### Suggested Next Steps
- Implement Task 2's shared protocol records, strict codec, and dependency-free Java verification assertions using TDD.
## 2026-07-13 — Task 4 bounded world observations

### What Was Implemented
- Added immutable typed observation, entity, block, inventory, player/effect, world, action, and result snapshots with defensive collections and stable serialized field order.
- Added deterministic entity/block ordering and named hard caps for entities, blocks, effects, inventory summaries, and block-state probes; the configured radius is clamped to the existing `AgentConfig` range.
- Added client-thread-only, null-safe collection of player position, velocity, view, health, hunger, armor, effects, selected item, summarized inventory, nearby living entities, nearby non-air blocks, collision/fluid context, dimension, game/default-clock time, weather, and action/result placeholders.
- Added a nearest-first bounded block scan that checks client chunk availability before block-state access and never requests a chunk.
- Routed authenticated `request_observation` controls through the existing Minecraft executor and returned a flattened, strictly enveloped `observation` event without adding periodic scheduling or action execution.
- Added RED-first dependency-free verification for ordering, truncation, immutable snapshots, unavailable state, serialization order, unloaded-chunk avoidance, deterministic block scanning, client-executor dispatch, and observation event envelopes.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/perception/Observation.java` — defines the complete stable observation shape and explicit unavailable/action/result placeholders.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/EntitySnapshot.java` — defines validated nearby-living-entity facts.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/BlockSnapshot.java` — defines block, fluid, and collision facts.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/InventorySnapshot.java` — defines selected-item and aggregated inventory facts.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationCollector.java` — performs bounded client-thread Minecraft collection without loading chunks.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationLimits.java` — centralizes radius clamping and hard output/scan caps.
- `src/client/java/dev/agaminggod/arenaagents/client/perception/ObservationOrdering.java` — centralizes deterministic distance/stable-identifier ordering.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — wires the collector to bridge observation requests.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeEventSink.java` — adds a default observation-request callback while preserving the functional action callback API.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — validates and dispatches observation requests through the provided client executor.
- `src/test/java/dev/agaminggod/arenaagents/client/perception/ObservationCollectorVerification.java` — verifies deterministic bounded scanning and unloaded-chunk exclusion.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the Task 4 pure and live-loopback assertions.
- `PROJECT_LOG.md` — records Task 4 scope, decisions, and deferred work.

### Assumptions Made (flag these for review)
- Named output caps are 64 living entities, 128 blocks, 32 effects, and 64 aggregated inventory item IDs; at most 8,192 nearest block positions are probed per requested observation.
- Minecraft 26.1.2's `getDefaultClockTime()` is the correct stable world-clock fact to expose alongside monotonic game time.
- Task 5 may provide current action and last terminal result through the typed suppliers; until then both are explicit `present=false` placeholders.
- A non-air block is useful observation context when paired with its fluid ID and empty/full collision-shape flags; air is omitted to preserve the block cap for actionable context.

### Known Issues / Deferred
- Live in-game content validation remains part of the later official-launcher verification task; this task verifies pure collection boundaries, 26.1.2 compilation, and loopback dispatch.
- Task 5 still owns action lifecycle and cancellation, and Task 11 still owns periodic/event-driven autonomous scheduling.
- Unexpected runtime collection failures return the explicit `collection_failed` unavailable observation; structured diagnostics/traces remain a later runtime concern.

### Suggested Next Steps
- Have Task 5 connect its action-state and last-result owners to the existing typed observation suppliers.
- Keep Task 9's Node observation validator synchronized with the field order, caps, placeholder shape, and flattened event envelope introduced here.
- Exercise ready-world observations in the copied test world during the official-launcher verification task.

## 2026-07-13 — Task 5 safe action lifecycle and primitive executors

### What Was Implemented
- Added a single-owner, client-thread action executor with explicit acceptance, progress, cancellation, timeout, failure, and exactly-once terminal-result behavior.
- Added a bounded 4,096-command duplicate history, named progress and timeout bounds, monotonic elapsed-time accounting, and observable containment of outbound event failures.
- Added validated factories and incremental implementations for `wait`, `look_at`, `chat`, `select_item`, and `use_item`; future movement, combat, block, and goal actions fail explicitly without destabilizing the bridge session.
- Added a Minecraft 26.1.2 action context for safety checks, gradual rotation, chat, deterministic hotbar selection, item use, and exhaustive synthetic-input/resource release.
- Wired authenticated `action_command` and targeted `cancel_action` controls through the supplied Minecraft executor, published strict `action_progress` and `action_result` envelopes, and connected live action/result owners to observations.
- Added stop/disconnect/death/screen safety handling so cancellation and unsafe transitions release movement, attack, use, and block-breaking state idempotently.
- Added RED-first dependency-free verification for lifecycle transitions, every supported primitive, duplicate/busy/deferred commands, clock regression, unsafe acceptance, resource-release failures, event-sink containment, bridge loopback dispatch, observation state, and runtime shutdown.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionContext.java` — defines the pure execution boundary and typed operation results.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionCreationException.java` — carries explicit action-construction failure codes.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionEventPublisher.java` — publishes strict progress and terminal bridge events while containing delivery failures.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionExecutor.java` — owns the single active lifecycle, cancellation, cleanup, timeouts, progress, and terminal results.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java` — maps validated protocol commands to supported primitives and explicit deferred failures.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionProgress.java` — defines immutable running-action progress.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionUpdate.java` — defines incremental primitive updates.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ChatAction.java` — sends validated chat exactly once.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ClientActionRuntime.java` — owns bridge callbacks, ticking, observation suppliers, and shutdown cleanup.
- `src/client/java/dev/agaminggod/arenaagents/client/action/CommandIdHistory.java` — provides bounded duplicate-command tracking.
- `src/client/java/dev/agaminggod/arenaagents/client/action/LookAtAction.java` — rotates incrementally to a validated target with tolerance and timeout bounds.
- `src/client/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContext.java` — adapts the pure action boundary to Minecraft 26.1.2 client APIs.
- `src/client/java/dev/agaminggod/arenaagents/client/action/RunningAction.java` — defines the incremental primitive contract.
- `src/client/java/dev/agaminggod/arenaagents/client/action/SafetyState.java` — centralizes safe and unsafe client states.
- `src/client/java/dev/agaminggod/arenaagents/client/action/SelectItemAction.java` — selects the lowest matching hotbar slot deterministically.
- `src/client/java/dev/agaminggod/arenaagents/client/action/UseItemAction.java` — starts, holds, stops, and cancels bounded item use.
- `src/client/java/dev/agaminggod/arenaagents/client/action/WaitAction.java` — completes waits from monotonic elapsed time without blocking the client thread.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — wires the runtime, client tick, bridge controls, observations, and stop cleanup.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeEventSink.java` — adds the targeted cancellation callback without breaking the functional action callback API.
- `src/client/java/dev/agaminggod/arenaagents/client/bridge/BridgeSession.java` — validates and dispatches `cancel_action` through the existing callback lifecycle.
- `src/test/java/dev/agaminggod/arenaagents/client/action/ActionExecutorVerification.java` — verifies pure lifecycle, primitives, failure containment, and runtime facade behavior.
- `src/test/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContextVerification.java` — verifies safety precedence, gradual rotation, deterministic slots, and exhaustive cleanup attempts.
- `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeActionIntegrationVerification.java` — verifies live-loopback controls, event envelopes, cancellation, observations, and healthy-session reuse after ordinary failures.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the Task 5 suites through `verifyCore`.
- `PROJECT_LOG.md` — records Task 5 scope, assumptions, evidence, and deferred work.

### Assumptions Made (flag these for review)
- The approved protocol's `use_item` shape contains only `durationMs`, so Task 5 uses the validated internal `MAIN_HAND`; selecting an off hand requires a later protocol revision rather than an unapproved wire field.
- Any open screen makes action execution unsafe, including inventory and chat screens, so acceptance/ticking fails safely and releases synthetic inputs.
- `select_item` searches only the nine hotbar slots and selects the lowest matching registry ID; it does not rearrange the wider inventory.
- A 4,096-entry recent command-ID history is the bounded duplicate-rejection window; completed IDs are refreshed so an immediately replayed terminal command cannot be evicted by concurrent rejected work.

### Known Issues / Deferred
- `move_to` remains Task 6; `attack`, `break_block`, and `place_block` remain Task 7; `complete_goal` remains coordinator-owned. Task 5 returns explicit `ACTION_NOT_IMPLEMENTED` results for them.
- Official-launcher validation in a copied world remains a later integration task; this task verifies pure behavior, live loopback behavior, and Minecraft 26.1.2 compilation.
- The current protocol cannot request off-hand use because it has no hand field.

### Suggested Next Steps
- Implement Task 6 movement against the existing incremental executor and cleanup boundary.
- Implement Task 7 combat/block actions without weakening the exactly-once terminal and resource-release invariants.
- Mirror the Task 5 progress/result envelope and cancellation semantics in Task 9's coordinator validators, then exercise them in the official-launcher integration task.

## 2026-07-13 — Task 5 lifecycle invariant review fixes

### What Was Implemented
- Hardened the bounded command-ID history so a running command remains pinned through more than 4,096 unique busy submissions, replaying it cannot emit a false terminal result, and completion reinserts it while preserving the exact bound.
- Moved running-action timeout resolution into guarded acceptance, validated it once as positive, cached it in active execution state, and routed factory/timeout/start-time failures through cleanup plus one explicit failed result.
- Verified that throwing `RunningAction.tick()` and `RunningAction.cancel()` boundaries remain contained, release resources, and emit exactly one failed terminal result.
- Made all nine synthetic key releases independent so a failing key cannot prevent later keys, item-use stop, or block-breaking abort; cleanup failures remain one aggregated `RESOURCE_RELEASE_FAILED` result.
- Replaced clock-regression failure with nonnegative elapsed-time clamping, including overflow saturation, so backward monotonic readings cannot emit negative progress or cause an early timeout.
- Added RED-first pure and live-loopback regressions covering history floods, active replay before/after completion, per-command result counts, throwing/cached/invalid timeouts, factory cleanup, healthy bridge reuse, per-key cleanup fan-out, and actual backward-clock behavior.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionExecutor.java` — pins active IDs, caches guarded timeout/start facts, centralizes rejected-action cleanup, and clamps elapsed time.
- `src/client/java/dev/agaminggod/arenaagents/client/action/CommandIdHistory.java` — adds protected bounded eviction and bounded terminal reinsertion.
- `src/client/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContext.java` — releases each synthetic key through the aggregate cleanup boundary independently.
- `src/test/java/dev/agaminggod/arenaagents/client/action/ActionExecutorVerification.java` — adds flood/replay, timeout, factory, lifecycle-boundary, cleanup-result, and clock-regression coverage.
- `src/test/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContextVerification.java` — proves later fake keys and resources run after multiple key failures and failures are suppressed once.
- `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeActionIntegrationVerification.java` — proves a throwing timeout emits failure without closing the authenticated bridge and a follow-up command is accepted.
- `PROJECT_LOG.md` — records the Task 5 review fixes, assumptions, evidence, and deferred work.

### Assumptions Made (flag these for review)
- The approved 4,096-entry history remains the total bound. When full, the oldest unprotected ID is evicted; the single active ID is protected, and its terminal transition refreshes or reinserts it.
- A backward monotonic reading clamps that tick's elapsed time to zero; later readings are still measured from the original accepted start, and subtraction overflow saturates to `Long.MAX_VALUE` so it times out safely.
- `RunningAction.timeoutMs()` is a signed integral duration and therefore requires only a positive-value check; action factories retain ownership of their protocol-specific maximum bounds.

### Known Issues / Deferred
- The existing Task 5 deferrals remain unchanged: movement belongs to Task 6, combat/block interactions to Task 7, goal completion to the coordinator, and off-hand use to a future validated protocol revision.
- Official-launcher copied-world validation remains a later integration task; this review fix adds deterministic pure and live-loopback evidence plus Minecraft 26.1.2 compilation.

### Suggested Next Steps
- Continue to Task 6 only after this review-fix commit is accepted.
- Preserve the pinned-ID, cached-timeout, exactly-once terminal, independent-cleanup, and nonnegative-elapsed invariants in later action implementations.

## 2026-07-13 — Task 6 autonomous local navigation

### What Was Implemented
- Added deterministic cardinal A* pathfinding with explicit success, no-path, node-limit, and time-limit outcomes; same-level walking, one-block jumps, and safe drops of up to three blocks.
- Added conservative cached-chunk walkability checks that reject unloaded terrain, incomplete support, collisions, fluids, and hazardous blocks without synchronously loading chunks.
- Added incremental movement steering, node advancement, sprint/jump input, exact destination tolerance, stuck detection, three bounded recovery replans, and exhaustive input release on every terminal path.
- Enabled `move_to` in the existing action factory and integrated it with cancellation, unsafe-state handling, timeout handling, observations, and healthy bridge-session reuse.
- Added RED-first pure, Minecraft-adapter, executor, and live-loopback verification for planning, movement, recovery, cleanup, and follow-up command health.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/navigation/` — adds immutable path types, the deterministic local planner, cached-world safety adapter, movement controller, and stuck detector.
- `src/client/java/dev/agaminggod/arenaagents/client/action/MoveToAction.java` — owns incremental planning, movement, recovery replans, timeout, cancellation, and terminal reason codes.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionContext.java` — adds typed navigation snapshots, walkability access, and synthetic movement input.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java` — enables validated `move_to` commands while preserving later-task deferrals.
- `src/client/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContext.java` — maps navigation state, cached-world queries, and movement keys to Minecraft 26.1.2.
- `src/test/java/dev/agaminggod/arenaagents/client/navigation/` — verifies deterministic planning, safe traversal, limits, movement, stuck recovery, and cached-chunk classification.
- `src/test/java/dev/agaminggod/arenaagents/client/action/MoveToActionVerification.java` — verifies success, planner failures, unsafe transitions, recovery exhaustion, timeout, and cancellation.
- `src/test/java/dev/agaminggod/arenaagents/client/action/ActionExecutorVerification.java` — verifies factory enablement and lifecycle integration for `move_to`.
- `src/test/java/dev/agaminggod/arenaagents/client/bridge/BridgeActionIntegrationVerification.java` — verifies loopback cancellation/failure followed by a healthy command.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the Task 6 suites through `verifyCore`.
- `PROJECT_LOG.md` — records Task 6 scope, assumptions, evidence, and deferred work.

### Assumptions Made (flag these for review)
- Local planning is cardinal-only and deterministic; diagonal movement, parkour, swimming, ladders, doors, mining, and block placement remain outside Task 6.
- A full solid support surface is required, and fluids plus fire, cactus, magma, campfires, sweet berry bushes, wither roses, and powder snow are treated conservatively as hazards.
- `move_to` uses a 120-second action timeout, a 0.1-block progress threshold, a 2.5-second stuck window, and exactly three recovery replans before explicit failure.

### Known Issues / Deferred
- `attack`, `break_block`, and `place_block` remain Task 7; `complete_goal` remains coordinator-owned.
- Official-launcher validation in a copied world remains a later integration task; Task 6 verifies pure behavior, live loopback behavior, and mapped Minecraft 26.1.2 compilation.
- Planning intentionally fails when required chunks are not already cached instead of loading terrain synchronously on the client thread.

### Suggested Next Steps
- Reuse `MoveToAction` and its safety/recovery boundary when Task 7 needs approach movement.
- Exercise navigation in the official launcher against a copied world before broader autonomous playtesting.

## 2026-07-13 — Task 7 combat and world interaction actions

### What Was Implemented
- Added deterministic combat target selection by player name, UUID, entity type, nearest hostile, or nearest player, with distance/UUID tie-breaking and UUID pinning after selection.
- Added deterministic hotbar melee-weapon selection, cooldown-aware facing/attacking, and ordinary navigation-assisted approach movement without teleportation.
- Added incremental attack, block-break, and block-placement actions with explicit failure/success codes and the existing centralized timeout, cancellation, unsafe-state, and exactly-once cleanup boundaries.
- Added cached-chunk, survival-reach, raycast-visible-face, block-presence, and requested block-item checks before calling normal Minecraft interaction-manager methods.
- Added pure action/controller verification and a live-loopback regression proving combat failure does not close the authenticated bridge or block a follow-up command.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/combat/` — adds immutable target/weapon facts, deterministic selectors, and combat phase decisions.
- `src/client/java/dev/agaminggod/arenaagents/client/interaction/BlockInteractionPreconditions.java` — centralizes conservative block-action preconditions and reason codes.
- `src/client/java/dev/agaminggod/arenaagents/client/action/AttackAction.java` — tracks, approaches, faces, equips for, and attacks a selected target incrementally.
- `src/client/java/dev/agaminggod/arenaagents/client/action/BreakBlockAction.java` — performs bounded incremental block breaking through the client interaction manager.
- `src/client/java/dev/agaminggod/arenaagents/client/action/PlaceBlockAction.java` — selects the requested block item and submits a visible-face placement through the client interaction manager.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionContext.java` — adds typed combat and block-interaction boundaries with safe unavailable defaults.
- `src/client/java/dev/agaminggod/arenaagents/client/action/ActionFactory.java` — enables the three Task 7 protocol actions.
- `src/client/java/dev/agaminggod/arenaagents/client/action/MinecraftActionContext.java` — maps typed operations to cached-world reads and normal Minecraft 26.1.2 interaction APIs.
- `src/test/java/dev/agaminggod/arenaagents/client/action/CombatInteractionVerification.java` — verifies selectors, weapons, combat phases, preconditions, outcomes, and cleanup.
- Existing executor, bridge, and verification entrypoint suites — verify factory enablement and healthy authenticated-session reuse.
- `PROJECT_LOG.md` — records Task 7 scope, assumptions, evidence, and deferred work.

### Assumptions Made (flag these for review)
- `player:<name>`, `uuid:<uuid>`, `type:<namespaced-id>`, `nearest_hostile`, and `nearest_player` are the supported deterministic selector forms.
- Placement coordinates identify the visible support block and `face` identifies the clicked face; the requested block is placed adjacent according to normal server rules.
- An attack-strength scale of at least 0.9 is considered ready, and the fixed weapon ordering prefers swords before comparable axes for predictable behavior.

### Known Issues / Deferred
- Placement success means the normal `useItemOn` request consumed the action; final server-world confirmation remains part of official-launcher integration evidence.
- Target approach is limited to Task 6 cardinal walking/jumping/dropping and inherits its conservative unloaded/hazard behavior.
- Server goal commands/payloads remain Task 8; goal completion remains coordinator-owned.

### Suggested Next Steps
- Complete Task 8 goal delivery without widening the action protocol.
- Exercise combat, breaking, and placement against a copied-world server and capture server-confirmed results during official-launcher validation.

## 2026-07-13 — Task 13 safe runtime preparation and packaging

### What Was Implemented
- Added strict PowerShell entrypoints for verified Java setup, idempotent isolated runtime preparation, authenticated server startup, coordinator startup, and consolidated automated verification.
- Added SHA-256 verification for the official Fabric installer, non-loading source-world path checks, copied-world manifest evidence, two isolated client configs, and online-mode server safeguards.
- Documented the exact GPT-5.5/xhigh/Fast and GPT-5.6-Sol/high/Fast launcher installations, evidence classes, commands, and second-account limitation.

### Files Modified
- `scripts/install-toolchain.ps1` — verifies or installs a caller-supplied SHA-256-pinned Temurin 25 archive.
- `scripts/prepare-runtime.ps1` — builds, installs Fabric 0.19.3, prepares isolated clients/server, and makes a verified world copy.
- `scripts/start-test-server.ps1` — starts only the explicitly selected authenticated or labeled offline-smoke server mode.
- `scripts/start-coordinator.ps1` — verifies Node/Codex prerequisites and starts the selected runtime profile.
- `scripts/run-automated-verification.ps1` — runs the complete Java/Fabric and Node/fake-E2E gates.
- `README.md`, `runtime/README.md`, `.gitignore` — document operation and keep generated state out of Git.

### Assumptions Made (flag these for review)
- Fabric Installer 1.1.1 is pinned to SHA-256 `2487A69DD6F9D9C2605265A7142D77C26AB62EDC620E6BCF810D581D2EE31B79` from the official Fabric Maven sidecar.
- Launcher installations are created and verified through the official UI; scripts do not copy account files or write credentials.
- Existing agent configs or server properties that disagree with the required isolated settings cause a hard failure instead of being overwritten.

### Known Issues / Deferred
- `prepare-runtime.ps1` must run after all implementation commits are integrated so it packages the final JAR.
- Simultaneous authenticated two-player verification remains blocked until a second licensed Minecraft Java account is available.

### Suggested Next Steps
- Integrate the coordinator and remaining Java commits, run consolidated verification, then run preparation twice.
- Create both launcher installations through the official UI and execute the sequential authenticated live-test matrix.

## 2026-07-13 — Task 8 server goal control and client delivery

### What Was Implemented
- Added operator-only `/arenaagent goal <players> <goal>`, `/arenaagent stop <players>`, and `/arenaagent status <players>` commands using the Minecraft 26.1.2 command API.
- Added strict goal normalization, a 4,096-character input limit, explicit validation errors, and per-player server-session goal status.
- Added a typed Fabric clientbound goal payload and bounded codec, with delivery restricted to the selector's supported players and explicit success, unsupported-client, and failure acknowledgements.
- Added a client-thread goal receiver that cancels and releases the active action before publishing a fixed-shape generated `goal_event`; both replacement goals and stop commands use the existing idempotent lifecycle cleanup.
- Added RED-first verification for normalization, limits, authorization policy, target filtering, status transitions, strict event fields, and cancellation-before-publication ordering.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/server/GoalControl.java` — validates goals, checks the operator policy, tracks session status, and performs exact selected-target delivery.
- `src/main/java/dev/agaminggod/arenaagents/server/GoalPayload.java` — defines the typed set/stop payload and bounded Fabric stream codec.
- `src/main/java/dev/agaminggod/arenaagents/server/ArenaAgentCommands.java` — registers goal, stop, and status commands and reports delivery outcomes.
- `src/main/java/dev/agaminggod/arenaagents/ArenaAgents.java` — registers the clientbound payload codec and server commands.
- `src/client/java/dev/agaminggod/arenaagents/client/network/GoalReceiver.java` — dispatches payload handling on the client thread, stops active work, and emits `goal_event`.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` — wires the goal receiver to the action runtime and loopback bridge.
- `src/test/java/dev/agaminggod/arenaagents/server/GoalControlVerification.java` — verifies goal control policy, validation, targeting, and status.
- `src/test/java/dev/agaminggod/arenaagents/client/network/GoalReceiverVerification.java` — verifies goal/stop ordering and strict bridge payload fields.
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java` — runs the Task 8 verification suites.
- `PROJECT_LOG.md` — records Task 8 scope, decisions, and deferred work.

### Assumptions Made (flag these for review)
- Minecraft's vanilla game-master permission check is the intended operator threshold for all three commands.
- `/arenaagent status` reports the last successfully delivered server-session goal state; detailed client action state remains available through observations rather than a new reverse network payload.
- A replacement goal cancels the previous macro action immediately, matching the approved combat and action-lifecycle goal-replacement semantics.

### Known Issues / Deferred
- If no authenticated coordinator bridge session is active when a payload arrives, the client logs the failed `goal_event` publication; coordinator reconnect/replay policy remains Task 11.
- In-game command and payload negotiation will be exercised in the copied test world during the official-launcher verification task.

### Suggested Next Steps
- Keep Task 9's `goal_event` validator synchronized with the exact `operation` and `goal` fields introduced here.
- Have Task 11 treat `operation=set` as a fresh planning trigger and `operation=stop` as terminal until another goal arrives.

## 2026-07-13 — Tasks 9–12 autonomous coordinator and integration verification

### What Was Implemented
- Added a dependency-free Node coordinator with strict versioned JSONL validation, authenticated loopback bridge sessions, bounded observations, action dispatch, cancellation, reconnect backoff, and isolated per-agent traces.
- Added one persistent Codex app-server thread per agent, exact fixed model profiles, strict structured decision parsing, proactive plan-act-observe scheduling, goal replacement/stop handling, retry behavior, and app-server restart recovery.
- Added strict `--config`, `--agent`, and `--check-models` CLI handling plus deterministic selection of `agent-55`, `agent-56`, or both.
- Added integration fixtures covering two concurrent isolated agents, real TCP framing, malformed planner recovery, reconnects, graceful shutdown, and live model-catalog verification.

### Files Modified
- `coordinator/src/` — implements protocol validation, Codex app-server control, autonomous runtimes, retries, traces, and the CLI.
- `coordinator/config/agents.json` — fixes GPT-5.5/xhigh/Fast and GPT-5.6-Sol/high/Fast to distinct identities, ports, and runtime paths.
- `coordinator/test/` — verifies schemas, framing, planner parsing, lifecycle behavior, recovery, and two-agent end-to-end operation.
- `scripts/run-automated-verification.ps1` — includes the complete coordinator test suite.
- `scripts/start-coordinator.ps1`, `runtime/README.md` — create and document the actual isolated trace locations.

### Assumptions Made (flag these for review)
- The local authenticated Codex app server remains the planner boundary; neither Minecraft client receives shell, file, launcher, or credential access.
- A new goal starts a fresh internal revision, a stop event remains terminal until a later set event, and only the exact Java wire fields `operation` and `goal` are accepted.
- Both agents intentionally share prompts, validation, action limits, and recovery policy; only model identity, reasoning effort, player identity, bridge port, and runtime directory differ.

### Known Issues / Deferred
- Simultaneous authenticated online-mode evidence still requires a second licensed Minecraft Java account; one account supports sequential profile validation only.
- Official-launcher and copied-world evidence remains the final live-test step.

### Suggested Next Steps
- Prepare the isolated runtimes twice, create both official-launcher installations, and run the sequential authenticated live-test matrix.

## 2026-07-13 — Automated integration hardening and deferred live test

### What Was Implemented
- Added explicit Codex `experimentalApi` capability negotiation while declining attestation requests, preserving deterministic empty `environments` and `dynamicTools` arrays so planner turns cannot inherit a host-default environment or dynamic tools.
- Added a regression assertion for the exact initialize capability contract after the local Codex 0.144.0 app server rejected unnegotiated experimental fields.
- Replaced the Windows PowerShell 5.1-incompatible `Path.GetRelativePath` call with a bounded world-root helper and added hash-gated recovery for an interrupted copy that exists without evidence.
- Added an idempotent launcher-profile installer that preserves all existing profiles, writes a local backup, and adds the two exact Fabric 0.19.3 isolated installations.
- Restored the disposable copied world after preflight startup, then independently verified all 80 source/target files and `level.dat` hashes match with no stale `session.lock`.

### Files Modified
- `coordinator/src/codex-app-server.mjs` — declares the capability required by explicit no-environment/no-dynamic-tool thread settings.
- `coordinator/test/codex-app-server.test.mjs` — verifies the exact initialization contract.
- `scripts/prepare-runtime.ps1` — supports Windows PowerShell 5.1 manifest paths and safe interrupted-copy recovery.
- `scripts/install-launcher-profiles.ps1` — installs or verifies the two isolated official-launcher profiles without overwriting mismatched entries.
- `README.md`, `PROJECT_LOG.md` — document preparation, launcher-profile preservation, evidence, and the deferred live boundary.

### Verification Evidence
- `run-automated-verification.ps1` passed: 4,673 Java protocol/bridge assertions, Gradle `check`, `build`, `verifyCore`, `verifyEntrypoints`, JAR assembly, and 42 Node tests with zero failures, skips, or cancellations.
- All six PowerShell scripts parse with zero syntax errors under Windows PowerShell 5.1.
- All four packaged Arena Agent JARs share SHA-256 `DAAF4E27DE901A79164A48FD8DD8BA7C3D4E43514333BD0921BE359F4D6BFF91`.
- Both launcher profiles resolve to Fabric `0.19.3` for Minecraft `26.1.2`, the isolated game directories, the project-local Java 25 executable, and `-Xms1G -Xmx4G`.
- No launcher/game process remains and ports `25565`, `25571`, and `25572` are closed.

### Assumptions Made (flag these for review)
- Negotiating the experimental protocol surface is limited to fields the coordinator sends deliberately; Minecraft actions remain the only planner-controlled effect and both environment and dynamic-tool lists remain explicitly empty.
- Launcher JSON installation is acceptable because the merge is local, backed up, idempotent, and fails closed instead of overwriting an existing mismatched Arena profile.

### Known Issues / Deferred
- Per user direction, no Minecraft client was launched and no gameplay, server join, in-game command, action execution, or two-agent fight was tested in this phase.
- Simultaneous authenticated online-mode testing still requires a second licensed Minecraft Java account.

### Suggested Next Steps
- When live testing is authorized, start the authenticated server and coordinator, launch each isolated profile sequentially, exercise goal/stop/status and action traces, then use two licensed accounts for the simultaneous fight.
