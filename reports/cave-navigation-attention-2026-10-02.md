# Cave travel and preparation: bounded before/after evidence

The installed Fabric fixture also passed: the same six authored navigation controls crossed corners, a one-block ascent and a raised ledge in **4.450 s before versus 3.931 s after p50**. All six arms completed, with 36 successful physical actions and clean wrapper cleanup. This compares two notification policies on the same current JAR with a declared 100 ms response fixture; it does not measure provider inference or whole-agent performance.

An explicit agent-authored `reassessWhen` predicate removed redundant progress reconsideration in the real native runtime. In this timer-controlled bridge fixture, the same six navigation controls completed in about **387–388 ms rather than 938–941 ms p50**, using a declared 100 ms decision-response delay. All 36 measured predicate pairs were faster. This is control-chain timing against a fake bridge, not Minecraft navigation, provider inference, token speed, FPS or whole-goal performance.

The new benchmark exercises `normalizeMinecraftToolCall → NativeToolRuntime → ArenaScript`. Its three route descriptions contain turns, changing elevation and ledge coordinates. Those coordinates and support blocks are supplied observations; the fixture sets the final position on a successful timer-controlled receipt. It does not run collision physics, occlusion, a pathfinder or hostile AI. A passing hostile case means delivered hostile facts reach the authored predicate, not that installed perception detects a hidden enemy.

The before import graph was captured at `2026-10-02T10:43:58.166Z`, before the movement edits. The hash-checked [baseline source bundle](cave-navigation-baseline-sources-2026-10-02.json) contains the complete native-runtime relative import graph and actual Minecraft agent guidance, 28 files in total. Both arms load the same installed Acorn version. The [final result and raw rows](cave-navigation-attention-2026-10-02.json) were stamped `2026-10-02T10:58:38.411Z`; all current import hashes still matched disk after the run. The initial [baseline-only capture](cave-navigation-attention-baseline-2026-10-02.json) is retained separately; the table uses paired runs against the frozen baseline.

Each comparison has one warmup pair and 12 measured pairs, alternating arm order. Both arms execute the same six destinations, navigation arguments and first-failed-leg checkpoint checks. The only source difference in the predicate comparison is adding the optional pure predicate to `pause_and_notify`. It checks health, dimension, an observed hostile, support-list completeness and exact known support identities. Unknown additions, removed support and replaced support require reassessment. No runtime chooses a route, equipment, supply reserve or reaction.

| Supplied route shape | Route time p50, before / after | Body idle p50, before / after | Handoff p50, before / after | Reconsideration responses per route |
| --- | ---: | ---: | ---: | ---: |
| Turns | 938.720 / 388.247 ms | 553.507 / 6.807 ms | 110.679 / 1.379 ms | 6 / 0 |
| Slope coordinates | 938.196 / 386.946 ms | 551.631 / 6.725 ms | 110.446 / 1.346 ms | 6 / 0 |
| Ledge coordinates | 940.707 / 387.954 ms | 554.125 / 6.414 ms | 111.030 / 1.339 ms | 6 / 0 |

Route time runs from first action dispatch to the final action receipt. Body idle is that interval minus the union of the six nonoverlapping active-action intervals; it includes gaps awaiting the declared response fixture. Handoff runs from one action receipt to the next action dispatch. Reconsideration counts are actual `program_attention` callbacks answered by the declared continue fixture, excluding terminal exhaustion. They are simulated planning turns and incurred no provider requests. Exhaustion still yields control after the final destination.

The requested physical-action duration is 60 ms, response delay 100 ms and explicit sample delay 5 ms. Real Windows timers yield slightly different durations. Every action publishes a fresh post-result observation, allowing the production continuation path to reuse that publication; no explicit sample was required during the successful route rows. Both arms receive all six fresh observations. All 144 measured arms completed their exact supplied targets with successful fake-bridge receipts, and action-shape SHA-256 matches within every before/after pair.

The legacy same-source control still asks six times on both runtimes. Its route p50 values were 929.458 → 936.552 ms for turns, 935.250 → 936.778 ms for slopes and 927.728 → 928.922 ms for ledges. This does not show an interpreter speedup. Existing `continue_and_notify`, watchers, batching and queue handoff already supported useful continuous work; the new declaration lets the selected agent preserve a pause policy for meaningful changes while waiving redundant ordinary notifications.

All nine behavior cases passed. New geometry, changed support, a new iron block, an observed hostile, urgent damage and a changed dimension paused after the third chosen action, with no fourth target dispatched. An exact-false predicate still admitted urgent damage. A single blocked navigation returned `NO_STANDABLE_PATH` and reached the explicitly authored checkpoint after three actions; it did not claim route completion. An unchanged failed retry loop with an exact-false predicate still reached the existing repeated-action-failure reconsideration after two attempts. None of these partial routes claimed factual success. A lone failed action needs an authored receipt check; automatic recovery detects repeated deterministic failures.

The focused benchmark test passed, one test and no failures. It verifies matched controls, successful supplied destinations, retained fresh observations, legacy behavior and all nine boundaries. This is separate from the repository suite and actual Fabric/JAR checks reported by the main implementation task.

Four bounded fresh Codex CLI responses also compared the actual before/after AGENTS instructions and relevant skill excerpts. The CLI used the existing ChatGPT subscription login, with `gpt-6.1-sol`, medium reasoning and fast service tier submitted identically, a read-only temporary work directory, no inherited API key and no gameplay or tools. Full supplied prompts, responses, guidance hashes and emitted request usage are in [the model comparison](cave-guidance-model-2026-10-02.json). No reasoning content is retained. One response per case and arm is an anecdotal guidance check, not a reliable behavior estimate.

| Actual model decision | Before guidance | After guidance |
| --- | --- | --- |
| Preparation before seeking three unobserved diamonds | Gather 3 of the 6 safe iron blocks; make one spare iron pickaxe; obtain and cook nearby beef; use and recover workstations. | Gather all 6 iron blocks; make two spare iron pickaxes; obtain and cook beef; carry the table and leave the reusable furnace at the entrance. |
| First batch along six verified irregular waypoints | All six waypoints; no broad inspection after each waypoint; reconsider at new geometry, threats, resources, failure or the unknown junction. | All six waypoints; no broad inspection after each waypoint; explicitly use the attention filter and check every movement receipt, pausing at the first blocked leg. |

The preparation response shows increased chosen reserves in this case. It does not establish that the extra gathering saves time or that two spares are optimal. Both route responses already chose the full verified leg, so this pair shows **no increase in route batch length** from revised guidance. The after response describes the new filter, but no generated program was executed by this text-only comparison.

| CLI request | Input tokens | Cached input, included in input | Output tokens |
| --- | ---: | ---: | ---: |
| Preparation before | 21,329 | 7,296 | 431 |
| Preparation after | 18,387 | 0 | 381 |
| Route after | 20,559 | 12,544 | 250 |
| Route before | 20,219 | 12,544 | 254 |
| Total | 80,494 | 32,384 | 1,316 |

These are provider-emitted per-request totals, summed once each. Cached input is a subset of input and is not added again. CLI fixed context makes input much larger than the short supplied scenario. The output totals include any output categories the provider counts. Shared account allowance, allowance percentage changes and dollar cost are unavailable; no conversion is inferred. CLI output does not echo the effective model profile, so the retained evidence is the identical submitted configuration and successful responses.

Reproduce the paid-call-free timing and focused verification from the repository root:

```powershell
node coordinator/src/benchmark/cave-navigation-attention.mjs --repetitions=12 --warmups=1 --quiet=true --json=reports/cave-navigation-attention-2026-10-02.json
node --test coordinator/test/cave-navigation-attention-benchmark.test.mjs
```

The model runner is `coordinator/src/benchmark/cave-guidance-model.mjs`. Running it again consumes four additional subscription turns; the completed four-turn result is already retained. Natural-cave route completion, real occlusion/hostile detection, equipment use, reserve effectiveness and end-to-end agent behavior still require a separate installed scenario with the same model settings and starting inventory. The bounded installed route check below verifies the physical control chain.

The provider-free installed comparison used [the live probe](../coordinator/src/benchmark/cave-navigation-live.mjs) through the authenticated bridge and actual Fabric navigation controller in an isolated Desktop server. Both arms ran `arena-agents-0.2.0.jar`, SHA-256 `0d3a542a8c41dad92dfadfde2884b04bd1a56fbe4154ab1f58cda58a08b3df34`. The registered profile and all command provenance retained `codex / gpt-6.1-sol / medium / fast`; this is metadata, with zero model calls in this physical fixture. Three pairs alternated before/after, after/before and before/after, with the same full health, full food, empty inventory and initial position. No player-world instance was used.

| Installed metric | Before policy | Predicate policy |
| --- | ---: | ---: |
| Route time p50, 3 routes per arm | 4,449.900 ms | 3,930.665 ms |
| Completion-to-next-dispatch p50, 15 gaps per arm | 106.497 ms | 3.360 ms |
| Sum of inter-leg gaps p50 | 535.034 ms | 18.316 ms |
| Fixed reconsideration responses per route | 6 | 0 |
| Completed physical routes | 3 / 3 | 3 / 3 |

All three pairs had lower route time with the predicate. The six destinations and every navigation argument matched across all arms; their measured action-shape SHA-256 was identical. All 36 terminal receipts were `SUCCEEDED`, physically attempted, standable at the target and verified against world-position progress. Every arm ended through `PROGRAM_EXHAUSTED`, retained health 20 and reached the destination within the declared tolerance in both player observations and independent RCON entity-position reads. All 36 boundary observations had fresh server times/ticks, health 20, grounding, overworld dimension and the actual stone support underfoot. The retained raw artifact contains all 372 physical progress updates.

The before source used `pause_and_notify`. The after source added a pure predicate checking fresh health, grounding, dimension and the observed current stone support. Unknown support requires reconsideration. Both sources checked each receipt and explicitly checkpointed a failed leg. The fixture declared ordinary attention only at successful physical boundaries and updated facts on other ordinary pushes; actual urgent survival callbacks remained live and would fail this harmless-route fixture instead of receiving an automatic continue. A boundary notification was ingested before any successor dispatch, preserving the current fresh-observation continuation path. The declared response waited 100 ms and chose only `continue` along the fixed authored route. Neither runtime selected a destination, route branch, supply or combat tactic.

Route time runs from first dispatch to the final terminal receipt. The idle value is a proxy summing the five completion-receipt-to-next-dispatch gaps; it does not directly measure vanilla input inactivity. The before arm answered six notifications because its final completion also notified before exhaustion, but that sixth response falls outside the route-time clock. These three pairs are a bounded mechanism check on artificial stone terrain under peaceful difficulty. They do not establish natural-cave success rates, installed occluded-hostile detection, model latency, token speed, FPS or end-to-end goal improvement. Both arms use the current JAR, so this is a physical policy comparison, separate from the frozen-source before/after runtime evidence above.

The [final probe summary](cave-navigation-live-2026-10-02/player-capability-report.json), [complete raw evidence](cave-navigation-live-2026-10-02/cave-navigation-live-raw.json) and [final wrapper report](cave-navigation-live-2026-10-02/harness-report.json) are retained together. Probe source and runtime hashes matched the current files during independent verification. The wrapper returned `PASSED`, exit 0 and `CLEAN` process/listener cleanup. Initial runs passed the physical assertions but exceeded report limits through repeated facts and PowerShell formatting expansion; the fixture-specific wrapper now writes compressed JSON under the unchanged 262,144-byte limit. The final probe summary is 203,552 bytes and the complete serialized scenario report is 127,084 bytes. No cap or navigation behavior changed for that serialization fix.

Reproduce the installed comparison with the built current JAR and an isolated existing server template, from the repository root:

```powershell
$projectRoot = (Get-Location).Path
& runtime/action-speed-harness/run-cave-navigation-live.ps1 -ProjectRoot $projectRoot -MatrixPath runtime/action-speed-harness/matrix.json -ScenarioId native-successor-live -ServerTemplate runtime/proximity-goal-server-template -CapabilityProbe -KeepArtifacts
```

The wrapper reuses the existing matrix scenario identifier for startup and cleanup; its invoked probe records `cave-navigation-live`. It creates a separate generated server world, requires the configured supported JDK, authenticates the bridge and runs without a provider. The report stores the exact two program sources and actual command/receipt times.
