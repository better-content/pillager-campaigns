package com.gerald.pillagercampaigns.engine

import kotlin.math.exp

object EcologyMath {
    data class ResourceConsumption(val remaining: ResourceVector, val items: Map<String, Int>)

    fun environmentalYield(resource: ResourceDefinition, environment: EnvironmentTraits): Double {
        val affinity = resource.environmentalAffinity.bounded()
        val env = environment.bounded()
        return resource.environmentalAvailability.coerceAtLeast(0.0) * (
            affinity.habitability * env.habitability + affinity.biomass * env.biomass +
                affinity.mineralPotential * env.mineralPotential + affinity.exoticPotential * env.exoticPotential
            ) / (1.0 + affinity.travelFriction * env.travelFriction)
    }

    fun chooseEnvironmentalResource(
        resources: Collection<ResourceDefinition>,
        environment: EnvironmentTraits,
        carried: Map<String, Int>,
        tieBreaker: (String) -> Long = { 0L },
    ): ResourceDefinition? = resources.asSequence().filter { environmentalYield(it, environment) > 1.0e-9 }
        .maxWithOrNull(compareBy<ResourceDefinition> {
            environmentalYield(it, environment) / (1.0 + carried.getOrDefault(it.itemId, 0))
        }.thenByDescending { tieBreaker(it.itemId) })

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

    fun segmentDemand(
        livingThreat: Double,
        rangedThreat: Double,
        equippedMembers: Int,
        totalInjury: Double,
        environment: EnvironmentTraits,
        rules: WarbandRules = WarbandRules(),
    ) = ResourceVector(
        sustenance = livingThreat.coerceAtLeast(0.0) * rules.sustenancePerThreatChunk,
        munitions = rangedThreat.coerceAtLeast(0.0) * rules.munitionsPerRangedThreatChunk,
        maintenance = equippedMembers.coerceAtLeast(0) * rules.maintenancePerEquipmentChunk * (0.5 + environment.bounded().travelFriction),
        recovery = totalInjury.coerceAtLeast(0.0) * 0.05,
    )

    fun consumeCargo(
        cargo: List<MutableMap<String, Int>>,
        resources: Map<String, ResourceDefinition>,
        demand: ResourceVector,
    ): ResourceConsumption {
        var remaining = demand.positive()
        val consumed = linkedMapOf<String, Int>()
        while (remaining.sum() > 1.0e-9) {
            val choice = cargo.asSequence().flatMap { manifest ->
                manifest.asSequence().filter { it.value > 0 }.map { Triple(manifest, it.key, it.value) }
            }.mapNotNull { (manifest, id, _) -> resources[id]?.let { Triple(manifest, id, it) } }
                .maxWithOrNull(compareBy<Triple<MutableMap<String, Int>, String, ResourceDefinition>> {
                    it.third.unitsPerItem.dot(remaining) / it.third.mass
                }.thenByDescending { it.second }) ?: break
            if (choice.third.unitsPerItem.dot(remaining) <= 1.0e-9) break
            choice.first[choice.second] = choice.first.getValue(choice.second) - 1
            if (choice.first.getValue(choice.second) == 0) choice.first.remove(choice.second)
            consumed[choice.second] = consumed.getOrDefault(choice.second, 0) + 1
            remaining = (remaining - choice.third.unitsPerItem).positive()
        }
        return ResourceConsumption(remaining, consumed)
    }

    fun supplySatisfaction(demand: ResourceVector, remaining: ResourceVector): Double =
        if (demand.sum() <= 1.0e-9) 1.0 else (1.0 - remaining.sum() / demand.sum()).coerceIn(0.0, 1.0)

    fun equipmentWear(environment: EnvironmentTraits, satisfaction: Double, rules: WarbandRules = WarbandRules()): Double =
        rules.equipmentWearPerFrictionChunk * environment.bounded().travelFriction * (2.0 - satisfaction.coerceIn(0.0, 1.0))

    fun attritionLoss(environment: EnvironmentTraits, satisfaction: Double, deficitExposure: Double, rules: WarbandRules = WarbandRules()): Double =
        if (deficitExposure <= rules.deficitGraceChunks) 0.0 else
            rules.attritionPerDeficitChunk * (1.0 - satisfaction.coerceIn(0.0, 1.0)) * (0.5 + environment.bounded().travelFriction)

    fun shouldRetreatFromShortage(deficitExposure: Double, aggression: Int, rules: WarbandRules = WarbandRules()): Boolean {
        val aggressionNormalized = (aggression - rules.minimumAggression).toDouble() /
            (rules.maximumAggression - rules.minimumAggression).coerceAtLeast(1)
        return deficitExposure > rules.deficitGraceChunks + 1.0 + aggressionNormalized.coerceIn(0.0, 1.0) * 3.0
    }
}
