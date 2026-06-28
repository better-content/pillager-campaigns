# Pillager Campaigns Testing

`./gradlew test jacocoTestReport jacocoTestCoverageVerification` runs the JVM test suite and emits coverage reports.

Current coverage focus:

- `data/*`: NBT save/load, corrupt-entry isolation, reference repair, enum/resource/UUID fallback, campaign/warband/officer round trips.
- `system/PillagerCampaignEngine`: warband collapse, campaign cleanup, officer reuse, and difficulty-driven pressure behavior.
- `system/PillagerWarbandDiscovery*`: deterministic warband placement and active discovery-radius logic.
- `util/PillagerIdentity`: deterministic faction/officer identity generation.

Plain JUnit deliberately does not bootstrap full Forge networking or a Minecraft world. Runtime/event/entity behavior remains covered by build compilation plus Forge GameTests and manual/in-world validation commands:

- `/pillagercampaigns status`
- `/pillagercampaigns tick_once`
- `/pillagercampaigns warbands list`
- `/pillagercampaigns warbands materialize_warlord <prefix>`
- `/pillagercampaigns campaign list`

Required live-world validation before shipping as a pack-wide surface pressure replacement:

- Start a new overworld with other hostile surface spawns disabled by the pack.
- Verify `/pillagercampaigns status` reports enabled systems and nonzero warbands after overworld discovery has run near a player.
- Travel within discovery radius of a vanilla pillager outpost in the overworld and verify a strategic warband appears in `/pillagercampaigns warbands list`.
- Force a rally presence with `/pillagercampaigns warbands materialize_warlord <prefix>` on a loaded rally chunk and verify the command reports a handled presence result.
- Let a warband run for several campaign ticks and verify travel progresses toward a live player before squad materialization begins.
- Kill a warlord and verify the home warband collapses, active campaigns resolve, and the warlord's officer record no longer returns to circulation.
- Confirm no generic fallback squad appears. Ambient pressure should come only from registered warbands, campaign objectives, and loaded campaign materialization.

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `check` task enforces at least 90% class coverage for the bundle. This keeps new source files from silently arriving with no tests while acknowledging that world/event classes need Forge GameTest or live-instance harness coverage.
