package com.bettercontent.pillagercampaigns.runner

import com.gerald.warband.core.*
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
            "mvp" -> mvp(args.drop(1))
            "record" -> record(args.drop(1))
            "replay" -> replay(args.drop(1))
            "example" -> println(json.encodeToString(exampleScenario()))
            "matrix-example" -> println(json.encodeToString(exampleMatrix()))
            null, "inspect" -> inspect(exampleScenario())
            else -> error("usage: warbandCoreExperiment [inspect|example|matrix-example|experiment SCENARIO_JSON OUTPUT_DIRECTORY|compare SCENARIO_JSON BASELINE_JSON OUTPUT_DIRECTORY|sweep MATRIX_JSON OUTPUT_DIRECTORY|explore RUNTIME_SPEC_JSON OUTPUT_DIRECTORY|mvp RUNTIME_SPEC_JSON OUTPUT_DIRECTORY|record SCENARIO_JSON TRACE_JSON|replay TRACE_JSON]")
        }
    }

    private fun mvp(args: List<String>) {
        require(args.isNotEmpty()) { "mvp requires a Warband runtime-spec JSON path" }
        val output = File(args.getOrElse(1) { "build/warband-mvp" })
        val report = MvpReadinessEvaluator(json).evaluate(File(args[0]), output)
        println("${if (report.passed) "PASS" else "FAIL"}: ${report.checks.count { it.passed }}/${report.checks.size} checks; report=${output.absolutePath}")
        check(report.passed) { "Warband Core MVP readiness failed: ${report.checks.filterNot { it.passed }.joinToString { it.id }}" }
    }

    private fun record(args: List<String>) {
        require(args.size >= 2) { "record requires scenario and trace JSON paths" }
        val scenario = json.decodeFromString<ExperimentScenario>(File(args[0]).readText())
        val trace = requireNotNull(ExperimentRunner(json).run(scenario, retainTrace = true).deterministicTrace)
        WarbandTraceCodec(json).write(trace, File(args[1]))
        println("recorded ${trace.steps.size} Core transitions to ${File(args[1]).absolutePath}")
    }

    private fun replay(args: List<String>) {
        require(args.isNotEmpty()) { "replay requires a trace JSON path" }
        val codec = WarbandTraceCodec(json)
        val result = codec.replay(codec.read(File(args[0])))
        println("replayed ${result.stepCount} Core transitions; final state ${result.finalStateHash}")
    }

    private fun explore(args: List<String>) {
        require(args.isNotEmpty()) { "explore requires a Warband runtime-spec JSON path" }
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
        val result = runner.run(scenario, retainTrace = false)
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
            runner.run(scenario, retainTrace = false).also { runner.write(it, output.resolve(label)) }.summary
        }
        output.mkdirs()
        output.resolve("matrix.csv").writeText(buildString {
            appendLine("name,reserve_threat,raid_pool,material_units,armory_items,dispatched,returned,peak_campaign_threat,model,minecraft_simulation,synthetic_external_observations")
            summaries.forEach { summary ->
                appendLine(listOf(
                    summary.name, summary.reserveThreat, summary.raidPool, summary.materialUnits,
                    summary.armoryItems, summary.campaignsDispatched, summary.campaignsReturned,
                    summary.peakCampaignThreat, summary.boundary.model,
                    summary.boundary.minecraftSimulation, summary.boundary.externalObservationsAreSynthetic,
                ).joinToString(","))
            }
        })
        println(summaries.joinToString("\n") { json.encodeToString(it) })
    }

    private fun experiment(args: List<String>) {
        require(args.isNotEmpty()) { "experiment requires a scenario JSON path" }
        val scenario = json.decodeFromString<ExperimentScenario>(File(args[0]).readText())
        val output = File(args.getOrElse(1) { "build/warband-experiment/${scenario.name}" })
        val result = ExperimentRunner(json).run(scenario, retainTrace = false)
        ExperimentRunner(json).write(result, output)
        println(json.encodeToString(result.summary))
    }

    private fun inspect(scenario: ExperimentScenario) {
        println(NOT_MINECRAFT_SIMULATION)
        println("Warband Core state inspector. Commands: status, advance TICKS, events, quit")
        val engine = WarbandEngine.restore(scenario.initialSnapshot, scenario.runtimeSpec)
        var recent = emptyList<CoreEvent>()
        while (true) {
            print("> ")
            val words = readLine()?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: return
            if (words.isEmpty()) continue
            val frame = runCatching {
                when (words[0]) {
                    "status" -> { println(json.encodeToString(engine.snapshot())); null }
                    "events" -> { recent.forEach(::println); null }
                    "advance" -> CoreFrame(words[1].toLong(), scenario.players)
                    "quit", "exit" -> return
                    else -> error("unknown command ${words[0]}")
                }
            }.getOrElse { println("ERROR: ${it.message}"); null }
            if (frame != null) {
                val result = engine.transition(frame)
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
            stockpile = linkedMapOf("ration" to 12, "bolts" to 12, "repair-kit" to 8, "tonic" to 4),
        )
        val state = CoreSnapshot(
            factions = linkedMapOf("faction" to FactionState("faction", "Example", 1)),
            warbands = linkedMapOf(warband.id to warband),
            officers = linkedMapOf("captain" to OfficerState("captain", "faction", warband.id)),
            territoryRelations = linkedMapOf(
                "${warband.id}|player" to TerritoryRelationState(warband.id, "player", TerritoryStatus.HOSTILE),
            ),
        )
        val runtimeSpec = WarbandRuntimeSpec.create(
                rules = CoreRules(),
                recruits = listOf(
                    RecruitDefinition("quick", 5.0, CapabilityVector(1.0, 0.7, 1.5, 0.4, 0.2), supportedEquipmentActions = setOf("melee")),
                    RecruitDefinition("stout", 7.0, CapabilityVector(1.8, 1.1, 0.6, 0.2, 0.5), supportedEquipmentActions = setOf("melee")),
                    RecruitDefinition("bowed", 6.0, CapabilityVector(0.8, 0.8, 0.8, 1.8, 0.3), supportedEquipmentActions = setOf("ranged")),
                ),
                materials = listOf(
                    MaterialDefinition("wood", 1, 12.0, CapabilityVector(durability = .4, mobility = .8, control = .5)),
                    MaterialDefinition("flint", 1, 12.0, CapabilityVector(damage = .9, mobility = .4)),
                    MaterialDefinition("iron", 2, 48.0, CapabilityVector(durability = 1.0, damage = .8, control = .6)),
                ),
                resources = listOf(
                    ResourceDefinition("ration", ResourceVector(sustenance = 2.0), .5, EnvironmentTraits(habitability = .9, biomass = .9)),
                    ResourceDefinition("bolts", ResourceVector(munitions = 2.0), .4, EnvironmentTraits(mineralPotential = .8)),
                    ResourceDefinition("repair-kit", ResourceVector(maintenance = 2.0), .8, EnvironmentTraits(mineralPotential = .9)),
                    ResourceDefinition("tonic", ResourceVector(recovery = 2.0), .3, EnvironmentTraits(exoticPotential = .9)),
                ),
                equipmentPlatforms = listOf(
                    EquipmentPlatformDefinition("long-tool", supportedActions = setOf("ranged"), components = listOf(
                        EquipmentComponentDefinition("head", "head", setOf("flint"), 1.0),
                        EquipmentComponentDefinition("handle", "handle", setOf("wood"), 2.0),
                    ), baseCapabilities = CapabilityVector(damage = 0.8, range = 1.5)),
                    EquipmentPlatformDefinition("hard-tool", supportedActions = setOf("melee"), components = listOf(
                        EquipmentComponentDefinition("head", "head", setOf("iron"), 2.0),
                        EquipmentComponentDefinition("binding", "binding", setOf("wood"), 1.0),
                    ), baseCapabilities = CapabilityVector(durability = 1.4, damage = 1.0)),
                ),
                environmentModel = EnvironmentModelDefinition(samples = listOf(
                    EnvironmentTraits(habitability = .8, biomass = .85, mineralPotential = .35, exoticPotential = .2, travelFriction = .25),
                    EnvironmentTraits(habitability = .3, biomass = .2, mineralPotential = .9, exoticPotential = .35, travelFriction = .8),
                    EnvironmentTraits(habitability = .5, biomass = .45, mineralPotential = .55, exoticPotential = .95, travelFriction = .55),
                )),
                rewards = listOf(RewardDefinition("token", 1.0)),
            )
        return ExperimentScenario(
            "example", 72_000L, initialSnapshot = state,
            runtimeSpec = runtimeSpec,
            players = listOf(PlayerFact("player", ChunkPosition("minecraft:overworld", 12, 0))),
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
