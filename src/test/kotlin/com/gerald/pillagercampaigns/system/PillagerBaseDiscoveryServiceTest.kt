package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals

class PillagerBaseDiscoveryServiceTest {
    @Test
    fun `effective discovery radius never undershoots campaign distance`() {
        assertEquals(1000, PillagerBaseDiscoveryService.effectiveDiscoveryRadius(baseDiscoveryRadiusChunks = 64, maxCampaignDistanceChunks = 1000))
        assertEquals(256, PillagerBaseDiscoveryService.effectiveDiscoveryRadius(baseDiscoveryRadiusChunks = 256, maxCampaignDistanceChunks = 64))
        assertEquals(1, PillagerBaseDiscoveryService.effectiveDiscoveryRadius(baseDiscoveryRadiusChunks = 0, maxCampaignDistanceChunks = 0))
    }
}

