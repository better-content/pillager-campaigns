package com.gerald.pillagercampaigns.engine

import kotlin.math.exp

object EcologyMath {
    fun environmentalYield(resource: ResourceDefinition, environment: EnvironmentTraits): Double {
        val affinity = resource.environmentalAffinity.bounded()
        val env = environment.bounded()
        return (
            affinity.habitability * env.habitability + affinity.biomass * env.biomass +
                affinity.mineralPotential * env.mineralPotential + affinity.exoticPotential * env.exoticPotential
            ) / (1.0 + affinity.travelFriction * env.travelFriction)
    }

    fun decay(memory: SelectionMemory, now: Long, halfLifeTicks: Long) {
        if (now <= memory.lastDecayTick) return
        val factor = exp(-Math.log(2.0) * (now - memory.lastDecayTick) / halfLifeTicks.coerceAtLeast(1).toDouble())
        listOf(memory.recruits, memory.materials, memory.equipment).forEach { values ->
            values.replaceAll { _, value -> value * factor }
            values.entries.removeIf { it.value < 1.0e-6 }
        }
        memory.lastDecayTick = now
    }

    fun repetitionPenalty(memory: Map<String, Double>, id: String, weight: Double): Double {
        val total = memory.values.sum()
        if (total <= 0.0) return 0.0
        val share = memory.getOrDefault(id, 0.0) / total
        return weight.coerceAtLeast(0.0) * share / (1.0 + share)
    }

    fun routeCost(
        route: List<TerrainObservation>,
        preferences: CapabilityVector,
        aggression: Int,
        rules: WarbandRules = WarbandRules(),
    ): Double {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY
        val aggressionNormalized = (aggression - rules.minimumAggression).toDouble() /
            (rules.maximumAggression - rules.minimumAggression).coerceAtLeast(1)
        return route.sumOf { observation ->
            val env = observation.traits.bounded()
            val friction = env.travelFriction * (1.0 - preferences.mobility.coerceIn(0.0, 1.0) * 0.5)
            val forage = env.biomass * 0.45 + env.mineralPotential * 0.25 + env.exoticPotential * 0.15
            1.0 + friction * (1.0 - aggressionNormalized.coerceIn(0.0, 1.0) * 0.35) -
                forage * (0.2 + preferences.durability.coerceIn(0.0, 1.0) * 0.2)
        }
    }

    fun chooseRoute(
        candidates: Collection<List<TerrainObservation>>,
        preferences: CapabilityVector,
        aggression: Int,
        rules: WarbandRules = WarbandRules(),
    ): List<ChunkPosition> = candidates.asSequence()
        .filter { it.isNotEmpty() }
        .minWithOrNull(compareBy<List<TerrainObservation>> { routeCost(it, preferences, aggression, rules) }
            .thenBy { route -> route.joinToString("|") { "${it.position.dimension}:${it.position.x}:${it.position.z}" } })
        ?.map(TerrainObservation::position).orEmpty()

    fun tacticalScore(
        candidate: TacticalPosition,
        capabilities: CapabilityVector,
        preferences: CapabilityVector,
        cohesionRadius: Double,
    ): Double {
        if (!candidate.reachable) return Double.NEGATIVE_INFINITY
        val desiredRange = (2.5 + capabilities.range * 12.5).coerceAtLeast(2.5)
        val rangeFit = 1.0 - kotlin.math.abs(candidate.targetDistance - desiredRange) / desiredRange.coerceAtLeast(1.0)
        val separation = kotlin.math.abs(candidate.nearestAllyDistance - cohesionRadius * 0.5) / cohesionRadius.coerceAtLeast(1.0)
        return rangeFit * (0.5 + preferences.range) + candidate.elevation * preferences.range * 0.25 +
            candidate.cover * (preferences.durability + 0.25) + candidate.flank * (preferences.mobility + 0.25) -
            candidate.pathCost * (0.1 + (1.0 - preferences.mobility.coerceIn(0.0, 1.0)) * 0.1) - separation * 0.35
    }

    fun chooseTacticalPosition(
        candidates: Collection<TacticalPosition>,
        capabilities: CapabilityVector,
        preferences: CapabilityVector,
        cohesionRadius: Double,
    ): TacticalPosition? = candidates.maxWithOrNull(
        compareBy<TacticalPosition> { tacticalScore(it, capabilities, preferences, cohesionRadius) }.thenByDescending(TacticalPosition::id),
    )?.takeIf { tacticalScore(it, capabilities, preferences, cohesionRadius).isFinite() }
}
