package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.*
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID

class PillagerCampaignRulesTest {
    @Test
    fun activeCampaignsExcludeDisbandedCampaigns() {
        val data = PillagerWorldData()
        val baseId = UUID.randomUUID()
        val factionId = UUID.randomUUID()
        data.campaigns[UUID.randomUUID()] = campaign(factionId, baseId, CampaignState.SCOUTING)
        data.campaigns[UUID.randomUUID()] = campaign(factionId, baseId, CampaignState.ENGAGING)
        data.campaigns[UUID.randomUUID()] = campaign(factionId, baseId, CampaignState.DISBANDED)
        data.campaigns[UUID.randomUUID()] = campaign(factionId, UUID.randomUUID(), CampaignState.SCOUTING)
        assertEquals(2, PillagerCampaignRules.activeCampaignsForBase(data, baseId))
    }

    @Test
    fun bestIntelBalancesConfidenceAndAgePenalty() {
        val base = base(UUID.randomUUID())
        val oldHigh = PlayerIntel(UUID.randomUUID(), "old", ChunkRef(0, 0), 0L, 20, null)
        val freshMedium = PlayerIntel(UUID.randomUUID(), "fresh", ChunkRef(1, 1), 48000L, 19, null)
        base.intel += oldHigh
        base.intel += freshMedium
        assertEquals(freshMedium, PillagerCampaignRules.bestIntel(base, 48000L))
    }

    @Test
    fun advanceTravelAccumulatesDebtUntilSpeedThreshold() {
        val campaign = campaign(UUID.randomUUID(), UUID.randomUUID(), CampaignState.SCOUTING).also {
            it.current = ChunkRef(0, 0)
            it.target = ChunkRef(3, 0)
            it.speedTicksPerChunk = 80
            it.tickDebt = 0
        }
        assertFalse(PillagerCampaignRules.advanceTravel(campaign, 40))
        assertEquals(ChunkRef(0, 0), campaign.current)
        assertEquals(40, campaign.tickDebt)
        assertTrue(PillagerCampaignRules.advanceTravel(campaign, 40))
        assertEquals(ChunkRef(1, 0), campaign.current)
        assertEquals(0, campaign.tickDebt)
    }

    @Test
    fun advanceTravelCanMoveMultipleChunksAndPreservesRemainderDebt() {
        val campaign = campaign(UUID.randomUUID(), UUID.randomUUID(), CampaignState.SCOUTING).also {
            it.current = ChunkRef(0, 0)
            it.target = ChunkRef(5, 0)
            it.speedTicksPerChunk = 80
            it.tickDebt = 10
        }
        assertTrue(PillagerCampaignRules.advanceTravel(campaign, 250))
        assertEquals(ChunkRef(3, 0), campaign.current)
        assertEquals(20, campaign.tickDebt)
    }

    @Test
    fun advanceTravelStopsAtTargetEvenWithLargeDebt() {
        val campaign = campaign(UUID.randomUUID(), UUID.randomUUID(), CampaignState.SCOUTING).also {
            it.current = ChunkRef(0, 0)
            it.target = ChunkRef(1, 0)
            it.speedTicksPerChunk = 80
        }
        assertTrue(PillagerCampaignRules.advanceTravel(campaign, 1000))
        assertEquals(ChunkRef(1, 0), campaign.current)
    }

    @Test
    fun campaignExpirationUsesStrictTtlGreaterThanThreshold() {
        val campaign = campaign(UUID.randomUUID(), UUID.randomUUID(), CampaignState.SCOUTING).also { it.createdTick = 100L }
        assertFalse(PillagerCampaignRules.isExpired(campaign, 100L + PillagerCampaignRules.CAMPAIGN_TTL_TICKS))
        assertTrue(PillagerCampaignRules.isExpired(campaign, 101L + PillagerCampaignRules.CAMPAIGN_TTL_TICKS))
    }

    private fun campaign(factionId: UUID, baseId: UUID, state: CampaignState) = PillagerCampaign(UUID.randomUUID(), factionId, baseId, null, state, ChunkRef(0, 0), ChunkRef(1, 1), 80, 0, 3, 0, 0L, 0L)

    private fun base(factionId: UUID) = PillagerBase(UUID.randomUUID(), factionId, null, BaseType.MAJOR, ResourceLocation("minecraft", "overworld"), null, BlockPos.ZERO, ChunkRef(0, 0), null, BaseState.ACTIVE, 10, 20, 30, 40, 50, 60, 70L)
}
