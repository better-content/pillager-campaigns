package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExperimentRunnerTest {
    private fun scenario() = ExperimentScenario(
        "runner-test", 16_000L, 20L,
        EngineState(
            warbands = linkedMapOf("w" to WarbandState("w", "f", ChunkPosition("overworld", 0, 0), 96.0, 18.0, 12.0, preferences = linkedMapOf("damage" to 1.0))),
            officers = linkedMapOf("o" to OfficerState("o", "f", "w")),
        ),
        EngineCatalog("v1", listOf(RecruitDefinition("r", 5.0, CapabilityVector(damage = 1.0)))),
        players = listOf(PlayerFact("p", ChunkPosition("overworld", 7, 0), setOf("w"))),
    )

    @Test fun `experiment is deterministic and writes trace and summaries`() {
        val json = Json { encodeDefaults = true }
        val first = ExperimentRunner(json).run(scenario())
        val second = ExperimentRunner(json).run(scenario())
        assertEquals(json.encodeToString(first.summary), json.encodeToString(second.summary))
        assertTrue(first.summary.campaignsDispatched >= 1)
        assertTrue(first.summary.campaignsReturned >= 1)
        assertTrue(first.summary.eventCounts.getOrDefault("dematerialized", 0) >= 1)
        val output = Files.createTempDirectory("warband-runner-test").toFile()
        ExperimentRunner(json).write(first, output)
        assertTrue(output.resolve("trace.jsonl").readLines().isNotEmpty())
        assertTrue(output.resolve("summary.json").isFile)
        assertTrue(output.resolve("summary.csv").readText().startsWith("name,ticks"))
        val comparison = ExperimentRunner(json).compare(first.summary.copy(raidPool = first.summary.raidPool + 2.0), first.summary)
        assertEquals(2.0, comparison.raidPoolDelta)
    }
}
