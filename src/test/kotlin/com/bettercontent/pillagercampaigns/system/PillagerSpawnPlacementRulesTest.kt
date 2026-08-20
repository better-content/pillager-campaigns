package com.bettercontent.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals

class PillagerSpawnPlacementRulesTest {
    @Test
    fun `deterministic materialization search uses fixed three chunk radius order`() {
        val offsets = PillagerSpawnPlacementRules.deterministicChunkOffsets(3)

        assertEquals(49, offsets.size)
        assertEquals(-3 to -3, offsets.first())
        assertEquals(0 to 0, offsets[24])
        assertEquals(3 to 3, offsets.last())
        assertEquals(offsets, PillagerSpawnPlacementRules.deterministicChunkOffsets(3))
    }

    @Test
    fun `member surface fallback starts at requested column and expands in stable rings`() {
        val offsets = PillagerSpawnPlacementRules.deterministicBlockOffsets(2)

        assertEquals(25, offsets.size)
        assertEquals(0 to 0, offsets.first())
        assertEquals(
            listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1),
            offsets.drop(1).take(8),
        )
        assertEquals(2 to 2, offsets.last())
        assertEquals(offsets, offsets.distinct())
    }
}
