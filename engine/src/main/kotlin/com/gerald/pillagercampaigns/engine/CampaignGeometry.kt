package com.gerald.pillagercampaigns.engine

import kotlin.math.abs

object CampaignGeometry {
    fun stepToward(currentX: Int, currentZ: Int, targetX: Int, targetZ: Int): Pair<Int, Int> {
        val dx = targetX - currentX
        val dz = targetZ - currentZ
        if (dx == 0 && dz == 0) return currentX to currentZ
        return if (abs(dx) >= abs(dz)) {
            (currentX + dx.sign()) to currentZ
        } else {
            currentX to (currentZ + dz.sign())
        }
    }

    fun manhattan(x1: Int, z1: Int, x2: Int, z2: Int): Int = abs(x1 - x2) + abs(z1 - z2)

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
