# Gathering after preserving camera-sweep sightings

The repeated six-minute Minecraft task now reached **eight oak logs in both trials**, compared with **four and zero logs** in the two retained pre-fix trials. Both new runs remained incomplete because their generated goal predicates also required operator confirmation. This is a small exploratory comparison, not proof of reliable survival or a general speedup.

## Cause and change

The initial camera sweep really saw oak leaves at `(-56, 101, -43)`, about 72 blocks away. The sighting was tenth in its landmark list, behind repeated grass blocks. The sweep's 1,400-byte summary filled with redundant nearby terrain and animals before retaining that distinct landmark. The model therefore lacked information that Minecraft had already supplied.

The runtime now gives distinct observed types a first pass across entities, blocks, items and landmarks before retaining duplicates. Historical timestamps, identities and omission counts remain explicit. The full sample, including omission metadata, must fit the existing byte limit. No route, target or gathering tactic is selected by this change.

Replay of the original eight fresh inspection samples through `NativeToolRuntime.lookAround` reproduces the loss and verifies the fix. Action acknowledgements in this replay are simulated; the observations are recorded Minecraft facts.

| Recorded sweep result | Before | After |
| --- | ---: | ---: |
| Oak-tree landmarks retained at heading 135 | 0 of 1 | 1 of 1 |
| Bytes in that heading's sample | 1,379 | 1,344 |
| Bytes in serialized eight-heading result | 11,967 | 11,706 |

[Raw replay samples and source hashes](gathering-sweep-replay-2026-09-21.json).

## Live comparison

Both builds used the same request, fresh Normal survival worlds, seed `20260920`, Codex `gpt-5.6-sol` with low reasoning and priority service, and a 360,000 ms scenario budget. No items or terrain repairs were supplied. Before is the two retained realtime-build trials preceding this fix, not the older pre-realtime implementation. After trials ran later, so the order is not counterbalanced and provider/model variation remains a limitation.

Request: `Collect 8 oak logs with a reusable background runProgram routine saved in your notebook. Read programReference before authoring the routine.`

| Trial | Final oak logs | First observation of eight logs after goal start | Full lifecycle |
| --- | ---: | ---: | --- |
| Before 1 | 4 | Not reached | Timed out |
| Before 2 | 0 | Not reached | Timed out |
| After 1 | 8 | 182.541 s | Timed out awaiting operator confirmation |
| After 2 | 8 | 222.087 s | Timed out awaiting operator confirmation |

Inventory target met: **0/2 before, 2/2 after**. Full lifecycle completion: **0/2 in both groups**. All four runs cleaned up successfully. Final inventory is a fresh post-stop snapshot; first-target times come from observation timestamps relative to the server's goal-start timestamp, not the entire wrapper duration.

Both after runs saved a routine note and received authoritative completion facts reporting `minecraft:oak_log x8` satisfied and `operator_confirmed` unsatisfied. The freeform goal translator generated different predicates across trials: one earlier trial required inventory only, while the other earlier trial and both after trials also required operator confirmation. Inventory is therefore the consistent comparison, and the lifecycle outcomes must not be presented as equivalent successful completions. No operator confirmation was supplied.

[Raw live outcomes, completion predicates, settings and artifact hashes](gathering-retest-2026-09-21.json).

## Additional finding: waiting for confirmation

The two new trials exposed a separate efficiency failure: after gathering and requesting confirmation, they completed **54 and 35 zero-tool model turns**. The agent did not repeat physical gathering, but ordinary observations kept restarting model reasoning. The coordinator now retains the wait across turns for the same goal, lifecycle, connection and profile. Ordinary queued updates and automatic continuation cannot wake it; urgent input still can, and new goals or resumed work clear the wait. The gathering trials above used the sweep fix alone, before this second correction.

The same regression starts **7 model turns before the fix and 1 after**, while still delivering urgent operator input and admitting a new goal. A separate 60-second live Minecraft scenario on the final build requested operator confirmation once, completed **1 model turn with 0 zero-tool turns**, and took **no physical actions**. It remained quiet for the observed **48.396 seconds after confirmation became pending**. Cleanup was clean. Its lifecycle assertion intentionally timed out because no operator confirmation was supplied; this is evidence of quiet waiting, not completed gameplay.

[Raw final-build waiting check and regression counts](confirmation-wait-2026-09-21.json).

## Verification and limits

The sweep regression failed before the change. All **103 focused native-runtime and tool-contract tests** pass after the fix. Java 25 assembly passed; all **103 manifest entries** verify and **88 packaged source files** match the checkout. An independent review found no blocking issue in the sweep change. The confirmation regression also fails on the original coordinator (7 turns instead of 1) and passes after the fix. All **253 focused coordinator, native-runtime and tool-contract tests** pass on the final source.

The live trials loaded gameplay JAR SHA-256 `e8482ce2fd15af545b9ff45d3087efad2ed244926f8577fa7ef4c55a8ddc3fc3`. The final confirmation-wait build has JAR SHA-256 `f85791af22caf68755e1d2be03d8e4810c6cf4f11ae6e436880e797e1786bfdb` and was checked through the separate live waiting scenario. The six-minute gathering trials were not rerun on that second build. Raw reports retain every timeout. Two trials cannot establish a population success rate, unseen-seed reliability, or a cost reduction. This was a real server/production-bridge test, not visible Minecraft UI validation. Audible speech remains deferred.
