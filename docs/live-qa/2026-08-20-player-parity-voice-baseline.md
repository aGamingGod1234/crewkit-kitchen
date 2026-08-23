# Player parity and voice baseline

Date: 2026-08-20

## Environment

- Minecraft 26.1.2
- Fabric Loader 0.19.3
- Fabric API 0.150.0+26.1.2
- Carpet 26.1+v260402
- Java 25
- Node.js 22 or newer

Gradle initially inherited Android Studio's Java 21 runtime from `JAVA_HOME`. The baseline uses the installed Java 25 runtime for Gradle without committing a machine-specific path.

## Automated verification

- `npm test` in `coordinator`: 325 passed, 0 failed.
- `gradlew check` under Java 25: passed.

## Live server verification

The Fabric development server reached ready state and opened Minecraft on TCP 25565 and RCON on the configured development port.

RCON created one Carpet-backed agent with `/codex summon`. The server listed the fake player as online. Carpet input commands moved the agent forward, performed one jump, entered sneak, left sneak, and stopped all inputs. The authoritative position changed from `[-32.0, 67.0, 64.0]` to `[-32.0, 68.25220334025373, 64.69999998807907]`.

The test ended with a clean RCON `stop`; the Minecraft listener closed successfully.

## Baseline result

The Node, Java, Fabric, fake-player creation, input, physics, RCON, persistence, and shutdown paths are executable. Feature work may proceed.
