# Task 4A requirements: coordinator ordering and queue promotion

Implement only these two confirmed coordinator defects using red-green TDD:

1. Preserve per-agent ingress ordering when `action_result` and `goal_control` frames arrive synchronously. A terminal action result emitted first must be fully processed before a later goal-control frame for that agent advances its revision. Preserve immediate cancellation semantics for stop/steer and do not serialize unrelated agents globally.
2. Keep the coordinator queue mirror authoritative when Minecraft promotes a queued goal. Add an explicit compatible operation/payload if needed so promotion removes exactly the promoted queue head rather than treating it as an unrelated start. Cover A active, B and C queued, then promotion through exhaustion.

Constraints:

- Work only in `coordinator/src/**`, `coordinator/test/**`, and the Java bridge operation classification if strictly required.
- Do not touch GUI/client files, PROJECT_LOG, or install dependencies.
- Preserve protocol compatibility where practical and reject malformed promotion data fail-closed.
- Run focused tests and the complete coordinator suite.
- Write a concise implementation report to `docs/plans/task-4a-coordinator-ordering-report.md` with root cause, files, test evidence, and remaining concerns.
