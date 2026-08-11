package com.gerald.pillagercampaigns.runner

import com.gerald.warband.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExperimentRunnerTest {
    private fun scenario() = ExperimentScenario(
        "runner-test", 16_000L, 20L,
        CoreSnapshot(
            warbands = linkedMapOf("w" to WarbandState(
                "w", "f", ChunkPosition("overworld", 0, 0), 96.0, 18.0, 12.0,
                preferences = linkedMapOf("damage" to 1.0), stockpile = linkedMapOf("ration" to 24),
            )),
            officers = linkedMapOf("o" to OfficerState("o", "f", "w")),
        ),
        CoreCatalog(
            "v1", listOf(RecruitDefinition("r", 5.0, CapabilityVector(damage = 1.0))),
            resources = listOf(ResourceDefinition("ration", ResourceVector(sustenance = 1.0))),
        ),
        players = listOf(PlayerFact("p", ChunkPosition("overworld", 7, 0), setOf("w"))),
    )

    @Test fun `experiment is deterministic and writes trace and summaries`() {
        val json = Json { encodeDefaults = true }
        val first = ExperimentRunner(json).run(scenario())
        val second = ExperimentRunner(json).run(scenario())
        assertEquals(json.encodeToString(first.summary), json.encodeToString(second.summary))
        assertEquals(first.deterministicTrace, second.deterministicTrace)
        assertNotEquals(
            WarbandTraceCodec(json).stateHash(first.trace.first().state),
            WarbandTraceCodec(json).stateHash(first.trace.last().state),
        )
        assertTrue(first.summary.campaignsDispatched >= 1)
        assertTrue(first.summary.campaignsReturned >= 1)
        assertTrue(first.summary.eventCounts.getOrDefault("dematerialized", 0) >= 1)
        assertEquals(
            first.summary.campaignsReturned,
            first.summary.eventCounts.getOrDefault("snapshots_applied", 0),
            "the physical adapter must acknowledge each capture effect exactly once",
        )
        assertTrue(first.trace.last().state.pendingEffects.values.none {
            it.kind == EffectKind.MATERIALIZE || it.kind == EffectKind.CAPTURE_SNAPSHOTS
        })
        assertTrue(first.summary.resourcesConsumed > 0)
        assertTrue(first.summary.meanSupplySatisfaction in 0.0..1.0)
        val output = Files.createTempDirectory("warband-runner-test").toFile()
        ExperimentRunner(json).write(first, output)
        assertTrue(output.resolve("trace.jsonl").readLines().isNotEmpty())
        assertTrue(output.resolve("summary.json").isFile)
        assertTrue(output.resolve("summary.csv").readText().startsWith("name,ticks"))
        val comparison = ExperimentRunner(json).compare(first.summary.copy(raidPool = first.summary.raidPool + 2.0), first.summary)
        assertEquals(2.0, comparison.raidPoolDelta)
    }

    @Test fun `deterministic trace replays every boundary and detects state divergence`() {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val run = ExperimentRunner(json).run(scenario())
        val trace = requireNotNull(run.deterministicTrace)
        assertTrue(trace.steps.isNotEmpty())
        assertNotEquals(trace.initialStateHash, trace.steps.last().postStateHash)

        val root = Files.createTempDirectory("warband-trace-test").toFile()
        val file = root.resolve("trace.json")
        val codec = WarbandTraceCodec(json)
        codec.write(trace, file)
        val decoded = codec.read(file)
        val replayed = codec.replay(decoded)
        assertEquals(decoded.steps.size, replayed.stepCount)
        assertEquals(decoded.steps.last().postStateHash, replayed.finalStateHash)

        val divergent = decoded.copy(
            steps = decoded.steps.toMutableList().also { steps ->
                steps[0] = steps[0].copy(postStateHash = "0".repeat(64))
            },
        )
        val failure = assertFailsWith<TraceDivergenceException> { codec.replay(divergent) }
        assertEquals(0, failure.stepIndex)
        assertEquals("postStateHash", failure.component)

        val eventIndex = decoded.steps.indexOfFirst { it.events.isNotEmpty() }
        assertTrue(eventIndex >= 0)
        val eventDivergent = decoded.copy(
            steps = decoded.steps.toMutableList().also { steps ->
                steps[eventIndex] = steps[eventIndex].copy(events = emptyList())
            },
        )
        val eventFailure = assertFailsWith<TraceDivergenceException> { codec.replay(eventDivergent) }
        assertEquals(eventIndex, eventFailure.stepIndex)
        assertEquals("events", eventFailure.component)
    }

    @Test fun `record and replay CLI writes a self contained trace`() {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val root = Files.createTempDirectory("warband-trace-cli-test").toFile()
        val scenarioFile = root.resolve("scenario.json").also { it.writeText(json.encodeToString(scenario())) }
        val traceFile = root.resolve("trace.json")
        WarbandRunner.main(arrayOf("record", scenarioFile.path, traceFile.path))
        assertTrue(traceFile.isFile)
        WarbandRunner.main(arrayOf("replay", traceFile.path))
    }

    @Test fun `balance exploration cites existing evidence cells and writes all report formats`() {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val catalog = CoreCatalog(
            "forge-live-sha256:test",
            listOf(
                RecruitDefinition("quick", 5.0, CapabilityVector(damage = 1.0, mobility = 1.5)),
                RecruitDefinition("ranged", 6.0, CapabilityVector(range = 1.8, durability = .8)),
            ),
            listOf(MaterialDefinition("wood", 1, 12.0), MaterialDefinition("iron", 2, 48.0)),
            listOf(EquipmentDefinition("tool", listOf("wood"), CapabilityVector(damage = 1.0), mapOf("wood" to 2.0), setOf("melee"))),
            listOf(EnvironmentTraits()),
        )
        val root = Files.createTempDirectory("warband-balance-test").toFile()
        val catalogFile = root.resolve("catalog.json").also { it.writeText(json.encodeToString(catalog)) }
        val output = root.resolve("report")
        val exploration = BalanceExplorer(json).explore(
            catalogFile, output, BalanceExplorer.Settings(listOf(3L, 30L), listOf(11L), includeSensitivity = false),
        )
        val scenarioNames = exploration.summaries.map { it.name }.toSet()
        assertTrue(exploration.findings.flatMap { it.evidenceScenarios }.all { it in scenarioNames })
        assertTrue(output.resolve("exploration.json").isFile)
        assertTrue(output.resolve("summaries.csv").readText().startsWith("name,ticks"))
        assertTrue(output.resolve("balance-notes.md").readText().contains(catalog.revision))
    }
}
