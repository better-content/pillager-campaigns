package com.bettercontent.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PillagerWarbandDiscoveryRulesTest {
    private val settings = PillagerWarbandDiscoveryRules.Settings(
        spacingChunks = 64,
        jitterChunks = 8,
        spawnChancePercent = 100,
        minSpawnDistanceChunks = 24,
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
    fun `raw candidate generation does not apply strategic distance gates`() {
        val observed = PillagerWarbandDiscoveryRules.candidateForCell(
            seed = 9L,
            dimension = ResourceLocation("minecraft", "overworld"),
            cellX = 0,
            cellZ = 0,
            settings = settings.copy(minSpawnDistanceChunks = 256),
        )

        assertTrue(observed != null)
        assertTrue(observed.chunkX in 24..40)
        assertTrue(observed.chunkZ in 24..40)
    }

    @Test
    fun `candidate jitter stays centered inside its cell`() {
        val observed = PillagerWarbandDiscoveryRules.candidateForCell(
            seed = 44L,
            dimension = ResourceLocation("minecraft", "overworld"),
            cellX = -2,
            cellZ = 3,
            settings = settings,
        )!!

        assertTrue(observed.chunkX in -104..-88)
        assertTrue(observed.chunkZ in 216..232)
    }

    @Test
    fun `coverage candidates are deterministic distinct and in dispatch range`() {
        val dimension = ResourceLocation("minecraft", "overworld")
        val first = PillagerWarbandDiscoveryRules.coverageCandidates(9L, dimension, "player", 100, -40, 24, 32)
        val second = PillagerWarbandDiscoveryRules.coverageCandidates(9L, dimension, "player", 100, -40, 24, 32)

        assertEquals(first, second)
        assertEquals(8, first.map { it.id }.distinct().size)
        assertTrue(first.all { candidate ->
            kotlin.math.abs(candidate.chunkX - 100) + kotlin.math.abs(candidate.chunkZ + 40) == 32 &&
                candidate.coveragePlayerId == "player" && candidate.siteId.isNotBlank()
        })
    }

    @Test
    fun `cells around covers local neighborhood`() {
        val cells = PillagerWarbandDiscoveryRules.cellsAround(130, -70, radiusChunks = 1, spacingChunks = 64).toSet()

        assertTrue((2 to -2) in cells)
        assertTrue((1 to -1) in cells)
        assertEquals(25, cells.size)
    }
}
