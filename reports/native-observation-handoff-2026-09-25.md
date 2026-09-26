# Native action handoff: before and after

The selected model still authors every program step. This change removes execution-settings and unresolved-notebook reads from fresh observations used only by internal program continuation, frontier lookup, and camera sweeps. Public `observe` and post-action feedback keep those fields. Every continuation still requests a newer server observation and checks its lifecycle before the next authored action.

## Measured local effect

Both arms start from merged main `f1fdeffe`. The optimized runtime alone has the change in `native-tool-runtime.mjs`. On MiniPC, one Node process alternated 120 programs per arm after 10 warmup programs per arm. Each program issued the same eight authored `wait` controls. The bridge acknowledged immediately; the benchmark used the real native runtime, ArenaScript parser/executor, and disk-backed ModelNotebook. The action-shape SHA-256 matched across arms: `818b3a2e29903dd525d15836326aafc897b505905d056c535917ce3013b1e108`.

| Metric | Current main | Optimized | Difference |
| --- | ---: | ---: | ---: |
| Completion-to-next-dispatch, p50 (840 gaps/arm) | 4.111 ms | 1.306 ms | 68.2% lower |
| Completion-to-next-dispatch, p95 | 5.821 ms | 1.991 ms | 65.8% lower |
| Whole eight-action program, p50 (120/arm) | 67.141 ms | 66.102 ms | 1.5% lower |
| Whole eight-action program, p95 | 81.152 ms | 77.169 ms | 4.9% lower |
| Fresh server samples | 960 | 960 | same |
| Unresolved-notebook queries | 960 | 0 | removed |
| Execution-settings reads | 960 | 0 | removed |

All 120 matched programs had a lower median handoff gap with the optimized runtime. Whole-program time was lower in 84 of 120 pairs. A second 120-pair run after the shared provider compatibility fix reproduced the handoff result: p50 **3.720 → 1.135 ms** (69.5% lower), p95 **5.730 → 1.823 ms** (68.2% lower), with 120/120 paired handoff wins. Its whole-program p50 moved 59.145 → 58.417 ms, while p95 moved 76.699 → 79.452 ms; whole-program improvement is not established. These are local synthetic controller timings, not model inference, Minecraft movement, or task completion.

Run `node coordinator/src/benchmark/native-observation-handoff.mjs <baseline-root> <optimized-root> 120` from the optimized checkout. The [first result](native-observation-handoff-2026-09-25.json) and [replication](native-observation-handoff-2026-09-25-replicate.json) record sample counts, percentiles, read counts, and action hashes. The script fails if action count, outcome, fresh-sample count, or action hashes differ.

## Provider compatibility and live Minecraft

The Codex CLI now requires `default_permissions` when the Minecraft workspace declares a named permissions profile. The previous generated config omitted it, causing live provider turns to fail. The identical compatibility fix was applied to both arms; it does not touch action handoff. A live Codex native-tool probe then passed chat, navigation, mining, crafting, and steer cases with `gpt-6-sol` / low / priority.

With that fix in both builds, a fixed-arena `Get 1 oak log` trial passed in **21.675 s** on the baseline and **27.211 s** on the optimized build. Both removed the placed oak log, held it in inventory, reached `COMPLETED`, and cleaned up. The optimized run was 5.536 s slower in this single pair. Its model made eight decision segments and completed 12 actions, versus six segments and 11 actions in the baseline; measured model-segment time totaled 16.581 s versus 10.037 s. Neither run used `runProgram`, so this live pair does **not** exercise the optimized continuation path or show a gameplay speedup. A follow-up task that explicitly named `runProgram` was rejected by goal translation before model execution, so it supplies no performance sample.

The focused native runtime, background-program, and program-executor tests passed (110/110). Workspace/Codex tests passed on both arms (72/72 each). The full optimized coordinator suite passed (1,772 passed, 3 skipped, 0 failed). Java 25 `assemble` passed before the compatibility fix, and `jar` passed for both final builds. The regression tests cover fresh program facts, public metadata, and the required default permission profile.

The [sanitized live summary](native-observation-live-2026-09-25.json) links the two runs to their local reports at `agent-arena-speed-main-baseline/runtime/headless-runs/run-1790300540067-b4633dc2/matrix-report.json` and `agent-arena-speed-main-optimized/runtime/headless-runs/run-1790300602195-44e56af5/matrix-report.json`. The shared scenario is `coordinator/config/speed-headless-matrix.json` (SHA-256 `2E667752D55BE9FCAD29B35F1B3FD381534DE5D9EAF297FCBA580C2E1B54AE89`). The baseline and optimized game JAR hashes were `98EDB728B05298050CA179960DCBC6718D838DD306443703BC2FDB5C9129F17A` and `B86F0F67A8AEDDF558899D0460D238809ABBBDA92DEF84CF794FE285301C0AF5`. Private provider traces stay local. Speech was outside this comparison.
