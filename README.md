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
./gradlew -q :runner:run --args='explore ../build/warband-catalog/live-catalog.json ../build/warband-balance'
./gradlew runClient
./gradlew runServer
```

`verifyFast` runs the Forge-facing JVM suite, the Minecraft-independent Warband Core suite, runner tests, and both JaCoCo gates. `verifyFull` adds the headless Forge GameTest pass.

## Project Layout

- `warband-core/` is the authoritative Minecraft-independent warband state machine and formula library.
- `runner/` is the interactive spreadsheet game, batch experiment host, trace writer, and baseline comparator.
- `src/main/kotlin/com/gerald/pillagercampaigns/` contains Forge observation/effect adapters, persistence, and physical entity behavior.
- `src/test/kotlin/com/gerald/pillagercampaigns/` contains JVM tests for pure logic and scenario behavior.
- `src/main/resources/META-INF/mods.toml` is expanded from Gradle properties during resource processing.
- `docs/testing.md` documents the current test coverage focus and manual in-game validation commands.

## Notes

Warband Core owns discovery, territory, economy, selection, manufacturing, campaign and garrison composition, tactical intent, learning, geometry, logistics, rewards, succession, return outcomes, collapse, and reconciliation. Forge supplies registry/world observations, physical combat facts, entity snapshots, and effect execution through one adapter. Effects use a persistent retry outbox, and the persisted Core sequence owns every canonical identity. The runner calls the same compiled transition API, so empirical play and live play cannot silently evolve separate formulas.

World saves use the strict `warband-core` schema 5 envelope. The Core snapshot is the sole persisted strategic state; Minecraft UUIDs, NBT item/entity snapshots, and cosmetics live in a nonstrategic sidecar. Older strategic save formats intentionally fail with an explicit unsupported-schema error rather than being guessed or partially migrated.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
