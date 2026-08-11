package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.CampaignRouteStep
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState as LiveOfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PlannedCampaignMember
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.engine.CapabilityVector
import com.gerald.pillagercampaigns.engine.CampaignPhase
import com.gerald.pillagercampaigns.engine.CampaignState as CoreCampaignState
import com.gerald.pillagercampaigns.engine.ChunkPosition
import com.gerald.pillagercampaigns.engine.EngineCatalog
import com.gerald.pillagercampaigns.engine.EngineFrame
import com.gerald.pillagercampaigns.engine.EngineState
import com.gerald.pillagercampaigns.engine.MemberManifest
import com.gerald.pillagercampaigns.engine.OfficerState
import com.gerald.pillagercampaigns.engine.RecruitDefinition
import com.gerald.pillagercampaigns.engine.WarbandEngine
import com.gerald.pillagercampaigns.engine.WarbandRules
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PillagerEngineBridgeParityTest {
    @Test fun `Forge projection and direct engine produce identical abstract travel state`() {
        val factionId = UUID.randomUUID()
        val warbandId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val warband = PillagerWarband(
            warbandId, factionId, ResourceLocation.tryParse("minecraft:overworld")!!, 1, 0, 0,
            reserve = 0, capacity = 156, raidPool = 0.0, aggression = 6,
            defeated = false, warlordOfficerId = officerId, warlordEntityId = null,
            nextRaidTick = 0L, cooldownUntilTick = 0L, lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )
        val officer = PillagerOfficer(
            officerId, factionId, warbandId, "Parity", "the Captain",
            rank = OfficerRank.CAPTAIN, state = LiveOfficerState.DEPLOYED,
            preferenceGraph = mutableMapOf("damage" to 1.0),
        )
        val route = (1..12).mapTo(mutableListOf()) { CampaignRouteStep(it, 0) }
        val campaign = PillagerCampaign(
            campaignId, factionId, warbandId, officerId, playerId,
            ResourceLocation.tryParse("minecraft:overworld")!!, 0, 0, 12, 0, 10, 7L, 0,
            CampaignState.TRAVELING, null, null, 0L, mutableListOf(), committedThreat = 5.0,
            plannedMembers = mutableListOf(PlannedCampaignMember(
                ResourceLocation.tryParse("minecraft:pillager")!!, 5.0, manifestId = "engine:member:1",
            )),
            route = route,
        )
        val recruit = RecruitDefinition("minecraft:pillager", 5.0, CapabilityVector(damage = 1.0))

        val coreWarband = PillagerEngineBridge.coreWarband(warband)
        val coreOfficer = OfficerState(officerId.toString(), factionId.toString(), warbandId.toString(), mutableMapOf("damage" to 1.0))
        val coreCampaign = CoreCampaignState(
            campaignId.toString(), warbandId.toString(), officerId.toString(), playerId.toString(),
            ChunkPosition("minecraft:overworld", 0, 0), ChunkPosition("minecraft:overworld", 12, 0),
            mutableListOf(MemberManifest("engine:member:1", "minecraft:pillager", 5.0)),
            phase = CampaignPhase.OUTBOUND,
            route = (1..12).mapTo(mutableListOf()) { ChunkPosition("minecraft:overworld", it, 0) },
        )
        val direct = EngineState(
            tick = 0L, sequence = 7L,
            warbands = linkedMapOf(coreWarband.id to coreWarband),
            officers = linkedMapOf(coreOfficer.id to coreOfficer),
            campaigns = linkedMapOf(coreCampaign.id to coreCampaign),
        )
        val frame = EngineFrame(120L, advanceEconomy = false, allowAutomaticDispatch = false)
        WarbandEngine.transition(
            direct, frame,
            EngineCatalog("forge-live", listOf(recruit)),
            WarbandRules(),
        )

        PillagerEngineBridge.transitionCampaign(
            warband, officer, campaign, listOf(recruit), 120L, 120L, false,
            resources = emptyList(), coreRules = WarbandRules(),
        )

        assertEquals(coreCampaign.position.x, campaign.currentChunkX)
        assertEquals(coreCampaign.position.z, campaign.currentChunkZ)
        assertEquals(coreCampaign.routeIndex, campaign.routeIndex)
        assertEquals(coreCampaign.travelTickDebt, campaign.tickDebt.toLong())
        assertEquals(coreCampaign.supplySatisfaction, campaign.supplySatisfaction)
        assertEquals(coreWarband.raidPool, warband.raidPool)
        assertEquals(coreWarband.aggression, warband.aggression)
    }

    @Test fun `Forge projection and direct engine produce identical return reconciliation`() {
        val factionId = UUID.randomUUID()
        val warbandId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val dimension = ResourceLocation.tryParse("minecraft:overworld")!!
        val recruitId = ResourceLocation.tryParse("minecraft:pillager")!!
        val warband = PillagerWarband(
            warbandId, factionId, dimension, 1, 0, 0,
            reserve = 0, capacity = 156, raidPool = 3.0, aggression = 8,
            defeated = false, warlordOfficerId = officerId, warlordEntityId = null,
            nextRaidTick = 0L, cooldownUntilTick = 0L, lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )
        val officer = PillagerOfficer(
            officerId, factionId, warbandId, "Parity", "the Captain",
            rank = OfficerRank.CAPTAIN, state = LiveOfficerState.DEPLOYED,
            preferenceGraph = mutableMapOf("conservation" to 0.5),
        )
        val campaign = PillagerCampaign(
            campaignId, factionId, warbandId, officerId, playerId,
            dimension, 1, 0, 12, 0, 10, 17L, 0,
            CampaignState.RETURNING, null, null, 0L, mutableListOf(), committedThreat = 5.0,
            plannedMembers = mutableListOf(PlannedCampaignMember(
                recruitId, 5.0, cargo = mutableMapOf("minecraft:bread" to 2),
                manifestId = "engine:member:18", healthFraction = 0.8,
            )),
            returnReason = "repelled",
            returnAggressionDelta = 1,
        )
        val recruit = RecruitDefinition(recruitId.toString(), 5.0, CapabilityVector(damage = 1.0))
        val coreWarband = PillagerEngineBridge.coreWarband(warband)
        val coreOfficer = OfficerState(
            officerId.toString(), factionId.toString(), warbandId.toString(),
            mutableMapOf("conservation" to 0.5),
        )
        val coreCampaign = CoreCampaignState(
            campaignId.toString(), warbandId.toString(), officerId.toString(), playerId.toString(),
            ChunkPosition(dimension.toString(), 1, 0), ChunkPosition(dimension.toString(), 12, 0),
            mutableListOf(MemberManifest(
                "engine:member:18", recruitId.toString(), 5.0, 0.8,
                cargo = mutableMapOf("minecraft:bread" to 2),
            )),
            phase = CampaignPhase.RETURNING,
            returnReason = "repelled",
            returnAggressionDelta = 1,
        )
        val direct = EngineState(
            tick = 0L, sequence = 17L,
            warbands = linkedMapOf(coreWarband.id to coreWarband),
            officers = linkedMapOf(coreOfficer.id to coreOfficer),
            campaigns = linkedMapOf(coreCampaign.id to coreCampaign),
        )
        val catalog = EngineCatalog("forge-live", listOf(recruit))
        val rules = WarbandRules()
        WarbandEngine.transition(direct, EngineFrame(10_000L, advanceEconomy = false, allowAutomaticDispatch = false), catalog, rules)
        val projected = PillagerEngineBridge.transitionCampaign(
            warband, officer, campaign, listOf(recruit), 10_000L, 10_000L, false,
            sequence = 17L, resources = emptyList(), coreRules = rules,
        )

        assertEquals(coreCampaign.phase.name, campaign.state.name)
        assertEquals(coreWarband.raidPool, warband.raidPool)
        assertEquals(coreWarband.aggression, warband.aggression)
        assertEquals(coreWarband.stockpile, warband.stockpile)
        assertEquals(coreCampaign.members.single().healthFraction, campaign.plannedMembers.single().healthFraction)
        assertEquals(coreCampaign.members.single().cargo, campaign.plannedMembers.single().cargo)
        assertEquals(coreOfficer.availableAtTick, officer.injuryOrRecoveryUntilTick)
        assertEquals(direct.sequence, projected.result.state.sequence)
    }
}
