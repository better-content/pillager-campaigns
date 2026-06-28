package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PillagerWarbandDiscoveryRulesTest {
    private val settings = PillagerWarbandDiscoveryRules.Settings(
        spacingChunks = 64,
        jitterChunks = 8,
        spawnChancePercent = 100,
        minSpawnDistanceChunks = 24,
        structureIds = listOf(ResourceLocation("minecraft", "pillager_outpost")),
    )

    @Test
    fun `candidate generation is deterministic per cell`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val first = PillagerWarbandDiscoveryRules.candidateForCell(1234L, dimension, 4, -2, settings)
        val second = PillagerWarbandDiscoveryRules.candidateForCell(1234L, dimension, 4, -2, settings)

        assertEquals(first, second)
    }

    @Test
    fun `candidate id changes with cell`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val first = PillagerWarbandDiscoveryRules.candidateForCell(1234L, dimension, 4, -2, settings)
        val second = PillagerWarbandDiscoveryRules.candidateForCell(1234L, dimension, 5, -2, settings)

        assertNotEquals(first?.id, second?.id)
    }

    @Test
    fun `spawn distance gate can block origin-adjacent warband`() {
        val blocked = PillagerWarbandDiscoveryRules.candidateForCell(
            seed = 9L,
            dimension = ResourceLocation("minecraft", "overworld"),
            cellX = 0,
            cellZ = 0,
            settings = settings.copy(minSpawnDistanceChunks = 256),
        )

        assertNull(blocked)
    }

    @Test
    fun `cells around covers local neighborhood`() {
        val cells = PillagerWarbandDiscoveryRules.cellsAround(130, -70, radiusChunks = 1, spacingChunks = 64).toSet()

        assertTrue((2 to -2) in cells)
        assertTrue((1 to -1) in cells)
        assertEquals(25, cells.size)
    }
}
