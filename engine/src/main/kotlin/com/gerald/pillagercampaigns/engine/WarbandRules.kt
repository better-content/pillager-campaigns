package com.gerald.pillagercampaigns.engine

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class WarbandRules(
    val minimumAggression: Int = 6,
    val maximumAggression: Int = 18,
    val idleReturnTicks: Long = 12_000L,
    val travelTicksPerChunk: Long = 120L,
    val materializeDistanceChunks: Int = 6,
    val raidCooldownTicks: Long = 6_000L,
    val captainRecoveryTicks: Long = 6_000L,
    val captainSuccessRecoveryTicks: Long = 2_400L,
    val maximumSquadMembers: Int = 24,
    val warbandLearningRate: Double = 0.05,
    val captainLearningRate: Double = 0.10,
    val threatLearningRate: Double = 0.10,
    val selectionMemoryHalfLifeTicks: Long = 240_000L,
    val diversityWeight: Double = 0.35,
    val sustenancePerThreatChunk: Double = 0.018,
    val munitionsPerRangedThreatChunk: Double = 0.012,
    val maintenancePerEquipmentChunk: Double = 0.025,
    val deficitGraceChunks: Double = 3.0,
    val attritionPerDeficitChunk: Double = 0.035,
    val equipmentWearPerFrictionChunk: Double = 0.004,
    val forageUnitsPerDeficitChunk: Double = 0.75,
    val shortageRetreatBaseChunks: Double = 6.0,
    val shortageAggressionRunwayChunks: Double = 18.0,
) {
    fun capacity(environment: EnvironmentTraits): Int =
        ((96.0 + 120.0 * environment.bounded().habitability) / 6.0).roundToInt() * 6

    fun recruitTicksPerThreat(environment: EnvironmentTraits): Double =
        (180.0 + 240.0 * (1.0 - environment.bounded().habitability)) * 20.0

    fun mobilizationTicksPerThreat(environment: EnvironmentTraits): Double =
        (30.0 + 60.0 * environment.bounded().travelFriction) * 20.0

    fun extractionTicks(environment: EnvironmentTraits): Double = recruitTicksPerThreat(environment) / 2.0

    /**
     * Derives the threat needed to express the warband's current aggression.
     * The additional recruit term grows
     * continuously across the aggression range: young warbands wait long enough
     * to form mixed squads, while highly aggressive warbands can field units
     * whose individual threat exceeds the raw aggression value.
     */
    fun aggressionRaidThreat(warband: WarbandState, minimumThreat: Double): Double {
        val aggressionRange = (maximumAggression - minimumAggression).coerceAtLeast(1)
        val normalizedAggression =
            (warband.aggression - minimumAggression).toDouble().div(aggressionRange).coerceIn(0.0, 1.0)
        return warband.aggression + minimumThreat * (1.0 + normalizedAggression)
    }

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

    /**
     * Continuous operational demand layered over learned preferences. Aggression
     * demands lethality, accumulated power demands equipment that survives a
     * campaign, and terrain changes what is useful without assigning a roster or
     * loadout archetype.
     */
    fun armamentPreferences(warband: WarbandState, officer: OfficerState?): CapabilityVector {
        val environment = warband.environment.bounded()
        val aggression = normalizedAggression(warband)
        val power = ((warband.reserveThreat + warband.raidPool + warband.garrisonThreat) /
            warband.capacity.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        return effectivePreferences(warband, officer) + CapabilityVector(
            durability = 0.35 * (1.0 - environment.habitability) + 0.25 * environment.travelFriction + 0.20 * power,
            damage = 0.40 * aggression + 0.15 * environment.mineralPotential,
            mobility = 0.35 * environment.travelFriction + 0.15 * (1.0 - environment.habitability),
            range = 0.25 * (1.0 - environment.biomass) + 0.25 * aggression,
            control = 0.25 * environment.biomass + 0.20 * environment.exoticPotential + 0.20 * power,
        )
    }

    /** Fraction of a dispatch that logistics should be able to arm. */
    fun armamentCoverageTarget(warband: WarbandState): Double {
        val environment = warband.environment.bounded()
        val power = ((warband.reserveThreat + warband.raidPool + warband.garrisonThreat) /
            warband.capacity.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        return (0.45 + 0.30 * normalizedAggression(warband) + 0.15 * power +
            0.05 * environment.mineralPotential + 0.05 * environment.exoticPotential).coerceIn(0.45, 1.0)
    }

    fun desiredArmoryItems(warband: WarbandState, recruits: List<RecruitDefinition>): Int {
        val minimumThreat = recruits.minOfOrNull { it.baseThreat.coerceAtLeast(1.0) } ?: return 0
        // Planning is free to favor several inexpensive recruits. Sizing against
        // the minimum closes that exact upper bound without defining a roster.
        val expectedMembers = aggressionRaidThreat(warband, minimumThreat) / minimumThreat
        return ceil(expectedMembers * armamentCoverageTarget(warband)).toInt().coerceIn(1, maximumSquadMembers)
    }

    /** Makes incomparable TCon stat domains commensurate without rank buckets. */
    fun capabilityUtility(capabilities: CapabilityVector, preferences: CapabilityVector): Double =
        listOf(
            capabilities.durability to preferences.durability,
            capabilities.damage to preferences.damage,
            capabilities.mobility to preferences.mobility,
            capabilities.range to preferences.range,
            capabilities.control to preferences.control,
        ).sumOf { (value, preference) -> value / (1.0 + abs(value)) * preference }

    fun equipmentSupportsRecruit(equipment: EquipmentManifest, recruit: RecruitDefinition): Boolean {
        val actions = equipment.supportedActions
        return actions.isEmpty() || recruit.supportedEquipmentActions.isEmpty() ||
            "defense" in actions || "utility" in actions || actions.any(recruit.supportedEquipmentActions::contains)
    }

    private fun normalizedAggression(warband: WarbandState): Double =
        ((warband.aggression - minimumAggression).toDouble() /
            (maximumAggression - minimumAggression).coerceAtLeast(1)).coerceIn(0.0, 1.0)
}
