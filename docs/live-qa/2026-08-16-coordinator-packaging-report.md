# Coordinator packaging verification

Date: 2026-08-16

The Java 25 `jar` task now embeds only the coordinator runtime allowlist:

- `package.json` and `package-lock.json`;
- `config/**` and `src/**`;
- `node_modules/acorn/**` for the declared Node runtime dependency;
- the sorted `coordinator-manifest.txt` metadata file.

The build does not embed logs, traces, tests, workspaces, generated runtime state, private files, or secrets. The normal-profile updater independently derives the same runtime roots, verifies source-to-archive hashes, stages the coordinator under the target, and removes stale Arena-only state during the staged verified swap. The older launcher-profile installer remains unchanged apart from its pre-existing coordinator/runtime changes.

## Evidence

Command:

```powershell
$env:JAVA_HOME = (Resolve-Path 'runtime/toolchains/temurin-25/jdk-25.0.3+9').Path
.\gradlew.bat clean jar --no-daemon --console=plain
.\scripts\verify-coordinator-packaging.ps1 -JarPath '.\build\libs\arena-agents-0.1.0.jar'
```

Result: Java 25 build succeeded; the coordinator manifest contains 49 files and the packaging verifier passed.

Built JAR SHA-256:

```text
A72D80736B4DA6FF71786B1BB1F50543BE75DF436900403F284188E4070B788D
```

No Minecraft process was launched during this verification. The normal `.minecraft` profile was then updated transactionally, and installed staging parity passed with all 49 coordinator files matching source and archive hashes. The retained rollback backup is `C:\Users\aGamingGod\AppData\Roaming\.minecraft\.arena-agents-backup-20260816T091100Z-0440f523bebb43f284debe3ac7ac3599`.

Installed staging parity can be repeated with:

```powershell
.\scripts\verify-coordinator-packaging.ps1 `
  -JarPath '.\build\libs\arena-agents-0.1.0.jar' `
  -StagingPath "$env:APPDATA\.minecraft-arena-agents\arena-agents-runtime\coordinator" `
  -SourceCoordinatorPath '.\coordinator'
```

The normal profile update is a staged verified swap with a permanent rollback backup and never reads or writes `launcher_profiles.json`. Backups are intentionally retained for manual review/deletion after acceptance; the updater never auto-deletes recoverable data. The updater assumes a trusted same-user local filesystem during its short transaction; handle-level protection against a malicious concurrent filesystem actor is out of scope.

```powershell
.\scripts\install-normal-profile-update.ps1 `
  -ProjectRoot 'C:\Users\aGamingGod\Desktop\Projects\agent arena' `
  -GameDirectory "$env:APPDATA\.minecraft"
```

Its isolated end-to-end test covers stale Arena JAR removal, unrelated-mod preservation, coordinator parity, forced-failure rollback, and temporary-target containment:

```powershell
.\scripts\test-install-normal-profile-update.ps1
```
