# Latency evidence and remaining hooks

The latency tools now produce comparable task-time samples and refuse to certify a 2x claim from synthetic or incomplete evidence. `coordinator/config/latency-acceptance.json` is the machine-readable policy.

The deterministic runner covers setup spans, task duration, action acceptance, factual completion, process CPU deltas, and virtual tick wall duration. `scripts/run-latency-headless.ps1` runs the existing real Fabric server, coordinator, and provider path five times at 1, 8, and 16 agents. It emits a redacted evidence file and treats missing provider credentials as skipped unless `-RequireLive` is set.

Three production instrumentation hooks are still needed before the 2x gate can pass on real gameplay:

- A provider-session lifecycle marker must identify process start, session creation, and the first request on an existing session. The headless runner currently supplies honest cold samples only. It does not relabel a restarted process as warm.
- The Fabric server must publish a bounded per-tick duration histogram with count, p50, p95, p99, and max for the scenario window. The existing headless report exposes `minecraftMspt`, which is retained under that name and is not passed off as tick p95.
- Voice must publish monotonic spans for utterance accepted, synthesis started, first PCM byte, playback queued, and playback started. The acceptance report marks voice checks `NOT_APPLICABLE` until both arms contain that evidence.

The action span already exists in the deterministic runner. A corresponding real-server monotonic goal-to-action-accepted span should be added to the headless report so the same check applies to Fabric evidence.

Run the final claim gate through `scripts/run-performance-reliability-verification.ps1 -ClaimTwoX` with baseline, optimized, instrumentation, and policy files. If live credentials, warm evidence, tick p95, factual parity, sample counts, or the instrumentation comparison are missing, the command fails.
