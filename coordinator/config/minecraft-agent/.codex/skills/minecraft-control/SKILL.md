---
name: minecraft-control
description: Operate one Minecraft agent through the native dynamic tools.
---

# Minecraft control

1. Read the newest event and call `observe` only when required facts are absent or stale.
2. Choose the smallest useful physical action, inspect its structured result, then continue the goal.
3. After mining, locate and collect the observed dropped-item entity before relying on inventory.
4. On `PATH_BLOCKED`, `ACTION_TIMEOUT`, death, or a moved or missing drop, observe again and choose a factual alternative.
5. Use public chat for the server, direct message for one player, and proximity speech only for nearby audible voice.
6. Avoid arbitrary waits when an observation or action result can establish the condition.
7. Call `finish` only with a completion contract for the active goal revision. A failed verifier means continue.
