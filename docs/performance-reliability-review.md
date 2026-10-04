# Performance and reliability review outcome

This change combines the performance, token-efficiency and long-goal reliability work on the review base `d087c3d5e4ce9d96c415fdf216f16feed172d78a`.

## What changed

- Model context is bound to the actual provider session and acknowledged only after delivery. Replacement sessions receive the retained baseline. Nested tool results, compaction and failed delivery preserve context continuity. Delta mode remains off by default.
- Unread messages survive coordinator restarts with lifecycle and delivery fencing. Memory uses owner partitions to avoid the old aggregate-history limit. Notebook and fact traversal preserve retained evidence.
- Native and scripted decisions retain current goals, complete steering and exact spatial and survival constraints. Lease retirement, stale completions, provider recovery, bridge delivery, navigation, perception and voice cleanup have focused regression coverage.
- Benchmark tools report actual queue/turn/tool measurements and honest unknowns. Paired-run deadlines and process cleanup, package verification, installer rollback and offline server policy have been corrected.
- Final PR closure preserves specific recovery reasons in session metadata without mixing them into factual recovery instructions. Synthetic test artifacts use temporary directories instead of dated review folders.

## Verification boundary

The final integrated Windows Node 22.23.2 suite passed **2,386 tests with zero failures and three skips** after the recovery-metadata and test-portability corrections. The focused service/planner checks passed 95/95; the four portability test files passed 267/267; five affected cases also passed in an isolated fixture without the audit-output directory. These focused counts overlap the full suite.

Fresh offline Java verification passed core, Director, voice, camera-mixin and map checks. Both JARs built, and the main JAR was rebuilt after the final metadata correction. Embedded coordinator verification compared all 114 runtime files against source and extraction, parsed 91 packaged modules and exercised the packaged entrypoint, fact encoding, queue and persisted inbox. Those are backend, controlled-fixture and build checks, not an installed-game result.

Run the coordinator suite using Node 22 or newer:

```sh
cd coordinator
npm ci --ignore-scripts --omit=dev
npm test
```

Java verification requires Java 25 and the existing Gradle build. The checked tasks were `verifyEntrypoints verifyCore verifyDirector verifyCameraMixin verifyMapBlockStateCatalog verifyMapStructureDataFixer :voice-addon:verifyVoiceAddon jar :voice-addon:jar`. The local build reused the existing offline dependency cache. The repository Reliability workflow performs its own clean build, package and platform checks.

## Compatibility and remaining limits

- Memory v2 requires the new reader. Back up both the manifest and owner files before migration. The partitioned store does not promise an atomic all-owner snapshot or downgrade compatibility; an interrupted multi-owner operation can expose an unacknowledged update.
- Expanded control snapshots require the updated server and client together. Retaining complete steering can increase context size.
- Two UI corrections are source/compile verified, without runtime interaction. Two POSIX process-group checks are skipped on Windows, and the opt-in Windows speech integration was not enabled.
- No live provider/game evaluation was added after the initial pilot budget. Diamond-pickaxe/endgame completion, installed UI behavior and improvements in token cost or model quality remain unproven. The earlier replay result of 2.079% fewer response bytes under optimistic continuity (zero under conservative continuity) is not a token or dollar measurement.
- The existing 900-second native cap and selected model/profile defaults remain. Healthy-thread reuse across cap retirement needs evidence before changing lifecycle behavior.
- Full-run model timing attribution remains unknown without producer capture-boundary evidence. Pending filesystem mutations remain serialized to prevent late writes from corrupting successors. Spawn searches still load chunks synchronously. Live task views retain cached-plus-fresh full bodies because clients do not acknowledge retained content.

## Finding dispositions

Of 144 individually verified findings, 126 have scoped fixes, two have UI source fixes without runtime proof, five are partial and eleven retain explicit qualifications. These are dispositions of the reported scope, not 144 independent live-game validations. Detailed synthetic failures, controls and audit logs are retained locally; generated binaries, provider captures and machine-specific audit files are excluded from the repository.

| ID | Original finding | Disposition | Result and boundary |
| --- | --- | --- | --- |
| f001 | a01-F1 | fixed-scoped | Prepared context and exact accepted receipts integrated; independent coordinator acceptance and full Node pass. |
| f002 | a01-F2 | fixed-scoped | Captured fact/conversation revisions acknowledged only after exact-session delivery; independent in-flight controls pass. |
| f003 | a02-F1 | fixed-scoped | Validated immutable observation facts use structural budgets derived from existing fact-byte envelope; arbitrary caller/authored canonical limits unchanged. Full fact values, geometry and aliases retained. |
| f004 | a02-F2 | fixed-scoped | Generated preflight IDs begin with an alphanumeric character; existing workspace path validation is preserved. |
| f005 | a02-F3 | fixed-scoped | Parser parameter shadowing integrated with interpreter behavior; independent compile/runtime controls and full Node pass. |
| f006 | a03-F1 | qualified | Intentional classic freshness fence; no change that admits stale decisions. Live continuation remains unverified. |
| f007 | a03-F2 | fixed-scoped | Benchmark second-pass bounds use the shared strict operational-token validator. Known numeric/null token objects survive both row locations and actual JSONL; malformed, accessor and proxy values and credentials remain redacted. Fresh real planner/collector two-turn integration passed with recorder-disabled control. |
| f008 | a04-F1 | fixed-scoped | Acquisition ownership is installed before the deadline race, retirement propagates an AbortSignal, late acquired adapters stop once, and unresolved acquisition is unclean and halts the matrix. |
| f009 | a04-F2 | fixed-scoped | One retained stop promise tracks pending/completed/rejected state. Background stop stays bounded but cannot certify cleanup. Original trial failure is retained, cleanup errors remain explicit, and subsequent repetitions do not start while unclean. |
| f010 | a05-F2 | partial | Rotated logs and observed sums repaired. Full-run timing attribution still needs producer capture-boundary evidence. |
| f011 | a06-F1 | fixed-scoped | Real paired CLI and PowerShell deadline/cleanup handoff integrated; full-budget inert AB/BA fixtures pass. |
| f012 | a07-F1 | fixed-scoped | Retired-child termination failure is diagnostic and cannot invalidate a successor; live-current failure still invalidates |
| f013 | a07-F2 | fixed-scoped | Fixed scheduler descriptors configure execution, conflicts fail early, unsupported adaptive descriptors require an explicit implementation, and passing custom runs must provide matching effective per-trial scheduler evidence. Manifest default reserve now agrees with executed zero reserve. |
| f014 | a07-F3 | fixed-scoped | Protocol audit queue now waits on its own setup; actual absent-directory, bounded backlog and late-setup acceptance pass. |
| f015 | a07-F4 | fixed-scoped | Task 9 CLI derives exit status from the completed report after writing artifacts and stdout. |
| f016 | a08-F1 | fixed-scoped | Native health producer and Java preflight agree; full-class native/legacy/missing/wrong-profile controls pass. |
| f017 | a08-F2 | fixed-scoped | Fresh same-chunk post-settlement counter preserved for next observed interval; completed-turn missing attribution remains |
| f018 | a09-F1 | fixed-scoped | Independent readiness ownership regression red/green; blocked historical receipt probe not rerun. Actual receipt-cycle runtime remains unverified. |
| f019 | a09-F2 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f020 | a10-F1 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f021 | a10-F2 | fixed-scoped | Recent-window join and successful-terminal precheck avoid overflow rescans for covered/no-witness cases; older candidates join in batches of 256 with full retained generation coverage. Expiry marks coverage incomplete and stops recovery. |
| f022 | a11-F1 | fixed-scoped | RCON serializes requests, aggregates same-ID packets to a distinct type-0 completion response, applies aggregate UTF-8 byte bounds, exposes complete/truncated, and headless runner rejects incomplete responses. |
| f023 | a11-F2 | fixed-scoped | Shared evaluator accepts requested Codex fast/reported priority only, preserves raw requested/effective settings and rejects reverse/other-provider/real field contradictions. |
| f024 | a12-F1 | fixed-scoped | Individually oversized notebook receipts receive bounded historical identity/state summaries with explicit omittedFields; page offsets advance across that represented row. Storage stays complete. |
| f025 | a12-F2 | fixed-scoped | Compaction factories execute sequentially and stop at first fitting candidate, preserving fallback order. |
| f026 | a13-F1 | fixed-scoped | Shared native action-dispatch boundary applies existing immutable matching-goal navigation clamp. |
| f027 | a13-F2 | fixed-scoped | A/B phases validate completed turn, tool order/count and relevant arguments; failed work remains visible. Paired arithmetic requires finite operands from passed trials. |
| f028 | a14-F1 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f029 | a14-F2 | fixed-scoped | Adaptive controller observes native_turn completion and recognized provider-pressure outcomes; fixed mode unchanged. No use of native total duration as provider-latency threshold; provider-only timing control excludes 500000ms tool execution. Generic native TURN_FAILED classification remains as provider reports it. |
| f030 | a14-F3 | qualified | Inbox batching deferred: no representative performance measurement or crash/recovery parity proof. Existing inbox protocol unchanged; affected tests pass. |
| f031 | a15-F1 | fixed-scoped | Disposed or superseded reactive error catch exits before recovery, engine mutation or error publication. Real coordinator/planner/scheduler fixture verifies steer silence, ordinary steer, current fatal error and successful correction controls. |
| f032 | a16-F1 | fixed-scoped | Queued attention observations retain their original snapshot and changedFacts; later quiet observations cannot overwrite them. Only adjacent quiet ready snapshots are replaceable. |
| f033 | a16-F2 | fixed-scoped | Tail-only replacement cannot cross any intervening result/lifecycle event, keeping post-result observations after their receipts. |
| f034 | a17-F1 | fixed-scoped | Equivalent catalog subscribers share accepted router outcome; different-read and stop fences preserved |
| f035 | a17-F2 | fixed-scoped | Auxiliary health has explicit successful/cancelled request retirement, waits for underlying callback settlement, and forgets only auxiliary profile/operation histories. Failed same-request retries retain their circuit until retirement. Cross-request admission identities deliberately remain isolated; no cap or circuit-sharing policy added. |
| f036 | a18-F1 | fixed-scoped | Ordinary model-view decoration reads recovery state without ingesting inventory; direct sparse death still records last live inventory. |
| f037 | a18-F2 | fixed-scoped | Public row prepared only with configured publicSink. Private-only capture now does zero hashes; public capture still makes two full hashes with bounded excerpts and identical private records. Capture-off/closed and private-write-failure controls pass. No measured latency savings claimed. |
| f038 | a19-F1 | fixed-scoped | Exact-identity eviction of settled task loads permits repaired durable data to be retried after a later observation in the same memory context. |
| f039 | a19-F2 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f040 | a20-F1 | fixed-scoped | Active hazards survive prior completed results through actual runLatencyMatrix; second-action hazard, quiet and terminal-order controls pass. |
| f041 | a20-F2 | fixed-scoped | Mining checks the explicit expected block identity and standing default block reach on every action tick before progress or mutation. |
| f042 | a20-F3 | fixed-scoped | Inventory mutations recompute tag totals, preserve known item membership after the last stack is consumed, accept explicit pickup tag metadata, and restore aggregates with failed transactional inventory changes. Inconsistent supplied aggregates fail construction instead of publishing unsupported facts. |
| f043 | a20-F4 | fixed-scoped | Sender chat no longer invents wakeAcknowledged or processed flags; missing recipients fail action execution. The original wake success predicate remains strict. |
| f044 | a21-F1 | fixed-scoped | Admission is 256 entries per owner; owner partitions retain physical byte bounds, privacy, sharing and owner-only replacement/retirement. |
| f045 | a21-F2 | fixed-scoped | Durable update revision selects recent replacements while preserving current-goal progress preference and query insertion order. |
| f046 | a21-F3 | fixed-scoped | Non-2xx Fish/Deepgram bodies are cancelled and owned transport aborted without awaiting cleanup; codes and Retry-After preserved. |
| f047 | a22-F1 | fixed-scoped | Per-channel numeric Retry-After deadline composes with exponential backoff and later failure extension; recovery and close cancel probes. |
| f048 | a22-F2 | fixed-scoped | Cancellation precedes stalled classification. The initial recovery interval is reused to observe settlement; exact physical ownership prevents overlap. Cooperative timeout preserves concurrent STT and cache; stuck control replaces the worker. |
| f049 | a22-F3 | fixed-scoped | Both authoring commands validate supported tones and report a domain error. Legacy persisted tones remain readable; full Java build and vocabulary check pass. |
| f050 | a23-F1 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f051 | a23-F2 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f052 | a23-F3 | fixed-scoped | Valid shared assignments remain in the reconstructed local store, so existing release callback can remove them. First-16 allocation uniqueness and newer-owner fences execute successfully. Ordinary over-16 current roster remains unsupported and unproven. |
| f053 | a24-F1 | qualified | Research proposal; no proof that stricter movement stall detection preserves legitimate useful revisits. |
| f054 | a25-F2 | fixed-scoped | Goal translation captures enqueue before schedule and freezes queue wait at admission. Real CodexService plus normalized public recorder proves 1200ms wait with 31ms startup, no-wait startup, zero-startup queue and native controls. |
| f055 | a27-F1 | fixed-scoped | Cause fix and original-scenario/control regression pass; see RESULT.md. |
| f056 | a28-F3 | fixed-scoped | Actual provider-delivery capture replaces constant/config-only checks. Parsed shared/private contract checks cover agent/model/session, retry ownership, private goals, observation and factual position. Full strings intentionally differ. |
| f057 | a29-F1 | fixed-scoped | Comparator requires a finite ratio for every successful pair. Acceptance also independently checks supplied pair duration completeness, preventing old PASSED incomplete artifacts from certifying. |
| f058 | a30-F1 | fixed-scoped | Native fault fixture validates mining, recipe availability, table/grid constraints and ingredients, consumes full recipe inputs, emits full outputs, and rejects unsupported/missing work. The wooden-pickaxe fault scenario gathers three real fixture logs, crafts batches, places a table and crafts the pickaxe. |
| f059 | a31-F1 | fixed-scoped | Quiet heartbeat storage ingests supplied spatial facts with snapshot:false while retaining recovery reuse. |
| f060 | a31-F2 | fixed-scoped | Useful overlap is the measured intersection of preparation and predecessor action intervals. |
| f061 | a32-F1 | fixed-scoped | Shipped collection example naturally exhausts after missing targets or physical failure; stops failed-aim mining and stops repeated failed actions. Manager entry regression verifies selected-model reassessment; deliberate external-blocker checkpoint remains PAUSED. |
| f062 | a33-F1 | partial | Hung reads and mkdir recover. Indefinitely pending filesystem mutations remain serialized to prevent stale writes. |
| f063 | a34-F1 | fixed-scoped | Owner-partitioned history removes the aggregate world-file overflow; full retained-history workload, subsequent authored notes, restart and legacy migration pass. |
| f064 | a34-F2 | fixed-scoped | Shared supported recipes now have one fixed full batch, grid requirements and output-count ceiling. Stone-tool scenario uses a seeded real table; inventory scenario crafts two plank batches and requires four final sticks. |
| f065 | a37-F1 | source-fix-runtime-unverified | Deferred widget marker survives changed snapshots and matching mutation receipts while editing; tick refreshes after blur and init clears completed work. |
| f066 | a38-F1 | source-fix-runtime-unverified | Saved selection uses shared feedback boundary; proposed name/row are sent before draft commit; rejected selection restores the cycle without another callback. |
| f067 | a39-F1 | fixed-scoped | Complete bounded steering crosses Java/Node and native input without starving unread messages. Raw 4096-unit bound remains; projected maximum is 266880. |
| f068 | a40-F1 | fixed-scoped | Core DEAD/snapshot lifecycle loss repaired and real codec/registry/reducer red-green verified. Bridge snapshot update added; full manager/transport acceptance requested. |
| f069 | a40-F2 | qualified | No supported populated historical custom-mob save established. Preserve migration evidence, no speculative destructive conversion. |
| f070 | a41-F1 | fixed-scoped | Snapshot goal is an explicit ellipsis-marked, surrogate-safe 512 UTF-16-unit display projection. Full immutable request, task identity, persisted scope, tool reads, events and usage are preserved. |
| f071 | a41-F2 | fixed-scoped | Aggregate codec and dependent packet string codec now share an 884800-byte bound derived from all supported schema-7 field/count limits and worst-case Gson escaping. No roster, groups, catalog or projected text is removed. |
| f072 | a41-F3 | fixed-scoped | Local revision queries below one chunk radius use a separate bounded chunk index. Wide observation scopes retain regional indexing. Both indexes receive mutation notifications. |
| f073 | a42-F1 | fixed-scoped | Actual removal receipt plus action baseline and exact fluid aftermath |
| f074 | a42-F2 | fixed-scoped | Exact UI Solo sentinel serializes as absent team; real setup/codec/completion negative controls pass. |
| f075 | a43-F1 | fixed-scoped | Empty contestant rollback no longer rewrites/forces the unchanged preparation owner journal. |
| f076 | a43-F2 | qualified | No behavior change: reset pacing remains cooperative between synchronous Minecraft operations. |
| f077 | a43-F3 | fixed-scoped | Pending results retain the already-built MatchResultV1 and reuse its canonical hash while participant projections stay live. |
| f078 | a44-F1 | fixed-scoped | Recovery accepts player-ready COMPLETED contestants alongside active contestants and never resumes their completed goals. |
| f079 | a44-F2 | fixed-scoped | Launch admission now rejects existing recovery and cleanup ownership before manager/site/confirmation/preparation work. |
| f080 | a44-F3 | fixed-scoped | Failed durable journal deletion is retained as exact-owner retry work and retried on ordinary ticks without repeating cleanup/reset. |
| f081 | a45-F1 | fixed-scoped | Reject FIRE and SOUL_FIRE at feet/head, and inspect fire/fluid throughout the actual standing body for exact and centered fallback candidates. |
| f082 | a45-F2 | qualified | Retained finite synchronous local/expanded search, coverage, ordering, admission and backoff. No speculative resumable lifecycle architecture introduced. |
| f083 | a46-F1 | fixed-scoped | Exact safety callback receives original doubles and checks the complete standing body with actual vanilla block collision iteration; safe fractions and rotation survive unchanged. Bounds and immediate chunk readiness are checked before body queries. |
| f084 | a46-F2 | partial | Removed one repeated successful-column read. Synchronous chunk loading and repeated search candidates remain. |
| f085 | a47-F1 | fixed-scoped | Qualified COORDINATOR_STATUS_TIMEOUT now advances the existing candidate failure policy. Three failures with known-good available roll back only after exact-child termination. Unqualified and sole candidates remain eligible; readiness and stability thresholds unchanged. |
| f086 | a47-F2 | fixed-scoped | Refresh derived request timeout while current property still equals the supervisor publication; retain original previous property for release. Client revision includes timeout property, so timeout-only and explicit override edits recreate clients. |
| f087 | a47-F3 | fixed-scoped | Production voice reconciliation consumes one readiness/revision snapshot, retaining complete-content hashing. The second bridge reconciliation is skipped only for STOPPED/manual supervisors whose tick cannot publish new paths. Manual rotation remains observable next tick. |
| f088 | a48-F2 | partial | Serialization/parsing reused. Responsive cached-plus-fresh full-body sends remain without client retention acknowledgements. |
| f089 | a49-F1 | fixed-scoped | 512 UTF-16 summary reader plus request-correlated validation catch repaired. Fresh extracted-method red-green and source correlation check; full handler acceptance requested. |
| f090 | a50-F1 | fixed-scoped | Confirmation policy examines every parsed compound clause, including inherited crafting verbs. A later creation clause can no longer lose its operator gate during normalization. |
| f091 | a50-F2 | fixed-scoped | Rejected human-gate workaround replaced by exact position/survival constraints. Actual manager and completion checks pass; faithful factual goals remain automatic. |
| f092 | a51-F1 | fixed-scoped | Integral capacity matching replaces greedy assignment of disjoint inventory guarantees. Explicit alternatives remain separate; duplicate and overlapping guarantees retain their prior conservative treatment. |
| f093 | a51-F2 | fixed-scoped | Equivalent kill leaves aggregate by entity into capacities and use the same matching solver, removing factorial permutations of interchangeable evidence. |
| f094 | a52-F1 | fixed-scoped | Landmark exclusion also requires actual local scan volume. Executed baseline red then green sparse-world controls; existing corner/far eligibility and loaded visibility preserved. |
| f095 | a52-F2 | fixed-scoped | Quiet raw changes route through normal queue; urgent evidence and heartbeat retained. Classifier/queue/publication controls pass; live-player full sampling path remains source-traced. |
| f096 | a54-F1 | qualified | No observation-capture deferral implemented. Existing latest-observation and same-snapshot semantics preserved. |
| f097 | a55-F2 | fixed-scoped | Removed the point-goal distance sort. Preparation retains deterministic enumeration; planner continues consuming Set.copyOf membership. |
| f098 | a56-F1 | fixed-scoped | One-shot synchronous same-player target handoff to Carpet getTarget |
| f099 | a57-F1 | qualified | No safe optimization within diagnostic-preservation scope. Empty audiences still transcribe intentionally: existing recognized/no-speech/failure diagnostics require the STT result. No-audience recognition and frozen snapshots verified unchanged; skipping requires an explicit product policy tradeoff. |
| f100 | a57-F2 | fixed-scoped | Skipped ordering outcomes retain no CompletedUtterance, releasing discarded PCM and captured delivery closure immediately while retaining sequence markers. |
| f101 | a57-F3 | fixed-scoped | Global and player backoff use the same discard helper and emit one FAILED notification for every discarded partial sequence, releasing its adapter context. |
| f102 | a58-F1 | fixed-scoped | Binding invalidation records FAILED; explicit stop records CANCELLED. First interruption wins, worker cleanup dispatches existing once-only callback, and coordinator retains its receipt identity guards. |
| f103 | a58-F2 | fixed-scoped | Acquire InputStream before header validation and use existing abort/asynchronous-close path on rejection; preserve size limits, signature checks, and typed errors. |
| f104 | a59-F2 | fixed-scoped | Finalize the last physical frame then freeze sampler before persistence; retain the exact bounded take on failure. |
| f105 | a60-F2 | fixed-scoped | Replaced raw-emoji and zero-byte negatives with otherwise-valid boundary JSON plus accepted companions; wrong-version module now has matching identity. All three negatives kill their specific guard-removal mutant. |
| f106 | a61-F2 | fixed-scoped | Added repository regression at real private findRecoverySpawn boundary with authentic block states, dimensions and collision iterator, independent exhaustive shape/hazard oracle, and readiness/border/build-height controls. |
| f107 | a61-F3 | qualified | Deferred continuous stdout/stderr rotation. Existing helper explicitly promises launch-only rotation and appends through process-lifetime file handles. A safe change requires new output ownership, byte retention, and nonblocking launcher verification; no practical volume was measured. No log or process-launch infrastructure changed. |
| f108 | a62-F2 | fixed-scoped | Java smoke now requires one exact codex/id/Smoke model displayName row unique to successful fake discovery. Builtin fallback remains unchanged. Added malformed/wrong-provider/fallback predicate controls and six real Node catalog publication cases. |
| f109 | a62-F3 | fixed-scoped | Predeadline dependency checks return without creating no-op maintenance tasks. Real deadlines, pending coalescing, monitor wake generations, promotion, exact-child cleanup and close semantics preserved. Three existing fixtures now operate on actual pending work rather than removed no-ops. |
| f110 | a63-F2 | fixed-scoped | Both helper entrypoints share reader readiness and character accumulation under a monotonic deadline, bounded/restored socket timeout, and one absolute deadline for typed polling. Existing verifier registers coalesced, separate and absent/partial-frame controls. |
| f111 | a64-F1 | fixed-scoped | A registered regression invokes real minecraftFacts(player).blockAt against inert ServerPlayer/ServerLevel/chunk-provider subclasses, rejects loading APIs, and checks unloaded then available chunk behavior. |
| f112 | a64-F2 | fixed-scoped | The real Minecraft adapter builds a lazy inventory summary shared only within one verifier evaluation. A default FactSource view method preserves injected sources; the Minecraft override creates a fresh view even when callers reuse an adapter. |
| f113 | a65-F1 | fixed-scoped | Full clear revokes logical input before physical cleanup and retries orphan neutralization |
| f114 | a65-F2 | fixed-scoped | Verification gap fixed with explicit real production-budget mixed-occlusion oracle. Existing bounded late-visible omission remains documented and asserted. Focused-inspection recovery unverified. |
| f115 | a65-F3 | fixed-scoped | Native craft-action transaction, native placement/commit/rollback and retry tests added to existing registered verification |
| f116 | a66-F1 | fixed-scoped | Point geometry pruning uses the conservative support-height interval and validates adjusted endpoints against actual collision-surface distance and unchanged tolerance. |
| f117 | a67-F2 | fixed-scoped | Terminate directly owned fixture and cancel retained read future before closing reader and executor. Add bounded regression fixtures to existing Node verifier. |
| f118 | a68-F3 | fixed-scoped | Capture exact replacement resource after acceptance; assert zero before close, one after first close, and one after repeated/stale closes. |
| f119 | a69-F1 | fixed-scoped | Committed deployment retains its recovery journal descriptor; Undo republishes durable recovery intent before consuming backups and uses the existing retryable recovery routine under the runtime lock. Conflicting transaction journals are rejected. |
| f120 | a69-F2 | fixed-scoped | Release preflight uses the existing normal-updater Java classifier policy and fails closed on unavailable inspection, before installer mutation. |
| f121 | a70-F1 | fixed-scoped | Reject parent components in both checked path and root before lexical containment; source and target preserved through actual CLI and API. Existing symlink/reparse checks retained. |
| f122 | a70-F2 | fixed-scoped | Bound initial inspector read to existing NbtLimits compressed byte budget plus one sentinel; retain original accepted bytes for SHA-256 and unchanged parser limits. |
| f123 | a70-F3 | fixed-scoped | Precreate partial, persist file identity before transport, pin against replacement during writes; startup accepts digest-less partial only on matching durable identity. Final files still require digest checks; same-content replacement remains preserved. |
| f124 | a70-F4 | fixed-scoped | Rejected existing Java can use a supplied trusted archive. Candidate validation occurs in unique owned staging, before exact directory renames. Existing destination retained as a unique backup; failed promotion restores it. Fixed unrelated .extracting directory is preserved. |
| f125 | a71-F1 | fixed-scoped | Paired trial runs the existing sampler until runner boundary or original phase expiry. Cleanup seeds its sampler with the trial peak; identities and peaks survive the boundary. |
| f126 | a71-F2 | fixed-scoped | Physical comments no longer absorb subsequent properties; real launcher rejects both reported comment variants and comment-hidden offline mode. |
| f127 | a71-F3 | fixed-scoped | Each cleanup iteration shares one discovery table and parent index across all historical roots. A separate fresh table still validates stop targets, followed by post-stop verification. |
| f128 | a72-F1 | fixed-scoped | Arm-relative runner resolution and explicit shared Node runner intent; executed orchestrator/module identity controls. |
| f129 | a72-F2 | fixed-scoped | Expected repetition set, truncation and complete metric validation before p95; direct validator and actual arm consumer controls. |
| f130 | a72-F3 | fixed-scoped | Retained process handles, creation identity and historical ownership; real owned orphan/direct/timeout controls and modeled replacement controls. |
| f131 | a73-F1 | fixed-scoped | Expected-failure assertion now throws outside the catch that handles action errors. |
| f132 | a73-F2 | fixed-scoped | Fast-exit lifecycle test dot-sources the supported FunctionsOnly entry to initialize dependent state and functions together. |
| f133 | a74-F1 | fixed-scoped | Recognize only an installed generation manifest whose SHA-256 equals the embedded manifest; enumerate hidden files and retain raw-extraction support. |
| f134 | a74-F2 | fixed-scoped | Compare every source and stage runtime SHA-256 directly with its embedded manifest record. |
| f135 | a74-F3 | qualified | Preserve the valid boot/process-launch gate. Help and PASS output now explicitly say authenticated, fresh, reconciled readiness is not verified. |
| f136 | a75-F1 | fixed-scoped | Loads 1/8/16 receive 2/16/31 oak logs and one shared crafting table before survival summons. Supply yields 8/64/124 planks against 5/40/80 planks for per-member pickaxe+stick recipes using shared workstation. Existing 32-block bound retained. |
| f137 | a75-F2 | fixed-scoped | Task9 rows are named action-timeout-load-1 and decision-correction-load-1, matching their unchanged strict timeout/correction scenarios. |
| f138 | a75-F3 | fixed-scoped | Flash identity metadata integrated with all historical aliases; fresh alias regression and full Java verification pass. |
| f139 | a76-F1 | fixed-scoped | Fresh JAR contains exact retained attribution; archive/source/extracted coordinator parity passes. |
| f140 | a78-F2 | fixed-scoped | Root README agrees with the five-component Windows archive contract and distinguishes optional use from included voice components. |
| f141 | a79-F1 | fixed-scoped | Tests await actual block-entry acknowledgement for both initial/replayed generations; independent pre-start and no-cancel controls remain. Production local speech provider unchanged. |
| f142 | a79-F2 | qualified | Live long-goal/model quality evidence requires new evaluation authorization; exhausted live pilot budget remains unchanged. |
| f143 | a80-F1 | fixed-scoped | Preparation creates and reuses authenticated loopback settings matching the default launcher; legacy offline settings require explicit migration and are not rewritten. |
| f144 | o01-F1 | partial | Factual recovery summary and specific reset-reason metadata are preserved separately. Healthy-thread retention remains unproved; the existing 900-second cap/replacement remains. |
