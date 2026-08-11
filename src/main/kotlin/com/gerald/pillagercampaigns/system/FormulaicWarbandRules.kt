package com.gerald.pillagercampaigns.system

import kotlin.math.roundToInt
import kotlin.random.Random

data class EnvironmentTraits(
    val habitability: Double = 0.5,
    val biomass: Double = 0.5,
    val mineralPotential: Double = 0.5,
    val exoticPotential: Double = 0.5,
    val travelFriction: Double = 0.5,
) {
    fun bounded() = EnvironmentTraits(
        habitability.coerceIn(0.0, 1.0), biomass.coerceIn(0.0, 1.0),
        mineralPotential.coerceIn(0.0, 1.0), exoticPotential.coerceIn(0.0, 1.0),
        travelFriction.coerceIn(0.0, 1.0),
    )
}

data class FormulaCandidate(
    val id: String,
    val threat: Double,
    val attributes: Map<String, Double>,
    val resourceCost: Map<String, Double> = emptyMap(),
)

object FormulaicWarbandRules {
    const val INITIAL_RESERVE = 18
    const val MIN_AGGRESSION = 6
    const val MAX_AGGRESSION = 18
    const val IDLE_RETURN_TICKS = 12_000L

    fun capacity(traits: EnvironmentTraits): Int =
        roundToSix(96.0 + 120.0 * traits.bounded().habitability)

    fun grossRecruitTicksPerStrength(traits: EnvironmentTraits): Double =
        180.0 + 240.0 * (1.0 - traits.bounded().habitability)

    fun mobilizationTicksPerStrength(traits: EnvironmentTraits, learnedAdjustment: Double = 0.0): Double =
        (30.0 + 60.0 * traits.bounded().travelFriction + learnedAdjustment).coerceIn(30.0, 90.0)

    fun extractionThreshold(tier: Int, additionalIngredientGroups: Int): Double =
        12.0 * tier.coerceAtLeast(1) * tier.coerceAtLeast(1) * (1.0 + 0.25 * additionalIngredientGroups.coerceAtLeast(0))

    fun escortCount(unallocatedReserve: Double): Int = (unallocatedReserve / 18.0).toInt().coerceIn(0, 8)

    fun retreatThreshold(conservationPreference: Double, aggression: Int): Double {
        val normalizedAggression = (aggression - MIN_AGGRESSION).toDouble() / (MAX_AGGRESSION - MIN_AGGRESSION)
        return (0.35 + 0.25 * conservationPreference.coerceIn(0.0, 1.0) - 0.15 * normalizedAggression.coerceIn(0.0, 1.0))
            .coerceIn(0.20, 0.60)
    }

    fun updatePreference(current: Double, contribution: Double, learningRate: Double): Double =
        current + learningRate.coerceAtLeast(0.0) * contribution.coerceIn(-1.0, 1.0)

    fun updateThreat(current: Double, observation: Double, learningRate: Double = 0.10): Double =
        current + learningRate.coerceIn(0.0, 1.0) * (observation.coerceAtLeast(0.0) - current)

    fun score(candidate: FormulaCandidate, preferences: Map<String, Double>, available: Map<String, Double>): Double {
        if (candidate.threat <= 0.0) return Double.NEGATIVE_INFINITY
        if (candidate.resourceCost.any { (key, amount) -> (available[key] ?: 0.0) < amount }) return Double.NEGATIVE_INFINITY
        val utility = candidate.attributes.entries.sumOf { (key, value) -> (preferences[key] ?: 0.0) * value }
        val scarcity = candidate.resourceCost.entries.sumOf { (key, amount) -> amount / ((available[key] ?: 0.0) + 1.0) }
        return utility / candidate.threat - scarcity
    }

    fun choose(candidates: Collection<FormulaCandidate>, preferences: Map<String, Double>, available: Map<String, Double>): FormulaCandidate? =
        candidates.maxWithOrNull(compareBy<FormulaCandidate> { score(it, preferences, available) }.thenByDescending { it.id })
            ?.takeIf { score(it, preferences, available).isFinite() }

    fun initialPreferences(seed: Long, traits: EnvironmentTraits = EnvironmentTraits()): MutableMap<String, Double> {
        val random = Random(seed)
        val env = traits.bounded()
        return mutableMapOf(
            "durability" to env.mineralPotential + random.nextDouble(-0.15, 0.15),
            "damage" to (1.0 - env.habitability) + random.nextDouble(-0.15, 0.15),
            "mobility" to (1.0 - env.travelFriction) + random.nextDouble(-0.15, 0.15),
            "range" to env.travelFriction + random.nextDouble(-0.15, 0.15),
            "conservation" to env.habitability + random.nextDouble(-0.15, 0.15),
            "exotic" to env.exoticPotential + random.nextDouble(-0.15, 0.15),
        )
    }

    private fun roundToSix(value: Double): Int = (value / 6.0).roundToInt() * 6
}
