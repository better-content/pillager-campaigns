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

    @Test fun `authoritative engine has no Minecraft runtime dependencies`() {
        val forbidden = Regex("net\\.minecraft|net\\.minecraftforge|slimeknights\\.tconstruct")
        val violations = Files.walk(Path("engine/src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line -> if (forbidden.containsMatchIn(line)) "$path:${index + 1}" else null }.stream()
            }.toList()
        }
        assertTrue(violations.isEmpty(), "Minecraft dependency leaked into authoritative engine: $violations")
    }

    @Test fun `Forge baseline formulas delegate into authoritative engine`() {
        val formulaSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/FormulaicWarbandRules.kt"))
        val geometrySource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/CampaignMath.kt"))
        val runtimeSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/PillagerRuntime.kt"))
        val campaignSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/PillagerCampaignEngine.kt"))
        assertTrue("FormulaMath" in formulaSource && "WarbandRules" in formulaSource)
        assertTrue("CampaignGeometry" in geometrySource)
        assertTrue("PillagerEngineBridge.chooseRecruit" in runtimeSource && "PillagerEngineBridge.planCampaign" in runtimeSource)
        assertTrue("PillagerEngineBridge.advanceEconomies" in campaignSource && "PillagerEngineBridge.raidBudget" in campaignSource)
        assertTrue("PillagerEngineBridge.transitionCampaign" in campaignSource)
        assertTrue("EcologyMath.consumeCargo" !in campaignSource && "CampaignDecisions" !in campaignSource)
        assertTrue("EcologyMath.tacticalScore" in Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/system/SquadRoutePlanner.kt")))
        val eventSource = Files.readString(Path("src/main/kotlin/com/gerald/pillagercampaigns/PillagerCampaignsEvents.kt"))
        assertTrue("recordCombatObservation" !in eventSource && "recordThreatObservation" !in eventSource)
        val directTransitionCallers = Files.walk(Path("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" && it.fileName.toString() != "PillagerEngineBridge.kt" }
                .filter { "WarbandEngine.transition" in Files.readString(it) }
                .toList()
        }
        assertTrue(directTransitionCallers.isEmpty(), "Forge bypassed the canonical projection bridge: $directTransitionCallers")
    }
}
