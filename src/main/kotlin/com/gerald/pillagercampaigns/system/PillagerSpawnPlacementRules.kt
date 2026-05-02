package com.gerald.pillagercampaigns.system

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

object PillagerSpawnPlacementRules {
    fun findMaterializationPos(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): BlockPos? {
        val playerChunkX = player.chunkPosition().x
        val playerChunkZ = player.chunkPosition().z
        val dirX = (playerChunkX - originChunkX).sign()
        val dirZ = (playerChunkZ - originChunkZ).sign()
        val desiredChunkX = playerChunkX - (dirX * distanceChunks)
        val desiredChunkZ = playerChunkZ - (dirZ * distanceChunks)
        return nearestSafePosInLoadedChunks(level, desiredChunkX, desiredChunkZ)
    }

    private fun nearestSafePosInLoadedChunks(level: ServerLevel, startChunkX: Int, startChunkZ: Int): BlockPos? {
        for (ring in 0..4) {
            for (dx in -ring..ring) {
                for (dz in -ring..ring) {
                    val x = startChunkX + dx
                    val z = startChunkZ + dz
                    if (!level.hasChunk(x, z)) continue
                    val pos = chunkCenterSurface(level, x, z) ?: continue
                    if (isWater(level, pos)) continue
                    return pos
                }
            }
        }
        return null
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
