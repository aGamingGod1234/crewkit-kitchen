# Task 4E — Full visual release validation report

Date: 2026-07-26

## Scope

Full official-launcher testing was performed only in the isolated `%APPDATA%\.minecraft-agent-56` profile and its `Arena Agents Visual QA` world. No normal Minecraft world was opened or modified.

## Live validation matrix

| Area | Result | Evidence |
| --- | --- | --- |
| Official launcher | Pass | `Arena Agent 56` launched Minecraft 26.1.2 with Fabric 0.19.3 and the rebuilt mod JAR. |
| Hotkey and GUI | Pass | `G` opened the control center; provider/agent cycling and all operational buttons rendered. |
| Short-window GUI | Pass after fix | Height-aware spacing and a shared management row kept Start, Queue, Steer, Stop, Resume, Remove, Refresh, and Done fully visible. |
| Agent spawning | Pass after fixes | Codex, Kimi, and Gemini NPCs spawned at collision-free nearby positions with provider-specific skins and model/thinking name tags. |
| Idle behavior | Pass | Summoned agents remained stationary until a goal command was issued. |
| Codex live goal | Pass | Agent `8f147d6b` completed a safe wait goal and returned to `IDLE` with no current goal. |
| Queue/steer/stop | Pass | Codex accepted queue at revision 10, steer at revision 11, and stop at revision 12; stopped state was `PAUSED` and the queued goal remained. |
| Kimi live goal | Pass | Agent `44cf7c3c` completed a safe wait goal and returned to `IDLE` at revision 4. |
| Gemini isolation | Pass with external limitation | Agent `79972561` entered agent-local `ERROR`; the installed CLI rejected ACP `session/new` with its Code Assist migration message. Codex, Kimi, bridge, and world remained healthy. |
| Save and shutdown | Pass | The QA world was saved through the title screen and Minecraft/coordinator processes were closed after each rebuild. |

## Problems found and fixed

- Registered the custom model command argument so the client can deserialize the command tree.
- Removed duplicate GUI background extraction that crashed the screen.
- Parsed quoted slash-bearing models correctly, including `kimi-code/k3`.
- Added collision-aware multi-agent spawn placement.
- Generated and wired a launcher-safe bridge secret file.
- Changed reconnect readiness to use the authoritative registry revision instead of provider-normalized profiles.
- Raised configurable provider planning timeouts from 45 seconds to 120 seconds for high-reasoning turns.
- Added bounded ACP invalid-decision diagnostics.
- Made the control GUI responsive at small logical heights.
- Replaced a Windows PowerShell 5.1-incompatible `String.Contains` overload in automated verification.
- Added stable provider/agent-scoped working directories for Codex threads and Gemini/Kimi processes plus ACP sessions.
- Added one bounded corrective retry for malformed planner decisions before an agent-local error is published.

## Final automated evidence

- Two consecutive full automated verification runs passed.
- Java/Fabric: 4,789 protocol and bridge assertions; Gradle `check`, `verifyCore`, `verifyEntrypoints`, and `build` passed.
- Coordinator: 95 Node tests passed with zero failures, skips, or cancellations.
- Post-change live headless check: Kimi agent `0d543377` completed to `IDLE` with `goal=none`; its isolated workspace was created under `runtime/agent-workspaces/kimi/0d543377-c204-431e-9750-af28b107ca84`.
- Final JAR: `build/libs/arena-agents-0.1.0.jar`.
- Standalone package: `dist/arena-agents-modpack-0.1.0.zip`.
- Standalone package SHA-256: `9a8cee02c2f7645bac7b6ee504d164d4e12366121d0c0a7b224ed28afa79deaa`.

## Known limitations

- The installed Gemini CLI/account is no longer accepted by Gemini Code Assist individual ACP. The provider remains supported for compatible Gemini ACP accounts.
- Gemini CLI 0.44.1 headless JSON mode is rejected by the same account eligibility gate, so there is no local transport fallback for this account.
- Container transfer, recipe crafting, furnace transactions, and forced chunk tickets remain intentionally fail-closed pending Minecraft 26.1.2 transaction-adapter validation.
- Simultaneous two-player authenticated arena testing still requires two licensed Minecraft Java accounts; it is separate from the multi-NPC single-world validation completed here.
