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
./gradlew runClient
./gradlew runServer
```

`verifyFast` runs the test suite and JaCoCo coverage verification. `verifyFull` adds the headless Forge GameTest pass.

## Project Layout

- `src/main/kotlin/com/gerald/pillagercampaigns/` contains the Forge entry point, event hooks, config, domain records, persistence, and campaign systems.
- `src/test/kotlin/com/gerald/pillagercampaigns/` contains JVM tests for pure logic and scenario behavior.
- `src/main/resources/META-INF/mods.toml` is expanded from Gradle properties during resource processing.
- `docs/testing.md` documents the current test coverage focus and manual in-game validation commands.

## Notes

The mod is designed to own pillager campaign scheduling. By default it can disable vanilla patrol spawning and intercept natural illager spawns near registered warbands according to common config values.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
