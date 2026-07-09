package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertFalse

class PillagerCampaignEngineRallyTest {
    @Test
    fun `rally drift is disabled when no level is loaded for the dimension`() {
        assertFalse(PillagerCampaignEngine.shouldApplyRallyDrift(null, 4, -3, 7, -1))
    }

    @Test
    fun `rally drift is blocked when current rally chunk is loaded`() {
        val isLoaded = loadedPredicate(setOf(4 to -3))
        assertFalse(PillagerCampaignEngine.shouldApplyRallyDrift(isLoaded, 4, -3, 7, -1))
    }

    @Test
    fun `rally drift is blocked when destination chunk is loaded`() {
        val isLoaded = loadedPredicate(setOf(7 to -1))
        assertFalse(PillagerCampaignEngine.shouldApplyRallyDrift(isLoaded, 4, -3, 7, -1))
    }

    @Test
    fun `rally drift is blocked when an intermediate chunk is loaded`() {
        val isLoaded = loadedPredicate(setOf(5 to -2))
        assertFalse(PillagerCampaignEngine.shouldApplyRallyDrift(isLoaded, 4, -3, 7, -1))
    }

    private fun loadedPredicate(loadedChunks: Set<Pair<Int, Int>>): (Int, Int) -> Boolean = { x, z -> (x to z) in loadedChunks }
}
