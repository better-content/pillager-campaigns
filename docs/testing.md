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

- `/pillager_campaigns status`
- `/pillager_campaigns tick_once`
- `/pillager_campaigns warbands list`
- `/pillager_campaigns warbands materialize_warlord <prefix>`
- `/pillager_campaigns campaign list`
- `/pillager_campaigns list captains`

Deterministic Warband Core scenario lane (not a Minecraft simulation):

```sh
# Inspect the exact Warband Core packaged by the mod
./gradlew warbandCoreExperiment

# Produce a starter scenario, then run or compare it
./gradlew -q :runner:run --args='example' > /tmp/warband-scenario.json
./gradlew -q :runner:run --args='experiment /tmp/warband-scenario.json build/warband-experiment/example'
./gradlew -q :runner:run --args='compare /tmp/warband-scenario.json build/warband-experiment/example/summary.json build/warband-comparison/example'

# Exercise lower/nominal/upper synthetic-input bounds
./gradlew -q :runner:run --args='matrix-example' > /tmp/warband-matrix.json
./gradlew -q :runner:run --args='sweep /tmp/warband-matrix.json build/warband-sweep/example'

# Record every exact frame/event/effect/state hash, then verify deterministic replay
./gradlew -q :runner:run --args='record /tmp/warband-scenario.json /tmp/warband-trace.json'
./gradlew -q :runner:run --args='replay /tmp/warband-trace.json'

# Analyze Warband Core under synthetic scenario inputs using an explicit runtime specification
./gradlew -q :runner:run --args='explore /path/to/runtime-spec.json ../build/warband-core-analysis'

# Hard MVP readiness gate; exits nonzero if any required behavior misses its envelope
./gradlew -q :runner:run --args='mvp /path/to/runtime-spec.json ../build/warband-mvp'
```

Scenario inputs are authored synthetic Core frames. The runner does not implement, approximate, or predict Minecraft combat, pathfinding, world generation, entity behavior, or player behavior. Every result is conditional on those explicit inputs. A recorded trace embeds the runtime specification, a machine-readable non-simulation boundary, pristine initial state/hash, every exact input frame, emitted events/effects, and every post-state hash. Replay fails at the first divergent component.

For the actual pack content, run `/pillager_campaigns export_runtime_spec` from an operator console after registries load. The command validates recruits, all four resource channels, compatible TCon material/platform data, and rewards, then atomically writes `pillager_campaigns/exports/warband-runtime-spec.json` beneath the world directory. It observes registry/config data only and does not advance or mutate strategic state.

Existing external harness lane:

- `tools/bc test scenario pillager_campaigns --lane rally`
- `tools/bc test scenario pillager_campaigns --lane nemesis_cycle`
- `tools/bc test scenario pillager_campaigns --lane warlord_collapse`
- `tools/bc test scenario pillager_campaigns --lane multiplayer_bias`

Required live-world validation before shipping as a pack-wide surface pressure replacement:

- Start a new overworld with other hostile surface spawns disabled by the pack.
- With server view and simulation distance set to four, remain near the initial base and verify `/pillager_campaigns status` reports full `pressure_coverage` and nonzero warbands after overworld discovery runs.
- Verify the first campaign dispatches and materializes within 48,000 ticks without requiring the player to approach or attack a rally.
- Verify abstract discovery does not load or generate the remote rally chunk; loading that chunk later must materialize exactly one warlord and its reserved garrison.
- Travel within discovery radius of a vanilla pillager outpost in the overworld and verify a strategic warband appears in `/pillager_campaigns warbands list`.
- Force a rally presence with `/pillager_campaigns warbands materialize_warlord <prefix>` on a loaded rally chunk and verify the warlord stays anchored to the rally instead of drifting.
- Let a warband run for several campaign ticks and verify a named captain, not the rally warlord, leads the materialized squad.
- Force a failed campaign where the captain survives, then verify `/pillager_campaigns list captains` shows the same captain in `recovering` with updated history before they return later.
- Kill a warlord and verify the home warband collapses, active campaigns resolve, and captain history records the collapse instead of converting the warlord into roaming pressure.
- Confirm no generic fallback squad appears. Ambient pressure should come only from registered warbands, campaign objectives, and loaded campaign materialization.

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `verifyFast` lane enforces at least 90% line coverage for both Warband Core and the testable Forge-facing bundle. Physical adapters remain covered through Forge GameTests and live-instance validation.
