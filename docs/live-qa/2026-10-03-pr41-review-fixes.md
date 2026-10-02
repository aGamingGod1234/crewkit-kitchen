# PR #41 CI and review verification

The original Windows coordinator CI failure and all five Codex review findings were reproduced and corrected. The review fixes preserve the selected agent's control over gameplay and do not complete goals without required operator confirmation.

## Corrections

| Failure | Correction | Regression evidence |
| --- | --- | --- |
| A depth-one CI checkout lacked the pinned historical successor benchmark source. | Fetch the exact baseline commit before the Windows benchmark runs. | A fresh shallow checkout failed before the fetch and passed afterward. All three CI jobs passed on `d028c4aa6c8363526bbc54fa05191597ec0c7c33`. |
| Steering changed the planner prompt and erased the live plan. | Identify the task by its immutable goal fingerprint and preserve the original request for saved-plan scope. Explicit steering also preserves legacy tasks; fresh replacement resets them. | Parsed-goal steering, retained events, remembered infrastructure, reload, and replacement are covered. The new steering regression failed before the fix. |
| Requested Priority could incorrectly accept a reported Fast tier. | Retain only the existing Fast-request to Priority-report alias. | A provider-response mismatch regression failed before the fix and passes afterward; the supported forward alias remains covered. |
| An initial defensive interrupt discarded an undispatched command or query. | Preserve the exact pending VM continuation. Dispatch it only after the main agent explicitly chooses to continue, and consume its real receipt. | Initial command/query regressions failed before the fix. New cases cover query isolation, local values, exact-once dispatch, and terminal or cancellation decisions. |
| Generic block demand disappeared inside compound goal predicates. | Retain recursive block requirements, merge overlapping inventory facts, and share one offhand across disjoint requirements. Include live wearable block items in the optimistic capacity bound. | Immutable pre-fix production sources fail the final regression. Cases cover impossible nested requirements, feasible alternatives, repeated/overlapping requirements, and worn pumpkins. |
| Failed alternative branches hid an otherwise confirmation-ready goal branch. | Search the goal predicate with cached observations and the existing kill-allocation backtracking. Return the selected branch with confirmation still false. | Immutable pre-fix production sources fail the final regression. Cases cover alternative branches, genuine unmet facts, kill allocation, speech confirmation, and survival tick accounting. |

## Verification of the combined review fixes

- Full coordinator suite: 1,926 passed, three skipped, zero failed.
- `gradlew check build`: successful; 15,921 protocol/core assertions and 387 voice-addon assertions. This includes launch-mode and transformed Minecraft mixin checks.
- Targeted Java goal checks: 216 assertions, including 31 added regression assertions.
- All 112 packaged coordinator manifest entries match both current source and JAR bytes.
- `git diff --check` passed.

Local artifact: `arena-agents-0.2.0.jar`, 3,463,907 bytes. SHA-256: `454c65dfbda6a0b41c316a4c8fb5dd855149d3cc24391d36bd7229d5ef028760`.

GitHub CI for the commit containing these five review fixes must pass separately. The earlier successful run covers the historical-source fetch fix only. The installed Desktop JAR was not changed, and no live gameplay, audible speech, paid inference, or new performance benchmark was run for these fixes. Earlier benchmark results remain scoped to their dated artifacts.
