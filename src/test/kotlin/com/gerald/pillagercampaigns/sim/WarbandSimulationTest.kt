package com.gerald.pillagercampaigns.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WarbandSimulationTest {
    private val catalog = SimulationCatalog(
        recruits = listOf(
            RecruitCandidate("low", 8.0, CapabilityVector(1.0, 1.0, 0.5, 0.2)),
            RecruitCandidate("mobile", 10.0, CapabilityVector(0.7, 0.8, 1.8, 0.6)),
        ),
        equipment = listOf(
            EquipmentCandidate("range", listOf("wood", "string"), CapabilityVector(range = 2.0), mapOf("wood" to 2.0, "string" to 1.0)),
            EquipmentCandidate("armor", listOf("iron"), CapabilityVector(durability = 2.0), mapOf("iron" to 2.0)),
        ),
    )

    private fun state() = WarbandModel("test", capacity = 156.0, reserveThreat = 18.0, environment = CapabilityVector(), preferences = CapabilityVector(1.0, 1.0, 1.0, 1.0, 1.0))

    @Test fun `minimum campaign waits for and then deploys real candidate threat`() {
        val state = state().copy(reserveThreat = 6.0)
        assertEquals(8.0, WarbandSimulation.minimumDeployableThreat(state, catalog))
        assertTrue(WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("player")).state.campaigns.isEmpty())
        state.reserveThreat = 8.0
        val result = WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("player"))
        assertEquals(1, result.state.campaigns.single().members.size)
        assertEquals(0.0, result.state.reserveThreat)
    }

    @Test fun `manufacture consumes exact ledger and return reconciles only at home`() {
        val state = state()
        state.resources.add(mapOf("wood" to 2.0, "string" to 1.0))
        WarbandSimulation.step(state, catalog, SimulationCommand.Manufacture())
        assertEquals(0.0, state.resources.amounts.getValue("wood"))
        assertEquals(1, state.armory.size)
        WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("player"))
        val campaign = state.campaigns.single()
        val committed = campaign.members.sumOf { it.threat }
        assertTrue(state.armory.isEmpty())
        WarbandSimulation.step(state, catalog, SimulationCommand.Return(campaign.id, "idle"))
        WarbandSimulation.step(state, catalog, SimulationCommand.Dematerialize(campaign.id))
        WarbandSimulation.step(state, catalog, SimulationCommand.Advance(7 * 120L))
        assertEquals(1, state.campaigns.size)
        WarbandSimulation.step(state, catalog, SimulationCommand.Advance(120L))
        assertTrue(state.campaigns.isEmpty())
        assertEquals(18.0, state.reserveThreat, 0.0001)
        assertEquals(committed, state.reserveThreat, 18.0 - committed + 0.0001)
        assertEquals(1, state.armory.size)
        assertEquals(7, state.aggression)
    }

    @Test fun `defeat learning changes environmental functional selection when counter exists`() {
        val state = state().copy(reserveThreat = 20.0, preferences = CapabilityVector(durability = 1.0, damage = 1.0, mobility = 0.1, range = 0.1))
        val before = WarbandSimulation.chooseRecruit(state, catalog, 20.0, emptyList())!!.id
        WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("player"))
        val campaign = state.campaigns.single()
        WarbandSimulation.step(state, catalog, SimulationCommand.Materialize(campaign.id))
        repeat(8) {
            WarbandSimulation.step(state, catalog, SimulationCommand.CombatRound(campaign.id, CombatObservation(1.0, 8.0, 12.0, 0.1, 0.3)))
        }
        val after = WarbandSimulation.chooseRecruit(state, catalog, 20.0, emptyList())!!.id
        assertNotEquals(before, after)
        assertTrue(state.preferences.mobility > 1.0 && state.preferences.range > 1.0)
    }

    @Test fun `seeded scenario replay is deterministic and never duplicates targets`() {
        fun play(): WarbandModel {
            val state = state().copy(reserveThreat = 30.0)
            repeat(200) { seed ->
                WarbandSimulation.step(state, catalog, SimulationCommand.Advance(20, mapOf("wood" to (seed % 3).toDouble())))
                if (seed % 20 == 0) WarbandSimulation.step(state, catalog, SimulationCommand.Manufacture())
            }
            WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("one"))
            WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("one"))
            WarbandSimulation.validate(state, catalog)
            return state
        }
        assertEquals(play(), play())
        assertEquals(1, play().campaigns.size)
    }

    @Test fun `idle physical campaign withdraws and casualties leave no returned equipment`() {
        val state = state().copy(reserveThreat = 20.0, resources = ResourceLedger(mapOf("wood" to 4.0, "string" to 2.0).toMutableMap()))
        assertEquals(state.resources, state.resources.copyLedger())
        WarbandSimulation.step(state, catalog, SimulationCommand.Manufacture())
        WarbandSimulation.step(state, catalog, SimulationCommand.Dispatch("player"))
        val campaign = state.campaigns.single()
        WarbandSimulation.step(state, catalog, SimulationCommand.Materialize(campaign.id))
        val killed = campaign.members.first()
        val result = WarbandSimulation.step(
            state, catalog,
            SimulationCommand.CombatRound(campaign.id, CombatObservation(0.0, 20.0, 12.0, 0.0, 0.0, setOf(killed.id))),
        )
        assertTrue(result.events.any { it is SimulationEvent.MemberLost && it.memberId == killed.id })
        assertTrue(campaign.direction == JourneyDirection.RETURNING)

        val idleState = state().copy(reserveThreat = 20.0)
        WarbandSimulation.step(idleState, catalog, SimulationCommand.Dispatch("other"))
        val idle = idleState.campaigns.single()
        WarbandSimulation.step(idleState, catalog, SimulationCommand.Materialize(idle.id))
        val idleResult = WarbandSimulation.step(idleState, catalog, SimulationCommand.Advance(12_000L))
        assertTrue(idleResult.events.any { it is SimulationEvent.ReturnStarted && it.reason == "idle" })
    }
}
