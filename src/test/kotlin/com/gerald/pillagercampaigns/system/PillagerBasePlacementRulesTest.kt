package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PillagerBasePlacementRulesTest {
    private val settings = PillagerBasePlacementRules.Settings(
        spacingChunks = 64,
        jitterChunks = 12,
        spawnChancePercent = 100,
        minSpawnDistanceChunks = 8,
        structureIds = listOf(ResourceLocation("minecraft", "pillager_outpost")),
    )

    @Test
    fun `placement candidate is deterministic for seed dimension and cell`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val first = PillagerBasePlacementRules.candidateForCell(1234L, dimension, 4, -2, settings)
        val second = PillagerBasePlacementRules.candidateForCell(1234L, dimension, 4, -2, settings)

        assertEquals(first, second)
        assertNotNull(first)
    }

    @Test
    fun `different cells produce different base ids`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val first = PillagerBasePlacementRules.candidateForCell(1234L, dimension, 4, -2, settings)
        val second = PillagerBasePlacementRules.candidateForCell(1234L, dimension, 5, -2, settings)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `minimum spawn distance filters near-origin cells`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val blocked = PillagerBasePlacementRules.candidateForCell(
            seed = 1234L,
            dimension = dimension,
            cellX = 0,
            cellZ = 0,
            settings = settings.copy(minSpawnDistanceChunks = 1024),
        )

        assertNull(blocked)
    }

    @Test
    fun `cell scan around chunk includes containing cell`() {
        val cells = PillagerBasePlacementRules.cellsAround(130, -70, radiusChunks = 1, spacingChunks = 64).toSet()

        assertEquals(true, 2 to -2 in cells)
    }
}
