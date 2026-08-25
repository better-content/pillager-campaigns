# Pillager Campaigns

Pillager Campaigns is a server-side Minecraft Forge mod for Minecraft 1.20.1. It supplies one thing: reliable, bounded, scaling pillager invasions for Survival players on the Overworld surface.

Each player has an independent deterministic pressure clock. An invasion is warned, approaches over an approximate solid-surface map, materializes as a bounded squad in already-loaded terrain, and then clears or retires. Pressure follows the current player; the mod does not simulate factions, bases, officers, territory, economy, logistics, equipment production, or rewards.

## Development

Java 17 and the checked-in Gradle wrapper are required.

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew invasionCoreExperiment
./gradlew -q :runner:run --args='example'
./gradlew -q :runner:run --args='mvp /path/to/invasion-runtime-spec.json build/invasion-readiness'
```

`verifyFast` runs the Forge-facing JVM suite, the Minecraft-independent Invasion Core suite, runner tests, and both JaCoCo gates. `verifyFull` adds the headless Forge GameTests.

The `mvp` runner gate proves deterministic Core properties only: per-player cadence, bounded scaling, composition, effect lifecycle, and terminal resolution under authored surface observations. It does not simulate Minecraft combat, entity AI, terrain, or player behavior.

Export the exact available roster and scalar rules from a running instance with `/pillager_campaigns export_runtime_spec`. The file is written beneath the world at `pillager_campaigns/exports/invasion-runtime-spec.json`.

## Architecture

- `invasion-core/` owns pressure clocks, scaling, roster composition, approximate surface traversal, lifecycle, persistence shapes, and the durable three-effect outbox.
- `runner/` executes the exact compiled Core against synthetic observations and writes the hard readiness report.
- `src/main/kotlin/com/bettercontent/pillagercampaigns/` observes loaded Minecraft terrain, executes warning/materialize/retire effects, persists the Core snapshot, and maintains native mob targeting.

The surface graph uses one block per cell, a solid floor, two blocks of body clearance, one-block ascent, two-block descent, and no traversal through unknown cells. Forge reads only `getChunkNow` chunks. A blocked or unknown approach remains immaterial and retries when terrain is naturally observed.

World saves use invasion schema 1. Pre-0.3 strategic state is intentionally discarded once; no warband state is migrated.

## Commands

- `/pillager_campaigns status [player]`
- `/pillager_campaigns force [player]`
- `/pillager_campaigns reset [player]`
- `/pillager_campaigns export_runtime_spec`

## Community

For modpack discussion and playtest feedback, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
