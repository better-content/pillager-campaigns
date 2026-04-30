package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.CampaignState
import com.gerald.pillagerpressure.data.PillagerBase
import com.gerald.pillagerpressure.data.PillagerCampaign
import com.gerald.pillagerpressure.data.PillagerWorldData
import com.gerald.pillagerpressure.data.PlayerIntel
import java.util.UUID

object PillagerCampaignRules {
    const val CAMPAIGN_TTL_TICKS: Long = 240000L

    fun activeCampaignsForBase(data: PillagerWorldData, baseId: UUID): Int =
        data.campaigns.values.count { it.originBaseId == baseId && it.state != CampaignState.DISBANDED }

    fun bestIntel(base: PillagerBase, now: Long): PlayerIntel? =
        base.intel.maxByOrNull { it.confidence - ((now - it.lastSeenTick) / 24000L).toInt() }

    fun advanceTravel(campaign: PillagerCampaign, tickInterval: Int): Boolean {
        val start = campaign.current
        campaign.tickDebt += tickInterval.coerceAtLeast(0)
        while (campaign.tickDebt >= campaign.speedTicksPerChunk && campaign.current != campaign.target) {
            campaign.current = campaign.current.stepToward(campaign.target)
            campaign.tickDebt -= campaign.speedTicksPerChunk
        }
        return campaign.current != start
    }

    fun isExpired(campaign: PillagerCampaign, now: Long): Boolean = now - campaign.createdTick > CAMPAIGN_TTL_TICKS
}
