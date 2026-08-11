package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.engine.FormulaMath
import com.gerald.pillagercampaigns.engine.WarbandRules

typealias EnvironmentTraits = com.gerald.pillagercampaigns.engine.EnvironmentTraits

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

    private val rules = WarbandRules()

    fun capacity(traits: EnvironmentTraits): Int = rules.capacity(traits)

    fun grossRecruitTicksPerStrength(traits: EnvironmentTraits): Double =
        rules.recruitTicksPerThreat(traits) / 20.0

    fun mobilizationTicksPerStrength(traits: EnvironmentTraits, learnedAdjustment: Double = 0.0): Double =
        (rules.mobilizationTicksPerThreat(traits) / 20.0 + learnedAdjustment).coerceIn(30.0, 90.0)

    fun extractionThreshold(tier: Int, additionalIngredientGroups: Int): Double =
        FormulaMath.extractionThreshold(tier, additionalIngredientGroups)

    fun escortCount(unallocatedReserve: Double): Int = FormulaMath.escortCount(unallocatedReserve)

    fun retreatThreshold(conservationPreference: Double, aggression: Int): Double =
        FormulaMath.retreatThreshold(conservationPreference, aggression, rules)

    fun updatePreference(current: Double, contribution: Double, learningRate: Double): Double =
        FormulaMath.updatePreference(current, contribution, learningRate)

    fun updateThreat(current: Double, observation: Double, learningRate: Double = 0.10): Double =
        FormulaMath.updateThreat(current, observation, learningRate)

    fun score(candidate: FormulaCandidate, preferences: Map<String, Double>, available: Map<String, Double>): Double {
        return FormulaMath.score(
            com.gerald.pillagercampaigns.engine.FormulaCandidate(candidate.id, candidate.threat, candidate.attributes, candidate.resourceCost),
            preferences,
            available,
        )
    }

    fun choose(candidates: Collection<FormulaCandidate>, preferences: Map<String, Double>, available: Map<String, Double>): FormulaCandidate? =
        FormulaMath.choose(
            candidates.map { com.gerald.pillagercampaigns.engine.FormulaCandidate(it.id, it.threat, it.attributes, it.resourceCost) },
            preferences,
            available,
        )?.let { selected -> candidates.first { it.id == selected.id } }

    fun initialPreferences(seed: Long, traits: EnvironmentTraits = EnvironmentTraits()): MutableMap<String, Double> {
        return FormulaMath.initialPreferences(seed, traits)
    }
}
