# Local dependency provenance

`fabric-carpet-26.1+v260402.jar` comes from the upstream Fabric Carpet `v26.1` GitHub release:

https://github.com/gnembon/fabric-carpet/releases/download/v26.1/fabric-carpet-26.1%2Bv260402.jar

GitHub published the asset with SHA-256 digest `59bd225d12423a7d7a635ca0c94fa786f97ccebb116922b16d76072da4ee67e7`. The tracked file matches that digest. `checksums.sha256` is the build-enforced record.

Verify it from PowerShell at the repository root:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\libs\fabric-carpet-26.1+v260402.jar
.\gradlew.bat verifyLocalDependencies
```

When updating Carpet, download an exact tagged release asset from the upstream project. Review its published digest, replace the JAR, and update `checksums.sha256` in the same change.
