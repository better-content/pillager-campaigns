package com.bettercontent.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals

class PillagerWarbandDiscoveryServiceTest {
    @Test
    fun `effective discovery radius tracks furthest active range`() {
        assertEquals(1000, PillagerWarbandDiscoveryService.effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks = 64, maxCampaignDistanceChunks = 1000))
        assertEquals(256, PillagerWarbandDiscoveryService.effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks = 256, maxCampaignDistanceChunks = 64))
        assertEquals(1, PillagerWarbandDiscoveryService.effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks = 0, maxCampaignDistanceChunks = 0))
    }
}
