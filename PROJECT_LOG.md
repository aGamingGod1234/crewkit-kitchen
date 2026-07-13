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
