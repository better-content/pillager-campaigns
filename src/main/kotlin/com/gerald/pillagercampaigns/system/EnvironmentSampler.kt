package com.gerald.pillagercampaigns.system

import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome

/** Samples the noise-biome source directly, so discovery never requests or generates a chunk. */
object EnvironmentSampler {
    fun sample(level: ServerLevel, chunkX: Int, chunkZ: Int): EnvironmentTraits {
        var h = .5; var b = .5; var m = .5; var e = .5; var f = .5
        val divisor = 49.0
        for (dz in -3..3) for (dx in -3..3) {
            val blockX = ((chunkX + dx * 2) shl 4) + 8
            val blockZ = ((chunkZ + dz * 2) shl 4) + 8
            val holder = level.getUncachedNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(level.seaLevel), QuartPos.fromBlock(blockZ))
            WarbandFormulaData.traitWeights.forEach { (name, delta) ->
                if (holder.`is`(TagKey.create(Registries.BIOME, ResourceLocation("pillagercampaigns", "warband/$name")))) {
                    h += delta.habitability / divisor; b += delta.biomass / divisor; m += delta.mineral / divisor; e += delta.exotic / divisor; f += delta.friction / divisor
                }
            }
        }
        return EnvironmentTraits(h, b, m, e, f).bounded()
    }

    /** Samples every formulaic route candidate directly from biome noise; no chunk is requested. */
    fun corridor(level: ServerLevel, startX: Int, startZ: Int, targetX: Int, targetZ: Int): List<com.gerald.pillagercampaigns.engine.TerrainObservation> {
        val points = linkedSetOf<Pair<Int, Int>>()
        var x = startX
        var z = startZ
        while (x != targetX || z != targetZ) {
            val next = CampaignMath.stepToward(x, z, targetX, targetZ)
            x = next.first; z = next.second; points += x to z
        }
        x = startX
        while (x != targetX) { x += if (targetX > x) 1 else -1; points += x to startZ }
        z = startZ
        while (z != targetZ) { z += if (targetZ > z) 1 else -1; points += targetX to z }
        z = startZ
        while (z != targetZ) { z += if (targetZ > z) 1 else -1; points += startX to z }
        x = startX
        while (x != targetX) { x += if (targetX > x) 1 else -1; points += x to targetZ }
        return points.map { (chunkX, chunkZ) ->
            val traits = samplePoint(level, chunkX, chunkZ)
            com.gerald.pillagercampaigns.engine.TerrainObservation(
                com.gerald.pillagercampaigns.engine.ChunkPosition(level.dimension().location().toString(), chunkX, chunkZ),
                com.gerald.pillagercampaigns.engine.EnvironmentTraits(
                    traits.habitability, traits.biomass, traits.mineralPotential, traits.exoticPotential, traits.travelFriction,
                ),
            )
        }
    }

    private fun samplePoint(level: ServerLevel, chunkX: Int, chunkZ: Int): EnvironmentTraits {
        var h = .5; var b = .5; var m = .5; var e = .5; var f = .5
        val holder = level.getUncachedNoiseBiome(
            QuartPos.fromBlock((chunkX shl 4) + 8), QuartPos.fromBlock(level.seaLevel), QuartPos.fromBlock((chunkZ shl 4) + 8),
        )
        WarbandFormulaData.traitWeights.forEach { (name, delta) ->
            if (holder.`is`(TagKey.create(Registries.BIOME, ResourceLocation("pillagercampaigns", "warband/$name")))) {
                h += delta.habitability; b += delta.biomass; m += delta.mineral; e += delta.exotic; f += delta.friction
            }
        }
        return EnvironmentTraits(h, b, m, e, f).bounded()
    }
}
