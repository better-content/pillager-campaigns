package com.gerald.pillagerpressure.scenario

import com.gerald.pillagerpressure.data.BaseState
import com.gerald.pillagerpressure.data.BaseType
import com.gerald.pillagerpressure.data.CampaignState
import com.gerald.pillagerpressure.data.ChunkRef
import com.gerald.pillagerpressure.data.FactionWarMemory
import com.gerald.pillagerpressure.data.OfficerAffix
import com.gerald.pillagerpressure.data.OfficerDoctrine
import com.gerald.pillagerpressure.data.OfficerGeneProfile
import com.gerald.pillagerpressure.data.OfficerLineage
import com.gerald.pillagerpressure.data.OfficerOutcome
import com.gerald.pillagerpressure.data.OfficerRank
import com.gerald.pillagerpressure.data.OfficerRole
import com.gerald.pillagerpressure.data.OfficerState
import com.gerald.pillagerpressure.data.PillagerBase
import com.gerald.pillagerpressure.data.PillagerCampaign
import com.gerald.pillagerpressure.data.PillagerFaction
import com.gerald.pillagerpressure.data.PillagerOfficer
import com.gerald.pillagerpressure.data.PillagerWorldData
import com.gerald.pillagerpressure.system.OfficerAffixRules
import com.gerald.pillagerpressure.system.OfficerDoctrineRules
import com.gerald.pillagerpressure.system.OfficerEngineeringRules
import com.gerald.pillagerpressure.system.OfficerGeneRules
import com.gerald.pillagerpressure.system.OfficerLoadoutRules
import com.gerald.pillagerpressure.system.OfficerOutcomeRules
import com.gerald.pillagerpressure.system.PillagerBaseService
import com.gerald.pillagerpressure.system.PillagerCampaignMaterializationRules
import com.gerald.pillagerpressure.system.PillagerCampaignRules
import com.gerald.pillagerpressure.system.PillagerObjectiveRules
import com.gerald.pillagerpressure.system.PillagerSpawnPlacementRules
import com.gerald.pillagerpressure.system.SquadCompositionPressure
import com.gerald.pillagerpressure.system.SquadCompositionRules
import com.gerald.pillagerpressure.util.OfficerOrdersRules
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PillagerPressureScenarioTest {
    @Test
    fun scoutCampaignInterceptionSpawnsAsRouteContactNotInstantPlayerTarget() {
        val campaign = campaign(CampaignState.APPROACHING_INTEL).also {
            it.current = ChunkRef(2, 0)
            it.target = ChunkRef(6, 0)
        }

        val enRoutePlan = PillagerCampaignMaterializationRules.planFor(campaign)
        val enRouteObjective = PillagerObjectiveRules.objectiveFor(campaign, target = null, fallback = BlockPos(32, 70, 0))

        assertFalse(enRoutePlan.targetPlayerImmediately)
        assertEquals(CampaignState.APPROACHING_INTEL, enRoutePlan.nextState)
        assertEquals("hunt_intel", enRouteObjective.kind)
        assertEquals(campaign.target.centerBlock(70), enRouteObjective.pos)

        campaign.current = campaign.target
        val searchPlan = PillagerCampaignMaterializationRules.planFor(campaign)

        assertFalse(searchPlan.targetPlayerImmediately)
        assertEquals(CampaignState.SEARCHING, searchPlan.nextState)
    }

    @Test
    fun activeEngagementAtDestinationIsTheOnlyScenarioThatTargetsImmediately() {
        val campaign = campaign(CampaignState.ENGAGING).also {
            it.current = ChunkRef(8, -2)
            it.target = ChunkRef(8, -2)
        }

        val plan = PillagerCampaignMaterializationRules.planFor(campaign)

        assertTrue(plan.targetPlayerImmediately)
        assertEquals(CampaignState.ENGAGING, plan.nextState)
    }

    @Test
    fun scoutEscapeOutcomeFeedsGeneticPreferenceForFastRangedSurvivors() {
        val memory = FactionWarMemory()
        val scout = officer(
            rank = OfficerRank.SCOUT,
            role = OfficerRole.SCOUTMASTER,
            genes = OfficerGeneProfile.neutral(20).copy(range = 75, speed = 90, survival = 85),
            victories = 1,
            escapedEncounters = 2,
        )

        val outcomes = OfficerOutcomeRules.outcomesFor(scout)
        OfficerGeneRules.recordOutcome(memory, scout.genes, outcomes)
        val replacement = OfficerGeneRules.rollReplacement(memory, scout, OfficerGeneProfile.neutral(20).copy(range = 70, speed = 80, survival = 75), seed = 9157L)
        val doctrine = OfficerDoctrineRules.doctrineFor(replacement)
        val affixes = OfficerAffixRules.affixesFor(replacement, OfficerRank.CAPTAIN, outcomes)

        assertTrue(OfficerOutcome.SCOUT_ESCAPED in outcomes)
        assertTrue(memory.successfulGenes.speed > memory.failedGenes.speed)
        assertTrue(replacement.speed >= replacement.melee)
        assertTrue(replacement.range >= 35)
        assertTrue(doctrine == OfficerDoctrine.HUNTER || doctrine == OfficerDoctrine.STALKER || doctrine == OfficerDoctrine.SURVIVOR)
        assertTrue(OfficerAffix.SWIFT in affixes || OfficerAffix.LONGSHOT in affixes)
    }

    @Test
    fun officerWhoKillsPlayerBecomesGraveMarkedAndReadableWithoutBecomingSoloBoss() {
        val killer = officer(
            rank = OfficerRank.LIEUTENANT,
            doctrine = OfficerDoctrine.BREAKER,
            genes = OfficerGeneProfile.neutral(20).copy(melee = 90, armor = 80, siege = 70),
            victories = 2,
            killedPlayers = 1,
        )
        val outcomes = OfficerOutcomeRules.outcomesFor(killer)
        killer.affixes += OfficerAffixRules.affixesFor(killer.genes, killer.rank, outcomes)

        val loadout = OfficerLoadoutRules.forOfficer(killer)
        val squad = SquadCompositionRules.plan(killer.doctrine, killer.rank, OfficerEngineeringRules.talentFor(killer), SquadCompositionPressure.fromGenes(killer.genes))

        assertTrue(OfficerOutcome.PLAYER_KILL in outcomes)
        assertTrue(OfficerAffix.GRAVE_MARKED in killer.affixes)
        assertEquals("minecraft:iron_axe", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertTrue(squad.manifest.values.sum() >= 6)
        assertTrue((squad.manifest[SquadCompositionRules.VINDICATOR] ?: 0) > 0)
    }

    @Test
    fun siegeOfficerScenarioCombinesEngineeringSquadCompositionOrdersAndFarEdgeSpawn() {
        val faction = faction()
        val base = base(faction.id)
        val siege = officer(
            factionId = faction.id,
            baseId = base.id,
            rank = OfficerRank.WARLORD,
            role = OfficerRole.SIEGE_ENGINEER,
            doctrine = OfficerDoctrine.SIEGE_CAPTAIN,
            genes = OfficerGeneProfile.neutral(20).copy(siege = 95, survival = 70, armor = 65),
        )
        val campaign = campaign(CampaignState.APPROACHING_INTEL, faction.id, base.id, siege.id).also {
            it.current = ChunkRef(0, 0)
            it.target = ChunkRef(12, 0)
        }

        val talent = OfficerEngineeringRules.talentFor(siege)
        val squad = SquadCompositionRules.plan(siege.doctrine, siege.rank, talent, SquadCompositionPressure.fromGenes(siege.genes))
        val orders = OfficerOrdersRules.generate(faction, base, siege, campaign)
        val spawn = PillagerSpawnPlacementRules.chooseFarthest(
            center = BlockPos.ZERO,
            minRadius = 32,
            maxRadius = 96,
            isLoaded = { it.x >= 0 },
            isValid = { it.z == 0 },
        )

        assertEquals("FIELD_ENGINEER", talent.name)
        assertTrue((squad.manifest[SquadCompositionRules.ENGINEER] ?: 0) >= 3)
        assertTrue(orders.loreLines.any { it.contains("Engineering: field engineer") })
        assertTrue(orders.loreLines.any { it.contains("Squad:") })
        assertEquals(BlockPos(96, 0, 0), spawn)
    }

    @Test
    fun deadOfficerReplacementKeepsBaseFactionRankAndLineageSignal() {
        val data = PillagerWorldData()
        val faction = faction()
        val base = base(faction.id)
        val dead = officer(faction.id, base.id, rank = OfficerRank.WARLORD, victories = 4, killedPlayers = 1).also {
            it.state = OfficerState.DEAD
        }
        data.factions[faction.id] = faction
        data.bases[base.id] = base
        data.officers[dead.id] = dead

        val replacement = PillagerBaseService.officerForBase(data, base)

        assertNotEquals(dead.id, replacement.id)
        assertEquals(faction.id, replacement.factionId)
        assertEquals(base.id, replacement.homeBaseId)
        assertEquals(OfficerRank.WARLORD, replacement.rank)
        assertEquals(dead.id, replacement.lineage.predecessorOfficerId)
        assertTrue(replacement.lineage.causeOfSuccession.contains(dead.name))
    }

    @Test
    fun officerOrdersPreserveTheScenarioTenLineEnvelopeBeforeRuntimeItemRendering() {
        val faction = faction()
        val base = base(faction.id)
        val officer = officer(faction.id, base.id, killedPlayers = 1, escapedEncounters = 1).also {
            it.lineage = OfficerLineage(UUID.randomUUID(), it.rank, faction.patternSeed, "took up a fallen banner")
        }
        val campaign = campaign(CampaignState.ENGAGING, faction.id, base.id, officer.id)
        val orders = OfficerOrdersRules.generate(faction, base, officer, campaign)

        assertEquals(OfficerOrdersRules.MAX_LORE_LINES, orders.loreLines.size)
        assertTrue(orders.loreLines.any { it.startsWith("Officer:") })
        assertTrue(orders.loreLines.any { it.startsWith("Campaign:") })
    }

    @Test
    fun campaignTravelExpirationAndResourceEconomyStayBoundedInLongRunningScenario() {
        val data = PillagerWorldData()
        val faction = faction()
        val base = base(faction.id).also {
            it.manpower = 79
            it.supplies = 159
            it.morale = 99
        }
        val campaign = campaign(CampaignState.SCOUTING, faction.id, base.id).also {
            it.current = ChunkRef(0, 0)
            it.target = ChunkRef(5, 5)
            it.speedTicksPerChunk = 40
            it.createdTick = 100L
        }
        data.factions[faction.id] = faction
        data.bases[base.id] = base
        data.campaigns[campaign.id] = campaign

        repeat(10) { PillagerCampaignRules.advanceTravel(campaign, 40) }
        PillagerBaseService.tickEconomy(data)

        assertEquals(campaign.target, campaign.current)
        assertEquals(80, base.manpower)
        assertEquals(160, base.supplies)
        assertEquals(100, base.morale)
        assertFalse(PillagerCampaignRules.isExpired(campaign, 100L + PillagerCampaignRules.CAMPAIGN_TTL_TICKS))
        assertTrue(PillagerCampaignRules.isExpired(campaign, 101L + PillagerCampaignRules.CAMPAIGN_TTL_TICKS))
    }

    private fun faction() = PillagerFaction(UUID.randomUUID(), "Blackroot Standard", "black", "red", 12, 3, 2)

    private fun base(factionId: UUID) = PillagerBase(
        id = UUID.randomUUID(),
        factionId = factionId,
        parentBaseId = null,
        type = BaseType.MAJOR,
        dimension = ResourceLocation("minecraft", "overworld"),
        structureId = ResourceLocation("minecraft", "pillager_outpost"),
        center = BlockPos(120, 80, -64),
        chunk = ChunkRef(0, 0),
        bounds = null,
        state = BaseState.ACTIVE,
        manpower = 40,
        supplies = 60,
        morale = 90,
        aggression = 20,
        loyalty = 80,
        influence = 30,
        lastValidatedTick = 0L,
    )

    private fun officer(
        factionId: UUID = UUID.randomUUID(),
        baseId: UUID = UUID.randomUUID(),
        rank: OfficerRank = OfficerRank.CAPTAIN,
        role: OfficerRole = OfficerRole.HUNTER,
        doctrine: OfficerDoctrine = OfficerDoctrine.HUNTER,
        genes: OfficerGeneProfile = OfficerGeneProfile.neutral(35).copy(range = 75, speed = 65),
        victories: Int = 0,
        defeats: Int = 0,
        killedPlayers: Int = 0,
        escapedEncounters: Int = 0,
    ) = PillagerOfficer(
        id = UUID.randomUUID(),
        name = "Krag",
        title = "the Scenario",
        factionId = factionId,
        homeBaseId = baseId,
        rank = rank,
        role = role,
        state = OfficerState.ACTIVE,
        victories = victories,
        defeats = defeats,
        killedPlayers = killedPlayers,
        escapedEncounters = escapedEncounters,
        genes = genes,
        doctrine = doctrine,
        affixes = mutableSetOf(),
    )

    private fun campaign(
        state: CampaignState,
        factionId: UUID = UUID.randomUUID(),
        baseId: UUID = UUID.randomUUID(),
        officerId: UUID? = null,
    ) = PillagerCampaign(
        id = UUID.randomUUID(),
        factionId = factionId,
        originBaseId = baseId,
        officerId = officerId,
        state = state,
        current = ChunkRef(0, 0),
        target = ChunkRef(3, 5),
        speedTicksPerChunk = 40,
        tickDebt = 0,
        pillagers = 6,
        specials = 1,
        createdTick = 0L,
        lastMaterializedTick = 0L,
    )
}
