package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

object PillagerBasePlacementRules {
    data class Settings(
        val spacingChunks: Int,
        val jitterChunks: Int,
        val spawnChancePercent: Int,
        val minSpawnDistanceChunks: Int,
        val structureIds: List<ResourceLocation>,
    )

    data class Candidate(
        val id: UUID,
        val dimension: ResourceLocation,
        val structureId: ResourceLocation,
        val cellX: Int,
        val cellZ: Int,
        val chunkX: Int,
        val chunkZ: Int,
    )

    fun candidateForCell(seed: Long, dimension: ResourceLocation, cellX: Int, cellZ: Int, settings: Settings): Candidate? {
        val structures = settings.structureIds.ifEmpty { listOf(ResourceLocation("minecraft", "pillager_outpost")) }
        val spacing = settings.spacingChunks.coerceAtLeast(1)
        val jitter = settings.jitterChunks.coerceAtLeast(0).coerceAtMost(spacing / 2)
        val chance = settings.spawnChancePercent.coerceIn(1, 100)
        val cellSeed = mix(seed, dimension.toString().hashCode().toLong(), cellX.toLong(), cellZ.toLong())

        if (positiveModulo(cellSeed, 100) >= chance) return null

        val centerX = cellX * spacing + spacing / 2
        val centerZ = cellZ * spacing + spacing / 2
        val jitterX = if (jitter == 0) 0 else positiveModulo(cellSeed ushr 17, jitter * 2 + 1) - jitter
        val jitterZ = if (jitter == 0) 0 else positiveModulo(cellSeed ushr 37, jitter * 2 + 1) - jitter
        val chunkX = centerX + jitterX
        val chunkZ = centerZ + jitterZ

        if (CampaignMath.manhattan(0, 0, chunkX, chunkZ) < settings.minSpawnDistanceChunks) return null

        val structureId = structures[positiveModulo(cellSeed ushr 51, structures.size)]
        val id = UUID.nameUUIDFromBytes("pillagercampaigns:base:${dimension}:$cellX,$cellZ".toByteArray(StandardCharsets.UTF_8))
        return Candidate(id, dimension, structureId, cellX, cellZ, chunkX, chunkZ)
    }

    fun cellsAround(chunkX: Int, chunkZ: Int, radiusChunks: Int, spacingChunks: Int): Sequence<Pair<Int, Int>> = sequence {
        val spacing = spacingChunks.coerceAtLeast(1)
        val radiusCells = (radiusChunks.coerceAtLeast(0) + spacing - 1) / spacing + 1
        val centerCellX = floorDiv(chunkX, spacing)
        val centerCellZ = floorDiv(chunkZ, spacing)
        for (dz in -radiusCells..radiusCells) {
            for (dx in -radiusCells..radiusCells) {
                yield(centerCellX + dx to centerCellZ + dz)
            }
        }
    }

    private fun floorDiv(value: Int, divisor: Int): Int = Math.floorDiv(value, divisor)

    private fun positiveModulo(value: Long, modulus: Int): Int {
        val raw = (value and Long.MAX_VALUE) % modulus
        return abs(raw.toInt())
    }

    private fun mix(seed: Long, dimensionHash: Long, cellX: Long, cellZ: Long): Long {
        var value = seed xor 0x9E3779B97F4A7C15uL.toLong()
        value = value xor (dimensionHash * 0xBF58476D1CE4E5B9uL.toLong())
        value = value xor (cellX * 0x94D049BB133111EBuL.toLong())
        value = value xor (cellZ * 0xD6E8FEB86659FD93uL.toLong())
        value = (value xor (value ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        value = (value xor (value ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return value xor (value ushr 31)
    }
}
