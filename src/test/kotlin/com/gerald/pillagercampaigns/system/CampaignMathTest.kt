package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals

class CampaignMathTest {
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
}
