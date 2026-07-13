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
