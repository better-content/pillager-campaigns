package com.gerald.pillagercampaigns.system

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PillagerBaseDiscoveryServiceTest {
    @Test
    fun `effective discovery radius prefers campaign distance when larger`() {
        val radius = PillagerBaseDiscoveryService.effectiveDiscoveryRadius(
            baseDiscoveryRadiusChunks = 48,
            maxCampaignDistanceChunks = 100,
        )

        assertEquals(100, radius)
    }

    @Test
    fun `effective discovery radius keeps base radius when larger`() {
        val radius = PillagerBaseDiscoveryService.effectiveDiscoveryRadius(
            baseDiscoveryRadiusChunks = 100,
            maxCampaignDistanceChunks = 64,
        )

        assertEquals(100, radius)
    }

    @Test
    fun `build probe points are deterministic`() {
        val origin = BlockPos(128, 64, 256)
        val first = PillagerBaseDiscoveryService.buildProbePoints(origin, 64, 6)
        val second = PillagerBaseDiscoveryService.buildProbePoints(origin, 64, 6)

        assertEquals(first, second)
        assertEquals(6, first.size)
    }

    @Test
    fun `build probe points includes origin and stays chunk aligned`() {
        val origin = BlockPos(10, 80, 10)
        val probes = PillagerBaseDiscoveryService.buildProbePoints(origin, 24, 8)

        assertFalse(probes.isEmpty())
        assertEquals(origin, probes.first())
        probes.forEach { probe ->
            assertEquals(origin.y, probe.y)
            val deltaX = probe.x - origin.x
            val deltaZ = probe.z - origin.z
            assertEquals(0, deltaX % 16)
            assertEquals(0, deltaZ % 16)
        }
    }

    @Test
    fun `farthest point ordering keeps candidates unique and bounded`() {
        val points = listOf(
            0 to 0,
            4 to 4,
            -4 to 4,
            4 to -4,
            -4 to -4,
            8 to 0,
            0 to 8,
        )
        val ordered = PillagerBaseDiscoveryService.orderByFarthestFromExisting(points, 6)
        val seen = HashSet<Pair<Int, Int>>()

        assertEquals(6, ordered.size)
        ordered.forEach { orderedPoint ->
            assertTrue(seen.add(orderedPoint))
        }
    }

    @Test
    fun `farthest point ordering moves away from origin after first point`() {
        val points = listOf(0 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1, 2 to 2)
        val ordered = PillagerBaseDiscoveryService.orderByFarthestFromExisting(points, 6)

        assertTrue(PillagerBaseDiscoveryService.manhattan(ordered[0], ordered[1]) > 0)
    }
}
