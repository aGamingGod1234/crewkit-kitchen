# Task 4B client robustness report

## Status

Complete. The native control GUI now registers independently of the optional
legacy local bridge, and client bootstrap failures are contained instead of
terminating Minecraft startup.

## Root causes and fixes

- The legacy `arena-agents-client.json` feature gate returned before the entity
  renderer, hotkey, and control screen were registered. Registration now occurs
  before that compatibility gate.
- Malformed client JSON could throw unchecked parse/runtime exceptions. Config
  loading now reports the failure and safely disables only the optional legacy
  bridge.
- Legacy bridge bind/start failures propagated out of the client initializer.
  Startup is now guarded and failures are logged without taking down the client.
- Snapshot delivery can race during polling or reconnects. The client snapshot
  store now rejects older timestamps and clears on disconnect.

## Files changed

- `src/client/java/dev/agaminggod/arenaagents/client/ArenaAgentsClient.java`
- `src/test/java/dev/agaminggod/arenaagents/client/ArenaAgentsClientBootstrapVerification.java`
- `src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java`
- `src/client/java/dev/agaminggod/arenaagents/client/control/AgentControlClient.java`
- `src/main/java/dev/agaminggod/arenaagents/control/AgentControlSnapshotStore.java`
- `src/test/java/dev/agaminggod/arenaagents/control/AgentControlVerification.java`

## Verification evidence

- Red checks first failed because the bootstrap containment and snapshot store
  behavior did not exist.
- `verifyCore verifyEntrypoints` passed with 4,763 assertions.
- `clean check build` completed successfully.
- The packaged JAR contains the client controller, native screen, server sync,
  snapshot classes, and language resource.
- No rendered Minecraft client or GUI was opened.

## Remaining boundary

Visual layout, focus behavior, and real mouse/keyboard interaction remain for a
later client-side pass because this phase is explicitly automated/headless only.
