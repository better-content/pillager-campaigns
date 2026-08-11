package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.engine.CampaignGeometry

object CampaignMath {
    fun stepToward(currentX: Int, currentZ: Int, targetX: Int, targetZ: Int): Pair<Int, Int> {
        return CampaignGeometry.stepToward(currentX, currentZ, targetX, targetZ)
    }

    fun manhattan(x1: Int, z1: Int, x2: Int, z2: Int): Int = CampaignGeometry.manhattan(x1, z1, x2, z2)
}
