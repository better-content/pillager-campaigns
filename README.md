# Pillager Campaigns

Pillager Campaigns is a server-side Minecraft Forge mod for Minecraft 1.20.1. It supplies frequent overland scout pressure and sparse, warned pillager assaults for Survival players.

Each player has independent eligible-time clocks: scouts arrive every 6–12 minutes with 3–6 members and withdraw after two active minutes; assaults arrive every 60–120 minutes, warn for two surface minutes, then deliver three progress-gated waves of 8–12 members for one player. Nearby players within 64 blocks share one scaled encounter. Pressure follows current players rather than bases.

## Development

Java 17 and the checked-in Gradle wrapper are required.

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew verifyWorld
./gradlew invasionCoreExperiment
./gradlew -q :runner:run --args='example'
./gradlew -q :runner:run --args='mvp /path/to/invasion-runtime-spec.json build/invasion-readiness'
```

`verifyFast` includes the fixed 1,024-seed policy corpus. `verifyFull` adds headless Forge GameTests, and `verifyWorld` runs those tests with the exact It Takes a Pillage and Savage & Ravage runtime dependencies.

The `mvp` runner gate proves deterministic Core properties only: per-player cadence, bounded scaling, composition, effect lifecycle, and terminal resolution under authored surface observations. It does not simulate Minecraft combat, entity AI, terrain, or player behavior.

Export the exact available roster and scalar rules from a running instance with `/pillager_campaigns export_runtime_spec`. The file is written beneath the world at `pillager_campaigns/exports/invasion-runtime-spec.json`.

## Architecture

- `invasion-core/` owns both clocks, grouping, wave budgets, roster composition, loaded-surface traversal, caps, lifecycle, persistence shapes, and the durable effect outbox.
- `runner/` executes the exact compiled Core against synthetic observations and writes the hard readiness report.
- `src/main/kotlin/com/bettercontent/pillagercampaigns/` observes loaded Minecraft terrain, executes warning/materialize/retire effects, persists the Core snapshot, and maintains native mob targeting.

The surface graph uses a solid floor, two blocks of clearance, one-block ascent, two-block descent, and no traversal through unknown cells. Forge reads only `getChunkNow`. A route must connect from 48–72 blocks away to within 12 blocks of the target. Immediately before spawning, every chunk in the bounded route/navigation region must still be loaded and the real entity path must report `canReach()`. No chunk is loaded, generated, or ticketed on behalf of a campaign.

World saves use campaign schema 2. Schema-1 invasion state is intentionally discarded once because it cannot represent independent scout/assault clocks or waves.

## Commands

- `/pillager_campaigns status [player]`
- `/pillager_campaigns force [player]`
- `/pillager_campaigns reset [player]`
- `/pillager_campaigns export_runtime_spec`

## Community

For modpack discussion and playtest feedback, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
