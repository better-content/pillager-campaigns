package com.bettercontent.pillagercampaigns.system

import kotlin.math.sqrt

enum class TerritorialRelation { UNCONTACTED, WARNED, HOSTILE }

object WarbandTerritoryRules {
    fun distanceChunks(originX: Int, originZ: Int, x: Int, z: Int): Double {
        val dx = (x - originX).toDouble()
        val dz = (z - originZ).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    fun relation(distance: Double, radius: Int = 32, warningBand: Int = 4, attacked: Boolean = false): TerritorialRelation = when {
        attacked || distance < (radius - warningBand).coerceAtLeast(0) -> TerritorialRelation.HOSTILE
        distance <= radius -> TerritorialRelation.WARNED
        else -> TerritorialRelation.UNCONTACTED
    }

    fun contains(originX: Int, originZ: Int, x: Int, z: Int, radius: Int = 32): Boolean =
        distanceChunks(originX, originZ, x, z) <= radius.coerceAtMost(32)
}
