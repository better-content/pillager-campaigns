package com.gerald.pillagercampaigns.system

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

object PillagerSpawnPlacementRules {
    data class MaterializationSite(val pos: BlockPos, val overWater: Boolean)

    fun findMaterializationPos(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): BlockPos? {
        return findMaterializationSite(level, player, originChunkX, originChunkZ, distanceChunks)
            ?.takeUnless { it.overWater }
            ?.pos
    }

    fun findMaterializationSite(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): MaterializationSite? {
        val playerChunkX = player.chunkPosition().x
        val playerChunkZ = player.chunkPosition().z
        val dirX = (playerChunkX - originChunkX).sign()
        val dirZ = (playerChunkZ - originChunkZ).sign()
        val desiredChunkX = playerChunkX - (dirX * distanceChunks)
        val desiredChunkZ = playerChunkZ - (dirZ * distanceChunks)
        return nearestSafeSiteInLoadedChunks(level, desiredChunkX, desiredChunkZ, allowWater = true)
    }

    fun findRallyPos(level: ServerLevel, rallyChunkX: Int, rallyChunkZ: Int): BlockPos? =
        nearestSafePosInLoadedChunks(level, rallyChunkX, rallyChunkZ)

    private fun nearestSafePosInLoadedChunks(level: ServerLevel, startChunkX: Int, startChunkZ: Int): BlockPos? {
        return nearestSafeSiteInLoadedChunks(level, startChunkX, startChunkZ, allowWater = false)?.pos
    }

    private fun nearestSafeSiteInLoadedChunks(level: ServerLevel, startChunkX: Int, startChunkZ: Int, allowWater: Boolean): MaterializationSite? {
        var waterFallback: MaterializationSite? = null
        for (ring in 0..4) {
            for (dx in -ring..ring) {
                for (dz in -ring..ring) {
                    val x = startChunkX + dx
                    val z = startChunkZ + dz
                    if (!level.hasChunk(x, z)) continue
                    val pos = chunkCenterSurface(level, x, z) ?: continue
                    val water = isWater(level, pos)
                    if (!water) return MaterializationSite(pos, overWater = false)
                    if (allowWater && waterFallback == null) {
                        waterFallback = MaterializationSite(pos, overWater = true)
                    }
                }
            }
        }
        return waterFallback
    }

    private fun chunkCenterSurface(level: ServerLevel, chunkX: Int, chunkZ: Int): BlockPos? {
        val x = chunkX * 16 + 8
        val z = chunkZ * 16 + 8
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        if (y <= level.minBuildHeight) return null
        return BlockPos(x, y, z)
    }

    private fun isWater(level: ServerLevel, pos: BlockPos): Boolean {
        val below = pos.below()
        val state: BlockState = level.getBlockState(below)
        return state.fluidState.isSource
    }
}

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}
