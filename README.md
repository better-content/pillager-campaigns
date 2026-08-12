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
./gradlew warbandCoreExperiment
./gradlew :runner:run --args='example'
./gradlew -q :runner:run --args='explore /path/to/runtime-spec.json ../build/warband-core-analysis'
./gradlew -q :runner:run --args='mvp /path/to/runtime-spec.json ../build/warband-mvp'
./gradlew runClient
./gradlew runServer
```

`verifyFast` runs the Forge-facing JVM suite, the Minecraft-independent Warband Core suite, runner tests, and both JaCoCo gates. `verifyFull` adds the headless Forge GameTest pass.

The `mvp` runner command is the hard Core play-test readiness gate. It checks deterministic lifecycle completion, steady-escalation cadence, power growth, squad and equipment expression, logistics sensitivity, environment response, and dispatch boundaries under authored synthetic observations. Export actual registry content with `/pillagercampaigns export_runtime_spec`, then pass the generated `pillagercampaigns/exports/warband-runtime-spec.json` to the runner. Exporting content does not simulate Minecraft behavior.

## Project Layout

- `warband-core/` is the authoritative Minecraft-independent warband state machine and formula library.
- `runner/` is a Warband Core state inspector, synthetic-scenario experiment host, trace writer, and baseline comparator. It is not a Minecraft simulator.
- `src/main/kotlin/com/gerald/pillagercampaigns/` contains Forge observation/effect adapters, persistence, and physical entity behavior.
- `src/test/kotlin/com/gerald/pillagercampaigns/` contains JVM tests for pure logic and scenario behavior.
- `src/main/resources/META-INF/mods.toml` is expanded from Gradle properties during resource processing.
- `docs/testing.md` documents the current test coverage focus and manual in-game validation commands.

## Notes

Warband Core owns discovery, territory, economy, selection, manufacturing, campaign and garrison composition, tactical intent, learning, geometry, logistics, rewards, succession, return outcomes, collapse, and reconciliation. Forge supplies registry/world observations, physical combat facts, entity snapshots, and effect execution through one adapter. Effects use a persistent retry outbox, and the persisted Core sequence owns every canonical identity. The runner calls the same compiled transition API with authored synthetic observations. Its results describe Core behavior only and do not predict Minecraft combat, pathfinding, world generation, entities, or players.

World saves use the strict `warband-core` schema 6 envelope. The Core snapshot is the sole persisted strategic state; Minecraft UUIDs, NBT item/entity snapshots, and cosmetics live in a nonstrategic sidecar. Older unsupported strategic save formats fail explicitly.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
