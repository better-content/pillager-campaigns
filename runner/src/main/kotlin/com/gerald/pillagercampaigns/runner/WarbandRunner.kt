package com.gerald.pillagercampaigns.runner

import com.gerald.pillagercampaigns.engine.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object WarbandRunner {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "experiment" -> experiment(args.drop(1))
            "compare" -> compare(args.drop(1))
            "sweep" -> sweep(args.drop(1))
            "explore" -> explore(args.drop(1))
            "example" -> println(json.encodeToString(exampleScenario()))
            "matrix-example" -> println(json.encodeToString(exampleMatrix()))
            null, "play" -> play(exampleScenario())
            else -> error("usage: warbandSim [play|example|matrix-example|experiment SCENARIO_JSON OUTPUT_DIRECTORY|compare SCENARIO_JSON BASELINE_JSON OUTPUT_DIRECTORY|sweep MATRIX_JSON OUTPUT_DIRECTORY|explore LIVE_CATALOG_JSON OUTPUT_DIRECTORY]")
        }
    }

    private fun explore(args: List<String>) {
        require(args.isNotEmpty()) { "explore requires a Forge live-catalog JSON path" }
        val output = File(args.getOrElse(1) { "build/warband-balance" })
        val result = BalanceExplorer(json).explore(File(args[0]), output)
        println("wrote ${result.summaries.size} cells and ${result.findings.size} findings to ${output.absolutePath}")
    }

    private fun compare(args: List<String>) {
        require(args.size >= 2) { "compare requires scenario and baseline summary JSON paths" }
        val scenario = json.decodeFromString<ExperimentScenario>(File(args[0]).readText())
        val baseline = json.decodeFromString<ExperimentSummary>(File(args[1]).readText())
        val output = File(args.getOrElse(2) { "build/warband-comparison/${scenario.name}" })
        val runner = ExperimentRunner(json)
        val result = runner.run(scenario)
        runner.write(result, output)
        val comparison = runner.compare(result.summary, baseline)
        output.resolve("comparison.json").writeText(json.encodeToString(comparison))
        println(json.encodeToString(comparison))
    }

    private fun sweep(args: List<String>) {
        require(args.isNotEmpty()) { "sweep requires an experiment matrix JSON path" }
        val matrix = json.decodeFromString<ExperimentMatrix>(File(args[0]).readText())
        val output = File(args.getOrElse(1) { "build/warband-sweep/${matrix.scenario.name}" })
        val runner = ExperimentRunner(json)
        val runs = listOf("lower" to matrix.assumptions.lower, "nominal" to matrix.assumptions.nominal, "upper" to matrix.assumptions.upper)
        val summaries = runs.map { (label, assumptions) ->
            val scenario = json.decodeFromString<ExperimentScenario>(json.encodeToString(matrix.scenario)).copy(
                name = "${matrix.scenario.name}-$label",
                assumptions = assumptions,
            )
            runner.run(scenario).also { runner.write(it, output.resolve(label)) }.summary
        }
        output.mkdirs()
        output.resolve("matrix.csv").writeText(buildString {
            appendLine("name,reserve_threat,raid_pool,material_units,armory_items,dispatched,returned,peak_campaign_threat")
            summaries.forEach { summary ->
                appendLine(listOf(summary.name, summary.reserveThreat, summary.raidPool, summary.materialUnits, summary.armoryItems, summary.campaignsDispatched, summary.campaignsReturned, summary.peakCampaignThreat).joinToString(","))
            }
        })
        println(summaries.joinToString("\n") { json.encodeToString(it) })
    }

    private fun experiment(args: List<String>) {
        require(args.isNotEmpty()) { "experiment requires a scenario JSON path" }
        val scenario = json.decodeFromString<ExperimentScenario>(File(args[0]).readText())
        val output = File(args.getOrElse(1) { "build/warband-experiment/${scenario.name}" })
        val result = ExperimentRunner(json).run(scenario)
        ExperimentRunner(json).write(result, output)
        println(json.encodeToString(result.summary))
    }

    private fun play(scenario: ExperimentScenario) {
        println("Authoritative Pillager Campaigns engine. Commands: status, scores WARBAND BUDGET, advance TICKS, dispatch WARBAND PLAYER, return CAMPAIGN REASON, events, quit")
        var recent = emptyList<EngineEvent>()
        while (true) {
            print("> ")
            val words = readLine()?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: return
            if (words.isEmpty()) continue
            val frame = runCatching {
                when (words[0]) {
                    "status" -> { println(json.encodeToString(scenario.state)); null }
                    "scores" -> {
                        val warband = scenario.state.warbands.getValue(words[1])
                        val officer = scenario.state.officers.values.firstOrNull { it.homeWarbandId == warband.id }
                        scenario.catalog.recruits.filter { it.baseThreat <= words[2].toDouble() }.forEach { recruit ->
                            val selected = WarbandEngine.chooseRecruit(scenario.state, warband, officer, scenario.catalog.copy(recruits = listOf(recruit)), words[2].toDouble(), rules = scenario.rules)
                            val score = WarbandEngine.recruitScore(warband, officer, recruit, scenario.rules)
                            println("${recruit.id}: ${if (selected == null) "unavailable" else "eligible"} score=$score capabilities=${recruit.capabilities} threat=${recruit.baseThreat}")
                        }
                        null
                    }
                    "events" -> { recent.forEach(::println); null }
                    "advance" -> EngineFrame(words[1].toLong(), scenario.players)
                    "dispatch" -> EngineFrame(0L, scenario.players, commands = listOf(EngineCommand.Dispatch(words[1], words[2])))
                    "return" -> EngineFrame(0L, scenario.players, commands = listOf(EngineCommand.BeginReturn(words[1], words[2])))
                    "quit", "exit" -> return
                    else -> error("unknown command ${words[0]}")
                }
            }.getOrElse { println("ERROR: ${it.message}"); null }
            if (frame != null) {
                val result = WarbandEngine.transition(scenario.state, frame, scenario.catalog, scenario.rules)
                recent = result.events
                result.events.forEach(::println)
                result.effects.forEach { println("EFFECT $it") }
            }
        }
    }

    private fun exampleScenario(): ExperimentScenario {
        val warband = WarbandState(
            "warband", "faction", ChunkPosition("minecraft:overworld", 0, 0), 156.0, 18.0,
            preferences = linkedMapOf("durability" to 1.0, "damage" to 1.0, "mobility" to 0.8, "range" to 0.8, "control" to 0.6),
            materialLedger = linkedMapOf("wood" to 12.0, "flint" to 6.0, "iron" to 6.0),
        )
        val state = EngineState(
            factions = linkedMapOf("faction" to FactionState("faction", "Example", 1)),
            warbands = linkedMapOf(warband.id to warband),
            officers = linkedMapOf("captain" to OfficerState("captain", "faction", warband.id)),
        )
        return ExperimentScenario(
            "example", 72_000L, state = state,
            catalog = EngineCatalog(
                "example-v1",
                recruits = listOf(
                    RecruitDefinition("quick", 5.0, CapabilityVector(1.0, 0.7, 1.5, 0.4, 0.2), supportedEquipmentActions = setOf("melee")),
                    RecruitDefinition("stout", 7.0, CapabilityVector(1.8, 1.1, 0.6, 0.2, 0.5), supportedEquipmentActions = setOf("melee")),
                    RecruitDefinition("bowed", 6.0, CapabilityVector(0.8, 0.8, 0.8, 1.8, 0.3), supportedEquipmentActions = setOf("ranged")),
                ),
                materials = listOf(
                    MaterialDefinition("wood", 1, 12.0), MaterialDefinition("flint", 1, 12.0), MaterialDefinition("iron", 2, 48.0),
                ),
                equipment = listOf(
                    EquipmentDefinition("long-tool", listOf("head", "handle"), CapabilityVector(damage = 0.8, range = 1.5), mapOf("wood" to 2.0, "flint" to 1.0), setOf("ranged")),
                    EquipmentDefinition("hard-tool", listOf("head", "binding"), CapabilityVector(durability = 1.4, damage = 1.0), mapOf("wood" to 1.0, "iron" to 2.0), setOf("melee")),
                ),
            ),
            players = listOf(PlayerFact("player", ChunkPosition("minecraft:overworld", 12, 0), setOf(warband.id))),
        )
    }

    private fun exampleMatrix(): ExperimentMatrix {
        val nominal = BoundedAssumptions()
        return ExperimentMatrix(
            exampleScenario(),
            AssumptionSweep(
                lower = nominal.copy(routeConfidence = 0.35, cohesion = 0.45, campaignDamagePerEngagement = 6.0, playerDamagePerEngagement = 3.0),
                nominal = nominal,
                upper = nominal.copy(routeConfidence = 0.85, cohesion = 0.90, campaignDamagePerEngagement = 2.0, playerDamagePerEngagement = 8.0),
            ),
        )
    }
}
