# Opus speed suggestions: implementation and benchmark

The local candidate continues Claude's pushed-observation work at baseline commit `0f105949`. It clarifies the native tools for safe model-authored sequences, conditional ArenaScript, and overlapping a selected action with model reasoning. A sequence may now include an optional model-authored `finish` request. The runtime issues that request only after every step succeeds and a newer authoritative observation arrives; Minecraft still verifies the immutable goal. The candidate also fixes a failed-inspection/queued-push race and reports missing timing data as unknown instead of zero. The selected model remains the only source of gameplay decisions.

## Live result

Sixteen headless Fabric runs used the same Mini PC, fixed one-oak-log arena, `codex/gpt-6-sol/low/priority` profile, server template, and `coordinator/config/speed-headless-matrix.json`. The first four runs in each arm were grouped; the remaining four in each arm were interleaved in order after, before, before, after, before, after, after, before. Every run met the Minecraft lifecycle, block, and inventory assertions.

The live source was fixed throughout each arm. SHA-256 (`native-tool-runtime.mjs`, `native-minecraft-tools.mjs`): before `c7e201d22b40adf611229ca4a96c18722ca2fcc5bf7db1db426193d62faca682`, `84cdc4a3ee3ee7a47f449446dc20997ce5c2eb0b7fb3fc5d6b0e27b5b36273bb`; candidate `3bd2c273e3e04501ff3c042b83cdeeaed6a3cddf184907f5981a983f7a8ceb55`, `ad15a7e2e0ced0f001874b36cdbf11a49c63a94b3d2db4f66764edd6b7010a8d`.

| Metric | Before `0f105949` (8/8 pass) | Candidate (8/8 pass) | Difference |
| --- | ---: | ---: | ---: |
| Whole-task p50, nearest rank | 24,956 ms | 20,794 ms | **4,162 ms faster (16.7%)** |
| Whole-task range | 21,220–28,429 ms | 18,878–26,870 ms | Overlapping distributions |
| Recorded model segments per run, p50 | 6 | 5 | 1 fewer |
| Direct model-segment time per run, p50 | 13,748 ms | 10,589 ms | 3,159 ms less |
| Goal accepted to first action, p50 | 6,638 ms | 5,962 ms | 676 ms less |
| Between-action waits per run, p50 | 7,415 ms | 5,666 ms | 1,749 ms less |
| Physical action execution per run, p50 | 5,499 ms | 5,341 ms | 158 ms less |
| Camera-sweep handoff p50 | 50 ms | 50 ms | Already at one server tick |
| Explicit inspection requests per run, p50 | 11 | 11 | No traffic reduction |

Before durations: `27,376, 26,617, 24,956, 26,834, 28,429, 21,220, 23,202, 21,811` ms. Candidate durations: `22,481, 19,975, 24,536, 20,794, 20,045, 22,962, 18,878, 26,870` ms. In the four close alternating pairs, the candidate won two and lost two (candidate minus before: `-8,384, +1,742, -4,324, +5,059` ms). The median shift is promising, but this sample does not prove a repeatable speedup on each attempt or on other goals/seeds. The model chose different action sequences; the measurement is end-to-end behavior, not an isolated runtime microbenchmark.

Minecraft proactively verifies factual goals each tick. These one-log runs had no `goal_completed` request, so they **did not exercise** optional `sequence.finish`. They also did not use background `runProgram` or `startAction`. The live change primarily tests tool guidance and the resulting selected-model choices. In all eight candidate runs, direct decision timing was present; the new analyzer preserves unknown phases rather than attributing inclusive gaps entirely to the model.

## Score against Opus's estimates

| Proposal and estimate | Evidence and score |
| --- | --- |
| Fewer model round trips: **4–7 s**, **15–25%** per task | Whole-task p50 improved **4.16 s / 16.7%**, inside the estimate. Recorded model segments fell 6→5 at p50. Attribution is limited because model actions varied, and these runs did not use `runProgram` or bundled finish. |
| Overlap body movement with thinking: **1.5–3 s** | **Not observed live.** A deterministic five-action benchmark with the real native runtime, fake bridge, and fixed 2 s model-thinking intervals saved **2.0 s** for identical foreground/background program source; independent `startAction` steps saved **4.5 s** versus serial calls. These are simulated times, not Minecraft/provider latency. |
| Warm session / bundled finish: **2–6 s** | The Codex service already reuses the same profile/session; a cached lookup measured 0.0058 ms p50 locally. Factual goals complete proactively, so bundled finish saved no live turn here. Estimate unsupported for this workload. |
| Server-side sweep batching: **~350 ms** | `control_sequence` already runs authored frames server-side. Both arms' live sweep gap p50 was 50 ms. No additional gain measured. |
| Push matching / fewer inspections: **~0 ms**, fewer requests | A queued push now survives a same-turn inspection failure. Both arms still made 11 explicit inspection requests at p50; traffic reduction was not implemented or claimed. |
| Shorter model inputs: **1–3 s**, low confidence | No input compaction shipped. The retained provider-turn traces lack token/cache counts; the safely omittable repeated goal fields were only about 250 bytes, while observation deltas need within-session validation. |

The overlap benchmark is reproducible with `node coordinator/src/benchmark/overlap-latency-benchmark.mjs 3`; it matched all five authored action arguments and every successful receipt across all strategies. The live phase analyzer is `node coordinator/src/benchmark/live-action-gaps.mjs <arm>=<scenario-directory> ...`. Local raw summaries are at `runtime/speed-arms/opus-8v8-analysis.json`; the server and protocol audits remain in each arm's `runtime/headless-runs/` directories.

Validation: the full coordinator `npm test` suite passed; focused native runtime, sequence serialization, overlap, and phase-attribution tests passed 93/93. The headless Fabric matrix passed 16/16. No visible Minecraft client, random-seed survival run, installed mod, or audible speech was tested. This is local work only; no push or PR was made.
