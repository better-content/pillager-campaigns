# Pillager Pressure Testing

`./gradlew test jacocoTestReport jacocoTestCoverageVerification` runs the JVM test suite and emits coverage reports.

Current coverage focus:

- `data/*`: NBT save/load, corrupt-entry isolation, reference repair, enum/resource/UUID fallback, campaign/base/officer round trips.
- `system/PillagerCampaignRules`: pure campaign movement, active campaign counts, intel scoring, campaign TTL.
- `system/PillagerBaseService`: faction/officer reuse and base economy caps.
- `util/PillagerIdentity`: deterministic faction/officer identity generation.

Plain JUnit deliberately does not bootstrap full Forge networking or a Minecraft world. Runtime/event/entity behavior remains covered by build compilation plus manual/in-world validation commands:

- `/pillagerpressure status`
- `/pillagerpressure now`
- `/pillagerpressure tick_once`
- `/pillagerpressure base add_here`
- `/pillagerpressure base rescan_here`
- `/pillagerpressure base econ`
- `/pillagerpressure campaign list`

JaCoCo reports:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

The Gradle `check` task enforces at least 80% class coverage for the bundle. This keeps new source files from silently arriving with no tests while acknowledging that world/event classes need Forge GameTest or live-instance harness coverage.
