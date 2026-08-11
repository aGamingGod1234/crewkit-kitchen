# Task 4C — Dimension-aware entity recovery report

## Scope

Automated and static implementation only. Minecraft was not opened and no visual testing was performed.

## Root cause

Persisted agent records contained only an entity UUID. After a server restart, `CodexAgentManager` searched only entities already present in loaded levels, while its forced chunk ticket was created only after that search succeeded. An agent in an unloaded chunk therefore could not be found to recreate the ticket that would load it.

## Fix

- Added an optional persisted entity location containing dimension identifier and chunk coordinates.
- Preserved compatibility with existing snapshots: a missing `entity_location` field decodes as empty while retaining the entity UUID.
- Persisted the location on summon and respawn, refreshed it when the agent changes chunks, and cleared it with the entity UUID on death.
- Restored the saved ticket in the exact persisted dimension before lookup; subsequent ticks use direct UUID lookup in that level and retain the legacy all-level loaded-entity fallback.
- Invalid or unavailable dimensions fail safely without creating a ticket in the wrong level.

## Test-first evidence

1. `verifyCore` failed at `compileTestJava` because `AgentEntityLocation`, `entityLocation()`, and `AgentEntityRecoveryTarget` did not exist.
2. After the persistence implementation, `verifyCore` passed with 4,766 assertions.
3. A second test-first cycle failed at `compileTestJava` because `AgentEntityRecoveryTarget` did not exist.
4. After recovery-target and manager integration, `verifyCore` passed with 4,769 assertions.

## Automated coverage

- Dimension/chunk round-trip through the registry snapshot codec.
- Legacy snapshot migration with entity UUID preserved and no location.
- Recovery target generation for located records and safe omission for legacy records.
- Full Java/client compilation through the verification task.

## Deferred boundary

A real restart with an agent saved in an unloaded Nether/End chunk remains a future headless gameplay integration test; no client or visual test was authorized for this task.
