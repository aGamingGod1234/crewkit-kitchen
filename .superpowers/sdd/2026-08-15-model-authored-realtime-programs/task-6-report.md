# Task 6 report: command provenance and primitive-only server execution

## Delivered

- Program runtime now emits the canonical V2 action wire shape: flattened `actionType` and `arguments`, with the selected profile and immutable engine provenance translated to `{provider, model, reasoningEffort, serviceTier, programId, programVersion, sourceStepId, eventSequence}`.
- V2 accepts only canonical action command keys. It rejects `command`, `commandId`, `type`, and nested `action` aliases, custom/inherited payload objects, duplicate JSON keys, coercible strings/numbers, and any extra provenance key.
- Java bridge parsing now detects duplicate object keys at every nesting level. Before execution it validates the exact profile/revision/provenance tuple and binds each accepted agent/action ID to its immutable program tuple in a bounded bridge-lifetime ledger. Replays and changed provenance have stable failure codes.
- Every safely decodable rejected action command is returned to the coordinator as a correlated terminal `action_result` failure. The model program executor has a dedicated primitive-only entry point; the legacy controller-capable entry point is separate.
- Coordinator bridge-send failures now remove the active external mapping and inject a stable `FAILED` result into the current engine/facts, so backpressure cannot wedge a model program.
- Strict payload handling rejects `arguments.type` and all reserved outer fields before schema spread; every validated array is a dense native array with no accessors, symbols, holes, or custom keys. Java uses exact `BigDecimal.longValueExact` identities, and replay binding is bidirectional across accepted and terminal actions.
- Correlated Java rejection messages are code-point bounded to `ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH` with a stable nonblank fallback before encoding, so oversized malformed inputs cannot overflow a terminal `action_result` frame.
- Java rejection clamping now follows the Node/Java wire contract's UTF-16 `String.length` limit and backs off at a surrogate boundary, preserving non-BMP messages without emitting invalid UTF-16.
- Included the exact source/test dependency closure needed by the already-committed executor and bridge implementation: agent identity/profile and control catalog model sources, protocol action/schema sources, observation and physical controller sources, plus their focused verification classes.

## Verification

- `node --test coordinator/test/protocol-v2.test.mjs coordinator/test/program-runtime-manager.test.mjs`: 26/26 passed.
- `JAVA_HOME=<Temurin 25> .\\gradlew.bat verifyCore`: passed with 5,939 assertions in the dirty workspace.
- `git archive HEAD` clean export with Java 25 `verifyCore`: passed with 5,530 assertions from commit `79a6b6b`.
- `node --test coordinator/test/schema.test.mjs coordinator/test/protocol-v2.test.mjs coordinator/test/program-runtime-manager.test.mjs`: 35/35 passed.
- Latest dirty Java 25 `verifyCore`: 5,941 assertions passed; clean archive from `27789af`: 5,532 assertions passed.

## Risk

- The dirty worktree contains unrelated feature work; this task stages only the provenance changes and the compile/test dependency closure listed above.
- Legacy V1 fake-bridge fixtures remain outside this V2-only contract and are not a complete-suite gate for this task.

## Commit

- `eb9ef3d fix: harden program action provenance` (57 files: provenance hardening plus the exact Java/client/test dependency closure required for a clean archive build).
- `79a6b6b fix: close provenance rejection gaps` (manager recovery, strict payload shape/arrays, exact correlated Java failures, reverse replay binding, and registered verification classes).
- `27789af fix: bound rejection payloads and schema arrays` (bounded correlated failures and strict shared-schema arrays). This report update is intentionally uncommitted.
- `72f1174 fix: preserve UTF-16 rejection boundaries`; focused Node remained 35/35 and dirty Java 25 `verifyCore` passed 5,943 assertions. This report remains uncommitted.
