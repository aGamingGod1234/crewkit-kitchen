# Desktop coordinator disconnect, 2 October 2026

The initial investigation below was read-only. The subsequent authorized local implementation fixed both diagnosed defects; see [completed implementation and live verification](2026-10-02-remaining-improvements.md). Desktop's main installed JAR, model settings and account settings remain unchanged.

## Finding

The coordinator bridge first disconnected immediately after Haste was applied to GPT-6.1 Sol. Minecraft represents an infinite status-effect duration as `-1`; the observation collector forwards that value, but the coordinator requires every effect duration to be a nonnegative safe integer. Repeated reconnects encounter the same rejected observation. This left automation offline before Minecraft was closed.

Local-time sequence from the captured Minecraft log:

- 15:50:51: coordinator started, generation 1.
- 15:51:58: GPT-6.1 Sol joined.
- 16:48:25: Haste applied to GPT-6.1 Sol.
- 16:48:26: first `COORDINATOR_BRIDGE_DISCONNECTED`.
- 16:53:45: operator's “continue” message rejected because automation was offline.
- 16:53:57: Minecraft shut down.

Coordinator stderr repeats `INVALID_PAYLOAD: effects[0].duration must be a nonnegative safe integer [inbound type=observation, goalRevision=number:1]`. There is no second coordinator-start generation in this session. The log does not retain the original effect command's duration, so attributing the infinite duration to that command is an inference from the timing, error and serialization path.

## Focused reproduction

Using the current `validateProtocolV2Payload('observation', payload)` entry point and an otherwise valid server-observation fixture:

- Haste duration `120`: accepted.
- Haste duration `-1`: rejected with the same error as Desktop.

Inspection of the Minecraft 26.1.2 artifact confirms `MobEffectInstance.INFINITE_DURATION = -1`. `ServerObservationCollector.statusEffects()` serializes `effect.getDuration()` directly. `validateObservationPayload()` in `protocol-v2.mjs` rejects negative effect durations.

The follow-up now represents vanilla infinite effects consistently in both protocol validation and observation adaptation, retaining rejection of other invalid negative values. Finite/infinite regressions and a live isolated Fabric check passed. Infinite Haste remains connected and a subsequent action succeeds. Reconnecting indefinitely could not repair the previously rejected valid Minecraft state.

A separate shutdown exception (`Entity location requires an entity UUID`) occurred while persisting live-agent locations. It follows server shutdown and does not explain the earlier bridge failure. The subsequent fix persists only a living, matching, committed body; eight new lifecycle assertions passed, and ordinary live shutdown completed cleanly.

## Usage evidence and limits

The run's coordinator trace contains 108 first-tool/native-turn starts, 107 completed native turns, 987 recorded provider decision/wait segments and 1,541 dispatched physical commands. These counts are not billable-token totals. One native turn was cancelled as stale at the end of the run.

The dedicated Codex debug database records a final context/compaction scope of 198,894 tokens. This is the active context size, not the sum of charged input/output tokens. Native app-server threads are ephemeral; the state database contains no saved thread records for this run. The retained app-server logs acknowledge token-usage and rate-limit update notifications without preserving their numeric payloads. The coordinator's native-turn collector does not record those token updates; unlike the JSON planner collector, it filters notifications by a turn ID that thread token-usage updates do not carry.

Official Codex documentation states that GPT-6.1 Sol Fast mode consumes included subscription allowance at 2.5 times Standard mode. Task length, reasoning, context, tool usage and caching also affect allowance. The reported drop from approximately 13–15% remaining to 11% is therefore plausible for this run, but there is no exact before/after account snapshot or complete billing-token record to attribute a particular percentage to it. Account allowance can also be shared with other Codex and ChatGPT Work activity. No retained evidence indicates that a usage limit caused this coordinator disconnect.

Sources: [Codex speed](https://developers.openai.com/codex/speed), [Codex pricing](https://developers.openai.com/codex/pricing), [Codex app-server](https://developers.openai.com/codex/app-server).

## Display feature constraints

The live plan window needs a structured, agent-editable dependency plan in addition to the existing completion predicate and task memory. A goal predicate alone does not describe the entire dependency tree. Completion history, current inventory, persistent world infrastructure and remembered observations need separate meanings so a death can invalidate a required carried tool without erasing a known portal or historical milestone.

The live terminal should observe the existing Codex app-server process's output, tool calls/results, exposed summaries and usage notifications. Launching a second Codex thinker would create a separate context and spend more allowance. This proposed viewer is a terminal presentation of the actual running task's event stream; it is not an attached native Codex interactive TUI and cannot expose unavailable private reasoning.
