# Task 2 fix report

## Reviewer fixes

- Closing while connecting now rejects the active connect promise, destroys the socket, and permanently prevents reuse.
- Authentication failure now closes the session and does not retry on a destroyed socket.
- Synchronous socket-factory errors are typed as `RCON_CONNECT_FAILED` and leave the client closed.
- Incoming packets must contain both RCON string terminators before text decoding.

## Verification

- RED: the new regression tests failed before the implementation change (auth failure left state `new`, socket-factory errors were untyped, and terminators were accepted).
- GREEN: `node --test test/headless-rcon.test.mjs test/jsonl.test.mjs` passed (14 tests).
- `git diff --check` passed.

Implementation commit: `baa73d3`
