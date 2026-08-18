# Code-driven agent control implementation plan

1. Add failing coordinator tests for 16-way planning and one-to-four-action decision normalization.
2. Update the planner schema and prompt to emit bounded action programs with single-action compatibility.
3. Add failing coordinator lifecycle tests for immediate sequential dispatch and program invalidation on failure or interruption.
4. Implement per-agent program cursors while retaining one outstanding Minecraft action per agent.
5. Add failing Java tests for deterministic placement support selection and retry timing.
6. Implement idempotent, support-aware, bounded real-player placement retries with world-state confirmation.
7. Run focused tests, full coordinator tests, `verifyCore`, compilation, and diff checks.
