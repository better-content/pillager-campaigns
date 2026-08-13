package com.bettercontent.pillagercampaigns.system

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertTrue

class FormulaicArchitectureTest {
    @Test fun `runtime contains no fixed roster or origin classifications`() {
        val forbidden = Regex("(?i)(warband.?archetype|combat.?style|warband.?role|structure.?id|fallback.?kit)")
        val violations = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line -> if (forbidden.containsMatchIn(line)) "$path:${index + 1}" else null }.stream()
            }.toList()
        }
        assertTrue(violations.isEmpty(), "fixed classification leaked into runtime: $violations")
    }

    @Test fun `Warband Core has no Minecraft runtime dependencies`() {
        val forbidden = Regex("net\\.minecraft|net\\.minecraftforge|slimeknights\\.tconstruct")
        val violations = Files.walk(Path("warband-core/src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line -> if (forbidden.containsMatchIn(line)) "$path:${index + 1}" else null }.stream()
            }.toList()
        }
        assertTrue(violations.isEmpty(), "Minecraft dependency leaked into Warband Core: $violations")
    }

    @Test fun `Forge submits raw observations through the state owning engine adapter`() {
        val geometrySource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/CampaignMath.kt"))
        val runtimeSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/PillagerRuntime.kt"))
        val campaignSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/PillagerCampaignCoordinator.kt"))
        assertTrue("CampaignGeometry" in geometrySource)
        assertTrue("chooseFormulaMob" !in runtimeSource && "planCampaignSquad" !in runtimeSource)
        assertTrue("WarbandCoreAdapter.advanceCanonical" in campaignSource && "CoreCommand.Dispatch" !in campaignSource)
        assertTrue("transitionCampaign" !in campaignSource)
        assertTrue("EcologyMath.consumeCargo" !in campaignSource && "CampaignDecisions" !in campaignSource)
        val routeSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/SquadRoutePlanner.kt"))
        assertTrue("TacticalObservation" in routeSource && "WarbandCore.chooseTacticalPosition" !in routeSource)
        val eventSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/PillagerCampaignsEvents.kt"))
        assertTrue("DefeatObservation" in eventSource && "SelectCampaignSuccessor" !in eventSource)
        assertTrue("recordCombatObservation" !in eventSource && "recordThreatObservation" !in eventSource)
        val directTransitionCallers = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" && it.fileName.toString() != "WarbandCoreAdapter.kt" }
                .filter { "WarbandCore.transition" in Files.readString(it) }
                .toList()
        }
        assertTrue(directTransitionCallers.isEmpty(), "Forge bypassed the canonical Core adapter: $directTransitionCallers")

        val tconSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/TinkersArmoryOptimizer.kt"))
        assertTrue("liveEquipmentCandidates" !in tconSource && "choosePartMaterial" !in tconSource)
        assertTrue("equipmentPlatforms" in tconSource && "fun realize(manifest: EquipmentManifest)" in tconSource)
        val worldDataSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/data/PillagerWorldData.kt"))
        assertTrue("fun protectPlayerUntil" !in worldDataSource && "fun markPlayerInitialized" !in worldDataSource)
        val adapterSource = Files.readString(Path("src/main/kotlin/com/bettercontent/pillagercampaigns/system/WarbandCoreAdapter.kt"))
        assertTrue("fun transitionCampaign" !in adapterSource, "Forge retained a shadow campaign transition")
        assertTrue("WarbandRuntimeSpec.create" in adapterSource && "WarbandCore." !in adapterSource)
    }

    @Test fun `production has one strategic transition boundary and no preselected gameplay commands`() {
        val forgeFiles = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }
        val runnerFiles = Files.walk(Path("runner/src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }
        val forbiddenCommands = Regex(
            "CoreCommand\\.(Dispatch|ReserveGarrison|ResolveGarrison|SelectCampaignSuccessor|" +
                "PromoteSuccessor|BeginReturn|Dematerialize|DelayWarband|ResolveCampaign|RecordSchedulerProgress)",
        )
        val commandLeaks = forgeFiles.flatMap { path ->
            Files.readAllLines(path).mapIndexedNotNull { index, line ->
                if (forbiddenCommands.containsMatchIn(line)) "$path:${index + 1}" else null
            }
        }
        assertTrue(commandLeaks.isEmpty(), "Forge preselected a strategic outcome: $commandLeaks")

        val engineCallers = forgeFiles.filter { path ->
            "requireEngine().transition" in Files.readString(path) && path.fileName.toString() != "WarbandCoreAdapter.kt"
        }
        assertTrue(engineCallers.isEmpty(), "Forge bypassed WarbandCoreAdapter: $engineCallers")
        assertTrue(forgeFiles.none { "coreState" in Files.readString(it) }, "mutable canonical state escaped the engine")
        assertTrue(runnerFiles.none { "WarbandCore.transition" in Files.readString(it) }, "runner bypassed WarbandEngine")
    }
}
