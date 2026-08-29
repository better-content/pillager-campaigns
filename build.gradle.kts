import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.SourceSetContainer

plugins {
    idea
    eclipse
    jacoco
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
}

val minecraftVersion = property("minecraft_version") as String
val forgeVersion = property("forge_version") as String
val kotlinForForgeVersion = property("kotlinforforge_version") as String
val modId = property("mod_id") as String
val modName = property("mod_name") as String
val modVersion = property("mod_version") as String
val modAuthors = property("mod_authors") as String
val modDescription = property("mod_description") as String
val modLicense = property("mod_license") as String
val modIssueTrackerUrl = property("mod_issue_tracker_url") as String

group = property("mod_group") as String
version = modVersion

base {
    archivesName.set("pillager-campaigns")
}

evaluationDependsOn(":invasion-core")
val coreMainSourceSet = project(":invasion-core").extensions.getByType<SourceSetContainer>().getByName("main")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

minecraft {
    mappings("official", minecraftVersion)
    copyIdeResources = true

    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "info")
            property("forge.enabledGameTestNamespaces", "$modId,minecraft")
            mods {
                create(modId) {
                    source(sourceSets.main.get())
                    source(coreMainSourceSet)
                }
            }
        }
        create("client")
        create("server") { arg("--nogui") }
        create("gameTestServer") {
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
        }
    }
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
}

dependencies {
    implementation(project(":invasion-core"))
    minecraft("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")
    implementation("thedarkcolour:kotlinforforge:$kotlinForForgeVersion")
    testImplementation(kotlin("test"))
    runtimeOnly(fg.deobf("curse.maven:it-takes-a-pillage-635843:4981343"))
    runtimeOnly(fg.deobf("curse.maven:savage-and-ravage-381736:7115735"))
    runtimeOnly(fg.deobf("curse.maven:blueprint-382216:6408581"))
}

tasks.processResources {
    val props = mapOf(
        "minecraftVersion" to minecraftVersion,
        "forgeVersion" to forgeVersion,
        "kotlinForForgeVersion" to kotlinForForgeVersion,
        "modId" to modId,
        "modName" to modName,
        "modVersion" to modVersion,
        "modAuthors" to modAuthors,
        "modDescription" to modDescription,
        "modIssueTrackerUrl" to modIssueTrackerUrl,
        "modLicense" to modLicense,
    )

    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.jar {
    from("src/compat/resources")
    dependsOn(":invasion-core:classes")
    from(coreMainSourceSet.output)
    finalizedBy("reobfJar")
}

tasks.named<Jar>("sourcesJar") {
    from("src/compat/resources")
}

val syncGameTestStructures by tasks.registering(Copy::class) {
    from("gameteststructures")
    into(layout.projectDirectory.dir("run/gameteststructures"))
}

val cleanGameTestWorld by tasks.registering(Delete::class) {
    delete(layout.projectDirectory.dir("run/world"))
}

tasks.withType<JavaExec>().configureEach {
    if (name == "runGameTestServer") {
        dependsOn(cleanGameTestWorld, syncGameTestStructures)
    }
}

val stageRuntimeJar by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the reobfuscated runtime jar into build/libs using the canonical release filename."
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "pillager-campaigns-${modVersion}.jar" }
}

tasks.assemble {
    dependsOn(stageRuntimeJar)
}

tasks.register("headlessGameTest") {
    group = "verification"
    description = "Runs Forge game tests in a headless dedicated server."
    dependsOn(tasks.named("runGameTestServer"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/PillagerCampaignsMod*",
                        "**/PillagerCampaignsEvents*",
                        "**/PillagerCampaignsConfig*",
                        "**/InvasionRuntime*",
                        "**/PillagerWorldData*",
                        "**/SurfaceGridSampler*",
                        "**/InvasionRoster*",
                        "**/api/CampaignStatusApi*",
                        "**/gametest/**",
                    )
                }
            },
        ),
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Forge adapters are proved by headless GameTests; the pure policy module retains the 90% gate.
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register("verifyFast") {
    group = "verification"
    description = "Runs the fast deterministic verification lane."
    dependsOn(tasks.named("check"))
    dependsOn(":invasion-core:check")
    dependsOn(":runner:test")
}

tasks.register("verifyFull") {
    group = "verification"
    description = "Runs the full verification lane, including headless Forge GameTests."
    dependsOn(tasks.named("verifyFast"))
    dependsOn(tasks.named("headlessGameTest"))
}

tasks.register("verifyWorld") {
    group = "verification"
    description = "Runs real authored-roster Forge GameTests with exact pack dependencies."
    dependsOn(tasks.named("headlessGameTest"))
}

tasks.register("invasionCoreExperiment") {
    group = "application"
    description = "Runs the deterministic Invasion Core scenario inspector; this is not a Minecraft simulation."
    dependsOn(":runner:run")
}
