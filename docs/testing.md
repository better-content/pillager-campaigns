# Pillager Campaigns Testing

`./gradlew verifyFast` runs the JVM test suite and coverage verification. `./gradlew verifyFull` adds the headless Forge GameTest pass.

Current coverage focus:

- `warband-core/*`: deterministic transitions, time-partition invariance, exact ledgers/manifests, discovery, territory, campaign and garrison lifecycle, tactical intent, rewards, succession, collapse, persistent effect acknowledgement, and conservation.
- `runner/*`: reproducible experiments, exact trace record/replay with per-transition hashes, CSV summaries, assumption sweeps, and baseline comparisons.
- `data/*`: strict schema-5 Core snapshot persistence, exact Minecraft sidecars, malformed-input rejection, duplicate canonical-ID rejection, and catalog revision pinning.
- `system/WarbandCoreAdapter`: the single Forge transition boundary and rebuildable Minecraft-native projections.
- `system/PillagerWarbandDiscovery*`: deterministic warband placement and active discovery-radius logic.
- `util/PillagerIdentity`: deterministic native identity projection.

Plain JUnit deliberately does not bootstrap full Forge networking or a Minecraft world. Runtime/event/entity behavior remains covered by build compilation plus Forge GameTests and manual/in-world validation commands:

- `/pillagercampaigns status`
- `/pillagercampaigns tick_once`
- `/pillagercampaigns warbands list`
- `/pillagercampaigns warbands materialize_warlord <prefix>`
- `/pillagercampaigns campaign list`
- `/pillagercampaigns list captains`

Deterministic harness lane:

```sh
# Interactive use of the exact Warband Core packaged by the mod
./gradlew warbandSim

# Produce a starter scenario, then run or compare it
./gradlew -q :runner:run --args='example' > /tmp/warband-scenario.json
./gradlew -q :runner:run --args='experiment /tmp/warband-scenario.json build/warband-experiment/example'
./gradlew -q :runner:run --args='compare /tmp/warband-scenario.json build/warband-experiment/example/summary.json build/warband-comparison/example'

# Exercise the lower/nominal/upper physical-assumption bounds
./gradlew -q :runner:run --args='matrix-example' > /tmp/warband-matrix.json
./gradlew -q :runner:run --args='sweep /tmp/warband-matrix.json build/warband-sweep/example'

# Record every exact frame/event/effect/state hash, then verify deterministic replay
./gradlew -q :runner:run --args='record /tmp/warband-scenario.json /tmp/warband-trace.json'
./gradlew -q :runner:run --args='replay /tmp/warband-trace.json'

# Capture the actual Forge registry/TCon catalog, then explore 1/3/10/30-day balance
./gradlew verifyFull
./gradlew -q :runner:run --args='explore ../build/warband-catalog/live-catalog.json ../build/warband-balance'
```

Scenario inputs provide bounded physical observations; they do not reimplement Minecraft combat or pathfinding. Every strategic consequence is executed by `warband-core`. A recorded trace embeds the catalog revision and content, rules, pristine initial state/hash, every exact input frame, emitted events/effects, and every post-state hash. Replay fails at the first divergent component. Forge architecture tests fail if any runtime source calls Core outside the canonical adapter or imports Minecraft into Core.

Existing external harness lane:

- `tools/bc test scenario pillager_campaigns --lane rally`
- `tools/bc test scenario pillager_campaigns --lane nemesis_cycle`
- `tools/bc test scenario pillager_campaigns --lane warlord_collapse`
- `tools/bc test scenario pillager_campaigns --lane multiplayer_bias`

Required live-world validation before shipping as a pack-wide surface pressure replacement:

- Start a new overworld with other hostile surface spawns disabled by the pack.
- Verify `/pillagercampaigns status` reports enabled systems and nonzero warbands after overworld discovery has run near a player.
- Travel within discovery radius of a vanilla pillager outpost in the overworld and verify a strategic warband appears in `/pillagercampaigns warbands list`.
- Force a rally presence with `/pillagercampaigns warbands materialize_warlord <prefix>` on a loaded rally chunk and verify the warlord stays anchored to the rally instead of drifting.
- Let a warband run for several campaign ticks and verify a named captain, not the rally warlord, leads the materialized squad.
- Force a failed campaign where the captain survives, then verify `/pillagercampaigns list captains` shows the same captain in `recovering` with updated history before they return later.
- Kill a warlord and verify the home warband collapses, active campaigns resolve, and captain history records the collapse instead of converting the warlord into roaming pressure.
- Confirm no generic fallback squad appears. Ambient pressure should come only from registered warbands, campaign objectives, and loaded campaign materialization.

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `verifyFast` lane enforces at least 90% line coverage for both Warband Core and the testable Forge-facing bundle. Physical adapters remain covered through Forge GameTests and live-instance validation.
