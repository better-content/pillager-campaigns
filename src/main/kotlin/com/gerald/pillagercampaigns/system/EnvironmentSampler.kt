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
}
