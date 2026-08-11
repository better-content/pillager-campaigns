package com.gerald.pillagercampaigns.system

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

    @Test fun `Forge submits observations and commands through the canonical Core adapter`() {
        val formulaSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/FormulaicWarbandRules.kt"))
        val geometrySource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/CampaignMath.kt"))
        val runtimeSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/PillagerRuntime.kt"))
        val campaignSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/PillagerCampaignCoordinator.kt"))
        assertTrue("FormulaMath" in formulaSource && "CoreRules" in formulaSource)
        assertTrue("CampaignGeometry" in geometrySource)
        assertTrue("CoreCommand.ReserveGarrison" in runtimeSource && "WarbandCoreAdapter.transition" in runtimeSource)
        assertTrue("WarbandCoreAdapter.advanceCanonical" in campaignSource && "CoreCommand.Dispatch" in campaignSource)
        assertTrue("transitionCampaign" !in campaignSource)
        assertTrue("EcologyMath.consumeCargo" !in campaignSource && "CampaignDecisions" !in campaignSource)
        assertTrue("WarbandCore.chooseTacticalPosition" in Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/SquadRoutePlanner.kt")))
        val eventSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/PillagerCampaignsEvents.kt"))
        assertTrue("DefeatObservation" in eventSource && "SelectCampaignSuccessor" in eventSource)
        assertTrue("recordCombatObservation" !in eventSource && "recordThreatObservation" !in eventSource)
        val directTransitionCallers = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" && it.fileName.toString() != "WarbandCoreAdapter.kt" }
                .filter { "WarbandCore.transition" in Files.readString(it) }
                .toList()
        }
        assertTrue(directTransitionCallers.isEmpty(), "Forge bypassed the canonical Core adapter: $directTransitionCallers")

        val tconSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/TinkersArmoryOptimizer.kt"))
        assertTrue("seedLedger" !in tconSource && "consume =" !in tconSource && "fun create(" !in tconSource)
        val worldDataSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/data/PillagerWorldData.kt"))
        assertTrue("fun protectPlayerUntil" !in worldDataSource && "fun markPlayerInitialized" !in worldDataSource)
        val adapterSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/WarbandCoreAdapter.kt"))
        assertTrue("fun transitionCampaign" !in adapterSource, "Forge retained a shadow campaign transition")
    }
}
