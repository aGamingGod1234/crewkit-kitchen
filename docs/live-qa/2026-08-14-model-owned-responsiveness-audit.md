# Model-owned responsiveness live audit

Date: 2026-08-14

## Boundary result

- The Java runtime reports raw player changes and keeps executing the current explicit model action.
- Only a planner `continue`, `cancel`, or `replace` directive can preserve, cancel, or replace an active gameplay action.
- Death, operator controls, transport teardown, invalid commands, action completion, and timeouts remain mechanical terminal conditions.

## Actual-client evidence

- Launched Minecraft 26.1.2 from the repository with Java 25 and the supervised dynamic coordinator.
- During an explicit mining sequence, applied 2.0 damage at 17:07:31. The same agent mined again at 17:07:32 and 17:07:55. No damage handler cancelled the action or selected combat/flee behavior.
- Relaunched the client with visual perception filtering. The coordinator authenticated, the agent respawned, and observation delivery continued without a payload/schema reset.
- Reproduced a lifecycle ordering race where observation revision 4 followed respawn/resume while the asynchronous registry still exposed revision 3. Added transport-level ordered revision tracking, hot-reloaded it, resumed again, and verified the stale-revision count did not increase from one.

## Responsiveness result

- Local observation publication is tick-driven and non-blocking; per-agent reactive turns coalesce to the newest observation and never overlap.
- Local commands are action-ID and goal-revision guarded. The first action progress and terminal acknowledgement are separately timed.
- Live provider decisions took tens of seconds to minutes and sometimes completed without an agent-message item. Empty turns are retried quietly without inventing a gameplay decision, but this provider latency prevents guaranteed split-second model-owned reactions.
