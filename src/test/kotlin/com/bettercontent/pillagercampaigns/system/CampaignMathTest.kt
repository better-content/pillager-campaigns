package com.bettercontent.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals

class CampaignMathTest {
    @Test
    fun `step stays put when already at target`() {
        assertEquals(5 to -3, CampaignMath.stepToward(5, -3, 5, -3))
    }

    @Test
    fun `step prefers dominant axis`() {
        assertEquals(1 to 0, CampaignMath.stepToward(0, 0, 5, 2))
        assertEquals(0 to 1, CampaignMath.stepToward(0, 0, 1, 4))
    }

    @Test
    fun `manhattan distance is stable`() {
        assertEquals(7, CampaignMath.manhattan(0, 0, 3, -4))
        assertEquals(0, CampaignMath.manhattan(4, 9, 4, 9))
    }

    @Test
    fun `step handles reverse movement and equal axes`() {
        assertEquals(3 to 0, CampaignMath.stepToward(4, 0, 0, 0))
        assertEquals(1 to 0, CampaignMath.stepToward(1, 1, 1, -3))
        assertEquals(2 to 1, CampaignMath.stepToward(1, 1, 2, 1))
        assertEquals(2 to 1, CampaignMath.stepToward(1, 1, 2, 2))
    }
}
