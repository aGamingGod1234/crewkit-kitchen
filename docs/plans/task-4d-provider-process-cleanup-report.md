# Task 4D provider process cleanup report

## Status

Complete. Codex, Gemini ACP, and Kimi ACP transports now tear down an
unresponsive provider after a request timeout and escalate shutdown from
`SIGTERM` to `SIGKILL` after a bounded grace period.

## Root cause and fix

- Request timeouts only rejected the pending promise; the child process and
  transport stayed live.
- Transport shutdown sent a soft kill and returned after two seconds even if the
  provider ignored it.
- A shared lifecycle helper now owns bounded graceful termination and forced
  escalation.
- Both transports accept an injected stop timeout for deterministic tests, route
  request timeouts through teardown, and use the same teardown after malformed
  provider output.

## Files changed

- `coordinator/src/child-process-lifecycle.mjs`
- `coordinator/src/acp-transport.mjs`
- `coordinator/src/codex-app-server.mjs`
- `coordinator/test/provider-process-cleanup.test.mjs`

## Verification evidence

- Six red regressions initially passed 0 of 6: three shutdown-escalation cases
  and three request-timeout teardown cases across Codex, Gemini, and Kimi.
- After production wiring, the focused suite passed 6 of 6.
- No Minecraft client or other GUI was opened.
