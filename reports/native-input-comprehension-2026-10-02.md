# Native input model comprehension check

Status: PASSED. Requested GPT-6.1 Sol, medium effort, Fast tier. Existing ChatGPT subscription; no API key billing.

Two controlled factual questions, each tested once before and after. Every turn used the actual native tool collector with a fake read-only observe/say executor. The before arm used frozen old instructions/tools and decoded plain replies; the after arm used the production compact formatter.

| Scenario | Arm | Facts correct | Native calls valid | Input | Cached | Uncached | Output | Elapsed ms |
|---|---|---|---|---:|---:|---:|---:|---:|
| precise_mining_target_and_inventory | before | true | true | 81188 | 52736 | 28452 | 78 | 10403.679 |
| precise_mining_target_and_inventory | after | true | true | 58010 | 37504 | 20506 | 78 | 9622.942 |
| urgent_health_identity_death_and_unknowns | after | true | true | 67202 | 63232 | 3970 | 109 | 7417.312 |
| urgent_health_identity_death_and_unknowns | before | true | true | 92549 | 87808 | 4741 | 109 | 9551.874 |

Reported token totals for these two scenarios only:

| Arm | Input | Cached | Uncached | Output |
|---|---:|---:|---:|---:|
| before | 173737 | 140544 | 33193 | 187 |
| after | 125212 | 100736 | 24476 | 187 |

The checks cover literal block identifiers, target coordinates, item counts, hostile UUID/type, health, current death flag, world/dimension identity, previous-death coordinates, missing fields, and null versus false. Compact production minecraft-facts-v1 observations reached the model in 2/2 after turns. Exact factual passes: 4/4.

Total uncached input: 57669; cap: 60000. Turns: 4; cap: 4.

The provider confirmed the exact model and reported Fast as its priority alias. Medium effort was submitted; it was not independently echoed in the effective settings.

Limits:

- Two factual scenarios, one before/after response each; this is a comprehension check, not a statistical behavior or speed benchmark.
- Only a fake read-only observation and captured say answers are used. No Minecraft commands execute.
- Elapsed times include provider scheduling and tool round trips. Raw reasoning is not retained.
- Actual reported tokens are shown separately from offline proxy counts; subscription allowance cost is not inferred.

No statistical latency improvement, unchanged long-task behavior, weekly allowance savings, or installed-gameplay success is claimed from these four turns.
