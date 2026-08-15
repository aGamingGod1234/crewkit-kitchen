# Task 6 report: command provenance and primitive-only server execution

## Delivered

- Program runtime now emits the canonical V2 action wire shape: flattened `actionType` and `arguments`, with the selected profile and immutable engine provenance translated to `{provider, model, reasoningEffort, serviceTier, programId, programVersion, sourceStepId, eventSequence}`.
- V2 accepts only canonical action command keys. It rejects `command`, `commandId`, `type`, and nested `action` aliases, custom/inherited payload objects, duplicate JSON keys, coercible strings/numbers, and any extra provenance key.
- Java bridge parsing now detects duplicate object keys at every nesting level. Before execution it validates the exact profile/revision/provenance tuple and binds each accepted agent/action ID to its immutable program tuple in a bounded bridge-lifetime ledger. Replays and changed provenance have stable failure codes.
- Every safely decodable rejected action command is returned to the coordinator as a correlated terminal `action_result` failure. The model program executor has a dedicated primitive-only entry point; the legacy controller-capable entry point is separate.
- Included the exact source/test dependency closure needed by the already-committed executor and bridge implementation: agent identity/profile and control catalog model sources, protocol action/schema sources, observation and physical controller sources, plus their focused verification classes.

## Verification

- `node --test coordinator/test/jsonl.test.mjs coordinator/test/protocol-v2.test.mjs coordinator/test/program-runtime-manager.test.mjs coordinator/test/dynamic-main.test.mjs`: 32/32 passed.
- `JAVA_HOME=<Temurin 25> .\\gradlew.bat verifyCore`: passed with 6,091 assertions in the dirty workspace.
- `git archive HEAD` clean export with Java 25 `verifyCore`: passed with 5,495 assertions from commit `eb9ef3d`.

## Risk

- The dirty worktree contains unrelated feature work; this task stages only the provenance changes and the compile/test dependency closure listed above.
- Legacy V1 fake-bridge fixtures remain outside this V2-only contract and are not a complete-suite gate for this task.

## Commit

- `eb9ef3d fix: harden program action provenance` (57 files: provenance hardening plus the exact Java/client/test dependency closure required for a clean archive build).
