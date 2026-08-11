package com.gerald.pillagercampaigns.sim

/** Small deterministic warband spreadsheet game; intentionally has no Minecraft dependencies. */
object WarbandScenarioMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val catalog = SimulationCatalog(
            recruits = listOf(
                RecruitCandidate("quick", 5.0, CapabilityVector(1.0, 0.7, 1.5, 0.4, 0.2)),
                RecruitCandidate("stout", 7.0, CapabilityVector(1.8, 1.1, 0.6, 0.2, 0.5)),
                RecruitCandidate("bowed", 6.0, CapabilityVector(0.8, 0.8, 0.8, 1.8, 0.3)),
            ),
            equipment = listOf(
                EquipmentCandidate("long-tool", listOf("head", "handle"), CapabilityVector(damage = 0.8, range = 1.5), mapOf("wood" to 2.0, "flint" to 1.0)),
                EquipmentCandidate("hard-tool", listOf("head", "binding"), CapabilityVector(durability = 1.4, damage = 1.0), mapOf("wood" to 1.0, "iron" to 2.0)),
            ),
        )
        val state = WarbandModel("cli", capacity = 156.0, reserveThreat = 18.0, environment = CapabilityVector(0.4, 0.2, 0.7, 0.4, 0.5), preferences = CapabilityVector(1.0, 1.0, 0.8, 0.8, 0.6))
        println("Pillager warband spreadsheet. Commands: status, advance TICKS, extract KEY RATE TICKS, make [COUNT], dispatch PLAYER, materialize ID, round ID dealt taken range route cohesion [deadIds], return ID, dematerialize ID, events, quit")
        var recent = emptyList<SimulationEvent>()
        while (true) {
            print("> ")
            val line = readLine()?.trim() ?: break
            val words = line.split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.isEmpty()) continue
            val command = runCatching { when (words[0]) {
                "status" -> { printState(state); null }
                "events" -> { recent.forEach(::println); null }
                "advance" -> SimulationCommand.Advance(words[1].toLong())
                "extract" -> SimulationCommand.Advance(words[3].toLong(), mapOf(words[1] to words[2].toDouble()))
                "make" -> SimulationCommand.Manufacture(words.getOrNull(1)?.toInt() ?: 1)
                "dispatch" -> SimulationCommand.Dispatch(words[1])
                "materialize" -> SimulationCommand.Materialize(words[1])
                "return" -> SimulationCommand.Return(words[1], "operator")
                "dematerialize" -> SimulationCommand.Dematerialize(words[1])
                "round" -> SimulationCommand.CombatRound(words[1], CombatObservation(words[2].toDouble(), words[3].toDouble(), words[4].toDouble(), words[5].toDouble(), words[6].toDouble(), words.drop(7).toSet()))
                "quit", "exit" -> return
                else -> error("unknown command ${words[0]}")
            } }.getOrElse { println("ERROR: ${it.message}"); null }
            if (command != null) {
                recent = WarbandSimulation.step(state, catalog, command).events
                recent.forEach(::println)
            }
        }
    }

    private fun printState(state: WarbandModel) {
        println("tick=${state.tick} aggression=${state.aggression} reserve=${"%.2f".format(state.reserveThreat)}/${state.capacity}")
        println("resources=${state.resources.amounts} armory=${state.armory.map { it.candidateId }} preferences=${state.preferences}")
        state.campaigns.forEach { println("campaign=${it.id} target=${it.targetId} direction=${it.direction} presence=${it.materialization} home=${it.distanceFromHomeChunks} target=${it.targetDistanceChunks} morale=${"%.2f".format(it.morale)} members=${it.members.map { member -> member.recruitId }}") }
    }
}

