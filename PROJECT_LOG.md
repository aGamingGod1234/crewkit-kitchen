## 2026-07-22 — Headless Minecraft repair and end-to-end verification

### What Was Implemented
- Fixed the Java bridge compile constant, deterministic coordinator test synchronization, directional protocol fixture, nullable persistence serialization, and Windows PowerShell 5.1 secret generation.
- Negotiated the live Codex catalog correctly: dynamic agents use the supported `priority` app-server service tier while retaining the separate Fast launcher profile.
- Replaced the rejected planner `oneOf` output schema with an API-compatible fixed nullable action schema, then compacted and strictly validated it before execution.
- Kept summoned agents executable without nearby players by adding reference-counted, moving chunk simulation tickets that are released on movement, death, removal, and shutdown.
- Converted missing-entity action rejection into a terminal failed action result instead of allowing the bridge task to throw and leave the goal stuck.
- Disabled empty-server pausing only in the isolated headless runtime so multi-turn verification remains deterministic without a connected player.
- Ran a live authenticated headless lifecycle through the local Codex OAuth/app-server bridge: persisted agent reload, two agents sharing one chunk, peer removal, exact model-authored chat, goal clearing, second reload, registry cleanup, graceful shutdown, and closed-port verification.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentManager.java` — manages reference-counted moving chunk tickets for persistent agents.
- `src/main/java/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.java` — maintains agent chunk tickets during server ticks.
- `src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java` — reports unloaded-entity action failures without wedging the bridge.
- `src/main/java/dev/agaminggod/arenaagents/server/bridge/MultiplexedServerBridge.java` and `src/main/java/dev/agaminggod/arenaagents/agent/AgentRegistrySnapshotCodec.java` — compile and nullable-persistence repairs.
- `coordinator/src/constants.mjs`, `coordinator/src/model-catalog-cache.mjs`, `coordinator/src/codex-service.mjs`, `coordinator/src/dynamic-main.mjs`, and `coordinator/config/dynamic-agents.json` — supported live service-tier defaults.
- `coordinator/src/prompts.mjs` and `coordinator/src/decision-parser.mjs` — app-server-compatible structured output and strict post-compaction validation.
- `coordinator/test/*.test.mjs` — timing, protocol, catalog, service-tier, and structured-output regressions.
- `scripts/start-test-server.ps1`, `scripts/start-dynamic-coordinator.ps1`, and `scripts/prepare-runtime.ps1` — PowerShell 5.1-safe secrets and deterministic headless server behavior.
- `PROJECT_LOG.md` — final repair and verification evidence.

### Assumptions Made (flag these for review)
- The copied online-mode dedicated server remains the correct non-destructive runtime target; the original Minecraft world was not launched or modified.
- A completed goal is correctly represented by the persistent lifecycle as `IDLE`, a newer revision, and `goal=none` after the transient completion transition.
- A radius-two non-persistent simulation ticket is sufficient for continuous agent navigation while avoiding permanent vanilla forced-chunk changes.

### Known Issues / Deferred
- Client-only rendering, skins, and visible name tags cannot be observed in a headless dedicated server and still require one normal client launch for visual acceptance.
- The copied source world contains stale Axiom gamerule keys; Minecraft logs one non-fatal load warning because Axiom is not installed, but the server and agent lifecycle continue normally.
- Container crafting, furnace transactions, and arbitrary mod-specific interactions remain outside the current bounded server action catalog.

### Suggested Next Steps
- Launch one normal Fabric client against the isolated server to visually accept the four skins and model/reasoning name tags.
- Package the verified JAR, coordinator, configuration, and launcher scripts as the distributable mod-pack archive.

## 2026-07-22 — First headless build and runtime-readiness test

### What Was Implemented
- Ran the project-local Java/Fabric build entrypoint and the independent Node coordinator suite after the user opened the runtime-testing phase.
- Confirmed the local prerequisites: Temurin Java 25.0.3, Node 25.2.1, Codex CLI 0.144.0, and an authenticated ChatGPT OAuth session.
- Classified the coordinator failures by tracing their assertions to production code: the protocol test incorrectly treats bidirectional `heartbeat` as directional, while the FIFO scheduler test assumes a fixed number of promise microtasks under Node 25.

### Files Modified
- `PROJECT_LOG.md` — recorded the first real build/test evidence and the resulting headless-launch stop condition.

### Assumptions Made (flag these for review)
- No stale previously built mod JAR was treated as evidence for the newly implemented source.

### Known Issues / Deferred
- `compileJava` fails at `MultiplexedServerBridge.java:416-419` because `AgentConstants.MAX_GOAL_LENGTH` does not exist; no Java tests or fresh mod packaging can run until that reference is corrected.
- Coordinator tests report 69 passed and 2 failed. The two failures are test-contract/timing defects identified above, but the suite is not green.
- The headless dedicated server was deliberately not launched with the stale runtime JAR, so command registration, persistence, bridge connection, and agent behavior remain unverified.
- Client-only rendering, skins, and visible name tags cannot be verified in a headless dedicated server.

### Suggested Next Steps
- Correct the compile constant and the two coordinator tests, rerun the complete verification script, install the fresh mod JAR into the isolated runtime, then launch the dedicated server and exercise `/codex summon`, lifecycle, persistence, coordinator connection, and a bounded goal through the console.

## 2026-07-22 — Summonable Codex NPC mod-pack implementation

### What Was Implemented
- Added persistent custom humanoid Codex NPC entities, renderer registration, four original interlocking-mark skin variants, model/reasoning name tags, inventories, lifecycle state, death reconciliation, and manual respawn.
- Added `/codex summon`, `start`, `stop`, `resume`, `respawn`, `queue`, `steer`, `status`, `list`, and `remove`; bare summon defaults to `gpt-5.6-sol` with `high` reasoning.
- Added the authenticated loopback protocol-v2 bridge, dynamic multi-agent coordinator, shared Codex app-server transport, per-agent Codex threads, fair planning scheduler, catalog validation, revision/action replay protection, and safe interruption handling.
- Added server-authoritative observation and execution for navigation, look, attack, select/use item, break/place, doors, pickup/drop, chat, wait, and goal completion with explicit terminal results.
- Closed the final static-review defects: strict action-type decoding, action-construction rollback, target-position placement protection and collision checks, transactional item-drop rollback, and deterministic bridge thread interruption.
- Completed static-only verification: 117 Java sources passed a comment/string-aware delimiter scan, 39 JavaScript modules and 7 PowerShell scripts parsed, 4 JSON files and 5 PNG assets validated, mapped placement API signatures were confirmed from the local Minecraft 26.1.2 cache, and `git diff --check` passed.
- Added isolated runtime launchers and secret handling, client/server assets, protocol/domain verification sources, implementation documentation, and preserved the legacy two-client arena mode.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/agent/**` — persistent NPC domain, entity, lifecycle, validation, and codecs.
- `src/main/java/dev/agaminggod/arenaagents/server/**` — commands, manager, SavedData, bridge, observations, executor, leases, protection policy, death/respawn runtime.
- `src/client/java/dev/agaminggod/arenaagents/client/**` — entity renderer and legacy-client compatibility.
- `src/main/resources/**` — mod metadata, language, icon, and four generated NPC skins.
- `coordinator/src/**`, `coordinator/test/**`, `coordinator/config/dynamic-agents.json`, `coordinator/package.json` — dynamic coordinator, exact wire schemas, catalog, scheduler, Codex service, planner, configuration, and regression sources.
- `scripts/start-test-server.ps1`, `scripts/start-dynamic-coordinator.ps1`, `scripts/generate-agent-skins.mjs` — shared-secret startup and deterministic asset generation.
- `README.md`, `runtime/README.md`, `docs/summonable-codex-agents-implementation-plan.md`, `PROJECT_LOG.md` — operator and implementation documentation.

### Assumptions Made (flag these for review)
- The confirmed defaults are `gpt-5.6-sol`, `high`, Fast service tier, four planning turns at once, 16 persistent agents, and 32 queued goals per agent.
- `/codex` remains operator/single-player controlled through the existing `GoalControl::mayControl` permission boundary.
- Original AI-generated interlocking marks are used instead of copying an official OpenAI trademark asset.
- The trusted local operator protection policy permits mutations in this isolated pack; integrations for protected third-party servers must inject a stricter policy.

### Known Issues / Deferred
- Per the user boundary, no Gradle build, automated test suite, Minecraft launch, Codex launch, or gameplay test has run yet; current evidence is static syntax, lexical, contract, and diff auditing only.
- Container transfers, recipe crafting, furnace transactions, and forced chunk tickets remain explicit fail-closed capabilities pending exact Minecraft 26.1.2 runtime/API validation.
- Live model/reasoning command suggestions are not yet exposed; invalid profiles are rejected against the loaded Codex catalog at summon time.
- Runtime validation is still required for Fabric mappings, entity rendering, persistence reload, OAuth/app-server negotiation, multiplayer protection behavior, and long-running concurrency.

### Suggested Next Steps
- Open the next phase by running the build and automated suites, fixing compile/API issues, then launch only the isolated copied-world runtime.
- Perform sequential command/entity/persistence checks before enabling multiple concurrent Codex NPCs or protected-server integration.

## 2026-07-22 — Summonable Codex NPC architecture and protocol audit

### What Was Implemented
- Audited the fixed two-client Fabric/Codex architecture against the confirmed custom humanoid NPC mod-pack requirements.
- Defined the server-authoritative entity, lifecycle, command, persistence, rendering, dynamic coordinator, planning scheduler, protocol-v2, action, observation, security, and failure-recovery contracts.
- Recorded a phased implementation and static verification plan while preserving the explicit boundary against launching Minecraft, launching Codex, building, or running tests.

### Files Modified
- `docs/summonable-codex-agents-implementation-plan.md` — records the approved product contract, static gap audit, target architecture, protocol, phases, risks, and definition of done.
- `PROJECT_LOG.md` — records this architecture/audit task and its deferred implementation boundary.

### Assumptions Made (flag these for review)
- The user explicitly approved the recommended custom humanoid NPC architecture and all proposed defaults, including Minecraft 26.1.2, operator-only commands, a configurable default cap of 16 agents, four concurrent Codex planning turns, persistence, survival interactions, and original OpenAI-inspired textures.
- The target coordinator uses one shared local Codex app-server process with isolated per-agent threads; the current static API path supplies per-turn model and reasoning fields, but live multi-thread behavior remains unverified by instruction.

### Known Issues / Deferred
- No production code or assets have been added yet.
- No build, automated test, Minecraft launch, Codex launch, or live protocol test was performed.
- Custom entity compatibility with vanilla player-only container/recipe/protection APIs requires adapter-level implementation and later runtime verification.
- The existing two-client comparison implementation remains present and must not be removed without explicit authorization.

### Suggested Next Steps
- Approve Phase 1 implementation of protocol-v2 contracts, the persistent agent record, lifecycle reducer, revision invariants, scheduler contract, and dependency-free static verification.
- Keep Minecraft/Codex launch and live integration work deferred until the user explicitly opens Phase 7.

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
- Made exact server-property checks accept both LF and the CRLF line endings written by the Minecraft server while still rejecting whitespace or value changes.
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

## 2026-07-23 — Gemini CLI and Kimi CLI provider support

### What Was Implemented
- Added provider-aware summon profiles for `codex`, `gemini`, and `kimi` while preserving legacy Codex command and snapshot compatibility.
- Added persistent, cancellable ACP planner sessions for Gemini CLI and Kimi CLI with strict model/thinking validation, denied tool permissions, bounded timeouts, and the existing Minecraft decision schema.
- Isolated each Kimi NPC process with `KIMI_MODEL_THINKING_EFFORT`, supporting exact K3 `low`, `high`, and `max` settings without copying OAuth credentials.
- Added provider-aware bridge catalogs, registry persistence, entity synchronization, command routing, and Gemini/Kimi-themed skin variants.

### Files Modified
- `coordinator/src/acp-transport.mjs`, `coordinator/src/acp-service.mjs`, `coordinator/src/provider-service.mjs` — ACP transport, provider sessions, and backend routing.
- `coordinator/src/agent-registry.mjs`, `coordinator/src/agent-planner.mjs`, `coordinator/src/codex-service.mjs`, `coordinator/src/dynamic-main.mjs`, `coordinator/src/protocol-v2.mjs`, `coordinator/config/dynamic-agents.json` — provider-aware persistence, planning, configuration, and wire protocol.
- `coordinator/test/acp-service.test.mjs`, `coordinator/test/provider-service.test.mjs`, `coordinator/test/agent-registry.test.mjs` — provider lifecycle, cancellation, isolation, and legacy migration coverage.
- `src/main/java/dev/agaminggod/arenaagents/agent/**`, `src/main/java/dev/agaminggod/arenaagents/server/**`, `src/client/java/dev/agaminggod/arenaagents/client/render/**` — provider profile storage, commands, bridge validation, entity sync, and rendering.
- `scripts/generate-agent-skins.mjs`, `src/main/resources/assets/arenaagents/textures/entity/**` — deterministic Codex, Gemini, and Kimi texture generation.
- `README.md`, `runtime/README.md`, `PROJECT_LOG.md` — commands, runtime topology, provider limits, and verification notes.

### Verification Evidence
- Test-first red checks failed on the missing ACP modules, absent provider migration, missing Java profile field, missing recovery context, and the slash-bearing `kimi-code/k3` command token before their implementations were added.
- Full automated verification passed with 4,722 Java/Fabric protocol and bridge assertions plus 79 Node coordinator tests, with zero failures, skips, or cancellations.
- Live no-prompt ACP checks created Kimi K3 sessions at `low`, `high`, and `max` using the existing login. Gemini ACP initialization succeeded, while `session/new` returned the installed CLI's individual-account migration error and was surfaced as `PROVIDER_UNAVAILABLE`.
- A real headless Minecraft server and dynamic coordinator accepted unquoted `/codex summon kimi kimi-code/k3 high KimiCheck2` and `/codex summon gemini auto high GeminiCheck2`. One real Kimi goal completed from `STARTING` to `IDLE` at revision 2; both agents were removed and the server exited with code 0.
- Runtime preparation rebuilt and copied the provider-enabled JAR into the isolated server and both official-launcher game directories while re-verifying the existing copied world.

### Assumptions Made (flag these for review)
- Gemini model `auto` means retain the CLI session's configured model; an explicit Gemini model must be advertised by the ACP session before it is accepted.
- Kimi K3 exposes `low`, `high`, and `max`; the two older Kimi coding aliases are conservatively limited to `high` because the installed configuration does not advertise exact effort support for them.

### Known Issues / Deferred
- Gemini CLI 0.44.1 initializes ACP locally but this machine's individual Code Assist account rejects `session/new` and directs the user to Antigravity. The failure is isolated to Gemini agents.
- Provider-themed textures compile and package correctly, but final visual inspection inside a rendered Minecraft client remains deferred.
- The copied source world still logs pre-existing missing Axiom game-rule keys during load; Minecraft continues to `Done` and this is unrelated to the provider integration.

### Suggested Next Steps
- Configure a Gemini ACP-compatible account or gateway, then repeat the no-prompt session check and one bounded in-world goal.
- Perform the final client-side visual pass for the 12 provider skin variants.

## 2026-07-26 — Arena Agents native control GUI and logic audit

### What Was Implemented
- Added a remappable `G` hotkey that opens a native Minecraft control screen without depending on the optional legacy client bridge.
- Added server-authoritative snapshots, bounded in-world polling, stale-response rejection, disconnect cleanup, permission-aware controls, agent selection and status, provider/model/thinking configuration, optional names, summon, start, queue, steer, stop, resume, respawn, refresh, and confirmed removal.
- Added Codex, Gemini, and Kimi presets while keeping model entry editable.
- Fixed same-agent coordinator ordering so goal control and action results share one chain while cancellation remains immediate.
- Fixed queued-goal promotion to remove only the exact queued head and fail closed on mismatches.
- Contained malformed legacy client configuration and bridge startup failures so they cannot crash the Minecraft client.
- Added persisted dimension/chunk recovery anchors and restores chunk tickets in the correct dimension before entity lookup.
- Added bounded provider-process termination with `SIGTERM` to `SIGKILL` escalation and automatic teardown on request timeout for Codex, Gemini, and Kimi.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/control/` — immutable GUI contracts, catalog, command construction, selection, snapshot codec/store, and payloads.
- `src/main/java/dev/agaminggod/arenaagents/server/AgentControlSync.java` — permission-aware server snapshot synchronization.
- `src/client/java/dev/agaminggod/arenaagents/client/control/AgentControlClient.java` — hotkey, networking, polling, command dispatch, and disconnect lifecycle.
- `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java` — native agent management screen and removal confirmation.
- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java` and `src/main/java/dev/agaminggod/arenaagents/ArenaAgents.java` — robust bootstrap and payload registration.
- `src/main/resources/assets/arenaagents/lang/en_us.json` — control-screen translations.
- `coordinator/src/dynamic-main.mjs` and `coordinator/src/agent-registry.mjs` — deterministic per-agent ordering and exact queue promotion.
- `src/main/java/dev/agaminggod/arenaagents/agent/` and `server/CodexAgentManager.java` — backward-compatible persisted entity location and recovery targets.
- `coordinator/src/child-process-lifecycle.mjs`, `acp-transport.mjs`, and `codex-app-server.mjs` — bounded provider teardown and timeout cleanup.
- `src/test/java/dev/agaminggod/arenaagents/` and `coordinator/test/` — regression and verification coverage.
- `docs/plans/2026-07-26-agent-control-gui.md` and `docs/plans/task-4a` through `task-4d` reports — implementation and audit evidence.

### Verification Evidence
- `verifyCore` passed with 4,769 assertions.
- Full coordinator suite passed 87 of 87 tests.
- `clean check build` completed successfully.
- Runtime preparation installed byte-identical JARs in the server and both isolated launcher profiles.
- A headless Fabric server reached `Done`, saved all dimensions, stopped cleanly, and left ports 25565 and 25570 closed.
- The packaged JAR contains all required control GUI, synchronization, snapshot, and language resources.

### Assumptions Made (flag these for review)
- `G` is the default hotkey and remains remappable through Minecraft controls.
- Provider presets mirror the currently supported coordinator profiles; the editable model field is the escape hatch for future models.
- Harmless GUI choices are session-local and no OAuth tokens, secrets, or CLI credentials are exposed to Minecraft.

### Known Issues / Deferred
- Rendered GUI layout, focus, mouse, and keyboard interaction remain untested by explicit phase boundary.
- The reused test world logs stale Axiom gamerule keys; Minecraft still reaches `Done` and the message is unrelated to Arena Agents.
- Legacy saved agents without location metadata need one natural rediscovery before their first dimension/chunk anchor can be persisted.

### Suggested Next Steps
- Perform the deferred visual/client interaction pass when authorized.
- When live testing is authorized, include restart recovery for unloaded Nether/End agents and cancellation against intentionally unresponsive provider fixtures.

## 2026-07-26 — Full launcher visual QA and standalone modpack release

### What Was Implemented
- Completed official-launcher visual QA in a disposable isolated world with Codex, Kimi, and Gemini NPCs.
- Fixed launcher bridge-secret wiring, custom command argument registration, quoted slash-bearing model parsing, GUI startup, collision-aware spawning, reconnect revision ownership, high-reasoning planner timeout budgets, bounded ACP decision diagnostics, and short-window GUI layout.
- Added a standalone installer and coordinator launcher that create an isolated profile, generate the bridge secret locally, copy both required mod JARs, validate Java/Node versions, and preserve the user's launcher profile.
- Packaged the release with a manifest, checksums, runtime coordinator, scripts, and no credentials, secrets, logs, or worlds.

### Files Modified
- `src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java` — height-aware compact layout verified in the live launcher window.
- `src/main/java/dev/agaminggod/arenaagents/server/AgentModelArgumentType.java`, `AgentSpawnPlacement.java`, and `CodexAgentCommands.java` — safe custom model parsing and collision-free multi-agent placement.
- `coordinator/src/dynamic-main.mjs`, `protocol-v2.mjs`, and `acp-service.mjs` — authoritative reconnect revisions, safe diagnostics, and provider error handling.
- `coordinator/config/dynamic-agents.json` — 120-second configurable planner budgets for high-reasoning turns.
- `scripts/install-launcher-profiles.ps1`, `install-distribution.ps1`, `start-pack-coordinator.ps1`, and `run-automated-verification.ps1` — secure profile installation, standalone startup, and Windows PowerShell 5.1 compatibility.
- `README.md`, `docs/plans/task-4e-full-visual-release-validation-report.md`, and `dist/arena-agents-modpack-0.1.0/` — release instructions, evidence, and package.

### Verification Evidence
- Codex agent `8f147d6b` completed a live safe goal to `IDLE`; queue, steer, and stop passed at revisions 10, 11, and 12.
- Kimi agent `44cf7c3c` completed a live safe goal to `IDLE`.
- Gemini agent `79972561` failed locally and clearly with the installed CLI's Code Assist ACP migration rejection; other agents and the world remained healthy.
- Codex, Kimi, and Gemini provider skins/name tags rendered simultaneously without entity overlap.
- The rebuilt `G` hotkey GUI displayed every action and footer control in the small launcher window after relaunch.
- Two consecutive full automated verification runs passed: 4,789 Java protocol/bridge assertions and 89 Node tests with zero failures, skips, or cancellations.

### Assumptions Made (flag these for review)
- Java 25, Node.js 22+, Fabric Loader 0.19.3, Minecraft 26.1.2, and authenticated provider CLIs are acceptable standalone package prerequisites.
- The standalone installer should fail closed instead of overwriting an existing mismatched `arena-agents-modpack` launcher profile.

### Known Issues / Deferred
- The installed Gemini CLI/account rejects individual Code Assist ACP sessions and requires the provider migration it reports; compatible Gemini ACP accounts remain supported.
- Container transfer, recipe crafting, furnace transactions, and forced chunk tickets remain intentionally fail-closed pending validated Minecraft 26.1.2 adapters.
- Simultaneous authenticated legacy two-player arena testing still requires a second licensed Minecraft Java account.

### Suggested Next Steps
- Use `dist/arena-agents-modpack-0.1.0.zip` for installation on another machine and repeat the bounded smoke test with that machine's provider logins.

## 2026-07-26 — Per-agent workspace isolation and planner resilience

### What Was Implemented
- Added stable provider/agent-scoped working directories and wired them into Codex thread cwd plus Gemini/Kimi process and ACP session cwd.
- Added one configurable, bounded corrective retry for malformed planner decisions; terminal world-action failures remain authoritative and are not replayed.
- Probed Gemini CLI 0.44.1 headless JSON mode as an ACP fallback and confirmed the installed individual Code Assist account rejects both transports at the same eligibility gate.

### Files Modified
- `coordinator/src/agent-workspace.mjs` and `coordinator/test/agent-workspace.test.mjs` — safe directory creation, traversal rejection, stability, and provider/agent isolation.
- `coordinator/src/agent-planner.mjs` and `coordinator/test/agent-planner.test.mjs` — bounded malformed-decision correction and exhaustion behavior.
- `coordinator/src/codex-service.mjs`, `acp-service.mjs`, `dynamic-main.mjs`, and related tests/config — exact cwd propagation and configurable retry wiring.
- `README.md`, `runtime/README.md`, and `docs/plans/task-4e-full-visual-release-validation-report.md` — updated isolation, retry, and Gemini compatibility evidence.

### Verification Evidence
- Full Gradle/Fabric build passed with 4,789 protocol and bridge assertions.
- Full coordinator suite passed 95 tests with zero failures, skips, or cancellations.
- Targeted coverage verifies distinct workspaces, traversal rejection, Codex thread cwd, ACP process/session cwd, retry success, and retry exhaustion.
- Live headless Kimi agent `0d543377` completed to `IDLE` with `goal=none` and created `runtime/agent-workspaces/kimi/0d543377-c204-431e-9750-af28b107ca84`; the agent was removed and ports 25565/25570 were closed afterward.

### Assumptions Made (flag these for review)
- Persistent agent workspaces should remain after NPC removal so user-created files are never deleted implicitly.
- One corrective retry is the safest balance between transient formatting recovery and avoiding unbounded model loops.

### Known Issues / Deferred
- This machine's Gemini account remains externally ineligible for both ACP and headless Gemini CLI use; changing transport cannot bypass the provider-side restriction.
- Container transfer, recipe crafting, furnace transactions, and forced chunk tickets remain outside the planner allowlist until version-validated transactional adapters exist.

### Suggested Next Steps
- Re-authenticate Gemini with an ACP-compatible account, then repeat one bounded provider goal.
- Keep the default retry limit at one unless production traces show a justified need for a higher bounded value.
## 2026-07-26 — Antigravity-backed Gemini agents
### What Was Implemented
- Replaced the active Gemini ACP route with Antigravity CLI while preserving the `gemini` provider identity, skins, commands, GUI controls, name tags, queues, stop/steer behavior, and provider-scoped workspaces.
- Added exact model/thinking mapping for Gemini 3.1 Pro, Gemini 3.6 Flash, and Gemini 3.5 Flash; each decision uses one cancellable sandboxed `agy --print` process in the NPC's UUID-scoped directory.
- Added bounded stdout/stderr capture, spawn/nonzero-exit diagnostics, timeout and process-tree cleanup, cancellation, stale-revision rejection, profile conflict checks, and a safe Windows prompt-size boundary.
- Tightened the planner output contract so models use `action.type`, and added focused Antigravity tests plus a reusable live headless Fabric smoke harness.
- Verified the authenticated local Antigravity CLI, exact `gemini-3.1-pro-low` routing through a real NPC lifecycle, the default `gemini-3.1-pro-high` planner profile, return to `IDLE`, isolated workspace creation, removal, and clean port/process shutdown.

### Files Modified
- `coordinator/src/antigravity-service.mjs` — Antigravity provider service and per-turn process lifecycle.
- `coordinator/src/dynamic-main.mjs` — routes Minecraft Gemini profiles to Antigravity instead of ACP.
- `coordinator/src/prompts.mjs` — explicit strict action key contract.
- `coordinator/config/dynamic-agents.json` — Antigravity executable and Gemini model/thinking catalog.
- `coordinator/test/antigravity-service.test.mjs` — process, profile, parsing, timeout, cancellation, output, and workspace coverage.
- `coordinator/test/dynamic-main.test.mjs` — Gemini catalog normalization assertions.
- `src/main/java/dev/agaminggod/arenaagents/control/AgentControlCatalog.java` — GUI Gemini presets.
- `src/test/java/dev/agaminggod/arenaagents/control/AgentControlVerification.java` — GUI catalog verification.
- `scripts/run-antigravity-headless-smoke.ps1` — reproducible headless Fabric/Antigravity end-to-end test.
- `README.md`, `runtime/README.md`, and `dist/arena-agents-modpack-0.1.0/**` — operator guidance and synchronized release artifacts.

### Assumptions Made (flag these for review)
- The Minecraft-facing provider remains named `gemini`, while Antigravity is only the execution backend.
- The recommended Gemini default is `gemini-3.1-pro` with `high` thinking.
- Antigravity's installed effort-specific model IDs are the authoritative way to apply the selected thinking level.

### Known Issues / Deferred
- Antigravity print mode exposes no stdin or prompt-file option. Windows prompts above 24,000 characters therefore fail closed with `PROMPT_TOO_LARGE` instead of risking command-line truncation.
- This change received automated GUI catalog coverage and headless Minecraft verification; no new visual skin/layout pass was needed because provider identity and assets were unchanged.
- The copied test world still logs historical missing Axiom game-rule keys at startup; this does not affect Arena Agents.

### Suggested Next Steps
- Re-run `scripts/run-antigravity-headless-smoke.ps1` after Antigravity model catalog updates.
- If Antigravity adds stdin or a structured-output API, replace the Windows prompt-size boundary with that transport.

## 2026-10-06 — Claude provider replaces Kimi and Cursor

### What Was Implemented
- Added the `claude` provider (`coordinator/src/claude-service.mjs`): one Claude Code CLI process per native agent in stream-json mode, reusing the operator's existing `claude` login. Claude Opus 5.5, Sonnet 5.5, and Fable 5.1 are offered at Low, Medium, and High reasoning only; no fast tier.
- Claude agents receive exactly what Codex agents receive: the shared `runtime/minecraft-agent/workspace` directory, the same AGENTS.md and minecraft-control skill as the system prompt, and the same native Minecraft tools served through a per-agent loopback MCP endpoint (`coordinator/src/claude-tool-server.mjs`) with a bearer token per body.
- Claude Code isolation: `--setting-sources ""`, `--strict-mcp-config`, `--restricted`, `--tools Read,Glob,Grep`, `--permission-mode dontAsk`, auto-memory and slash commands disabled, no session persistence. User CLAUDE.md, hooks, plugins, and other MCP servers never reach an agent.
- Steering during a Claude turn rides along with the next tool result instead of Claude Code's own mid-turn queue (which can spill into a second turn); a steer that never reaches a tool boundary is rejected so the coordinator defers it to the next turn.
- Account-level refusals from Claude Code (usage credits, access, login) map to non-retryable `MODEL_UNAVAILABLE` / `AUTHENTICATION_REQUIRED` codes.
- Removed the Kimi and Cursor providers end to end: services, catalog discovery, config sections, environment allowlists, headless/latency matrices, Java catalog, commands, Skit Director entries, colors, skins, locator icons, and tests. Saved worlds that still contain Kimi or Cursor agents drop those agents on load instead of failing; installed configs with `kimi`/`cursor` sections are ignored.
- Claude agents wear the Claude brand skin; the visual manifest now declares codex, gemini, and claude with new `claude_*` family textures, transport codes `a00`–`a33`, and regenerated locator-bar sprites.
- Generated default names no longer end on a separator at the 16-character limit (`Claude_Sonnet_5`, not `Claude_Sonnet_5_`).

### Files Modified
- `coordinator/src/claude-service.mjs`, `coordinator/src/claude-tool-server.mjs`, `coordinator/test/claude-service.test.mjs` — new provider, MCP tool endpoint, and coverage with a fake Claude Code process.
- `coordinator/src/provider-identity.mjs`, `provider-environment.mjs`, `provider-service.mjs`, `agent-planner.mjs`, `dynamic-main.mjs`, `codex-service.mjs` (exported instruction builders) — provider set, native-tool providers, config, and wiring.
- `coordinator/src/acp-transport.mjs`, `provider-catalog-discovery.mjs`, `headless-matrix.mjs`, `benchmark/*` — Kimi/Cursor removal; `acp-service.mjs` and `cursor-service.mjs` deleted.
- `coordinator/config/dynamic-agents.json`, `headless-provider-matrix.json`, `latency-experiment-matrix.json` — Claude catalog and scenarios.
- `src/main/java/.../agent/*`, `control/*`, `server/CodexAgentCommands.java`, `server/AgentChatReporter.java`, `server/AgentVerboseChat.java`, `src/client/.../gui/ConsoleTheme.java`, `gui/SkitDirectorScreen.java` — provider lists, model names, catalog, commands, colors, retired-provider load handling.
- `src/main/resources/assets/arenaagents/identity/agent_visual_manifest.json`, `textures/entity/*`, `textures/gui/sprites/hud/locator_bar_dot/agent/*`, `waypoint_style/agent/*` — regenerated identity assets.
- `scripts/generate-agent-skins.mjs`, `scripts/GenerateAgentWaypointIcons.java`, `scripts/run-headless-provider-matrix.ps1`, `scripts/test-run-headless-provider-matrix.ps1` — generators and preflight.
- `README.md`, `runtime/README.md`, `docs/agent-logo-skins.md` — operator guidance.

### Assumptions Made (flag these for review)
- Claude model slugs are `claude-opus-5-5`, `claude-sonnet-5-5`, and `claude-fable-5-1`; Opus and Sonnet were exercised live.
- `claude` on PATH is the Claude Code CLI (`claude.exe`), the same way `codex` is resolved.
- The Skit Director and `/codex skit summon claude` now target the Claude provider; Gemini's Antigravity-hosted Claude 4.6 models remain under `gemini`.

### Known Issues / Deferred
- Fable 5.1 was refused on this account ("requires usage credits"); the agent stops with `MODEL_UNAVAILABLE` until credits are enabled.
- Claude Code does not report the effective reasoning effort; execution settings record it as a launch argument.
- No in-game session was run; verification covered the coordinator suite (1669 pass, one pre-existing Windows-SID sandbox failure), the Java core verification (15,318 assertions), the Gradle build, and live Claude turns against a fake world.

### Suggested Next Steps
- Run `scripts/run-headless-provider-matrix.ps1` with the three Claude scenarios once Fable credits are available.
- Consider `--include-partial-messages` for verbose streaming parity with Codex.

## 2026-10-06 — Agent POV spectating and takeover

### What Was Implemented
- `/spectator <agent>` and `/spectator exit`: the operator's camera binds to the agent's first-person view while the operator's own body stays where it is. The server streams the agent's vitals, hotbar, offhand, armor, XP, effects, open container contents and death state (`pov/` payloads); the client feeds them into an unregistered stand-in player that vanilla's HUD renders, mirrors container screens read-only, and shows an agent death screen. Several operators may spectate one agent.
- `/takeover <agent>` and `/takeover exit`: exclusive per agent. The agent's model is stopped through the existing lifecycle (`stop`/`resume`) behind a transient reservation (`AgentControlReservations`, folded into skit mode's `requireNormalControlAllowed` gate) so start, steer, resume, queue, skit placement and conversation wakes are refused while an operator owns the body. Operator inputs are captured client-side without moving the operator's body (keyboard, mouse look, hotbar, clicks) and applied server-side through an `InputOwner.OPERATOR` lease (priority 1000) on Carpet's action pack; attack clicks hit entities once per click with vanilla cooldown, held attack mines, use runs vanilla's main-then-off-hand loop, drop/swap/pick relay through the agent's own connection, container clicks relay into the agent's menu. Automatic respawn is suspended during a takeover; the operator decides from the death screen. On exit the agent resumes from the state it was left in and receives a takeover report as an operator DM.
- Exit rules: `/takeover exit`, operator disconnect/death/teleport/dimension change/lost permission, agent removal or dimension change (message tells the operator to re-run the command once in the same dimension), skit or scenario claim, server stop. Takeover only: the operator's body losing 4.0 HP (health plus absorption) or dying, via Fabric entity damage events.
- Operator view follows the agent: `ChunkMap`, `PlayerChunkSender`, `PlayerList.broadcast`, `ServerLevel.sendParticles`/`destroyBlockProgress`/`explode` read a per-operator anchor (`PovViewAnchors`) so chunks, entities, sounds and particles arrive around the agent while the body's own chunk tickets are untouched.
- `verifyPovMixins` Gradle task: loads every POV mixin target class through Fabric's Knot (26 classes) so a mis-targeted injector fails in CI instead of at first launch.
- Also in this branch: the Claude provider work (see the 2026-10-06 Claude entry) rebased onto main, and goal translation now runs on Luna for Codex agents and Sonnet 5.5 for Claude agents at medium effort.

### Files Modified
- `src/main/java/dev/agaminggod/arenaagents/pov/**` — payload records, codecs, `PovViewAnchors`, `OperatorBodyController` contracts.
- `src/main/java/dev/agaminggod/arenaagents/server/pov/**` — `PovSessionRuntime`, `PovSession`, `PovCommands`, `AgentControlReservations`, `PovStatePublisher`, `PovAgentSnapshot`, `PovTakeoverSummary`, `PovViewRedirect`, `CarpetOperatorBodyController`, `OperatorActionDispatcher`.
- `src/main/java/dev/agaminggod/arenaagents/mixin/*Pov*`, `AbstractContainerMenuAccessor`, `ServerGamePacketListenerImplAccessor` — server mixins.
- `src/client/java/dev/agaminggod/arenaagents/client/pov/**`, `client/mixin/*Pov*`, `MenuScreensInvoker` — client session, camera, HUD proxy, badge, input capture, mirrored screens, death screen.
- `server/CodexAgentServerRuntime.java`, `CodexAgentCommands.java`, `CodexAgentManager.java`, `SkitModeRuntime.java`, `AgentControlSync.java`, `conversation/ServerAgentConversationRouter.java`, `runtime/input/CarpetInputStateSink.java`, `InputOwner.java`, `client/ArenaAgentsClient.java`, `client/camera/CameraDirectorClient.java` — wiring and gates.
- `build.gradle`, `src/test/resources/pov-mixin-targets.txt`, `src/test/java/.../pov/*Verification.java` — verification.
- `README.md`, `docs/plans/2026-10-06-agent-pov-takeover.html` — operator docs and the plan.

### Assumptions Made (flag these for review)
- A second operator taking over an already taken-over agent is rejected; spectating never exits on body damage; creative operators may take over (the damage rule is inert for them); first person is forced while in POV.
- Cross-dimension POV is out of scope: the session ends when the agent changes dimension.
- The takeover report is delivered as an operator DM (existing path); no coordinator protocol change.

### Known Issues / Deferred
- Not yet run in a real client or server: camera binding, HUD rendering through the stand-in, mirrored screens, death screen, input feel and latency, chunk following, sounds, damage exit, respawn flow, summary delivery. Only dependency-free rules, codec round-trips, and mixin application under Fabric were verified.
- A paused, taken-over agent can still produce chat replies on the coordinator side (conversation-only turns are not suppressed there).
- First-person hands are hidden in POV; merchant trade selection, anvil rename and recipe-book placement are not relayed; horse inventories are not mirrored.
- During the 20 ticks between an agent's death and its body removal, the view anchor drops back to the body until respawn.
- The reservation is in-memory: a server crash mid-takeover leaves the agent PAUSED until `/codex resume`.

### Suggested Next Steps
- Live client QA of `/spectator` then `/takeover` (`docs/live-qa`), then tune movement feel (position prediction) and add first-person hands.

## 2026-10-06 — Launch-time provider CLI notices, /spectate rework with agent hands, ordered thinking selectors

### What Was Implemented
- Provider CLI health at agent launch (`coordinator/src/provider-cli-health.mjs`): when an agent is registered (fresh launch or re-registration after a coordinator reconnect) the coordinator checks, with the same launch resolution and child environment the agent process would use, that the provider CLI is installed (resolved executable exists or is found on PATH with PATHEXT), works (`--version` exits 0 within 15 s) and is signed in (`claude auth status --json` loggedIn, `codex login status`, `agy models`; an API-key environment variable counts as signed in). A failing check sends the new `agent_notice` protocol message (`PROVIDER_CLI_MISSING`, `PROVIDER_CLI_BROKEN`, `PROVIDER_CLI_UNAUTHENTICATED`) with a player-facing fix hint; the server always broadcasts it to chat as `[Agent] ...` in red, logs it, and replays the latest notice per agent to players who join later. One notice per agent per connection; results are cached (20 s, 60 s when healthy); the three probes also run once per coordinator process and print `[provider-cli]` console lines. Codex and Antigravity launch resolution now also finds npm shim entrypoints and `%LOCALAPPDATA%gyingy.exe`; Claude resolution (`resolveClaudeLaunch`) finds `claude.exe`, the npm `@anthropic-ai/claude-code` entrypoint or `%USERPROFILE%\.localin\claude.exe`, so an npm-installed Claude Code works on Windows.
- `/spectate` replaces `/spectator` and the vanilla `/spectate` (the vanilla literal is removed from the Brigadier root through `CommandNodeAccessor` before ours registers). Grammar for `/spectate` and `/takeover` (and the `/codex spectate|takeover` aliases): `<agent>` starts, `<agent> start` starts, `<agent> stop|exit` ends that agent's session, bare `exit|stop` ends any session. Chat messages dropped the vanilla disclaimer.
- First-person hands in a view are the agent's (`ItemInHandRendererPovMixin`, `PovHands`): every operator read inside `ItemInHandRenderer.renderHandsWithItems`, its arm/map helpers and `tick` is answered by the agent's in-level entity (swing, item in use, held items, skin, slim/wide arm, sleeves, invisibility, smoothed look lag), with the HUD stand-in as fallback for held items when the signal is lost. Hooking the renderer itself also covers Iris, whose HandRenderer bypasses `GameRenderer.renderItemInHand`. No hand is drawn while the agent is unavailable or dead.
- `MultiPlayerGameMode.getPlayerMode()` reports the viewed agent's game mode during a session, so Axiom's creative tool slot (and any other third-party game-mode gate) hides while a creative operator views a survival agent; the F3+F4 switcher reads the operator's real mode through `GameModeSwitcherScreenPovMixin`.
- The top-left POV badge is gone. "Waiting for <agent>" / "Signal lost - waiting for <agent>" and the takeover "Your body took damage" hint show briefly on the vanilla action bar instead.
- Thinking depth and speed selectors: `AgentControlModelOption` sorts efforts on one ascending scale (none, minimal, low, medium, high, xhigh, max, ultra; unknown values last) and tiers as priority then fast, for both the built-in and the coordinator-provided catalog. Menus open at the lowest depth and Normal speed every time (preferences keep provider and model only); the ranked `ConsoleCycleButton` steps up on the right, down on the left, stops at both ends and mutes the dead arrow.

### Files Modified
- `coordinator/src/provider-cli-health.mjs` (new), `provider-environment.mjs`, `claude-service.mjs`, `antigravity-service.mjs`, `codex-app-server.mjs`, `dynamic-main.mjs`, `protocol-v2.mjs`, `benchmark/latency-runner*.mjs`; tests `provider-cli-health.test.mjs` (new), `dynamic-main.test.mjs`, `protocol-v2.test.mjs`, `latency-runner*.test.mjs`.
- `server/bridge/MultiplexedServerBridge.java` (`agent_notice`, replay on join), `server/AgentChatReporter.java` (`notice`), `server/CodexAgentServerRuntime.java`; `server/pov/PovCommands.java`, `PovExitReason.java`, `PovSessionRuntime.java`, `mixin/CommandNodeAccessor.java` (new).
- Client: `mixin/ItemInHandRendererPovMixin.java` (new), `mixin/MultiPlayerGameModePovMixin.java`, `mixin/MultiPlayerGameModeAccessor.java` (new), `mixin/GameModeSwitcherScreenPovMixin.java` (new), `mixin/GuiPovMixin.java`, `mixin/GameRendererPovMixin.java`, `pov/PovHands.java` (new), `pov/PovClient.java`, `pov/PovBodyMonitor.java`, `pov/PovBadge.java` (deleted), `arenaagents.client.mixins.json`, `arenaagents.mixins.json`.
- Selectors: `control/AgentControlModelOption.java`, `control/AgentControlCatalog.java`, `client/gui/widget/ConsoleCycleButton.java`, `client/gui/AgentControlScreen.java`, `client/gui/scenario/*`, `client/control/AgentControlClient.java`.
- Verification: `AgentNoticeVerification` (new), `BoundedServerTaskQueueVerification`, `PovSessionVerification`, `PovClientStateVerification`, `AgentControlVerification`, `ConsoleThemeVerification`, `ScenarioSetupStateVerification`, `AgentClientPresentationVerification`, `pov-mixin-targets.txt` (29 targets), `README.md`.

### Assumptions Made (flag these for review)
- The vanilla `/spectate` (spectator-mode players attaching to an entity) is intentionally unavailable on servers running the mod; `/spectate` is ours now.
- `/codex summon` command defaults (xhigh, fast) and the coordinator launch profile are unchanged; the Low/Normal defaults apply to the menus.
- `getPlayerMode()` is overridden globally during a view; its vanilla callers were checked (HUD, hand gate, block outline, quick-play log, tutorial toasts) and only the F3+F4 switcher is exempted.
- The startup `[provider-cli]` console lines run after the first bridge handshake, not inside `start()`, so a first-time Codex desktop-CLI cache copy cannot eat into the handshake window.

### Known Issues / Deferred
- Not yet verified in a running client with Iris and Axiom: agent hands, Axiom tool-slot hiding, action-bar messages, vanilla `/spectate` removal from client suggestions. Proven by compile, `verifyPovMixins` (29 targets), `verifyCore` and a headless server run.
- The item-use hand dip (`ItemInHandRenderer.itemUsed`) and the dip after a hit are local-only in vanilla (the client never sees those events for a remote player) and are not reproduced for the agent; entering or leaving a view plays the vanilla item-swap dip. A map held by the agent renders blank because map data only goes to its holder.
- Three independent judges compared this hands implementation with a second one written from the same brief; the merged version keeps vanilla's hand-selection logic (wrapped reads) and took the other's bob seeding at camera bind and reset on signal loss.
- Antigravity has no auth subcommand, so its sign-in check relies on `agy models` output.
- Late-join replay of notices happens on player join only; `/codex status` does not list them.

### Suggested Next Steps
- Desktop pass with Iris + Axiom: `/spectate <agent>`, hotbar/offhand, hands while the agent mines, eats and draws a bow; `/takeover` damage hint; then install Claude Code on the Desktop (`npm install -g @anthropic-ai/claude-code`, `claude auth login`) and confirm the launch notice disappears.
