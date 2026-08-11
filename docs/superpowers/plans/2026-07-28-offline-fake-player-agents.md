# Offline Fake-Player Agents Implementation Plan

## Goal

Replace custom mob-backed agents with Carpet offline fake players so each agent is a real `ServerPlayer` with vanilla physics, collision, health, hunger, inventory, game mode, and player interactions while preserving the existing model/provider control plane.

## Constraints

- Keep existing agent IDs, model display names, lifecycle, prompt queue, bridge protocol, and GUI hotkey.
- Use a short stable internal Minecraft username per agent; continue showing the human model name in the GUI.
- Require Fabric Carpet at runtime and compile against the exact installed Minecraft 26.1.2-compatible artifact.
- Do not expose private chain-of-thought. Show concise model-authored decision summaries, actions, results, and failures.
- Preserve unrelated worktree changes.

## Phase 1: Runtime dependency and player identity

1. Add Carpet as a compile-only dependency and declare it in `fabric.mod.json`.
2. Extend persistent agent data with a stable offline-player username, migrating older saves deterministically.
3. Add a fake-player factory/reconciler using `EntityPlayerMPFake.createFake`.

## Phase 2: Manager and lifecycle migration

1. Spawn and respawn fake players in the selected Survival, Creative, or Adventure mode.
2. Resolve agents through the server player list rather than custom entity lookup.
3. Reconcile visible fake players with persisted records so an alive player cannot remain logically `DEAD`.
4. Stop movement/action packs while agents are idle, paused, or stopped.

## Phase 3: Real-player observations and actions

1. Collect vanilla health, hunger, saturation, inventory, armor, effects, game mode, attackers, nearby entities, and nearby blocks from `ServerPlayer`.
2. Drive movement, looking, jumping, attacking, and item use through Carpet's player action pack.
3. Use vanilla player game-mode interaction paths for breaking and placing blocks.
4. Keep command validation, revision fencing, and action result reporting intact.

## Phase 4: Survival behavior and progress

1. Add a survival-first default policy: preserve life, assess threats, fight or flee, obtain/eat food, and avoid attacking invulnerable Creative players.
2. Emit immediate `Planning`, `Acting`, `Completed`, and `Blocked` updates in formatted, provider-colored Minecraft chat.
3. Include concise decision summaries only; never forward hidden reasoning traces.

## Phase 5: GUI controls

1. Replace the single selected agent with an ordered multi-selection.
2. Add Select All/Clear controls and apply prompt/start/stop/queue/steer operations to all selected agents.
3. Add per-agent sidebar visibility and automatic-progress settings, plus a reveal-hidden toggle.
4. Color sidebar rows by provider family while retaining head icons and readable model names.

## Phase 6: Verification and delivery

1. Run focused compilation and existing nonvisual verification.
2. Rebuild the mod JAR.
3. Synchronize distribution/runtime copies and replace only the prior Arena Agents artifact in the normal Minecraft mods directory.
4. Record exact artifact hashes and any remaining vanilla-parity limitations.
