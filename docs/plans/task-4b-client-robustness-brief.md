# Task 4B requirements: client initialization containment

Implement the two confirmed client robustness defects using red-green TDD:

1. Malformed legacy agent configuration must not crash Minecraft client initialization. Contain and log configuration validation/runtime failures and leave the legacy bridge disabled.
2. A legacy `BridgeServer` bind/start failure must not crash Minecraft client initialization. Contain and log the failure, release any partially initialized client runtime resources, and leave unrelated renderer/hotkey/GUI setup able to function.

Constraints:

- Work only in existing client configuration/bootstrap/bridge files and focused verification tests.
- Do not implement the new GUI, key binding, or control snapshot networking.
- Do not touch coordinator files or PROJECT_LOG.
- Add no dependencies and preserve successful legacy bridge behavior.
- Run focused verification and relevant Gradle compile/check tasks.
- Write a concise implementation report to `docs/plans/task-4b-client-robustness-report.md` with root cause, files, test evidence, and remaining concerns.
