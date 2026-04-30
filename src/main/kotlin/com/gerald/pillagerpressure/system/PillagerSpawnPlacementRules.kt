package com.gerald.pillagerpressure.system

import net.minecraft.core.BlockPos

object PillagerSpawnPlacementRules {
    data class Offset(val dx: Int, val dz: Int) {
        val distanceSqr: Int = dx * dx + dz * dz
    }

    fun farthestFirstOffsets(minRadius: Int, maxRadius: Int, step: Int = 8): List<Offset> {
        val minR = minRadius.coerceAtLeast(1)
        val maxR = maxRadius.coerceAtLeast(minR)
        val stride = step.coerceAtLeast(1)
        val offsets = linkedSetOf<Offset>()
        for (x in -maxR..maxR step stride) {
            for (z in -maxR..maxR step stride) {
                addIfInRing(offsets, x, z, minR, maxR)
            }
        }
        // Ensure exact cardinal far points are checked even when the stride misses them.
        listOf(
            Offset(maxR, 0), Offset(-maxR, 0), Offset(0, maxR), Offset(0, -maxR)
        ).forEach { addIfInRing(offsets, it.dx, it.dz, minR, maxR) }
        return offsets.sortedWith(compareByDescending<Offset> { it.distanceSqr }.thenBy { it.dx }.thenBy { it.dz })
    }

    fun chooseFarthest(
        center: BlockPos,
        minRadius: Int,
        maxRadius: Int,
        isLoaded: (BlockPos) -> Boolean,
        isValid: (BlockPos) -> Boolean,
    ): BlockPos? {
        for (offset in farthestFirstOffsets(minRadius, maxRadius)) {
            val probe = center.offset(offset.dx, 0, offset.dz)
            if (!isLoaded(probe)) continue
            if (isValid(probe)) return probe
        }
        return null
    }

    private fun addIfInRing(offsets: MutableSet<Offset>, dx: Int, dz: Int, minRadius: Int, maxRadius: Int) {
        val distanceSqr = dx * dx + dz * dz
        if (distanceSqr in (minRadius * minRadius)..(maxRadius * maxRadius)) offsets += Offset(dx, dz)
    }
}

object PillagerObjectiveRules {
    data class Objective(val kind: String, val pos: BlockPos)

    fun objectiveFor(campaign: com.gerald.pillagerpressure.data.PillagerCampaign?, target: net.minecraft.server.level.ServerPlayer?, fallback: BlockPos): Objective {
        if (target != null) return Objective("player", target.blockPosition())
        if (campaign != null) {
            val kind = when (campaign.state) {
                com.gerald.pillagerpressure.data.CampaignState.SCOUTING -> "scout"
                com.gerald.pillagerpressure.data.CampaignState.APPROACHING_INTEL -> "hunt_intel"
                com.gerald.pillagerpressure.data.CampaignState.SEARCHING -> "search"
                com.gerald.pillagerpressure.data.CampaignState.ENGAGING -> "engage"
                com.gerald.pillagerpressure.data.CampaignState.RETREATING_WITH_INTEL -> "retreat"
                com.gerald.pillagerpressure.data.CampaignState.RETURNING_TO_BASE -> "return"
                com.gerald.pillagerpressure.data.CampaignState.EXPANDING -> "expand"
                com.gerald.pillagerpressure.data.CampaignState.SUPPLYING -> "supply"
                com.gerald.pillagerpressure.data.CampaignState.DISBANDED -> "disbanded"
            }
            return Objective(kind, campaign.target.centerBlock(fallback.y))
        }
        return Objective("patrol", fallback)
    }
}
