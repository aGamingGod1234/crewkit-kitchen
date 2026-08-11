# Eight-Agent Performance and Reliability Report

## Result

The balanced eight-agent reliability gate passed on 2026-08-11 using only automated and headless tooling. The tested build preserves the four-turn planning limit, suppresses redundant planning, budgets server observation work, publishes bounded progress and latency telemetry, and isolates advisory telemetry failures from the authoritative server tick.

## Measured evidence

- Clean Gradle/Fabric `check`, `build`, and `verifyCore`: **5,414 assertions passed**.
- Coordinator Node suite: **134/134 tests passed**, 0 failed; a separate summary run completed in 887 ms.
- Eight-agent fake-provider soak: **50/50 independent Node processes passed**.
- Combined clean verifier and 50-run soak: **48.1 seconds** wall-clock time.
- Headless Minecraft 26.1.2 smoke: Fabric Loader 0.19.3 loaded 43 mods, including the current `arenaagents 0.1.0` JAR; the server reached `Done`, accepted `stop`, saved every dimension, and exited successfully.
- Headless JAR SHA-256: `E091C2E01BBD420CB1D8EE79A49DFBB6D98956D2ECBA73FBADCF2D72AAABBA08`.

The headless smoke used the existing ignored `runtime/nonvisual-audit-server` offline world. It did not open the normal runtime world or any Minecraft client. The dedicated optional paths `runtime/server-offline-smoke/fabric-server-launch.jar` and `runtime/server-offline-smoke/server.properties` were absent.

## Implemented bounds

- Coordinator planning remains capped at four concurrent turns by default.
- Repeated observations coalesce by agent, goal revision, and deterministic observation hash; an agent with an outstanding action cannot start overlapping planner work.
- Observation delivery is FIFO and coalesced, with at most two complete observations sent per server tick and at most 16 pending agent identities.
- Expensive block and nearby-container sections use a 16-entry LRU cache with a 10-tick freshness window and terminal-action invalidation. Health, hunger, velocity, entities, inventory, action, result, and world fields remain fresh.
- Progress emits immediately, then only after at least five percentage points of forward progress or a one-second heartbeat. Progress transport failure is advisory and cannot abort a Minecraft server tick.
- `coordinator_status.latencies` is optional and backward compatible. Each operation retains at most 50 samples, at most 16 operation names are accepted, and snapshots expose only count, p50, and p95 durations.

## Reliability coverage

Deterministic tests cover eight-agent isolation, scheduler pressure, malformed planner output, slow providers, disconnect/reconciliation, stale action rejection, observation coalescing, bounded cache and queue behavior, action-progress throttling, protocol strictness, and telemetry privacy. Existing goal revisions and action IDs remain authoritative, and terminal results are not coalesced away.

## Unverified live metrics

No interactive client or real provider process was permitted for this pass. Consequently, sustained 20 TPS under eight live Minecraft players, real-provider latency percentiles, real provider rate limits, and full gameplay quality remain unverified. The new bounded telemetry makes those measurements observable during a future authorized live run; this report does not infer them from fake-provider tests.

## Warnings

The successful build reported an existing deprecated Minecraft API use in `ServerObservationCollector`, plus upstream JOML `Unsafe` and JNA native-access warnings on Java 25. None failed the build or headless startup. The smoke server was intentionally offline and is not authenticated-player evidence.

## Reproduce

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-performance-reliability-verification.ps1
```
