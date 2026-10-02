# Local survival and recovery verification

Implemented locally on top of the action-latency worktree. The selected agent remains the gameplay author: the runtime records evidence, releases unrelated input on unhandled urgent danger, and executes only authored reactions. It does not choose combat targets, escape routes, retrieval attempts or rebuilding strategies.

ArenaScript defensive watchers can use `after:"reconsider"`. Their authored actions finish before control returns to the agent; ordinary work does not silently resume. A reconsider interrupt guard also applies when already true at installation. Repeated hits, including a hit after healing, cannot recursively cancel that same reconsider handler. Other authored watchers can still escalate. Legacy watcher behavior and an explicit survival `continue_and_notify` policy remain available. Repeated notifications preserve a paused continuation. Healing and merely observing lava do not become urgent damage.

`taskMemory` and `world.taskMemory` expose the same persistent store. It retains multiple death sites, lost inventories, outbound position trails and observed workstations. Agents can record places, routes connecting named places, progress and lessons, and explicitly share selected notes with teammates. Notes and observations stay distinct. World and dimension remain explicit; a read may recall another dimension within the same world, which supports Nether recovery after an Overworld respawn. Writes stay in the current scope. Earlier equipment losses survive later empty-handed deaths and coordinator restart. Historical routes and drops require current verification, and summaries expose omissions and pagination.

The gameplay instructions prioritize survival, useful bounded defenses, recovery versus rebuilding, reuse of infrastructure and meaningful progress notes. Native base instructions remain 1,369 characters; the shared ArenaScript reference remains 12,906 bytes, within the existing limits.

## Verification

- Full Node suite: **1,854 passed, 3 skipped, 0 failed** (1,857 total).
- Java core/protocol: **15,824 assertions passed**. Voice addon: **387 assertions passed**. Gradle `check` and `build` passed.
- New regressions cover danger behind an existing decision, initial danger guards, reconsideration, explicit continuation, repeated hits after healing, memory persistence, multiple deaths, route retention, sharing, missing facts, capacity/pagination, bounded summaries and cross-dimension recall.
- Live headless Fabric fixture: **5/5 checks passed** on the final JAR. The new case ran real mining, injected two damage events with explicit healing between them, cancelled mining, completed the authored retreat, and remained suspended until an explicit program decision. Input/action assertions used the authenticated production bridge and real server results.
- Latest live fixture: **97.46 ms** from issuing the first damage command to retreat dispatch; the entire case took 1,770 ms. This is one mechanics measurement, including command/bridge/tick time. It is not a model inference benchmark or a statistical latency claim.
- Synthetic warm-cache memory check, 1,000 observations: ingestion p50 **0.0585 ms**, p95 **0.1148 ms**; recall p50 **0.0441 ms**, p95 **0.1124 ms**; final durable flush **7.02 ms**. These measure coordinator memory overhead, not Minecraft success or model decisions.

The live test used deterministic fixture authorship and **zero provider calls**. It proves execution, cancellation and memory routing, not that a model will always author an effective defense. A long two-agent diamond-pickaxe run, natural mob combat, recovery of real death drops and arbitrary-seed game completion remain unverified with the new version.

## Artifact and evidence

Built `build/libs/arena-agents-0.2.0.jar`, **3,388,804 bytes**. SHA-256:

`753F9DBDB8A775CDDEB803F1531A7E58428251C451DEFE5329DEC9893D142270`

The final live fixture installed exactly this hash in its isolated server. The packaged memory modules, engine, parser, interpreter and gameplay instructions match local source. Test agents and server processes were cleaned up; memory was flushed. Desktop's installed mod and GitHub were not changed.

Local evidence (ignored runtime artifacts):

- `runtime/survival-all-node-tests.txt`
- `runtime/survival-gradle-build.txt` and `runtime/survival-final-build.txt`
- `runtime/survival-memory-latency.json`
- `runtime/action-speed-runs/run-1790924360244-3be4e503/matrix-report.json` and its `player-capability-report.json`

Published implementation plan: https://cs9uszbutj3x.postplan.dev
