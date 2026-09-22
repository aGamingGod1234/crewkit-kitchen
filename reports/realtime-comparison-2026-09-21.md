# Realtime execution: review and before/after evidence

This comparison isolates the realtime changes: **before `47c83ca8`**, **after `e4e1c94f`**. It does not compare the entire PR against `main`. Measurement-only fix `35fb566f` is applied to both live runners. Agent gameplay code, prompts, and packaged mod remain at their respective recorded revisions.

## Bot comments

Checked conversation comments, submitted reviews, and all inline review threads on PR #38. There were **zero inline threads and zero actionable bot findings**:

- [CodeRabbit](https://github.com/aGamingGod1234/agent-arena/pull/38#issuecomment-5748329975): free-plan walkthrough and summary; no line-by-line findings.
- [Copilot](https://github.com/aGamingGod1234/agent-arena/pull/38#pullrequestreview-5259867761): unable to review because of quota.
- [Codex](https://github.com/aGamingGod1234/agent-arena/pull/38#issuecomment-5748350349): completion notice for the earlier `040d1fd` review.

These notices do not establish a substantive review of the current head. There were no bot threads to mark resolved.

## Corrected measurement

The previous timeout reports could reuse the empty **starting** inventory as their final RCON assertion evidence. Their reported empty final inventories are therefore withdrawn; the recorded timeouts and provider-wait samples remain usable for their stated purposes.

The corrected runner excludes natural-world preflight reads from final evidence, freezes task elapsed time, requires the exact agent-specific pause acknowledgement, then permits a bounded post-stop read. An unavailable read stays unknown and the run remains `TIMEOUT`. The post-stop snapshot is not claimed to be an exact deadline snapshot. Both comparison arms use identical corrected measurement code. Three regression tests cover a changed inventory, failed final read, and invalid stop acknowledgement.

## Controlled native-runtime comparison

Six paired trials per build, in alternating order; all 12 trials completed the same two actions.

| Controlled runtime metric | Before | After |
| --- | ---: | ---: |
| Handoff gap p50 | 111.6 ms | 1.7 ms |
| Handoff gap p95 | 113.7 ms | 5.5 ms |
| Advance advisories observed | 0/6 | 6/6 |
| Trials completing both actions | 6/6 | 6/6 |
| Body time remaining after advisory, p50 | Unavailable (no advisory) | 125.0 ms |

[Raw controlled samples and provenance](realtime-controlled-comparison-2026-09-21.json). Percentiles use nearest rank; with six samples, p95 is the maximum.

The benchmark imports each revision's actual `NativeProgramExecutor`, parser and action dispatcher. The bridge and controller are synthetic. Both arms use the same two-action workload and a fixed 100 ms simulated preparation delay. The controller starts preparation on an advance advisory, or on routine completion if no advisory arrived. It explicitly starts the successor only after both preparation and the first routine have completed.

The measured handoff gap is the successor action's start minus the first action's finish. This is not first-token latency, a real provider measurement, or automatic successor execution by the product. Raw paired samples, source/configuration hashes and nearest-rank summaries accompany the final results.

The controlled workload directly supplies a **200 ms preparation lead**, **300 ms routine deadline**, a seeded **214–226 ms first action**, and an **8 ms successor action**. The simulated preparation delay is 100 ms. It tests the executor's advisory opportunity; it does not measure the live planner, its learned lead estimate, or the full model-to-Minecraft path.

The raw `usefulOverlapMs` field is the body time remaining after the advisory. It measures available overlap, not time spent computing a decision.

Reproduce from the repository root with installed coordinator dependencies:

```powershell
node coordinator/src/benchmark/native-realtime-comparison.mjs --baseline 47c83ca8 --optimized e4e1c94f --repetitions 6 --output build/native-realtime-comparison.json
```

## Live Minecraft comparison

Four sequential trials use **before / after / after / before** order. Each uses a fresh natural Normal-survival world on development seed `20260920`, one agent, a 360,000 ms scenario budget and the configured **Codex `gpt-5.6-sol` / low / priority** profile. The unchanged task is:

> Collect 8 oak logs with a reusable background runProgram routine saved in your notebook. Read programReference before authoring the routine.

No supplied items, terrain repairs or gameplay intervention. Workspaces/worlds are fresh for every trial. Environment: Mini PC, Windows, Node 22.23.2, Java 25, Minecraft 26.1.2. Full wrapper time includes setup and cleanup; task elapsed time excludes post-stop evidence collection. Inventory is read after a confirmed stop on timeout. Final inventory count and lifecycle completion are reported separately.

| Order | Build | Post-stop oak logs | Outcome | Scenario elapsed | Stop acknowledgement after deadline |
| --- | --- | ---: | --- | ---: | ---: |
| 1 | Before | 0 | TIMEOUT | 360.008 s | 12 ms |
| 2 | After | 4 | TIMEOUT | 360.011 s | 18 ms |
| 3 | After | 0 | TIMEOUT | 360.010 s | 14 ms |
| 4 | Before | 2 | TIMEOUT | 360.010 s | 14 ms |

| Live outcome | Before | After |
| --- | ---: | ---: |
| Eight-log inventory target met | 0/2 | 0/2 |
| Completed lifecycle | 0/2 | 0/2 |
| Final oak logs, by trial | 0, 2 | 4, 0 |
| Clean cleanup | 2/2 | 2/2 |

**No reliable gameplay speedup is established.** All runs timed out; their durations are not successful completion times. The after build made more total inventory progress in this small sample, but results varied widely and neither build met the target. Both configurations were verified through Minecraft snapshots; effective internal provider settings were not independently attested.

The after trials captured 32 and 18 model-wait samples respectively: p50 **2,819 / 2,761 ms**, p95 **11,560 / 8,600 ms**. These exclude tool execution and are not pooled across agents. Comparable before samples and token-cost data are unavailable.

[Raw live samples, matrix, artifact hashes and provenance](realtime-live-comparison-2026-09-21.json).

The existing entry point is `scripts/run-headless-provider-matrix.ps1`, with each arm's project root, the identical matrix preserved in the JSON report, and the same server template. The published results retain every failed or unavailable trial. Before timing instrumentation is absent, so before provider-wait latency is **unavailable**, not inferred from native whole-turn duration. Token cost remains unknown when the provider does not supply usage.

## Limits

Two live trials per build are exploratory and cannot establish generalisation or a reliable speedup. Provider delays and model decisions vary. The controlled benchmark demonstrates the opportunity to overlap preparation with current work; gameplay improvement requires the model to use that opportunity successfully. No 2x, cost-saving, autonomous survival-win or universal human-level claim is made. Audible speech validation remains deferred.

## Verification

The [code CI run at `dc5940f8`](https://github.com/aGamingGod1234/agent-arena/actions/runs/35583479423) passed every job, including Java and Windows packaging. The full Windows coordinator suite reported **1,758 passed, 4 skipped, 0 failed**. Focused local checks passed the three timeout-evidence regressions and 16 executor/benchmark tests. A separate review found no remaining blocking issue in the evidence fix.

Final local Java 25 assembly passed. All 103 packaged manifest entries verified, and all 88 packaged source files match the checkout. The gameplay JAR is unchanged from the measured after artifact; headless and benchmark tools are intentionally excluded from the shipped coordinator.

A subsequent CI rerun exposed a test that raced a real one-millisecond reminder against goal stopping. The test now holds the timer until after the stop, then advances it explicitly. All 149 dynamic-coordinator tests pass locally. This changes test scheduling only; production code and the measured artifacts are unchanged.
