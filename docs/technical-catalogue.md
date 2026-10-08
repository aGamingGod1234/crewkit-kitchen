# Technical catalogue

The hard technical problems in Arena Agents, how each was solved, and what changed, in the order they were tackled. Written for the YouTube deep-dive: plain problem, solution and result, with numbers labelled measured or estimated.

Reconstructed on 2026-10-08 from the merged pull requests, the repository's plans and benchmark reports, and the T3 Code sessions where the work happened. Where a number could not be verified it says so. New entries are added with each pull request (see `AGENTS.md`).

## PRs #1 to #11 (Aug 18 to 29, 2026)

Dates are the PR merge date (UTC). PR #7 and #8 were never merged; #8 was split into #9, #10 and #11, so its content is credited to those.

### Testing real AI players without opening Minecraft
*PR #3 · Aug 19*
- **Problem:** The only way to know whether an AI agent really played was to watch Minecraft by hand. Random terrain made valid movement fail, the bridge used a fixed port, an open TCP port was mistaken for a logged-in coordinator, and a "pass" could be recorded even when Minecraft later said the action failed.
- **Solution:** A headless runner that boots a throwaway Fabric server per scenario, waits for an authenticated handshake, builds a flat test platform, sends a real task to a real provider, and only passes when Minecraft's own final action result says it succeeded. Everything is torn down afterwards, including the Windows process tree.
- **Result:** One live run on Fabric 26.1.2 with `gpt-5.6-sol` (high reasoning, fast tier) passed end to end: move_to `SUCCEEDED / DESTINATION_REACHED`, chat `SUCCEEDED / ACTION_COMPLETED`, program `FINISHED`, lifecycle `COMPLETED` (measured, PR description). 373 coordinator tests and 6,110 Java assertions passed (measured). No latency benchmark recorded.
- **Sources:** PR #3; T3 thread "Run Minecraft Testing System"

### Importing Minecraft structures from the internet without trusting them
*PR #4 · Aug 19*
- **Problem:** The arena needed a tower built from community map data, but downloaded archives can be malicious or corrupt, block data differs between Minecraft versions, and AI-generated map data had been only weakly validated.
- **Solution:** A fail-closed map pipeline: licensed sources only, no redirects, safe archive extraction, crash-safe journals, bounded parsing, and exact block-state checks against Minecraft 26.1.2. Four verified, upgraded Re-Structured modules plus a project-owned starter room make a five-stage "Thinking Tower"; structures containing entities, loot, jigsaw or control blocks are rejected.
- **Result:** Repeated conversion is byte-identical (measured, PR description); the pinned 26.1.2 catalog holds 29,873 validated block states (measured); 55/55 Python map tests, 422/422 coordinator tests, 8,633 Java assertions passed (measured). The same PR added 64 distinct validated agent skins. Live in-game visual QA was explicitly left as follow-up. No latency benchmark recorded.
- **Sources:** PR #4

### Agents that took 30 seconds or more to do anything
*PR #5 · Aug 23*
- **Problem:** Agents often waited 30 s or longer before a first visible action or a reply to a direct message. The old path asked the model for a big serialized plan, then pushed it through several scheduling layers, and each action's result only came back at the next planning call. Urgent player messages also sat behind the active model turn.
- **Solution:** The chosen model became the agent's only brain. Each agent keeps a persistent tool-enabled Codex thread, prewarms it as soon as it is ready, and calls Minecraft tools (observe, move, mine, craft, chat, short sequences) inside the same live turn. Urgent events are steered into the running turn instead of waiting.
- **Result:** Single-agent first visible action: 53 s, then 141.8 s, and a commonly observed 30 s floor on the Desktop (measured, earlier real-game runs) → 1.71 s (Luna low) / 1.87 s (Luna xhigh) warm (measured, authenticated Codex with simulated Minecraft results). That is 93.8-98.8% faster by the thread's own arithmetic, but it is a provider-path number, not an end-to-end game run. 16 concurrent agents: typical first actions 1.4-2.7 s (Luna low) and 1.8-2.9 s (Luna xhigh); steering accepted in about 1 ms (measured). Cold-thread first action stayed slow at 6.67 s low / 5.77 s xhigh (measured). A 10-trial alternating A/B on Luna xhigh showed no broad regression: warm DM first action 1.829 → 1.766 s (-3.4%), mine 4.386 → 4.012 s (-8.5%), but cold DM first action 4.949 → 5.560 s (+12.3%) (measured, P50). Open limit: the model cannot issue another tool while a Minecraft result is unresolved. Not measured: live provider load at 8 and 16 agents in a real Minecraft server.
- **Sources:** PR #5; `reports/native-tool-loop-performance-2026-08-22.json`; `reports/native-tool-loop-three-runs-2026-08-22.json`; `reports/native-tool-steering-ab-2026-08-23.json`; T3 thread "Run Minecraft Testing System" (comparison table, Aug 22)

### The "optimization" that made 16 agents 3.4 times slower
*PR #5 · Aug 23*
- **Problem:** An earlier optimized build looked fine for 1 to 4 agents but got far worse when 16 agents planned at once. The comparison was also confounded, because the baseline allowed 16 simultaneous planning slots and the optimized build only 4.
- **Solution:** The team kept a before-implementation baseline file with the exact commits and stated the confound, then normalized and re-measured the fixed scheduler, and eventually replaced the planning queue with the per-agent persistent loop above.
- **Result:** 16-agent stone task 3.184 s → 10.721 s (+236.7%) and 16-agent DM 2.640 s → 10.127 s (+283.6%); planning p95 3.3 ms → 7,529 ms (measured, deterministic simulator with delayed provider replay, 5 reps per cell, 50/50 passed). The fixed ArenaScript scheduler came back to 3.279 s (stone) and 2.708 s (DM) normalized (measured, per the thread's table). 1-4 agent cells were within about 1% (measured). One real Desktop baseline run of "gather wood, craft a wooden pickaxe" also failed: first action at 53 s, reported complete at 143 s, but the pickaxe was not in the inventory (measured, single run).
- **Sources:** `reports/performance-baseline-2026-08-22.json`; PR #5; T3 thread "Run Minecraft Testing System"

### Tests all green, real game broken
*PR #5 (fixes made on this branch, Aug 21-22; merged Aug 23)*
- **Problem:** The full suite passed (6,263 Java assertions, 359 coordinator tests) yet the first real laptop play-through failed in ways no simulator caught: the Create button always failed after launch, an agent cancelled its own walking action, one crafting script looped on a doomed recipe, and a server crash occurred on the first real launch.
- **Solution:** Each failure was reproduced as a red test from the live trace, then fixed. The coordinator now publishes the Codex model list immediately instead of waiting for Gemini and Kimi discovery. Ordinary movement observations no longer count as "attention" events that cancel the current action. Identical consecutive terminal failures stop a program. Respawn waits until Carpet has actually removed the old fake player. Lucas's rule afterwards: no build is "ready" until a live acceptance gate passes.
- **Result:** Startup race: auxiliary provider discovery could take up to 30 s sequentially while Minecraft was already authenticated (from the agent's code reading in the thread, not a timed run). Self-cancel: movement action cancelled after about 3 s (measured from live trace). Craft loop reproduced as `failedCrafts: 28`; the live trace held 32 identical invalid `break_block` attempts, 20 identical 10 s timeout retries and 26 repeated crafts (measured from trace). A DM took about 216 s to the first action although the command then reached the bridge in 5 ms (measured from trace). After the fixes the clean gate rose to 6,270 Java assertions and 368 coordinator tests. No before/after latency benchmark was recorded for these fixes.
- **Sources:** T3 thread "Run Minecraft Testing System" (positions about 437-500); PR #5

### Keeping the coordinator's child processes from escaping on Windows
*PR #10 · Aug 29*
- **Problem:** If the coordinator's root process exited, Windows reparented its children, so a short gap in process-tree polling (about 100 ms) let a provider process escape and survive as an orphan. A harmless startup failure, like a port conflict, could also be counted as a corrupt build and cause a healthy version to be quarantined.
- **Solution:** On Windows the coordinator starts inside a named kill-on-close Job Object before any coordinator code runs, so every descendant stays owned even after the root exits, and a replacement supervisor can reopen the same job without touching unrelated processes. Quarantine now needs the same attempt to authenticate and reconcile before a failure counts against the build. On Linux an equivalent session-ownership fix was proven on Ubuntu/WSL.
- **Result:** A provider spawned inside the old 100 ms gap is now killed after its root exits while an unrelated process survives (measured on WSL, per the thread). PR #10 coordinator 730/730, 8,843 Java assertions, 81 voice assertions; exact #9 to #11 integration 1,169/1,169 coordinator tests and 13,737 Java assertions (measured). No latency benchmark recorded.
- **Sources:** PR #10; T3 thread "Fix Agent Spawner and Group UI" (about positions 1360-1383)

### Minecraft decides when a goal is done, not the model
*PR #11 · Aug 29*
- **Problem:** Agents would acknowledge a task, do one action, stop, time out against a wall, or claim success when the item did not exist. Progress also got lost across reconnects and recovery.
- **Solution:** Goals are stored as frozen predicates (for example "block at X Y Z is air") that the game state itself checks, with evidence recorded. Unfinished work keeps a bounded recovery lease. Movement uses loaded waypoints, elevation-aware routing, hazard avoidance and progress supervision. Kill counts are server-authored so the model cannot weaken them.
- **Result:** Behavior change, not timed: an agent keeps working until Minecraft verifies the result, and asks for clarification when a goal cannot be represented safely. 1,088/1,088 coordinator tests, 13,158 Java assertions (measured). No benchmark recorded for completion rate or movement failure rate.
- **Sources:** PR #11; T3 thread "Fix Agent Spawner and Group UI"

## PRs #15 to #27 (Aug 29 to Sep 4, 2026)

### Shaving the coordinator's hot path, one allocation at a time
*PRs #15, #16, #17, #19, #20 · Aug 29, 2026*
- **Problem:** Every observation (a ~34 KB snapshot of nearby blocks, entities and inventory) passes through the same pipeline many times per second. The protocol guard that rejects duplicate JSON keys cost about 6x more than parsing the JSON itself, the fact-tree safety walk allocated a path string and an entry pair for every key, and the Minecraft server thread ran a full raycast on up to 512 candidate blocks only to throw most of them away.
- **Solution:** Each fix keeps output identical: visibility is checked lazily inside the ordered quota walk, the JSON scanner reads character codes instead of allocating strings and regexes, fragmented frames are no longer re-copied, labels and entry pairs are built only when an error is thrown, and inventory changes and item tags are tracked and cached instead of rebuilt every tick. Every PR is guarded by a differential test that compares against the old implementation.
- **Result:** (measured, 33.5 KB frame, 4000 iterations, 3 runs) JSON frame scan + parse 0.63 ms → 0.29 ms per frame; the duplicate-key scan alone was ~0.53 ms vs ~0.09 ms for JSON.parse. (measured, 33.8 KB fact payload, 300 iterations, alternating against main) per-watcher fact canonicalisation 1.57-1.63 ms → 1.01-1.06 ms (about 35%). Lazy visibility on a saturated test case tested 3 candidates instead of 41 (test case, not live game). Equivalence tests: 132 assertions (lazy selection, 64 seeded sets), 24 hand-picked + 2,000 random frames (JSON decoder), 44 adversarial + 400 random fact trees. No in-game benchmark recorded for #15; #19 and #20 state no figures (#19 added a fragmentation benchmark script). Caveat: a later audit (Optimize Agent Arena Latency thread) judged JSON framing "unlikely to move end-to-end latency", because model calls take seconds and coordinator work takes milliseconds.
- **Sources:** PR #15, #16, #17, #19, #20; T3 thread "Optimize Agent Arena Latency"

### Stopping actions that went stale in flight
*PR #18 · Aug 29, 2026*
- **Problem:** Minecraft kept reporting "Coordinator action revision is stale". The coordinator checked an action was still valid, then awaited the handoff to the game; during that gap a goal change or agent lifecycle event could advance the revision, so an obsolete command crossed the bridge and was only rejected by the server.
- **Solution:** The coordinator re-reads the live agent state immediately before sending, in both the native-tool and ArenaScript paths, and drops stale or disposed actions locally. The server-side check stays as the final authority.
- **Result:** No benchmark recorded. Observable outcome: a stale action no longer enqueues any bridge command (regression test), while a genuine bridge-originated STALE_PLAN is still reported. 64 focused tests passed.
- **Sources:** PR #18

### Giving agents real player controls, with a deadman switch
*PR #22 · Aug 30, 2026*
- **Problem:** Agents only had high-level actions, so they could not do precise jumps, strafing, attack timing or aiming like a human player. A stalled agent holding an input could also leave its player walking or attacking forever, and arena matches ignored their configured duration.
- **Solution:** A strict `control` action sends one bounded frame of raw input (movement, jump, sneak, sprint, attack, use, yaw, pitch, hotbar slot) for a set number of server ticks, with a 40-tick deadman that releases held controls and hands input back if the owner goes silent. Scenario clocks now finish exactly once at the configured duration, and parkour courses gained sideways variation.
- **Result:** No benchmark recorded. Observable: stalled control expires after 40 server ticks (2 s at 20 TPS, computed from tick rate); matches end on schedule with a recorded reason. PR reports 1,198 coordinator tests, 14,072 Java core assertions and 260 voice assertions passing. Third-party map geometry was kept out of the repo for lack of license evidence.
- **Sources:** PR #22

### A 19-finding security audit, fixed the same day
*PR #23 · Aug 30, 2026*
- **Problem:** Nine parallel reviewers audited the whole project and, after deduplication, found 19 issues (0 critical, 3 high, 12 medium, 4 low). The highs: ordinary players could start or replace operator-controlled agent goals through chat or voice; Gemini planning handed untrusted game text to a CLI with no enforceable no-tool boundary; and builds ran a mutable Fabric Loom snapshot. Others included reusable bridge secrets exposed to spoofed loopback services, unbounded queues, and provider subprocesses inheriting unrelated credentials.
- **Solution:** Replaced the shared bearer secret with nonce-bound mutual HMAC authentication, separated bridge and voice credentials, gave each provider only its own environment variables, bounded every queue and body, restricted control actions to operators, and pinned and checksum-verified dependencies. A separate bypass review after the first patch found more gaps (pre-auth socket eviction DoS, Windows ACL handling, journal growth), fixed before the PR. Gemini was disabled in production rather than shipped unsafe. SEC-001 was knowingly accepted and left unchanged.
- **Result:** (measured, from PR and thread) SEC-002 to SEC-019 fixed; `npm audit --omit=dev` 0 vulnerabilities; 1,217 coordinator tests passed (2 skipped); 14,144 Java core and 271 voice assertions passed. Rollout caveat: Java and Node protocol must deploy together. No exploit-success numbers exist.
- **Sources:** PR #23; T3 thread "Agent Arena Security Audit"

### Making agents react faster under load, and refusing to claim a 2x it could not prove
*PR #24 · Aug 30, 2026*
- **Problem:** The goal was agents deciding about 2x faster. An audit found warm model turns re-sent a large planner prompt, observations were built every tick even for idle agents, urgent work (damage, speech) queued behind routine thinking, the coordinator slept a fixed 3 s at startup, and voice endpoints published late. An earlier experiment showed that capping planning concurrency from 16 to 4 made 16-agent runs about 3x slower, so a blunt cap was ruled out.
- **Solution:** Warm provider sessions with compact prompts, priority scheduling with reserved urgent capacity and cancellation fencing, raycast limits and trusted-fact reuse, cached protocol frames, faster voice endpoint publication, and removal of the startup delay for the owned coordinator. A paired A/B benchmark was built to fail closed on incomplete or mismatched runs. An adversarial review of the combined branch found regressions that normal tests missed (planner calls exceeding concurrency after an ignored abort, one speech channel falsely healthy, an occluded-world raycast spike), all fixed before the PR.
- **Result:** (measured, fixtures stated in the PR) repeated planner contract 8,681 → 485 bytes on durable continuations, i.e. warm prompt payload 94.4% smaller; voice endpoint published 140 ms earlier; ArenaScript fact reuse 0/50 → 50/50; 16-watcher fixture 27 → 438 stable observations per second; 8x fewer entity line-of-sight checks in crowded scenes. The deterministic A/B run (8 arms, 40 repetitions) matched outcome hashes but showed no overall 2x wall-clock gain, and the PR says so. Baseline warm first-tool latency was ~1.7-2.4 s and cold first action ~5-8 s (existing repo evidence, mostly model time). The 438/s and 140 ms figures are quoted from the PR; I did not find the raw artifacts.
- **Sources:** PR #24; docs/plans/2026-08-30-agent-latency-performance-optimization-plan.html; T3 thread "Optimize Agent Arena Latency"

### Surviving crashes, reconnects and bad installs
*PRs #25 and #26 · Aug 30 and Aug 31, 2026*
- **Problem:** A nine-agent reliability audit found failure paths where a finished result could be lost across a disconnect, completion retries stopped forever after a bridge outage longer than five seconds, cleanup could leave stuck inputs or items, one player's voice overload could hit unrelated players, and installs could leave the coordinator and bundled Node on different versions. A follow-up audit added: action results were not durable across a crash, arena resets had no safe confirmation or evacuation, and offline test servers could bind beyond loopback.
- **Solution:** Acknowledged terminal-result replay, a checksummed append-and-fsync action journal with torn-tail recovery, transactional input leases, per-player voice backoff, confirmation before destructive arena rebuilds, loopback-only validation, and transactional distribution updates that roll back as one unit with a pinned, checksum-verified Node.js 22.23.2. New Windows and Ubuntu CI gates exposed platform assumptions in existing tests (Windows paths, timing on shared runners).
- **Result:** (measured, from PRs) #25: 1,221 coordinator tests, 14,290 Java and 277 voice assertions, 119 embedded coordinator files verified in the jar, rollback tested at injected failure points. #26: 1,320 coordinator tests (1,317 passed, 3 skipped), 18,908 Java and 298 voice assertions, Windows distribution 0.2.0 with 142 files, and a real staged Fabric boot with the bundled coordinator. No recovery-time or failure-rate benchmark recorded.
- **Sources:** PRs #25, #26; docs/plans/2026-08-30-application-reliability-sweep.html; T3 thread "Improve Agent Arena Reliability"

### Agents that only claim what the world confirms
*PR #27 · opened Sep 3, merged Sep 4, 2026*
- **Problem:** Agents looked functional while the game disagreed. Clicking Create once could spawn five agents because a regression left the button live. Movement could report arrival while blocked, mining could advance while aiming at air, proximity voice entered "processing" without real speech, and agents inherited Lucas's global Codex instructions instead of arena rules.
- **Solution:** Success is now decided from what Minecraft shows (observed position and collision, target in reach, live break progress, verified block change) rather than timers or controller intent. Creation uses a one-shot gate that rejects duplicate submits and releases only on a failed send; each agent gets an isolated Codex home; voice requires sustained speech and reports explicit listening, processing and no-speech states; release installs became transactional across both jars, the coordinator and Node.
- **Result:** (measured, real headless Minecraft) exact oak-log mining passed 3/3 with an independent RCON check that the block was air; a movement run reported arrival in 10.4 s only after a verified standing position (thread report). Create gate: 5 attempts → exactly 1 dispatch and 1 close, and a failed send allows one retry (unit test, not an in-game demo). Suites: 1,341 coordinator tests (1,338 passed, 3 skipped), 374 voice assertions. A live mining run also showed that adding the word "action" to a goal changed its completion check to operator confirmation, which the factual check correctly refused to skip.
- **Sources:** PR #27; T3 threads "Prevent Duplicate Agent Creation", "Fix Voice Chat and Goal Responses"

## PRs #30 to #37 (Sep 4 to 20, 2026) and the Ender Dragon feasibility run

### An agent that treats death as a brand-new game
*PR #31 · Sep 4*
- **Problem:** When an LLM agent died, it behaved like a new episode. It re-crafted a stone pickaxe it had just lost on its corpse, and once it had tools it stalled instead of going to look for Nether or structure facts. Minecraft is an exploration game, and death is the same run.
- **Solution:** A coordinator-side wrap, with no extra planner call and no new Java tactics. It remembers the last live state across death and disconnects, separates what is on the agent now from what it lost, and offers hints such as recover corpse, explore frontier or interact with a cue. These are options only. The wrap never dispatches an action, so the model still decides.
- **Result:** Observable outcome only: death keeps the goal alive, and "already have" is computed from current inventory, not corpse loot. Tests: 74 coordinator wrap and native-tool tests passed (measured, PR description). The PR diff was +2,731 / -40 lines over 15 files. No live before/after benchmark recorded.
- **Sources:** PR #31

### Making a fake player look like ChatGPT, Claude or Gemini
*PR #32 · Sep 5*
- **Problem:** Agents were visually generic, and the in-world bodies are Carpet fake players, so changing the old entity texture path changed nothing the audience would see. Staging a repeatable short scene also needed hand-placing every actor.
- **Solution:** Generated deterministic 64x64 pixel-art brand skins, with each logo confined to the 8x8 front-face UV square. These are routed through the identity lookup that the roster, fake players and legacy renderer all use. On top came an opt-in, world-persisted, server-authoritative Skit Mode with named summons, placement, saved timelines and playback.
- **Result:** Brand skins for ChatGPT/OpenAI, Claude, DeepSeek, Gemini and Kimi. DeepSeek has a skin but no model backend, and the PR says so. Verified with the skin generator `--check` and `verifyCore`: 19,003 protocol and bridge assertions passed (measured). The PR diff was +3,479 / -89 lines over 173 files. No rendering benchmark recorded.
- **Sources:** PR #32; docs/plans/2026-09-04-agent-skit-mode.html; docs/agent-logo-skins.md; T3 thread "Add Agent Skins and Skit Mode"

### Deleting 12,881 lines without losing a feature
*PR #33 · Sep 5*
- **Problem:** A retired fixed two-client control stack still sat beside the live provider paths. It bloated the release and kept expensive observation and journal work on hot paths even when nothing had changed.
- **Solution:** Parallel Luna subagents in isolated worktrees removed the legacy client stack and dead executor branches. Idle agents now get per-agent heartbeat cadence and an unchanged-observation fast path. Bridge messages are sized by exact UTF-8 length, and the action journal compacts by streaming. A retained-feature audit script checked that camera, skit, voice, map and pathfinder features survived.
- **Result:** Footprint (measured by git): +889 / -12,881 lines over 107 files. The post-merge audit reported 11,992 fewer tracked lines, 7,437 fewer production-source lines, and main/client Java down from 67,138 to 59,714 lines (measured, audit pasted by Lucas). Production packaging went to 96 coordinator files. Coordinator tests were 1,410 passed, 3 skipped, 0 failed. One idle agent "schedules roughly 90% fewer observations" (estimated from the audit; the method is not in the sources). No CPU or FPS benchmark recorded; PR #34 says live gains still need one.
- **Sources:** PR #33; T3 thread "Optimize Performance, Reduce Code"

### Proving a cache is safe: stationary agents stop re-casting 91 rays
*PR #34 · Sep 5*
- **Problem:** Every observation rebuilt the same 91-ray landmark fan for an agent that had not moved, and re-asked the level whether the same chunks were loaded. A naive cache would serve stale terrain after a block changed.
- **Solution:** A bounded cache keyed on exact eye pose, view angles, dimension and collision context, plus bounded regional mutation revisions. Block writes, chunk loads and animated shapes (shulkers, pistons) invalidate it, and a secondary line-of-sight check stays fresh. The coordinator also shares one cloned observation instead of two.
- **Result:** A 200-update observation benchmark with the same 13,467-byte observation went from 600 to 400 structuredClone calls (measured, independently reproduced). A headless Fabric boot reached "Done", and 10 transformed-runtime assertions covered same-tick writes, direct chunk writes, distant mutations and shulker/piston animation (measured). A first live smoke failed because the access interface sat in Fabric's reserved mixin package; it was moved and then passed. No CPU, FPS or TPS figure was recorded.
- **Sources:** PR #34; T3 thread "Optimize Performance, Reduce Code"

### A twelve-reviewer audit that hunted bugs at chunk borders
*PR #35 · Sep 7*
- **Problem:** Failed control actions could leave physical keys held down or throw out of the server tick. Sight rays could load unloaded chunks at exact chunk boundaries, because vanilla clipping expands its start backwards. Invisible light blocks leaked into observations, and camera playback broke on respawn.
- **Solution:** Twelve subagents reviewed all 31 changed files and the adjacent callers in two waves, each fix had a separate file owner, and the primary agent reviewed them together. Fixes: cleanup survives partial input failures, camera sweeps are supervised, the sight guard covers the backward expansion and corner chunks, invisible blocks need a visible fluid, and camera playback stops on player replacement.
- **Result:** A fixed-seed 100,000-ray traversal found no returned endpoint entering an unloaded chunk after the fixes (measured). Java checks: 14,726 core assertions and 385 voice assertions. Coordinator suite: 1,428 total, 1,425 passed, 3 platform skips, 0 failures. Five camera-mixin checks ran under real Fabric class transformation. CI passed on the final head. Not measured: live provider sessions, FPS/TPS, speech playback.
- **Sources:** PR #35; reports/pr35-followup-audit-2026-09-07.md; T3 thread "Deep Audit PR 35 Improvements"

### Playing Minecraft with zero vision
*PR #37 · Sep 8*
- **Problem:** Agents had to do ordinary player tasks through text and tools alone, but inputs were missing, observations were sparse, menus were incomplete, and action outcomes got lost. An independent safety controller also picked some reactions outside the model, which broke the rule that the AI is always the brain.
- **Solution:** Native tools and ArenaScript now share one validated action contract with focused observation queries and bounded model-written programs. Added: richer inventory, menu, recipe and event facts, ordinary menu clicks, boat and mounted-jump inputs, persistent world-scoped memory, and action receipts that reconcile unknown outcomes. A compact "hold N items from this set" goal lets the model express category goals. The tactical safety controller was removed.
- **Result:** Production-bridge mechanics: 9 of 9 checks passed on Minecraft 26.1.2 with no model involved (measured). Node suite 1,656 passed, 3 skipped. Java: 15,291 core assertions and 385 voice assertions. In a natural world, Codex Sol (low reasoning, priority tier) collected a log in 68 seconds through nine model-written actions (measured). Kimi K3 timed out at the 240-second budget during goal translation, before any gameplay action (the 240 s figure comes from the dragon thread). Full-game completion was not proved. The PR diff was +16,069 / -3,148 lines over 176 files.
- **Sources:** PR #37; docs/plans/2026-09-08-zero-vision-player-capability.html; T3 threads "Universal Game-Playing Agent Tools" and "Agent Feasibility: Ender Dragon Run"

### Can the agent kill the Ender Dragon? An audit that found the tools could not hit it
*Investigation after PR #37 · Sep 9-12 (branch codex/zero-vision-player-capabilities; no merged PR in range)*
- **Problem:** Lucas asked whether the tools could guide an agent to beat the game on a random seed. A read-only audit found the convenient combat tools could not target dragon body parts, a bed-explosion kill had no player in its damage source so credit would fail, and all 169 saved observations replayed through the formatter exceeded the 16 KB result limit (38 lost a non-empty entity list). A re-audit also found that dragon kill credit could vanish if the game was paused or reconnected during the death animation.
- **Solution:** A shared resolver for dragon-part targets, melee aimed at the part's true center (its eye position can sit outside its hitbox), and deterministic handling where head and neck hitboxes overlap. Kill credit now follows the logical goal and survives reloads. Added single-use item controls, explicit bow aiming, causal credit for bed explosions, and filtered queries.
- **Result:** Controlled fixtures: 14 of 14 live mechanics checks passed (measured). The dragon kill goal completed 10.05 s after the lethal hit, and 10.03 s with a pause/resume and a world save during the death animation (measured). Nether bed-explosion credit passed. Totals: 15,313 Java core assertions and 1,672 Node tests. The 14-check run took 30.1 s. A random-seed win was not attempted; the thread says it "remains unproven".
- **Sources:** T3 thread "Agent Feasibility: Ender Dragon Run"

### Rehearsing a 5-minute demo: 160 seconds down to 63
*Investigation after PR #37 · Sep 10-12*
- **Problem:** For a stage demo (collect four logs, return, deposit in a chest), the first isolated rehearsal with a real Codex agent failed Lucas's zero-error bar: 5 failed tool calls out of 27 plus 3 failed completion checks (measured), and 3 min 13 s including the operator-confirmation wait. Causes included stale plans arriving after goal completion, a "Fast" tier the provider confirms as "priority", and a mining tool description that said "visible" when it needed the exact block under the crosshair.
- **Solution:** Goal completion now interrupts the turn so stale calls are blocked. Pickup, mining and movement results carry fresh inventory and nearby-item facts. The agent runs in its own workspace with its own AGENTS.md and skill, with global instructions kept out. A broken Windows trace-file helper was fixed. Five parallel subagents handled navigation, tool feedback, instructions, isolation and measurement.
- **Result:** Same task and model settings, headless real-agent runs, timed by the agent (measured): 193 s (first run, 5 of 27 calls failed), 118 s, 160 s (one `NO_STANDABLE_PATH`, recovered), then 63 s and 107 s on the final build. The 63 s run had 12 successful server actions and none failed, about 61% less than the previous 160 s run. The 107 s run recovered from 2 gameplay failures. All final runs had four logs verified in the chest and no schema or transport errors. Log collection alone went from 135 s to 41 s in one trial. Node tests grew from 1,681 to 1,704. This is two final runs only, so consistency is not established, and the on-screen desktop rehearsal was blocked by a firewall prompt.
- **Sources:** T3 thread "Agent Feasibility: Ender Dragon Run"

### Filming a skit: saved work, honest errors and a tripod camera
*PR #36 · Sep 20*
- **Problem:** Staging a skit mixed staged actors with autonomous agents, and a cached name from another world blocked a summon because the code treated "in the shared profile cache" as "owned". Server command failures were hidden behind the Director screen, background updates interrupted typing, and a hardcore respawn pushed actors into Spectator and changed a global gamerule.
- **Solution:** A persistent Director cast with per-world name indexes, a correlated request/result channel so the form shows the server's real rejection, and a cast-only respawn path that keeps identity and returns Survival. Also added a wheeled tripod camera with lens height, pan, tilt and dolly controls, and saved camera paths.
- **Result:** Java: 15,655 core assertions, 387 voice assertions and 9 camera/presentation mixin checks (measured). Node 22 suite: 1,670 passed, 3 skipped. Native Computer Use verified the cast, tripod, recording, playback, and a hardcore respawn in which all 58 gamerules were unchanged. A physical recording holds about 102 seconds of camera movement (stated in the PR). Not verified: Fish voice playback (HTTP 401 on the configured credential) and authenticated multiplayer. The PR diff was +6,277 / -467 lines over 211 files.
- **Sources:** PR #36; reports/pr36-independent-audit-2026-09-08.md; reports/world-player-names-2026-09-08.md; docs/plans/2026-09-04-director-mode.html; T3 thread "Improve Director Mode for Skits"

## PRs #38 to #47 (Sep 22 to Oct 7, 2026)

Notes: PR #43 was closed, not merged, so it is skipped. PR #48 is catalogued elsewhere. Almost every number below comes from a controlled fixture or a small live sample, and the sources say so. Do not present them as general gameplay speedups.

### Scripts instead of one model call per action
*PR #38 and #40 · Sep 22 and Sep 26, 2026*
- **Problem:** If the model has to be asked before every step (look, walk, mine, look again), most of the time goes to waiting on the model and the body stands still between actions. Agents also kept stopping their own routines to "reconsider" and then rebuilt familiar actions from scratch.
- **Solution:** The model writes a short bounded program (ArenaScript, run with `runProgram`), or a fixed chain of actions (`sequence`), once. The game then runs the steps at tick speed, checks fresh facts between them, and calls the model back only for something new. The model still makes every decision. #38 restored this path so a routine keeps running while the model thinks, and saved routines can be reused later by a short note key.
- **Result:** (measured, simulated bridge, 20 paired runs) one conditional routine instead of separate tool calls cut the gap between two actions from 126.092 ms to 16.610 ms p50, and the routine won 20 of 20 pairs. (measured, simulated bridge, fixed 2 s "thinking" delays) a 5-action program saved 2.0 s when the model's thinking overlapped movement, and independent `startAction` steps saved 4.5 s versus serial calls. (measured, Aug 22 probe from before this range, 16-agent provider with simulated bodies, quoted in the Sep 25 plan) move-then-mine took about 4.8 to 5.2 s through separate calls versus 3.26 to 3.36 s through one sequence. (measured, live Fabric, 8 vs 8 runs) the whole oak-log task median went 24.956 s to 20.794 s (16.7% faster) on #40, but those live runs did not actually use `runProgram`. The gain came from fewer model rounds, and the four close pairs split 2 wins and 2 losses, so it is promising, not proven. (measured, live, 2 trials each) in #38's six-minute gathering test the agent reached 8 oak logs in both after-trials versus 4 and 0 before, a small sample.
- **Sources:** PR #38, PR #40, `reports/opus-speed-benchmark-2026-09-25.md`, `reports/native-successor-handoff-2026-10-02.md`, `reports/realtime-comparison-2026-09-21.md`, `docs/plans/2026-09-25-human-speed-agent-actions.html`, `docs/performance/latency-evidence.md`

### Planning the next job while the current one runs
*PR #38 · Sep 22, 2026 (handoff numbers from reports dated Oct 2, shipped in PR #41)*
- **Problem:** When a routine ended, the body went idle until the model finished thinking about what to do next. That idle gap is wasted game time.
- **Solution:** Shortly before a routine's deadline, the system reminds the same model to prepare its next routine, with the lead time set from that agent's own measured provider-wait p95. The prepared successor is only allowed to start if the predecessor finished normally and a fresh guard check on the world still passes. It never starts on its own authority.
- **Result:** (measured, simulated bridge, 100 ms assumed decision delay, 20 pairs) the finish-to-next-start gap went 127.706 ms to 33.328 ms p50 (p95 128.989 to 33.769 ms), and the queued version won 20 of 20 pairs. (measured, installed Fabric jar, 1 sample) a real successor gap was 77.435 ms. Guard cases passed: a successor whose health guard was true when cached but false at handoff was rejected with no action sent. The earlier "50 to 200 ms saved" figure was an estimate and the report says the fixture does not validate it live. No model-latency or whole-game speedup was recorded.
- **Sources:** PR #38, PR #41, `reports/native-successor-handoff-2026-10-02.md`, `docs/performance/native-successor-live.md`, `docs/performance/latency-evidence.md`

### The agent could not see a tree it had already looked at
*PR #38 · Sep 22, 2026*
- **Problem:** In a six-minute "collect 8 oak logs" test, the agent did a full camera sweep and Minecraft really reported oak leaves about 72 blocks away. But the sweep summary has a 1,400-byte limit, and it was filled with repeated grass blocks and animals before reaching the tree, so the model never heard about it.
- **Solution:** The sweep summary now keeps one of each distinct thing it saw first (entities, blocks, items, landmarks) and only then adds duplicates, still within the same byte limit.
- **Result:** (measured, replay of the 8 recorded inspection samples) oak-tree landmark retained 0 of 1 before, 1 of 1 after, with the sample slightly smaller (1,379 to 1,344 bytes). (measured, live, 2 trials each, not counterbalanced) final oak logs 4 and 0 before, 8 and 8 after, first reaching eight at 182.541 s and 222.087 s. Both after-trials still timed out waiting for operator confirmation, so full lifecycle completion was 0 of 2, and the report warns it proves neither general reliability nor a causal speedup.
- **Sources:** PR #38, `reports/gathering-recovery-2026-09-21.md`, `reports/gathering-sweep-replay-2026-09-21.json`, `reports/gathering-retest-2026-09-21.json`

### An agent that kept thinking while it was only waiting
*PR #38 · Sep 22, 2026*
- **Problem:** After gathering the logs, the agent asked for operator confirmation and then waited. But every ordinary world update woke the model again, and the two new trials burned 54 and 35 model turns that used no tools at all.
- **Solution:** The waiting state now persists across turns for the same goal and session. Ordinary updates and automatic continuation cannot wake it, but urgent operator input still can, and a new goal clears it.
- **Result:** (measured, regression test) 7 model turns before, 1 after. (measured, 60 s live Minecraft check on the final build) 1 model turn, 0 zero-tool turns, no physical actions, quiet for 48.396 s after confirmation became pending. This is evidence of quiet waiting, not completed gameplay.
- **Sources:** PR #38, `reports/gathering-recovery-2026-09-21.md`, `reports/confirmation-wait-2026-09-21.json`

### Faster handoffs: a camera win that made one task slower
*PR #40 · Sep 26, 2026*
- **Problem:** Between actions the runtime asked the server for a fresh look at the world and waited, which could cost an extra server tick (50 ms) even though the server already pushes an observation after every action. Native continuations also built metadata only model turns need.
- **Solution:** Reuse the server's pushed observation (with a fallback to the explicit request), skip model-only work when the next step is an action continuation, and bound how much result data a sequence serializes while keeping the verification facts.
- **Result:** (measured, 4 live runs per arm, earlier experiment) the camera-sweep handoff median fell 103 ms to 50 ms (p95 112 to 71 ms), but the whole-task median got worse, 25.551 s to 29.814 s, because the model happened to choose more actions that time. (measured, tick model, 40 reps per arm) 8-step look-around 1,199.737 to 799.165 ms p50 with identical action hashes. (measured, final 8-vs-8 live comparison) whole-task median 24.956 s to 20.794 s (16.7% faster), camera handoff unchanged at 50 ms (already one tick), four close alternating pairs split 2 wins and 2 losses. The reports keep both the good and bad results on purpose. A single-trial pair in the Sep 25 plan (21.675 s baseline versus 27.211 s optimized) also showed the optimized build slower, and that plan concluded no live speedup follows.
- **Sources:** PR #40, `reports/native-pushed-observation-2026-09-25.md`, `reports/opus-speed-benchmark-2026-09-25.md`, `docs/plans/2026-09-25-human-speed-agent-actions.html`

### Sending the model about 45% fewer fixed tokens
*PR #41 · Oct 3, 2026*
- **Problem:** Every model call re-sent a large instruction block, tool definitions and verbose game-state replies. That costs money and slows turns, and cutting it blindly risks the model missing a fact.
- **Solution:** Shorter always-loaded guidance (with the full control reference still available on demand), repeated rows and values within a reply shared instead of repeated, and "what changed" views made opt-in with baselines reset at the right moments. Every compact reply had to reconstruct to the original exactly.
- **Result:** (estimated, `o200k_base` token proxy, offline replay, not exact billing) instructions plus tool definitions 16,067 to 8,767 tokens (45.4% less); 148 captured tool replies 193,694 to 174,084 (10.1% less, 148 of 148 reconstruct exactly, none grew); 30 generated decision events 126,623 to 114,228 (9.8% less). (measured, 2 factual scenarios, 4 runs with GPT-6.1 Sol) reported uncached input 33,193 to 24,476, with all four runs returning the expected facts. The PR says this does not establish gameplay quality, latency or allowance savings.
- **Sources:** PR #41, `reports/native-input-encoding-2026-10-02.md`, `reports/native-input-comprehension-2026-10-02.md`

### Teaching the agent to ignore progress that does not matter
*PR #41 · Oct 3, 2026*
- **Problem:** During a long cave walk, every finished leg of movement woke the model to "reconsider", even though nothing had changed. The body sat idle waiting for an answer that always said "continue".
- **Solution:** The model can attach its own `reassessWhen` condition to a pause (health, dimension, a hostile in view, changed support blocks), so ordinary progress is waived and only meaningful change interrupts. Urgent damage always still gets through, and the runtime never chooses a route or reaction itself.
- **Result:** (measured, simulated bridge, 12 pairs, 100 ms declared response delay) a 6-leg route took about 387 to 388 ms versus 938 to 941 ms p50, body idle time 553.507 to 6.807 ms, and reconsideration responses went from 6 to 0 per route, faster in all 36 pairs. (measured, installed Fabric jar, same 6 legs, 36 successful actions) route completion median 4,449.9 ms to 3,930.7 ms, and receipt-to-next-dispatch 106.497 to 3.360 ms. Neither measures model latency or whole-agent performance.
- **Sources:** PR #41, `reports/cave-navigation-attention-2026-10-02.md`, `docs/plans/cave-navigation-preparation-2026-10-02.html`

### Long-running agents that lose their place on restart
*PR #42 · Oct 4, 2026*
- **Problem:** Hard goals like a diamond pickaxe run for a long time, and that is where things fell apart. Context could be marked "delivered" before the live model session got it, unread messages were stranded after a restart, and cancellation ownership got lost during recovery. Benchmark receipts could also claim completion without trustworthy evidence.
- **Solution:** Acknowledgement now follows exact-session delivery, replacement sessions recover retained facts, unread input and memory survive lifecycle changes (memory partitioned by owner), and steering keeps the goal's spatial and survival limits. A 19-finding review follow-up fixed the remaining recovery and benchmark-receipt holes.
- **Result:** No speed benchmark recorded. (measured) Windows coordinator run 2,546 passed, 0 failed, 3 skipped; all 114 embedded coordinator files match source; the full CI workflow passed on the final commit. The PR states that token savings, model quality and diamond-pickaxe success remain unproven.
- **Sources:** PR #42, `docs/performance-reliability-followup.md` (linked from the PR), T3 threads "Review PR 42" and "Improve Agent Performance and Reliability"

### Actions stuck behind slow reads and disk writes
*PR #44 · Oct 5, 2026*
- **Problem:** Lucas asked for the agent's decided actions to reach the game as fast as possible. A review by 19 Astra and 2 Opus reviewers reproduced that cancellations queued behind slow reads, stale inspections stalled or failed a running program, disk writes sat directly on the action path, and results were truncated before compression even when the compressed answer fit (32 of 64 blocks sent in one synthetic case).
- **Solution:** Exact controls skip slow reads, each call tracks its own deadline, and valid receipts are released without waiting for durable bookkeeping. Repeated notebook snapshot writes became a checksummed, synced append-only journal, and stale inspections are fenced off.
- **Result:** (measured, simulated bridge, disk-backed, 30 alternating pairs, identical action hashes) receipt-to-next-send handoff 6.92 to 3.52 ms p50 and 11.25 to 5.95 ms p95. No installed-game, real-provider or power-loss latency was measured. The PR flags that older builds cannot replay the new journal, so saved state should be backed up. (measured) 2,683 coordinator tests passed, 3 skipped; 2,526,075 core assertions passed.
- **Sources:** PR #44, T3 thread "Review Game Agent Tool Calls"

### Watching and taking over an agent without teleporting yourself
*PR #45 · Oct 6, 2026*
- **Problem:** Vanilla spectator mode teleports your body to the target, hides the agent's hotbar, hearts and screens, and snaps you back every tick. A vanilla client can also only keep chunks loaded around one point, and remote players never receive food, XP, effects or hotbar contents. There was also no way to grab the controls of an agent.
- **Solution:** A custom `/spectator` view binds the client camera to the agent and draws vanilla's own HUD from a stand-in player fed by a per-tick state stream, while chunk, entity and sound tracking follow the agent and your own body stays loaded and vulnerable. `/takeover` pauses the model, leases the agent's body to the operator's input, and ends automatically if the operator's body loses 2 hearts, then resumes the model with a report of what changed. The same PR added the Claude provider.
- **Result:** No benchmark recorded. (measured) `verifyPovMixins` confirmed all 26 mixin target classes transform under Fabric Knot; about 400 new core checks passed; a real dedicated server booted with 43 mods in the smoke test. Camera, HUD feel and chunk following were not yet tested in a real client at merge time.
- **Sources:** PR #45, `docs/plans/2026-10-06-agent-pov-takeover.html`, T3 thread "Add Claude Models and Instructions"

### An agent that silently never thinks
*PR #46 · Oct 6, 2026*
- **Problem:** Launching a Claude agent on a machine without the Claude Code CLI produced no warning. The agent stood still and never thought, and the same happened with Codex or Antigravity when signed out or broken. The error was only sent as `agent_error`, which the server drops for agents with no active goal.
- **Solution:** A health probe at registration checks installed, working and signed in. A new `agent_notice` message always goes to chat with a fix hint and is replayed to late joiners. The same PR rebuilt first-person `/spectate` to show the agent's hands by hooking the hand renderer itself, which also covers Iris.
- **Result:** (measured, headless Fabric server plus coordinator on the Mini PC, `claude` removed from PATH) the chat notice arrived within the same second the agent joined, while a signed-in Codex agent produced no notice. No latency benchmark; the Iris and Axiom hand rendering was not yet checked in a live client.
- **Sources:** PR #46 (description and its headless check comment)

### Codex and Gemini vanished from the menu
*PR #47 · Oct 7, 2026*
- **Problem:** The Provider selector in the summon menu only offered Claude, so clicking it did nothing. Codex and Gemini had disappeared.
- **Solution:** The cause was that at connect the coordinator sent a model list containing only providers with saved agents, and the Minecraft bridge treated any non-empty list as complete and stopped asking for more. After startup reconciliation the coordinator now refreshes and publishes every provider, off the startup path so a slow or missing CLI cannot delay saved agents.
- **Result:** No benchmark recorded. (measured) a new regression test fails without the fix and passes with it; coordinator `dynamic-main` tests 55 of 55 pass; the full suite had 2,675 pass and 2 fail, the same two Windows ACL tests that also fail on unmodified main.
- **Sources:** PR #47, T3 thread "Restore Codex and Gemini Providers"

## PR #48 (Oct 7 to 8, 2026)

Notes: these numbers come from replaying recorded play sessions offline, headless servers and live play-testing. Cost figures are API-equivalent estimates at Claude Opus 5.5 list prices; the project actually runs on a Claude subscription through Claude Code.

### Long runs and the context bill
*PR #48 · Oct 8, 2026*
- **Problem:** Every agent kept one conversation that never stopped growing, and every model call re-read all of it, averaging about 87k tokens per call and climbing. The usual ways out are both expensive. Filling a 1M window means paying cache reads on up to 900k tokens per call (about $0.18 a call on Opus 5.5). Letting the provider auto-compact means the model writes a summary in output tokens and the cache restarts.
- **Solution:** At about 64k tokens the coordinator ends the conversation and starts a fresh one. It writes a short recap itself, with no model call, and every update already restates the goal, plan, notes and current facts. For our context growth rate, the cheapest point to reset works out at roughly 60 to 65k tokens.
- **Result:** (estimated, from a 58-minute Opus session's call counts) input tokens per hour of play fell from about 70M to about 20M. (measured offline, replaying a recorded Sol session) Codex input fell from 6.9M to 3.2M tokens (−53%). (estimated, 95% cache hits) the API-equivalent cost of that 58-minute Opus run fell from about $37.50 to about $13.20.
- **Sources:** PR #48 Technical catalogue, `coordinator/src/claude-service.mjs`, `coordinator/src/codex-service.mjs`, the T3 thread "Cut model token cost per session"

### The model was woken up for nothing
*PR #48 · Oct 7, 2026*
- **Problem:** While a script was running fine, every routine sighting woke the model, about every 3 seconds. 310 of 358 turns ended with the model doing nothing, and the model was busy about 90% of the time even at low reasoning effort. It also polled for status 93 times.
- **Solution:** While a script runs, routine updates are batched to at most one every 30 seconds. Danger, lava, damage and failures still wake the model immediately, and the tools now tell the model that results arrive on their own.
- **Result:** (measured, session replay) wake-ups fell from 310 to 41, and stale rejected answers from 17 to 0. (estimated) model busy time fell from about 90% to about 62%. (measured) a typical update shrank from 15.6 KB to 6.7 KB.
- **Sources:** PR #48, `coordinator/test/token-budget.test.mjs`, `coordinator/test/program-attention-throttle.test.mjs`

### Too slow to survive a fight
*PR #48 · Oct 7, 2026*
- **Problem:** A model turn takes several seconds, but a zombie hits about once a second and a creeper explodes 1.5 seconds after it starts hissing. The model only heard about a mob after being hit, and its "flee" was a walk that stopped at the first spot.
- **Solution:** Warnings fire the moment a mob starts targeting the agent. Fight and flee actions run until the job is done, with footing checks. The model writes its own reactions in advance ("creeper close, flee"), and they fire within a game tick. Each mob carries a live, uncapped risk score built from its size, health, speed, distance and real weapon damage.
- **Result:** (measured, replayed fight) updates sent to a thinking model fell from 13 to 7. (observed, live play) agents now escape creepers that used to kill them.
- **Sources:** PR #48, the T3 threads "Fix combat follow-through and dithering" and "Threat risk, retargeting, healing"

### Fake players aren't real players
*PR #48 · Oct 7, 2026*
- **Problem:** In Minecraft 26.1, Carpet fake players took no fall damage at all. A totem save was recorded as a death, so the agent was respawned at spawn. `/kill` disconnected the agent instead of killing it.
- **Solution:** Fall damage now runs vanilla's own check for fake players. Death capture checks for a totem the same way vanilla does. `/kill` is a real death followed by an in-place respawn.
- **Result:** (measured, headless server) a 20-block drop does 17 damage, the same as a real player. Before the fix it did 0. (observed, live play) totems and `/kill` behave correctly.
- **Sources:** PR #48, the T3 threads "Find why agents take no fall damage" and "Real deaths and in-place respawn"

### Takeover felt laggy
*PR #48 · Oct 6, 2026*
- **Problem:** Driving an agent's body had about a second of visible lag. The camera followed the body through vanilla's delayed entity sync, which takes 6 to 8 game ticks.
- **Solution:** The client follows the server's per-tick pose stream directly, and the hotbar responds instantly.
- **Result:** (estimated, from tick timing) movement delay fell from about 300 to 400 ms to about 75 to 125 ms. (observed, live play) it "works much better".
- **Sources:** PR #48, the T3 thread "Cut takeover input latency"

### Agents couldn't hear
*PR #48 · Oct 7, 2026*
- **Problem:** Agents only knew what they could see, so they tunnelled one block away from lava pools that a player would have heard.
- **Solution:** Server sounds now reach the agent, and client-only ambient sounds such as lava popping are recreated using the client's own odds. The model gets direction and distance.
- **Result:** (measured, headless server) an agent heard both lava pockets through one-block walls, in the right directions. A rescan costs 0.4 to 0.9 ms on the server. (observed, live play) a Sol agent located a hidden lava pool.
- **Sources:** PR #48, the T3 thread "Add hearing to agent perception"
