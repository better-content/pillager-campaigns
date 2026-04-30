package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.CampaignState
import com.gerald.pillagerpressure.data.PillagerCampaign
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
        step: Int = 8,
        isLoaded: (BlockPos) -> Boolean,
        isValid: (BlockPos) -> Boolean,
    ): BlockPos? {
        for (offset in farthestFirstOffsets(minRadius, maxRadius, step)) {
            val probe = center.offset(offset.dx, 0, offset.dz)
            if (!isLoaded(probe)) continue
            if (isValid(probe)) return probe
        }
        return null
    }

    fun chooseForced(
        center: BlockPos,
        normalMinRadius: Int,
        normalMaxRadius: Int,
        fallbackMinRadius: Int,
        fallbackMaxRadius: Int,
        fallbackStep: Int = 4,
        isLoaded: (BlockPos) -> Boolean,
        isValid: (BlockPos) -> Boolean,
    ): BlockPos? =
        chooseFarthest(center, normalMinRadius, normalMaxRadius, isLoaded = isLoaded, isValid = isValid)
            ?: chooseFarthest(center, fallbackMinRadius, fallbackMaxRadius, fallbackStep, isLoaded, isValid)
            ?: center.takeIf { isLoaded(it) && isValid(it) }

    private fun addIfInRing(offsets: MutableSet<Offset>, dx: Int, dz: Int, minRadius: Int, maxRadius: Int) {
        val distanceSqr = dx * dx + dz * dz
        if (distanceSqr in (minRadius * minRadius)..(maxRadius * maxRadius)) offsets += Offset(dx, dz)
    }
}

object PillagerAttemptRules {
    data class PlayerDecision(val shouldAttempt: Boolean, val skippedStatus: String?)

    fun playerDecision(
        force: Boolean,
        playerName: String,
        eligible: Boolean,
        inAllowedDimension: Boolean,
        chancePassed: Boolean,
    ): PlayerDecision = when {
        force && !inAllowedDimension -> PlayerDecision(false, "forced player not in overworld: $playerName")
        !force && !eligible -> PlayerDecision(false, "no eligible player: $playerName")
        !force && !chancePassed -> PlayerDecision(false, "skipped chance for $playerName")
        else -> PlayerDecision(true, null)
    }

    fun commandFeedback(spawnedGroups: Int, status: String): String =
        "Pillager Pressure forced attempt spawned_groups=$spawnedGroups status=$status"
}

object SquadCohesionRules {
    const val PULL_DISTANCE_BLOCKS = 6.0
    const val SPRINT_DISTANCE_BLOCKS = 16.0

    fun shouldPullToLeader(distanceSqr: Double): Boolean = distanceSqr > PULL_DISTANCE_BLOCKS * PULL_DISTANCE_BLOCKS

    fun moveSpeed(distanceSqr: Double): Double =
        if (distanceSqr > SPRINT_DISTANCE_BLOCKS * SPRINT_DISTANCE_BLOCKS) 1.35 else 1.15
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

object PillagerCampaignMaterializationRules {
    data class Plan(val targetPlayerImmediately: Boolean, val nextState: CampaignState)

    fun planFor(campaign: PillagerCampaign): Plan {
        if (campaign.current != campaign.target) return Plan(targetPlayerImmediately = false, nextState = campaign.state)
        return when (campaign.state) {
            CampaignState.SCOUTING,
            CampaignState.APPROACHING_INTEL -> Plan(targetPlayerImmediately = false, nextState = CampaignState.SEARCHING)
            CampaignState.ENGAGING -> Plan(targetPlayerImmediately = true, nextState = CampaignState.ENGAGING)
            CampaignState.SEARCHING,
            CampaignState.RETREATING_WITH_INTEL,
            CampaignState.RETURNING_TO_BASE,
            CampaignState.EXPANDING,
            CampaignState.SUPPLYING,
            CampaignState.DISBANDED -> Plan(targetPlayerImmediately = false, nextState = campaign.state)
        }
    }
}
