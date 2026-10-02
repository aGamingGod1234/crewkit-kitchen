# Native successor handoff: controlled before and after

A prepared, guarded successor reduced the local completion-to-next-dispatch gap from **127.706 ms to 33.328 ms p50** in this fixture, saving 94.378 ms with an assumed 100 ms decision delay. All 20 pairs had a lower queued gap. This measures the real native runtime and ArenaScript against a timer-controlled fake bridge. It uses no provider inference, paid calls, Minecraft server, or physical player actions.

The shorter gap comes from preparing the explicit successor while the predecessor runs. Source and guards are authored fixture stand-ins for selected-model tool output; decision time is an injected assumption. It does not demonstrate faster model thinking. A single authored conditional routine already achieved a roughly 16 ms gap on the baseline, and the same-source control shows no meaningful change in interpreter overhead.

The baseline is Git commit `267f1a3de226a55046a0f97c903f39a7bf5a898c`. The benchmark freezes all 21 relative-import dependencies directly from Git before loading it, including NativeToolRuntime and ArenaScript. The optimized graph has 23 files. Full source hashes, raw samples, explicit authored source, guards, queue receipts, and environment are in [the final paired result](native-successor-handoff-2026-10-02.json), stamped `2026-10-02T04:07:24.307Z`. The optimized runtime SHA-256 for this run is `104ef4298c0e5b1cd20b1fe393f647210fd1a474b435da75bdf078c7f1ebdcc9`. All optimized source hashes were verified against disk after the final run.

The script ran on Windows x64 with Node v24.14.0 on 2 October 2026. Each comparison has two warmup pairs followed by 20 measured pairs, with arm order alternating. Every measured arm dispatched the exact same two actions in order: `wait(150)` then `wait(40)`. The action-shape SHA-256 matches across all six arms: `00c7a57c3a4e5ef6d4d401d80a481c974f6fc3e9cdc7c63ed8ecb8253a88606d`. Each arm dispatched 40 actions across its 20 samples.

| Comparison | Before gap p50 / p95 | After gap p50 / p95 | Paired gap wins |
| --- | ---: | ---: | ---: |
| Individual tools, explicit observation and decision delay → one conditional routine | 126.092 / 127.307 ms | 16.610 / 17.111 ms | 20 / 20 |
| Foreground routine, then decision delay and another routine → prepared `queue_program` | 127.706 / 128.989 ms | 33.328 / 33.769 ms | 20 / 20 |
| Same conditional source on both runtimes | 16.648 / 16.791 ms | 16.223 / 16.999 ms | 11 / 20 |

The individual-tool comparison measures a change in how the caller expresses the routine. Conditional routines were available before this patch. The foreground-to-queue comparison exercises the new handoff path: the same successor source is prepared during the predecessor, admitted with the exact predecessor ID, goal revision and program version, then started after successful natural exhaustion and a fresh guard check.

The modeled decision delay is 100 ms, the predecessor action is 150 ms, and each server sample delay is 10 ms. Windows timer scheduling made the injected decision delay about 108–110 ms and a sample about 15–16 ms. All 20 queued successors were accepted before predecessor completion. The benchmark records these realized delays separately instead of subtracting the requested timer durations.

| Foreground-to-queue gap component, p50 | Before | After |
| --- | ---: | ---: |
| Fresh observation wait after action completion | 15.653 ms | 30.673 ms |
| Injected decision delay spent after completion | 109.716 ms | 0 ms |
| Remaining controller, parse and dispatch time | 2.271 ms | 2.750 ms |

Component medians need not sum exactly to the median total. The queued path keeps the predecessor's post-action sample and adds a new sample for the successor precondition, so it requested 60 samples across 20 pairs versus 40 in the foreground arm. The same-source control's remaining overhead means were 0.867 ms before and 0.833 ms after. Its small total-gap difference is mostly sample timing and does not establish an interpreter speedup.

The earlier **50–200 ms** figure was an estimate. This fixture intentionally inserts a 100 ms decision delay, so its roughly 128 ms baseline does not independently validate that estimate for live Minecraft. If a successor is ready in time, the avoided idle delay depends on the real remaining decision latency; the fresh-observation barriers still remain. If preparation arrives after exhaustion, the runtime rejects the old predecessor handle and waits for an explicit new routine. The fixture submits preparation after the first action dispatch; automatic planner-advisory scheduling, physical server tick admission, model latency distributions, real actions, and complete goal time are unmeasured here. The two-action fixture duration moved from 346.597 to 251.784 ms p50 for the queued comparison; that is a synthetic pair duration, not a whole-game speedup.

All five controlled behavior cases passed. A ready queue dispatched only after predecessor completion and fresh sampling. A guard cached at health 20 was rejected when the fresh sample reported health 8 (`SUCCESSOR_PRECONDITION_FALSE`). Goal lifecycle disposal canceled the old prepared successor. A failed predecessor dispatched no successor even though its source subsequently exhausted. A queue submitted after exhaustion rejected with `STALE_PROGRAM`, dispatched nothing automatically, and allowed a new foreground request to complete the same next action. These are focused harness checks; broader lifecycle and tool-boundary tests are separate.

The standalone benchmark test passed: 1 test, 0 failures. The final affected Node checks passed 162 tests; the earlier full Node suite passed 1,833 tests with three skipped. The final Java build reported `BUILD SUCCESSFUL`, with 15,824 protocol/core assertions and 387 voice-addon assertions; the final JAR is 3,378,389 bytes. These checks verify code and packaging. The separate live headless Minecraft probe is ongoing and supplies no live performance claim in this report.

From the repository root, reproduce the baseline capture, paired comparison, and focused check with:

```powershell
node coordinator/src/benchmark/native-successor-handoff.mjs --mode=baseline --repetitions=10 --quiet=true --json=reports/native-successor-handoff-baseline-2026-10-02.json
node coordinator/src/benchmark/native-successor-handoff.mjs --repetitions=20 --quiet=true --json=reports/native-successor-handoff-2026-10-02.json
node --test coordinator/test/native-successor-handoff-benchmark.test.mjs
```

The [baseline-only capture](native-successor-handoff-baseline-2026-10-02.json) was completed before the queue runtime was available. It records 10 samples for each baseline path; the paired table above uses the freshly frozen baseline in the same process as the optimized arm. CLI flags can vary `--model-delay-ms`, `--predecessor-ms`, `--successor-ms`, and `--observation-ms`. The ready-queue performance scenario deliberately requires its prepared successor to arrive before predecessor completion; late readiness is covered as a behavior case rather than mixed into the ready-handoff distribution.

The final JAR also passed [four provider-free headless Minecraft checks](../docs/performance/native-successor-live.md). Its one physical handoff sample was 77.435 ms; all 29 production modules used by that probe matched the JAR bundle. This separate check verifies game execution and exact-target mining, rather than a before/after model performance comparison.
