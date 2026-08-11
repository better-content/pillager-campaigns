package com.gerald.warband.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcologyMathTest {
    @Test fun `resource vectors preserve each independent requirement`() {
        val first = ResourceVector(2.0, 3.0, 5.0, 7.0)
        val second = ResourceVector(1.0, 1.5, 2.5, 3.5)
        assertEquals(ResourceVector(3.0, 4.5, 7.5, 10.5), first + second)
        assertEquals(second, first - second)
        assertEquals(ResourceVector(4.0, 6.0, 10.0, 14.0), first * 2.0)
        assertEquals(43.5, first.dot(second))
        assertEquals(17.0, first.sum())
        assertEquals(ResourceVector(0.0, 2.0, 0.0, 4.0), ResourceVector(-1.0, 2.0, -3.0, 4.0).positive())
        assertTrue(first.finite())
        assertTrue(!ResourceVector(sustenance = Double.NaN).finite())
    }

    @Test fun `environment selects resources from measured affinities`() {
        val berries = ResourceDefinition(
            "berries", ResourceVector(sustenance = 1.0),
            environmentalAffinity = EnvironmentTraits(habitability = 0.3, biomass = 1.0, mineralPotential = 0.0, exoticPotential = 0.0, travelFriction = 0.0),
        )
        val ore = ResourceDefinition(
            "ore", ResourceVector(maintenance = 1.0),
            environmentalAffinity = EnvironmentTraits(habitability = 0.0, biomass = 0.0, mineralPotential = 1.0, exoticPotential = 0.2, travelFriction = 0.0),
        )
        val forest = EnvironmentTraits(habitability = 0.8, biomass = 1.0, mineralPotential = 0.1, exoticPotential = 0.0)
        val mountain = EnvironmentTraits(habitability = 0.1, biomass = 0.0, mineralPotential = 1.0, exoticPotential = 0.5)
        assertTrue(EcologyMath.environmentalYield(berries, forest) > EcologyMath.environmentalYield(ore, forest))
        assertTrue(EcologyMath.environmentalYield(ore, mountain) > EcologyMath.environmentalYield(berries, mountain))
        assertEquals("berries", EcologyMath.chooseEnvironmentalResource(listOf(ore, berries), forest, emptyMap())?.itemId)
        assertEquals("ore", EcologyMath.chooseEnvironmentalResource(listOf(ore, berries), forest, mapOf("berries" to 100))?.itemId)
    }

    @Test fun `shared segment formulas consume exact manifests and delay attrition`() {
        val rules = CoreRules(
            sustenancePerThreatChunk = 0.1, munitionsPerRangedThreatChunk = 0.2,
            maintenancePerEquipmentChunk = 0.4, deficitGraceChunks = 2.0,
            equipmentWearPerFrictionChunk = 0.01, attritionPerDeficitChunk = 0.1,
        )
        val environment = EnvironmentTraits(travelFriction = 0.5)
        val demand = EcologyMath.segmentDemand(10.0, 5.0, 2, 1.0, environment, rules)
        assertEquals(ResourceVector(1.0, 1.0, 0.8, 0.05), demand)
        val first = linkedMapOf("meal" to 1)
        val second = linkedMapOf("bundle" to 1)
        val resources = mapOf(
            "meal" to ResourceDefinition("meal", ResourceVector(sustenance = 1.0)),
            "bundle" to ResourceDefinition("bundle", ResourceVector(munitions = 1.0, maintenance = 1.0)),
        )
        val result = EcologyMath.consumeCargo(listOf(first, second), resources, demand)
        assertEquals(mapOf("meal" to 1, "bundle" to 1), result.items)
        assertTrue(first.isEmpty() && second.isEmpty())
        val satisfaction = EcologyMath.supplySatisfaction(demand, result.remaining)
        assertTrue(satisfaction in 0.9..1.0)
        assertEquals(0.0075, EcologyMath.equipmentWear(environment, 0.5, rules), 1.0e-9)
        assertEquals(0.0, EcologyMath.attritionLoss(environment, 0.0, 2.0, rules))
        assertEquals(0.1, EcologyMath.attritionLoss(environment, 0.0, 3.0, rules), 1.0e-9)
        assertEquals(1.0, EcologyMath.supplySatisfaction(ResourceVector(), ResourceVector()))
        assertTrue(!EcologyMath.shouldRetreatFromShortage(3.0, rules.minimumAggression, rules))
        assertTrue(EcologyMath.shouldRetreatFromShortage(8.1, rules.minimumAggression, rules))
        assertTrue(!EcologyMath.shouldRetreatFromShortage(25.9, rules.maximumAggression, rules))
    }

    @Test fun `selection memory decays continuously and remains bounded`() {
        val memory = SelectionMemory(recruits = linkedMapOf("frequent" to 8.0, "rare" to 2.0))
        assertEquals(0.28, EcologyMath.repetitionPenalty(memory.recruits, "frequent", 0.63), 1.0e-9)
        assertEquals(0.0, EcologyMath.repetitionPenalty(emptyMap(), "frequent", 0.63))
        EcologyMath.decay(memory, 100L, 100L)
        assertEquals(4.0, memory.recruits.getValue("frequent"), 1.0e-9)
        assertEquals(1.0, memory.recruits.getValue("rare"), 1.0e-9)
        EcologyMath.decay(memory, 100L, 100L)
        assertEquals(4.0, memory.recruits.getValue("frequent"), 1.0e-9)
        memory.materials["trace"] = 1.0e-7
        EcologyMath.decay(memory, 200L, 100L)
        assertTrue("trace" !in memory.materials)
    }

    @Test fun `route selection trades distance against forage and friction`() {
        fun point(id: Int, traits: EnvironmentTraits) = TerrainObservation(ChunkPosition("overworld", id, 0), traits)
        val rough = listOf(point(1, EnvironmentTraits(biomass = 0.0, mineralPotential = 0.0, travelFriction = 1.0)))
        val supplied = listOf(
            point(2, EnvironmentTraits(biomass = 1.0, mineralPotential = 0.7, travelFriction = 0.0)),
            point(3, EnvironmentTraits(biomass = 1.0, mineralPotential = 0.7, travelFriction = 0.0)),
        )
        assertTrue(EcologyMath.routeCost(rough, CapabilityVector(), 6).isFinite())
        assertEquals(supplied.map(TerrainObservation::position), EcologyMath.chooseRoute(listOf(rough, supplied), CapabilityVector(durability = 1.0), 6))
        assertTrue(EcologyMath.routeCost(emptyList(), CapabilityVector(), 6).isInfinite())
        assertTrue(EcologyMath.chooseRoute(emptyList(), CapabilityVector(), 6).isEmpty())
    }

    @Test fun `tactical utility balances range cover flanking and cohesion`() {
        val exposed = TacticalPosition("exposed", ChunkPosition("overworld", 0, 0), 1.0, 15.0, nearestAllyDistance = 3.0)
        val covered = TacticalPosition("covered", ChunkPosition("overworld", 1, 0), 2.0, 15.0, elevation = 0.5, cover = 1.0, flank = 0.5, nearestAllyDistance = 3.0)
        val unreachable = covered.copy(id = "blocked", reachable = false)
        val capabilities = CapabilityVector(range = 1.0)
        val preferences = CapabilityVector(durability = 1.0, mobility = 0.6, range = 1.0)
        assertTrue(EcologyMath.tacticalScore(covered, capabilities, preferences, 6.0) > EcologyMath.tacticalScore(exposed, capabilities, preferences, 6.0))
        assertEquals(covered, EcologyMath.chooseTacticalPosition(listOf(exposed, unreachable, covered), capabilities, preferences, 6.0))
        assertNull(EcologyMath.chooseTacticalPosition(listOf(unreachable), capabilities, preferences, 6.0))
    }
}
