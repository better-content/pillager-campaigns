package com.gerald.pillagercampaigns.system

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
}
