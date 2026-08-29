# Pillager Campaigns

Pillager Campaigns is a server-side Minecraft Forge mod for Minecraft 1.20.1. It supplies frequent overland scout pressure and sparse, warned pillager assaults for Survival players.

Each player has independent eligible-time clocks: scouts arrive every 6–12 minutes with 3–6 members and withdraw after two active minutes; assaults arrive every 60–120 minutes, warn two surface minutes before arrival, then deliver three progress-gated waves of 8–12 members for one player. Nearby players within 64 blocks share one scaled encounter. Groups dispatch early from 512–768 blocks away so those windows remain arrival windows, then move immaterially at 1.6 blocks/second for scouts or 1 block/second for assaults.

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

- `invasion-core/` owns both clocks, grouping, wave budgets, roster composition, deterministic strategic traversal, caps, lifecycle, persistence shapes, and the durable effect outbox.
- `runner/` executes the exact compiled Core against synthetic observations and writes the hard readiness report.
- `src/main/kotlin/com/bettercontent/pillagercampaigns/` records genuinely loaded Minecraft terrain, executes warning/materialize/retire effects, persists the Core snapshot and terrain atlas, and maintains native mob targeting.

The atlas stores a compact surface column for chunks that normal play has loaded. The graph requires a solid floor, two blocks of clearance, one-block ascent, two-block descent, and never traverses unknown cells. Forge reads live terrain only with `getChunkNow`; no campaign route loads, generates, or tickets a chunk. Open routes are proved from a 512–768-block origin through recorded terrain and stop in the 48–72-block materialization band. A known wall or cliff instead produces an exterior defensive frontier. Exact placement uses only connected exterior columns, and real mob navigation must agree: `canReach()` for open routes, unreachable for a defensive frontier.

World saves use campaign schema 3. Schema-2 eligible clocks, cadence, sequence, and outcome adjustment migrate, while active schema-2 encounters are cleared because they have no distant origin or strategic position.

## Commands

- `/pillager_campaigns status [player]`
- `/pillager_campaigns force [player]`
- `/pillager_campaigns reset [player]`
- `/pillager_campaigns export_runtime_spec`

## Community

For modpack discussion and playtest feedback, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).
