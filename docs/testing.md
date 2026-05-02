# Pillager Campaigns Testing

`./gradlew test jacocoTestReport jacocoTestCoverageVerification` runs the JVM test suite and emits coverage reports.

Current coverage focus:

- `data/*`: NBT save/load, corrupt-entry isolation, reference repair, enum/resource/UUID fallback, campaign/base/officer round trips.
- `system/PillagerCampaignRules`: pure campaign movement, active campaign counts, intel scoring, campaign TTL.
- `system/PillagerBaseService`: faction/officer reuse, resource-gated officer replacement, persistent succession lineage, faction war-memory, inherited combat DNA, and base economy caps.
- `system/FactionStrengthRules`: faction strength scoring and garrison/replacement costs.
- `system/PillagerRouteRules`: scout route creation, routed campaign stalls, and nav-node sabotage.
- `scenario/PillagerCampaignsScenarioTest`: end-to-end pure-system scenarios for interception, engagement targeting, scout escape learning, grave-marked killers, siege engineering, succession, order lore, weak-faction scout pressure, strong-faction routed attacks, route sabotage, officer intel drops, garrison restock costs, and long-running economy/travel bounds.
- `util/PillagerIdentity`: deterministic faction/officer identity generation.

Plain JUnit deliberately does not bootstrap full Forge networking or a Minecraft world. Runtime/event/entity behavior remains covered by build compilation plus manual/in-world validation commands:

- `/pillagercampaigns status`
- `/pillagercampaigns tick_once`
- `/pillagercampaigns base add_here`
- `/pillagercampaigns base rescan_here`
- `/pillagercampaigns base econ`
- `/pillagercampaigns campaign list`

Required live-world validation before shipping as a pack-wide surface pressure replacement:

- Start a new overworld with other hostile surface spawns disabled by the pack.
- Verify `/pillagercampaigns status` reports enabled systems and nonzero bases after base discovery has run near a player.
- Travel within discovery radius of a vanilla pillager outpost without loading the outpost chunk directly; verify a strategic base appears in `/pillagercampaigns base list`.
- Load the outpost chunk and run `/pillagercampaigns base rescan_here`; verify the same base is refined rather than duplicated.
- Let a weak/new faction run for several campaign ticks; verify scouts and route markers appear before larger attacks.
- Break a route marker; verify later campaigns stall or produce scout repair behavior rather than continuing through the broken route.
- Kill an officer; verify drops include orders plus a campaign ledger with active campaign and route-node coordinates.
- Deplete a base through fights, then run `/pillagercampaigns base econ tick`; verify officer replacement/garrison pressure resumes only after resources recover.
- Confirm no generic fallback squad appears. Ambient pressure should come only from registered bases, campaign objectives, loaded campaign materialization, and base garrisons.

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `check` task enforces at least 80% class coverage for the bundle. This keeps new source files from silently arriving with no tests while acknowledging that world/event classes need Forge GameTest or live-instance harness coverage.
