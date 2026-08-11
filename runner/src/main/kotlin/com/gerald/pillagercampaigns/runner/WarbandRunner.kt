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
            "example" -> println(json.encodeToString(exampleScenario()))
            null, "play" -> play(exampleScenario())
            else -> error("usage: warbandSim [play|example|experiment SCENARIO_JSON OUTPUT_DIRECTORY]")
        }
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
        println("Authoritative Pillager Campaigns engine. Commands: status, advance TICKS, dispatch WARBAND PLAYER, return CAMPAIGN REASON, events, quit")
        var recent = emptyList<EngineEvent>()
        while (true) {
            print("> ")
            val words = readLine()?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: return
            if (words.isEmpty()) continue
            val frame = runCatching {
                when (words[0]) {
                    "status" -> { println(json.encodeToString(scenario.state)); null }
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
}
