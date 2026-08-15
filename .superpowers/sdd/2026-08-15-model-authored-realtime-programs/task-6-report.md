# Task 6 report: command provenance and primitive-only server execution

## Delivered

- Added immutable, bounded model-program provenance on the coordinator action-command wire shape.
- Added the `ActionProvenance` Java record and made it mandatory on `ServerActionRequest`.
- The server bridge decodes and validates exact nested provenance, rejects stale goal/profile provenance before executor submission, and permits only the Task 3 physical primitive set.
- Added coverage for missing, nonblank, safe-integer, deep-immutable provenance and the bridge rejection of `fight_target`.

## Verification

- `node --test coordinator/test/schema.test.mjs coordinator/test/protocol-v2.test.mjs`: 26/26 passed.
- `JAVA_HOME=<Temurin 25> .\\gradlew.bat verifyCore`: passed with 6,081 assertions.
- `npm --prefix coordinator test`: 267/269 passed. The two existing V1 fake-bridge end-to-end fixtures timed out because they expect `message.command`; they are outside Task 6 ownership and do not exercise the V2 coordinator/server path.

## Risk

- The legacy V1 end-to-end fixture must be migrated separately if that suite is required as a complete green gate.
