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

    @Test fun `public squad plan is the exact dispatch composition primitive`() {
        fun planned(): Pair<SquadPlan, EngineState> {
            val state = state()
            WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Manufacture("warband", 2))), catalog, rules)
            val warband = state.warbands.getValue("warband")
            val plan = WarbandEngine.planSquad(state, warband, state.officers.getValue("captain"), catalog, 12.0, rules)
            return plan to state
        }
        val (first, firstState) = planned()
        val (second, _) = planned()
        assertEquals(Json.encodeToString(first), Json.encodeToString(second))
        assertTrue(first.members.isNotEmpty())
        assertEquals(first.members.sumOf(MemberManifest::threat), first.committedThreat)
        assertEquals(2 - first.members.count { it.equipment != null }, firstState.warbands.getValue("warband").armory.size)
        assertEquals(2, first.members.map { it.recruitId }.distinct().size)
    }

    @Test fun `dispatch waits for formulaic readiness instead of minimum affordability`() {
        val state = state(reserve = 0.0, pool = 10.9)
        val player = PlayerFact("player", ChunkPosition("overworld", 12, 0), setOf("warband"))
        val waiting = WarbandEngine.transition(state, EngineFrame(0L, listOf(player)), catalog, rules)
        assertTrue(waiting.events.none { it.type == "dispatched" })
        state.warbands.getValue("warband").raidPool = 11.0
        val dispatched = WarbandEngine.transition(state, EngineFrame(0L, listOf(player)), catalog, rules)
        assertTrue(dispatched.events.any { it.type == "dispatched" })
        assertEquals(2, state.campaigns.values.single().members.map { it.recruitId }.distinct().size)
    }

    @Test fun `assignment and selected officer are engine owned`() {
        val state = state(pool = 20.0)
        state.officers["rival"] = OfficerState(
            "rival", "faction", "warband", lastTargetPlayerId = "far",
        )
        val near = PlayerFact("near", ChunkPosition("overworld", 4, 0), setOf("warband"))
        val far = PlayerFact("far", ChunkPosition("overworld", 8, 0), setOf("warband"))
        val assignment = WarbandEngine.chooseAssignment(state, state.warbands.getValue("warband"), listOf(near, far))
        assertEquals("rival", assignment?.officerId)
        assertEquals("far", assignment?.playerId)
        WarbandEngine.transition(state, EngineFrame(0L, listOf(near, far)), catalog, rules)
        assertEquals("rival", state.campaigns.values.single().officerId)
        assertEquals("far", state.campaigns.values.single().targetPlayerId)
        assertEquals("far", state.officers.getValue("rival").lastTargetPlayerId)
    }

    @Test fun `readiness accounts for a preferred expensive lead and distinct support`() {
        val state = state(reserve = 0.0, pool = 12.9)
        val warband = state.warbands.getValue("warband")
        warband.preferences["range"] = 8.0
        val officer = state.officers.getValue("captain")
        val expensiveCatalog = catalog.copy(recruits = catalog.recruits.map { recruit ->
            if (recruit.id == "bowed") recruit.copy(baseThreat = 8.0) else recruit
        })
        assertEquals(0.0, WarbandEngine.raidBudget(state, warband, officer, expensiveCatalog, rules))
        warband.raidPool = 13.0
        assertEquals(13.0, WarbandEngine.raidBudget(state, warband, officer, expensiveCatalog, rules))
    }

    @Test fun `automatic dispatch travels materializes fights and returns conservatively`() {
        val state = state(pool = 20.0)
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
        val state = state(pool = 12.0)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
        val campaign = state.campaigns.values.single()
        campaign.phase = CampaignPhase.ACTIVE
        campaign.physical = true
        val result = WarbandEngine.transition(state, EngineFrame(rules.idleReturnTicks), catalog, rules)
        assertEquals(CampaignPhase.RETURNING, campaign.phase)
        assertTrue(result.effects.any { it.kind == EffectKind.CAPTURE_SNAPSHOTS })
        assertEquals("idle", campaign.returnReason)
    }

    @Test fun `physical outcomes and captured snapshots reconcile through engine only`() {
        val state = state(pool = 12.0)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
        val campaign = state.campaigns.values.single().also { it.phase = CampaignPhase.ACTIVE; it.physical = true }
        val survivor = campaign.members.first()
        val outcome = WarbandEngine.transition(
            state,
            EngineFrame(0L, outcomes = listOf(CampaignOutcomeObservation(campaign.id, CampaignOutcomeKind.SURVIVING_DEFEAT, "repelled"))),
            catalog,
            rules,
        )
        assertTrue(outcome.effects.any { it.kind == EffectKind.CAPTURE_SNAPSHOTS })
        WarbandEngine.transition(
            state,
            EngineFrame(0L, snapshots = listOf(CampaignSnapshotResult(
                campaign.id,
                ChunkPosition("overworld", 2, 0),
                listOf(MemberSnapshot(survivor.id, .5, cargo = mapOf("ration" to 2))),
            ))),
            catalog,
            rules,
        )
        assertEquals(false, campaign.physical)
        assertEquals(.5, campaign.members.single().healthFraction)
        assertEquals(2, campaign.members.single().cargo["ration"])
        WarbandEngine.transition(state, EngineFrame(3L * rules.travelTicksPerChunk), catalog, rules)
        assertEquals(CampaignPhase.RESOLVED, campaign.phase)
        assertEquals(7, state.warbands.getValue("warband").aggression)
        assertEquals(1, state.officers.getValue("captain").defeats)
    }

    @Test fun `explicit outcome and combat facts in one frame count defeat once`() {
        val state = state(pool = 12.0)
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, rules)
        val campaign = state.campaigns.values.single().also { it.phase = CampaignPhase.ACTIVE; it.physical = true }
        val victim = campaign.members.first().id
        val result = WarbandEngine.transition(
            state,
            EngineFrame(
                0L,
                combat = listOf(CombatObservation(campaign.id, 1.0, 12.0, 10.0, 0.4, 0.3, setOf(victim))),
                outcomes = listOf(CampaignOutcomeObservation(campaign.id, CampaignOutcomeKind.SURVIVING_DEFEAT, "repelled")),
            ),
            catalog,
            rules,
        )
        assertEquals(1, state.officers.getValue("captain").defeats)
        assertEquals(CampaignPhase.RETURNING, campaign.phase)
        assertEquals("repelled", campaign.returnReason)
        assertTrue(campaign.members.none { it.id == victim })
        assertTrue(result.events.any { it.type == "combat_observed" })
        assertTrue(result.events.any { it.type == "campaign_outcome_observed" })
    }

    @Test fun `reconciliation honors configured aggression bounds`() {
        val boundedRules = rules.copy(minimumAggression = 20, maximumAggression = 30)
        val state = state(pool = 100.0)
        state.warbands.getValue("warband").aggression = 20
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.Dispatch("warband", "player"))), catalog, boundedRules)
        val campaign = state.campaigns.values.single().also {
            it.phase = CampaignPhase.RETURNING
            it.position = state.warbands.getValue("warband").rally
            it.returnAggressionDelta = -100
        }
        WarbandEngine.transition(state, EngineFrame(boundedRules.travelTicksPerChunk), catalog, boundedRules)
        assertEquals(CampaignPhase.RESOLVED, campaign.phase)
        assertEquals(20, state.warbands.getValue("warband").aggression)
    }

    @Test fun `resolved campaign history does not duplicate returned equipment ownership`() {
        val state = state()
        val equipment = EquipmentManifest("equipment", "blade", emptyList(), emptyMap(), CapabilityVector(damage = 2.0))
        state.warbands.getValue("warband").armory += equipment
        state.campaigns["history"] = CampaignState(
            "history", "warband", "captain", "player",
            ChunkPosition("overworld", 0, 0), ChunkPosition("overworld", 0, 0),
            mutableListOf(MemberManifest("historical-member", "quick", 5.0, equipment = equipment)),
            phase = CampaignPhase.RESOLVED,
        )
        WarbandEngine.validate(state, catalog, rules)
    }

    @Test fun `combat updates warband captain and empirical threat`() {
        val state = state(pool = 12.0)
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

    @Test fun `extraction values marginal progress toward missing armament materials`() {
        val state = state()
        val warband = state.warbands.getValue("warband")
        warband.materialLedger.clear()
        warband.environment = EnvironmentTraits(mineralPotential = 0.5, exoticPotential = 0.0)
        val logisticalCatalog = catalog.copy(
            materials = listOf(MaterialDefinition("irrelevant", 1, 0.0), MaterialDefinition("needed", 1, 0.0)),
            equipment = listOf(EquipmentDefinition("shield", listOf("core"), CapabilityVector(durability = 2.0), mapOf("needed" to 1.0), setOf("defense"))),
        )
        assertEquals("needed", WarbandEngine.chooseMaterial(state, warband, logisticalCatalog, rules)?.id)
    }

    @Test fun `environment changes formulaic part material preference without material archetypes`() {
        val state = state()
        val warband = state.warbands.getValue("warband")
        warband.preferences.clear()
        val durable = MaterialDefinition("durable", 1, 0.0, CapabilityVector(durability = 4.0, mobility = 2.0))
        val ranged = MaterialDefinition("ranged", 1, 0.0, CapabilityVector(range = 4.0, control = 1.0))
        val materials = listOf(durable, ranged)
        val available = mapOf("durable" to 10.0, "ranged" to 10.0)
        warband.environment = EnvironmentTraits(habitability = 0.0, biomass = 1.0, travelFriction = 1.0)
        assertEquals("durable", WarbandEngine.choosePartMaterial(state, warband, materials, materials.mapTo(linkedSetOf()) { it.id }, available, 1.0, 0, rules)?.id)
        warband.environment = EnvironmentTraits(habitability = 1.0, biomass = 0.0, travelFriction = 0.0)
        warband.aggression = rules.maximumAggression
        assertEquals("ranged", WarbandEngine.choosePartMaterial(state, warband, materials, materials.mapTo(linkedSetOf()) { it.id }, available, 1.0, 0, rules)?.id)
    }

    @Test fun `catalog and frames fail closed`() {
        assertFailsWith<IllegalArgumentException> { WarbandEngine.transition(state(), EngineFrame(-1L), catalog, rules) }
        assertFailsWith<IllegalArgumentException> { WarbandEngine.transition(state(), EngineFrame(0L), catalog.copy(revision = ""), rules) }
        assertFailsWith<IllegalArgumentException> {
            WarbandEngine.transition(state(), EngineFrame(0L), catalog.copy(resources = listOf(ResourceDefinition("bad", ResourceVector(), environmentalAvailability = -1.0))), rules)
        }
        assertNotNull(WarbandEngine.chooseRecruit(state(), state().warbands.getValue("warband"), null, catalog, 6.0))
    }

    @Test fun `selection history changes formulaic recruit choice without fixed rosters`() {
        val state = state()
        val warband = state.warbands.getValue("warband")
        val equalCatalog = catalog.copy(recruits = listOf(
            RecruitDefinition("first", 5.0, CapabilityVector(damage = 1.0)),
            RecruitDefinition("second", 5.0, CapabilityVector(damage = 1.0)),
        ))
        warband.selectionMemory.recruits["first"] = 100.0
        warband.selectionMemory.recruits["second"] = 0.0
        assertEquals("second", WarbandEngine.chooseRecruit(state, warband, null, equalCatalog, 5.0, rules = rules)?.id)
    }

    @Test fun `equipment assignment maximizes member utility instead of armory order`() {
        val state = state()
        val warband = state.warbands.getValue("warband")
        warband.preferences["damage"] = 4.0
        warband.armory += EquipmentManifest("weak", "blade", emptyList(), emptyMap(), CapabilityVector(damage = 0.1), setOf("melee"))
        warband.armory += EquipmentManifest("strong", "blade", emptyList(), emptyMap(), CapabilityVector(damage = 3.0), setOf("melee"))
        val meleeOnly = catalog.copy(recruits = listOf(catalog.recruits.first()))
        val plan = WarbandEngine.planSquad(state, warband, null, meleeOnly, 5.0, rules)
        assertEquals("strong", plan.members.single().equipment?.id)
        assertEquals(listOf("weak"), warband.armory.map { it.id })
    }

    @Test fun `armament demand rises continuously with aggression power and hostile terrain`() {
        val low = state(reserve = 2.0, pool = 6.0).warbands.getValue("warband").also {
            it.aggression = rules.minimumAggression
            it.environment = EnvironmentTraits(habitability = 1.0, biomass = 1.0, mineralPotential = 0.0, exoticPotential = 0.0, travelFriction = 0.0)
        }
        val high = low.copy(
            reserveThreat = 70.0, raidPool = 50.0, aggression = rules.maximumAggression,
            environment = EnvironmentTraits(habitability = 0.0, biomass = 0.0, mineralPotential = 1.0, exoticPotential = 1.0, travelFriction = 1.0),
        )
        val lowPreference = rules.armamentPreferences(low, null)
        val highPreference = rules.armamentPreferences(high, null)
        assertTrue(rules.armamentCoverageTarget(high) > rules.armamentCoverageTarget(low))
        assertTrue(rules.desiredArmoryItems(high, catalog.recruits) > rules.desiredArmoryItems(low, catalog.recruits))
        assertTrue(highPreference.damage > lowPreference.damage)
        assertTrue(highPreference.durability > lowPreference.durability)
        assertTrue(highPreference.mobility > lowPreference.mobility)
        assertTrue(highPreference.range > lowPreference.range)
    }

    @Test fun `defensive and utility equipment remain functional without hard coded recruit kits`() {
        val state = state()
        val warband = state.warbands.getValue("warband")
        warband.preferences["durability"] = 8.0
        warband.armory += EquipmentManifest("shield", "shield", emptyList(), emptyMap(), CapabilityVector(durability = 3.0), setOf("defense"))
        warband.armory += EquipmentManifest("pick", "pick", emptyList(), emptyMap(), CapabilityVector(mobility = 1.0), setOf("utility"))
        val plan = WarbandEngine.planSquad(state, warband, null, catalog.copy(recruits = listOf(catalog.recruits.last())), 12.0, rules)
        assertEquals(2, plan.members.size)
        assertEquals(setOf("shield", "pick"), plan.members.mapNotNull { it.equipment?.id }.toSet())
    }

    @Test fun `dispatch provisions exact items and returning refunds only unconsumed cargo`() {
        val resources = listOf(
            ResourceDefinition("ration", ResourceVector(sustenance = 1.0)),
            ResourceDefinition("arrow", ResourceVector(munitions = 1.0)),
            ResourceDefinition("oil", ResourceVector(maintenance = 1.0)),
        )
        val suppliedCatalog = catalog.copy(resources = resources)
        val state = state(pool = 20.0)
        val warband = state.warbands.getValue("warband")
        resources.forEach { warband.stockpile[it.itemId] = 50 }
        val player = PlayerFact("player", ChunkPosition("overworld", 12, 0), setOf("warband"))
        WarbandEngine.transition(state, EngineFrame(0L, listOf(player)), suppliedCatalog, rules)
        val campaign = state.campaigns.values.single()
        resources.forEach { resource ->
            assertEquals(50, warband.stockpile.getOrDefault(resource.itemId, 0) + campaign.members.sumOf { it.cargo.getOrDefault(resource.itemId, 0) })
        }
        val afterProvision = resources.associate { resource -> resource.itemId to campaign.members.sumOf { it.cargo.getOrDefault(resource.itemId, 0) } }
        val outbound = WarbandEngine.transition(state, EngineFrame(rules.travelTicksPerChunk, listOf(player)), suppliedCatalog, rules)
        assertTrue(resources.any { resource -> campaign.members.sumOf { it.cargo.getOrDefault(resource.itemId, 0) } < afterProvision.getValue(resource.itemId) })
        WarbandEngine.transition(state, EngineFrame(0L, commands = listOf(EngineCommand.BeginReturn(campaign.id, "test"))), suppliedCatalog, rules)
        val returning = WarbandEngine.transition(state, EngineFrame(2L * rules.travelTicksPerChunk), suppliedCatalog, rules)
        assertEquals(CampaignPhase.RESOLVED, campaign.phase)
        val consumed = (outbound.events + returning.events).filter { it.type == "resource_consumed" }
            .map { it.detail.split("=") }.groupingBy { it[0] }.fold(0) { total, value -> total + value[1].toInt() }
        assertTrue((outbound.events + returning.events).none { it.type == "resource_acquired" })
        resources.forEach { resource ->
            assertEquals(50 - consumed.getOrDefault(resource.itemId, 0), warband.stockpile.getOrDefault(resource.itemId, 0))
        }
    }

    @Test fun `severe sustained deficit causes attrition and preserves exact recoverable assets`() {
        val state = state(reserve = 0.0, pool = 0.0)
        val equipment = EquipmentManifest("tool", "blade", listOf("iron"), mapOf("iron" to 1.0), CapabilityVector(damage = 1.0))
        val member = MemberManifest("member", "quick", 5.0, healthFraction = 0.2, equipment = equipment)
        val campaign = CampaignState(
            "campaign", "warband", "captain", "player", ChunkPosition("overworld", 0, 0),
            ChunkPosition("overworld", 20, 0), mutableListOf(member), route = mutableListOf(ChunkPosition("overworld", 1, 0)),
        )
        state.campaigns[campaign.id] = campaign
        val harshRules = rules.copy(travelTicksPerChunk = 1L, deficitGraceChunks = 0.0, attritionPerDeficitChunk = 1.0)
        val resourceCatalog = catalog.copy(resources = listOf(ResourceDefinition("ration", ResourceVector(sustenance = 1.0))))
        val result = WarbandEngine.transition(state, EngineFrame(1L), resourceCatalog, harshRules)
        assertTrue(result.events.any { it.type == "member_lost_to_attrition" })
        assertTrue(result.events.any { it.type == "campaign_lost_to_attrition" })
        val cache = campaign.lostCaches.single()
        assertEquals(ChunkPosition("overworld", 1, 0), cache.position)
        assertEquals(listOf("tool"), cache.equipment.map { it.id })
        assertEquals(CampaignPhase.RESOLVED, campaign.phase)
    }

    @Test fun `invalid logistics manifests fail closed`() {
        val state = state()
        state.warbands.getValue("warband").stockpile["ration"] = -1
        assertFailsWith<IllegalArgumentException> { WarbandEngine.validate(state, catalog, rules) }
        state.warbands.getValue("warband").stockpile.clear()
        state.campaigns["bad"] = CampaignState(
            "bad", "warband", "captain", "player", ChunkPosition("overworld", 0, 0), ChunkPosition("overworld", 1, 0),
            mutableListOf(MemberManifest("bad-member", "quick", 5.0, equipment = EquipmentManifest("bad-tool", "blade", emptyList(), emptyMap(), CapabilityVector(), durabilityFraction = 1.1))),
        )
        assertFailsWith<IllegalArgumentException> { WarbandEngine.validate(state, catalog, rules) }
    }
}
