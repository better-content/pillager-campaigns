package com.bettercontent.pillagercampaigns.system

/** Typed policy compiled into runtime goals; values come from the campaign and equipped entity. */
data class RaidAiPolicy(
    val targetPlayer: Boolean,
    val territoryBound: Boolean,
    val preserveNativeAttacks: Boolean,
    val weaponRange: Double,
    val cohesionRadius: Double,
    val successorByScore: Boolean,
    val idleReturnTicks: Long,
)

class RaidAiDsl {
    private var targetPlayer = true
    private var territoryBound = true
    private var preserveNativeAttacks = true
    private var weaponRange = 2.5
    private var cohesionRadius = 24.0
    private var successorByScore = true
    private var idleReturnTicks = 1L

    fun assignedTarget(enabled: Boolean = true) = apply { targetPlayer = enabled }
    fun territoryBoundary(enabled: Boolean = true) = apply { territoryBound = enabled }
    fun nativeAttacks(enabled: Boolean = true) = apply { preserveNativeAttacks = enabled }
    fun actualWeaponRange(blocks: Double) = apply { weaponRange = blocks.coerceAtLeast(1.0) }
    fun cohesion(blocks: Double) = apply { cohesionRadius = blocks.coerceAtLeast(4.0) }
    fun formulaicSuccessor(enabled: Boolean = true) = apply { successorByScore = enabled }
    fun returnAfterIdle(ticks: Long) = apply { idleReturnTicks = ticks.coerceAtLeast(1L) }

    fun build() = RaidAiPolicy(targetPlayer, territoryBound, preserveNativeAttacks, weaponRange, cohesionRadius, successorByScore, idleReturnTicks)
}

fun raidAi(block: RaidAiDsl.() -> Unit): RaidAiPolicy = RaidAiDsl().apply(block).build()
