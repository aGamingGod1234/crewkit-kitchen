# Token usage and Minecraft plan icons

Implemented locally without interrupting the current Desktop run. The local JAR is built; it has not been installed on Desktop. No GitHub changes were published.

## Changes

- Native model replies share repeated column names and repeated values within a self-contained `minecraft-facts-v1` packet. Internal authoritative observations and game actions are unchanged.
- Default replies retain complete facts. An agent can explicitly request changes against its exact delivered observation ID. Missing or unsafe baselines resynchronize with a full observation. Baselines advance only after successful delivery and reset on compaction, death, interruption, reconnect, goal replacement or identity changes.
- Essential agent ownership, survival, recovery, precision and verification guidance stays loaded. The original 44,683-byte control reference remains verbatim and available through `capabilities`. All 27 tools and 38 actions have indexed contracts; all 83 reference pages remain within the existing result limit.
- The live usage footer separates cumulative input, cached input, uncached input, latest provider-reported model input and timed usage rates. Duplicate updates are not summed. Counter and lifecycle resets clear the rate baseline.
- Plan icons use Minecraft's native GUI item models and active resource pack. Completed images cross from the render thread to Swing through a cache. Reload, replacement, closure, stale callbacks and capture failures are covered.

## Before and after

| Measured input | Before proxy tokens | After proxy tokens | Reduction |
|---|---:|---:|---:|
| Instructions plus dynamic tool definitions | 16,067 | 8,767 | 45.4% |
| 148 captured tool replies | 193,694 | 174,084 | 10.1% |
| 30 generated production native events | 126,623 | 114,228 | 9.8% |

These are `o200k_base` estimates, not the exact Codex tokenizer. Static components are counted separately and exclude hidden provider instructions and envelopes. Percentages must not be added. Native events are generated through the production builder using captured facts, not captured actual event strings.

All 148 replies and 30 events reconstruct exactly. No sampled tool reply increased proxy tokens. Existing coverage, truncation, freshness, unknown facts, instruction headings and retry suffixes are retained. The original reference matches its captured before version byte for byte.

Two controlled before/after factual scenarios used GPT-6.1 Sol medium in fast mode through the existing subscription. All four runs produced valid native observe/say calls and exact expected factual answers. Both after replies used the compact format. Reported uncached input was 33,193 before and 24,476 after across the two scenarios. Total uncached input was 57,669, within the 60,000-token cap. This tiny sample verifies comprehension of these cases; it does not establish general gameplay quality, decision latency or weekly allowance savings.

Detailed measurements and limits: [benchmark report](../../reports/native-input-encoding-2026-10-02.md).

## Verification

- Full coordinator suite: 1,917 passed, three skipped, zero failed. Nineteen additional encoding and delivery regressions passed separately after the full run.
- Gradle `check build`: passed. This includes 15,890 protocol/core assertions, 387 voice assertions, client AWT launch-mode checks and the transformed Minecraft mixin checks.
- Icon bridge: 30 checks, including request deduplication, RGBA orientation, stale callbacks, failure retry and closed-plan behavior.
- All 112 packaged coordinator manifest entries match the current source and JAR bytes. The compact encoder, full reference and icon classes are present in the runtime artifact.

Built artifact: `build/libs/arena-agents-0.2.0.jar`, 3,460,843 bytes. SHA-256:

```text
7605d8882edc5904e14be337c3c5dcc8fd91a8b88ed5430b83caaff958550828
```

## Remaining verification

The current Desktop installation is unchanged. Actual GPU icon appearance, resource-pack output and installed-client behavior need a client check after the next update. A full Minecraft before/after task comparison is still needed before claiming preserved whole-task performance or faster actions. Audible speech was not tested.
