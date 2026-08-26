# Authoritative Minecraft goal verification

Date: 2026-08-27  
Branch: `codex/authoritative-minecraft-goals`

## Deterministic acceptance evidence

- An agent starting with a stone pickaxe cannot complete `Get an iron pickaxe`.
- The failed factual check reports `minecraft:iron_pickaxe x0` and keeps the goal active.
- Releasing the provider turn arms and fires the bounded two-second scheduled-work lease.
- The next provider turn obtains an iron pickaxe, and Minecraft accepts exactly one terminal completion.
- Every completion request retains goal revision `1` and the immutable goal fingerprint.
- Death and respawn both retain revision `1`; the same fingerprint is used for later completion.
- A position at `1,64,0` cannot satisfy `Go to 1 65 0`; only the exact three-dimensional target does.
- Focused native resilience, fault matrix, goal lifecycle, and work-lease tests: 30/30 passing.

## Full verification gate

- Coordinator suite: 900/900 passing.
- Java/Fabric `check build`: successful, including 8,678 core protocol and bridge assertions and 103 voice-addon assertions.
- Embedded coordinator package: 105 files matched the signed manifest and content hashes.
- Normal-profile updater: temporary end-to-end install and all injected rollback boundaries passed.
- Candidate jar SHA-256: `3926415C6404A3FE5AA92207EA2C03A700BCF29538217A22F763ADB0D2D25598`.

## Server-owned checks outside the coordinator fixture

The Java verification suite covers persisted reload, credited ender-dragon kills after goal creation, stable-tick position checks, exact inventory counts, block state properties, advancements, compound predicates, survival duration, and operator confirmation.

## Live selected-model run

Not run in this implementation worktree. A live run requires launching and visibly controlling Minecraft and installing the candidate jar, which are deliberately held behind the deployment gate. The deterministic suites and package checks must remain green before that separate authorized run.

Suggested live command: ask Sol to `Get an iron pickaxe`, then confirm an early finish is rejected, no unexplained idle gap exceeds two seconds, death recovery preserves the goal, and completion appears only after the exact item enters its inventory.
