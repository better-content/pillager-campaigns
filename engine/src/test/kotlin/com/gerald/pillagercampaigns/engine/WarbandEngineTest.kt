package com.gerald.pillagercampaigns.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WarbandEngineTest {
    private val rules = WarbandRules()
    private val catalog = EngineCatalog(
        "test-v1",
        recruits = listOf(
            RecruitDefinition("quick", 5.0, CapabilityVector(1.0, 0.7, 1.5, 0.4, 0.2), supportedEquipmentActions = setOf("melee")),
            RecruitDefinition("bowed", 6.0, CapabilityVector(0.8, 0.8, 0.8, 1.8, 0.3), supportedEquipmentActions = setOf("ranged")),
        ),
        materials = listOf(MaterialDefinition("wood", 1, 0.0), MaterialDefinition("iron", 2, 20.0)),
        equipment = listOf(
            EquipmentDefinition("blade", listOf("head", "handle"), CapabilityVector(damage = 2.0), mapOf("wood" to 2.0), setOf("melee")),
            EquipmentDefinition("bow", listOf("limb", "grip"), CapabilityVector(range = 2.0), mapOf("wood" to 2.0), setOf("ranged")),
        ),
    )

    private fun state(reserve: Double = 18.0, pool: Double = 18.0): EngineState {
        val warband = WarbandState(
            "warband", "faction", ChunkPosition("overworld", 0, 0), 156.0, reserve, pool,
            preferences = linkedMapOf("durability" to 1.0, "damage" to 1.0, "mobility" to 0.8, "range" to 0.8, "control" to 0.6),
            materialLedger = linkedMapOf("wood" to 20.0),
        )
        return EngineState(
            factions = linkedMapOf("faction" to FactionState("faction", "Faction", 1)),
            warbands = linkedMapOf(warband.id to warband),
            officers = linkedMapOf("captain" to OfficerState("captain", "faction", warband.id)),
        )
    }

    @Test fun `time partition produces identical economy state`() {
        val one = state(reserve = 18.0, pool = 0.0)
        val many = state(reserve = 18.0, pool = 0.0)
        WarbandEngine.transition(one, EngineFrame(72_000L), catalog, rules)
        repeat(3_600) { WarbandEngine.transition(many, EngineFrame(20L), catalog, rules) }
        assertEquals(Json.encodeToString(one), Json.encodeToString(many))
    }

    @Test fun `dispatch is deterministic globally exclusive and equipment compatible`() {
        fun play(): EngineState {
            val state = state()
            WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Manufacture("warband", 4))), catalog, rules)
            WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
            WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
            return state
        }
        assertEquals(Json.encodeToString(play()), Json.encodeToString(play()))
        val campaign = play().campaigns.values.single()
        assertTrue(campaign.members.isNotEmpty())
        campaign.members.forEach { member ->
            val recruit = catalog.recruits.single { it.id == member.recruitId }
            member.equipment?.let { equipment -> assertTrue(equipment.supportedActions.any(recruit.supportedEquipmentActions::contains)) }
        }
    }

    @Test fun `automatic dispatch travels materializes fights and returns conservatively`() {
        val state = state(pool = 12.0)
        state.warbands.getValue("warband").aggression = 12
        val player = PlayerFact("player", ChunkPosition("overworld", 12, 0), setOf("warband"))
        val dispatched = WarbandEngine.transition(state, EngineFrame(0L, listOf(player)), catalog, rules)
        assertTrue(dispatched.events.any { it.type == "dispatched" })
        val campaign = state.campaigns.values.single()
        val committed = campaign.members.sumOf { it.threat }
        val afterDispatch = state.warbands.getValue("warband").raidPool
        val travel = WarbandEngine.transition(state, EngineFrame(6 * rules.travelTicksPerChunk, listOf(player)), catalog, rules)
        assertTrue(travel.effects.any { it.kind == EffectKind.MATERIALIZE })
        WarbandEngine.transition(state, EngineFrame(0L, listOf(player), materializations = listOf(MaterializationResult(campaign.id, true))), catalog, rules)
        assertEquals(CampaignPhase.ACTIVE, campaign.phase)
        val victim = campaign.members.first().id
        WarbandEngine.transition(
            state,
            EngineFrame(0L, listOf(player), combat = listOf(CombatObservation(campaign.id, 8.0, 1.0, 12.0, 0.8, 0.8, setOf(victim)))),
            catalog,
            rules,
        )
        assertTrue(campaign.members.none { it.id == victim })
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.BeginReturn(campaign.id, "idle", 1))), catalog, rules)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dematerialize(campaign.id))), catalog, rules)
        val returned = WarbandEngine.transition(state, EngineFrame(12 * rules.travelTicksPerChunk), catalog, rules)
        assertEquals(CampaignPhase.RESOLVED, campaign.phase)
        assertTrue(returned.events.any { it.type == "returned" })
        assertTrue(state.warbands.getValue("warband").raidPool <= afterDispatch + committed)
        assertEquals(13, state.warbands.getValue("warband").aggression)
    }

    @Test fun `idle active campaign requests snapshot return`() {
        val state = state(pool = 6.0)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
        val campaign = state.campaigns.values.single()
        campaign.phase = CampaignPhase.ACTIVE
        campaign.physical = true
        val result = WarbandEngine.transition(state, EngineFrame(rules.idleReturnTicks), catalog, rules)
        assertEquals(CampaignPhase.RETURNING, campaign.phase)
        assertTrue(result.effects.any { it.kind == EffectKind.CAPTURE_SNAPSHOTS })
        assertEquals("idle", campaign.returnReason)
    }

    @Test fun `combat updates warband captain and empirical threat`() {
        val state = state(pool = 6.0)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
        val campaign = state.campaigns.values.single().also { it.phase = CampaignPhase.ACTIVE; it.physical = true }
        val beforeWarband = state.warbands.getValue("warband").preferences.getValue("range")
        val result = WarbandEngine.transition(
            state,
            EngineFrame(0L, combat = listOf(CombatObservation(campaign.id, 2.0, 6.0, 14.0, 0.2, 0.2))), catalog, rules,
        )
        assertTrue(result.events.any { it.type == "combat_observed" })
        assertTrue(state.warbands.getValue("warband").preferences.getValue("range") > beforeWarband)
        assertTrue(state.officers.getValue("captain").preferences.getValue("range") > 0.0)
        assertTrue(state.warbands.getValue("warband").empiricalThreat.isNotEmpty())
    }

    @Test fun `material ledger is exact and invalid state fails closed`() {
        val state = state()
        val before = state.warbands.getValue("warband").materialLedger.getValue("wood")
        val result = WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Manufacture("warband", 1))), catalog, rules)
        assertTrue(result.events.any { it.type == "manufactured" })
        assertEquals(before - 2.0, state.warbands.getValue("warband").materialLedger.getValue("wood"))
        state.warbands.getValue("warband").reserveThreat = -1.0
        assertFailsWith<IllegalArgumentException> { WarbandEngine.validate(state, catalog, rules) }
    }

    @Test fun `catalog and frames fail closed`() {
        assertFailsWith<IllegalArgumentException> { WarbandEngine.transition(state(), EngineFrame(-1L), catalog, rules) }
        assertFailsWith<IllegalArgumentException> { WarbandEngine.transition(state(), EngineFrame(0L), catalog.copy(revision = ""), rules) }
        assertNotNull(WarbandEngine.chooseRecruit(state(), state().warbands.getValue("warband"), null, catalog, 6.0))
    }
}
