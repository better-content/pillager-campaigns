package com.gerald.warband.core

import kotlin.random.Random

data class FormulaCandidate(
    val id: String,
    val threat: Double,
    val attributes: Map<String, Double>,
    val resourceCost: Map<String, Double> = emptyMap(),
)

object FormulaMath {
    fun extractionThreshold(tier: Int, additionalIngredientGroups: Int): Double =
        12.0 * tier.coerceAtLeast(1) * tier.coerceAtLeast(1) * (1.0 + 0.25 * additionalIngredientGroups.coerceAtLeast(0))

    fun escortCount(unallocatedReserve: Double): Int = (unallocatedReserve / 18.0).toInt().coerceIn(0, 8)

    fun retreatThreshold(conservationPreference: Double, aggression: Int, rules: CoreRules = CoreRules()): Double {
        val normalizedAggression = (aggression - rules.minimumAggression).toDouble() /
            (rules.maximumAggression - rules.minimumAggression).coerceAtLeast(1)
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
}
