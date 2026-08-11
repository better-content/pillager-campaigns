package com.gerald.pillagercampaigns.engine

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class WarbandRules(
    val minimumAggression: Int = 6,
    val maximumAggression: Int = 18,
    val idleReturnTicks: Long = 12_000L,
    val travelTicksPerChunk: Long = 120L,
    val materializeDistanceChunks: Int = 6,
    val raidCooldownTicks: Long = 6_000L,
    val maximumSquadMembers: Int = 24,
    val warbandLearningRate: Double = 0.05,
    val captainLearningRate: Double = 0.10,
    val threatLearningRate: Double = 0.10,
) {
    fun capacity(environment: EnvironmentTraits): Int =
        ((96.0 + 120.0 * environment.bounded().habitability) / 6.0).roundToInt() * 6

    fun recruitTicksPerThreat(environment: EnvironmentTraits): Double =
        (180.0 + 240.0 * (1.0 - environment.bounded().habitability)) * 20.0

    fun mobilizationTicksPerThreat(environment: EnvironmentTraits): Double =
        (30.0 + 60.0 * environment.bounded().travelFriction) * 20.0

    fun extractionTicks(environment: EnvironmentTraits): Double = recruitTicksPerThreat(environment) / 2.0

    fun raidBudget(warband: WarbandState, minimumThreat: Double): Double =
        minOf(warband.raidPool, maxOf(warband.aggression.toDouble(), minimumThreat))

    fun effectivePreferences(warband: WarbandState, officer: OfficerState?): CapabilityVector {
        // Preserve the current live precedence as the extraction baseline: warband values
        // replace captain values for shared keys. Experiments can now prove whether to revise it.
        val values = officer?.preferences.orEmpty() + warband.preferences
        return CapabilityVector(
            values["durability"] ?: 0.0,
            values["damage"] ?: 0.0,
            values["mobility"] ?: 0.0,
            values["range"] ?: 0.0,
            values["control"] ?: 0.0,
        )
    }
}
