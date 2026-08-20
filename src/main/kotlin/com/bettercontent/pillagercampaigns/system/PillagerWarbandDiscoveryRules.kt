package com.bettercontent.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

object PillagerWarbandDiscoveryRules {
    data class Settings(
        val spacingChunks: Int,
        val jitterChunks: Int,
        val spawnChancePercent: Int,
        val minSpawnDistanceChunks: Int,
    )

    data class Candidate(
        val id: UUID,
        val dimension: ResourceLocation,
        val cellX: Int,
        val cellZ: Int,
        val chunkX: Int,
        val chunkZ: Int,
        val siteId: String = "",
        val coveragePlayerId: String? = null,
    )

    fun candidateForCell(seed: Long, dimension: ResourceLocation, cellX: Int, cellZ: Int, settings: Settings): Candidate? {
        val spacing = settings.spacingChunks.coerceAtLeast(1)
        val jitter = settings.jitterChunks.coerceIn(0, spacing / 2)
        val cellSeed = mix(seed, dimension.toString().hashCode().toLong(), cellX.toLong(), cellZ.toLong())
        val centerX = cellX * spacing + spacing / 2
        val centerZ = cellZ * spacing + spacing / 2
        val jitterX = if (jitter == 0) 0 else positiveModulo(cellSeed ushr 17, jitter * 2 + 1) - jitter
        val jitterZ = if (jitter == 0) 0 else positiveModulo(cellSeed ushr 37, jitter * 2 + 1) - jitter
        val id = UUID.nameUUIDFromBytes("pillager_campaigns:warband:${dimension}:$cellX,$cellZ".toByteArray(StandardCharsets.UTF_8))
        return Candidate(id, dimension, cellX, cellZ, centerX + jitterX, centerZ + jitterZ)
    }

    /**
     * Supplies several unloaded-safe rally alternatives in dispatch range of an
     * uncovered player. Core still owns spacing, eligibility and acceptance.
     */
    fun coverageCandidates(
        seed: Long,
        dimension: ResourceLocation,
        playerId: String,
        playerChunkX: Int,
        playerChunkZ: Int,
        minimumDistanceChunks: Int,
        maximumDistanceChunks: Int,
    ): List<Candidate> {
        if (maximumDistanceChunks < minimumDistanceChunks || maximumDistanceChunks <= 0) return emptyList()
        val distance = maximumDistanceChunks.coerceAtLeast(minimumDistanceChunks).coerceAtLeast(1)
        val half = distance / 2
        val remainder = distance - half
        val offsets = listOf(
            distance to 0, -distance to 0, 0 to distance, 0 to -distance,
            half to remainder, half to -remainder, -half to remainder, -half to -remainder,
        )
        val rotation = positiveModulo(mix(seed, dimension.toString().hashCode().toLong(), playerChunkX.toLong(), playerChunkZ.toLong()), offsets.size)
        return offsets.indices.map { index ->
            val (dx, dz) = offsets[(index + rotation) % offsets.size]
            val chunkX = playerChunkX + dx
            val chunkZ = playerChunkZ + dz
            val siteId = "pillager_campaigns:coverage:${dimension}:$playerId:$playerChunkX,$playerChunkZ:$index"
            Candidate(
                UUID.nameUUIDFromBytes(siteId.toByteArray(StandardCharsets.UTF_8)),
                dimension,
                Math.floorDiv(chunkX, maximumDistanceChunks.coerceAtLeast(1)),
                Math.floorDiv(chunkZ, maximumDistanceChunks.coerceAtLeast(1)),
                chunkX,
                chunkZ,
                siteId,
                playerId,
            )
        }
    }

    fun cellsAround(chunkX: Int, chunkZ: Int, radiusChunks: Int, spacingChunks: Int): Sequence<Pair<Int, Int>> = sequence {
        val spacing = spacingChunks.coerceAtLeast(1)
        val radiusCells = (radiusChunks.coerceAtLeast(0) + spacing - 1) / spacing + 1
        val centerCellX = Math.floorDiv(chunkX, spacing)
        val centerCellZ = Math.floorDiv(chunkZ, spacing)
        for (dz in -radiusCells..radiusCells) {
            for (dx in -radiusCells..radiusCells) {
                yield(centerCellX + dx to centerCellZ + dz)
            }
        }
    }

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
