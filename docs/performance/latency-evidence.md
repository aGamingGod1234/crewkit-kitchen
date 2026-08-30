# Latency evidence and remaining hooks

The latency tools refuse to certify a 2x claim from synthetic, incomplete, duplicated, or mismatched evidence. `coordinator/config/latency-acceptance.json` is the machine-readable policy.

## Comparable trials

Every baseline and optimized sample must have a unique `trialId` plus `repetition`. A pair is valid only when these workload fields are identical:

- scenario ID and seed
- agent load and cold or warm session state
- provider, model, reasoning effort, and service tier
- evidence source, such as the Fabric headless runner
- a workload configuration hash that excludes the implementation arm and code revision

Each arm must also carry its own nonblank implementation `sourceHash`. Those hashes establish which code ran and are expected to differ between baseline and optimized builds.

Claim latency uses `timingScope: "full_path"`. In the deterministic runner, `durationMs` covers scenario resolution, provider and coordinator startup, and task execution, matching the historical baseline scope. `taskDurationMs` is the narrower post-setup diagnostic, while `setupDurationMs` reports the preceding setup portion. A task-only sample cannot enter the claim gate.

The gate uses nearest-rank p95. Five samples therefore select the slowest sample. Repeating or copying a repetition does not increase the sample count because duplicate identities are rejected.

## Required live evidence

Every passed trial in every required cold/warm and 1/8/16-agent cell must contain all of the following:

- a real provider path with `mode: "live"` and `synthetic: false`
- authoritative factual success
- a monotonic goal-to-action-accepted span
- a monotonic voice-first-audio span
- a bounded server tick-duration p95 for the scenario window

Missing action, voice, or tick evidence fails the gate. Voice is not optional. `minecraftMspt` is a distinct aggregate and is never accepted as tick p95.

The deterministic runner already measures action acceptance and virtual tick wall duration. `scripts/run-latency-headless.ps1` runs the real Fabric server, coordinator, and provider path five times at 1, 8, and 16 agents, but it currently emits honest cold samples only and does not yet provide every required span. Its output is diagnostic evidence, not a passing 2x claim artifact.

The remaining production hooks are:

- provider-session lifecycle markers for process start, session creation, and first request on an existing session
- a bounded Fabric per-tick duration histogram with count, p50, p95, p99, and max for the scenario window
- voice markers for utterance accepted, synthesis started, first PCM byte, playback queued, and playback started
- a real-server monotonic goal-to-action-accepted span

## Instrumentation overhead

The instrumentation comparison runs two counterbalanced rounds: disabled/enabled, then enabled/disabled, or the inverse when requested. Disabled runs turn off the latency tracker, benchmark recorder, control-latency registry, and system sampler. The check uses nearest-rank p95 of paired enabled/disabled full-path duration ratios. It also requires nonblank matching action-command and scenario-outcome hashes, so missing behavior evidence cannot pass as null parity.

Run the final claim gate through `scripts/run-performance-reliability-verification.ps1 -ClaimTwoX` with baseline, optimized, instrumentation, and policy files. Missing live credentials, warm evidence, exact workload pairing, required spans, factual parity, sample counts, or instrumentation comparison makes the command fail.
