# Pillager Campaigns

Pillager Campaigns is a Minecraft Forge mod for Minecraft 1.20.1. It replaces passive pillager pressure with a server-side campaign system: discovered warbands accrue pressure, officer-led squads scout and retaliate, and persistent world data tracks faction state over time.

## Development Setup

- Java 17 is required.
- Use the checked-in Gradle wrapper.
- Forge, Kotlin for Forge, and mod metadata versions are configured in `gradle.properties`.

Common commands:

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew warbandSim
./gradlew :runner:run --args='example'
./gradlew verifyFull
./gradlew -q :runner:run --args='explore ../build/warband-catalog/live-catalog.json ../build/warband-balance'
./gradlew runClient
./gradlew runServer
```

`verifyFast` runs the Forge-facing JVM suite, the Minecraft-independent engine suite, runner tests, and both JaCoCo gates. `verifyFull` adds the headless Forge GameTest pass.

## Project Layout

- `engine/` is the authoritative Minecraft-independent warband state machine and formula library.
- `runner/` is the interactive spreadsheet game, batch experiment host, trace writer, and baseline comparator.
- `src/main/kotlin/com/gerald/pillagercampaigns/` contains Forge observation/effect adapters, persistence, and physical entity behavior.
- `src/test/kotlin/com/gerald/pillagercampaigns/` contains JVM tests for pure logic and scenario behavior.
- `src/main/resources/META-INF/mods.toml` is expanded from Gradle properties during resource processing.
- `docs/testing.md` documents the current test coverage focus and manual in-game validation commands.

## Notes

The pure engine owns economy, selection, manufacturing, campaign decisions, learning, geometry, and conservation. Forge supplies live registry/world facts and executes physical effects. The old parallel simulation has been removed, so runner and live play cannot silently evolve separate formulas.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
