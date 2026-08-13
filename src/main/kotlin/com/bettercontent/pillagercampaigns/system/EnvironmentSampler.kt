package com.bettercontent.pillagercampaigns.system

import com.gerald.warband.core.EnvironmentTraits
import com.gerald.warband.core.EnvironmentModelDefinition
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome

/** Samples the noise-biome source directly, so discovery never requests or generates a chunk. */
object EnvironmentSampler {
    fun sample(level: ServerLevel, chunkX: Int, chunkZ: Int, model: EnvironmentModelDefinition): EnvironmentTraits {
        var h = .5; var b = .5; var m = .5; var e = .5; var f = .5
        val radius = model.sampleRadius.coerceAtLeast(0)
        val divisor = ((radius * 2 + 1) * (radius * 2 + 1)).toDouble()
        for (dz in -radius..radius) for (dx in -radius..radius) {
            val blockX = ((chunkX + dx * model.sampleStrideChunks) shl 4) + 8
            val blockZ = ((chunkZ + dz * model.sampleStrideChunks) shl 4) + 8
            val holder = level.getUncachedNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(level.seaLevel), QuartPos.fromBlock(blockZ))
            model.traitWeights.forEach { (name, delta) ->
                if (holder.`is`(TagKey.create(Registries.BIOME, ResourceLocation("pillager_campaigns", "warband/$name")))) {
                    h += delta.habitability / divisor; b += delta.biomass / divisor
                    m += delta.mineralPotential / divisor; e += delta.exoticPotential / divisor
                    f += delta.travelFriction / divisor
                }
            }
        }
        return EnvironmentTraits(h, b, m, e, f).bounded()
    }

    /** Samples every formulaic route candidate directly from biome noise; no chunk is requested. */
    fun corridor(level: ServerLevel, startX: Int, startZ: Int, targetX: Int, targetZ: Int, model: EnvironmentModelDefinition): List<com.gerald.warband.core.TerrainObservation> {
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
            val traits = samplePoint(level, chunkX, chunkZ, model)
            com.gerald.warband.core.TerrainObservation(
                com.gerald.warband.core.ChunkPosition(level.dimension().location().toString(), chunkX, chunkZ),
                com.gerald.warband.core.EnvironmentTraits(
                    traits.habitability, traits.biomass, traits.mineralPotential, traits.exoticPotential, traits.travelFriction,
                ),
            )
        }
    }

    private fun samplePoint(level: ServerLevel, chunkX: Int, chunkZ: Int, model: EnvironmentModelDefinition): EnvironmentTraits {
        var h = .5; var b = .5; var m = .5; var e = .5; var f = .5
        val holder = level.getUncachedNoiseBiome(
            QuartPos.fromBlock((chunkX shl 4) + 8), QuartPos.fromBlock(level.seaLevel), QuartPos.fromBlock((chunkZ shl 4) + 8),
        )
        model.traitWeights.forEach { (name, delta) ->
            if (holder.`is`(TagKey.create(Registries.BIOME, ResourceLocation("pillager_campaigns", "warband/$name")))) {
                h += delta.habitability; b += delta.biomass; m += delta.mineralPotential
                e += delta.exoticPotential; f += delta.travelFriction
            }
        }
        return EnvironmentTraits(h, b, m, e, f).bounded()
    }
}
