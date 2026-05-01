# Pillager Campaigns Testing

`./gradlew test jacocoTestReport jacocoTestCoverageVerification` runs the JVM test suite and emits coverage reports.

Current coverage focus:

- `data/*`: NBT save/load, corrupt-entry isolation, reference repair, enum/resource/UUID fallback, campaign/base/officer round trips.
- `system/PillagerCampaignRules`: pure campaign movement, active campaign counts, intel scoring, campaign TTL.
- `system/PillagerBaseService`: faction/officer reuse and base economy caps.
- `scenario/PillagerCampaignsScenarioTest`: end-to-end pure-system scenarios for interception, engagement targeting, scout escape learning, grave-marked killers, siege engineering, succession, order lore, and long-running economy/travel bounds.
- `util/PillagerIdentity`: deterministic faction/officer identity generation.

Plain JUnit deliberately does not bootstrap full Forge networking or a Minecraft world. Runtime/event/entity behavior remains covered by build compilation plus manual/in-world validation commands:

- `/pillagercampaigns status`
- `/pillagercampaigns now`
- `/pillagercampaigns tick_once`
- `/pillagercampaigns base add_here`
- `/pillagercampaigns base rescan_here`
- `/pillagercampaigns base econ`
- `/pillagercampaigns campaign list`

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `check` task enforces at least 80% class coverage for the bundle. This keeps new source files from silently arriving with no tests while acknowledging that world/event classes need Forge GameTest or live-instance harness coverage.
