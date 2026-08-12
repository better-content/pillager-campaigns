package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import java.nio.charset.StandardCharsets
import java.util.UUID

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
    )

    fun candidateForCell(seed: Long, dimension: ResourceLocation, cellX: Int, cellZ: Int, settings: Settings): Candidate? {
        val spacing = settings.spacingChunks.coerceAtLeast(1)
        val id = UUID.nameUUIDFromBytes("pillagercampaigns:warband:${dimension}:$cellX,$cellZ".toByteArray(StandardCharsets.UTF_8))
        return Candidate(id, dimension, cellX, cellZ, cellX * spacing, cellZ * spacing)
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

}
