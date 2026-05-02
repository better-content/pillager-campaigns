package com.gerald.pillagercampaigns.data

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

class PillagerWorldDataRepairTest {
    @Test
    fun `load repairs dangling officer and campaign references`() {
        val factionId = UUID.randomUUID()
        val goodBaseId = UUID.randomUUID()
        val badBaseId = UUID.randomUUID()
        val goodOfficerId = UUID.randomUUID()
        val badOfficerId = UUID.randomUUID()

        val faction = PillagerFaction(
            id = factionId,
            name = "Test Faction",
            bannerSeed = 4,
            bossOfficerId = null,
        )

        val base = PillagerBase(
            id = goodBaseId,
            factionId = factionId,
            dimension = ResourceLocation("minecraft", "overworld"),
            bannerSeed = 2,
            difficulty = 1,
            defeated = false,
            chunkX = 0,
            chunkZ = 0,
            center = BlockPos(0, 80, 0),
            lastSeenTick = 0L,
        )

        val goodOfficer = PillagerOfficer(
            id = goodOfficerId,
            factionId = factionId,
            homeBaseId = goodBaseId,
            name = "Good",
            title = "Captain",
            rank = OfficerRank.CAPTAIN,
            officerClass = OfficerClass.PILLAGER,
            state = OfficerState.AVAILABLE,
            preferenceGraph = mutableMapOf(),
        )

        val badOfficer = PillagerOfficer(
            id = badOfficerId,
            factionId = factionId,
            homeBaseId = badBaseId,
            name = "Bad",
            title = "Dangling",
            rank = OfficerRank.CAPTAIN,
            officerClass = OfficerClass.PILLAGER,
            state = OfficerState.AVAILABLE,
            preferenceGraph = mutableMapOf(),
        )

        val goodCampaign = PillagerCampaign(
            id = UUID.randomUUID(),
            factionId = factionId,
            originBaseId = goodBaseId,
            officerId = goodOfficerId,
            targetPlayerId = UUID.randomUUID(),
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 0,
            loadoutSeed = 1L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        val badCampaign = PillagerCampaign(
            id = UUID.randomUUID(),
            factionId = factionId,
            originBaseId = goodBaseId,
            officerId = badOfficerId,
            targetPlayerId = UUID.randomUUID(),
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 0,
            loadoutSeed = 2L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        val raw = PillagerWorldData().apply {
            factions[faction.id] = faction
            bases[base.id] = base
            officers[goodOfficer.id] = goodOfficer
            officers[badOfficer.id] = badOfficer
            campaigns[goodCampaign.id] = goodCampaign
            campaigns[badCampaign.id] = badCampaign
        }.save(net.minecraft.nbt.CompoundTag())

        val repaired = PillagerWorldData.load(raw)

        assertEquals(1, repaired.officers.size)
        assertEquals(true, repaired.officers.containsKey(goodOfficerId))
        assertEquals(1, repaired.campaigns.size)
        assertEquals(true, repaired.campaigns.containsKey(goodCampaign.id))
    }
}
