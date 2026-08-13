package com.bettercontent.pillagercampaigns.system

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap

object PillagerSpawnPlacementRules {
    private const val LAND_SEARCH_RADIUS_CHUNKS = 3

    data class MaterializationSite(val pos: BlockPos)

    fun findMaterializationPos(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): BlockPos? {
        return findMaterializationSite(level, player, originChunkX, originChunkZ, distanceChunks)
            ?.pos
    }

    fun findMaterializationSite(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): MaterializationSite? {
        return findMaterializationSites(level, player, originChunkX, originChunkZ, distanceChunks).firstOrNull()
    }

    fun findMaterializationSites(level: ServerLevel, player: ServerPlayer, originChunkX: Int, originChunkZ: Int, distanceChunks: Int): List<MaterializationSite> {
        val playerChunkX = player.chunkPosition().x
        val playerChunkZ = player.chunkPosition().z
        val dirX = (playerChunkX - originChunkX).sign()
        val dirZ = (playerChunkZ - originChunkZ).sign()
        val desiredChunkX = playerChunkX - (dirX * distanceChunks)
        val desiredChunkZ = playerChunkZ - (dirZ * distanceChunks)
        return deterministicDrySitesInLoadedChunks(level, desiredChunkX, desiredChunkZ, LAND_SEARCH_RADIUS_CHUNKS)
    }

    fun findRallyPos(level: ServerLevel, rallyChunkX: Int, rallyChunkZ: Int): BlockPos? =
        deterministicDrySiteInLoadedChunks(level, rallyChunkX, rallyChunkZ, LAND_SEARCH_RADIUS_CHUNKS)?.pos

    fun findRallyCandidates(level: ServerLevel, rallyChunkX: Int, rallyChunkZ: Int): List<BlockPos> =
        deterministicDrySitesInLoadedChunks(level, rallyChunkX, rallyChunkZ, LAND_SEARCH_RADIUS_CHUNKS).map(MaterializationSite::pos)

    private fun deterministicDrySiteInLoadedChunks(level: ServerLevel, startChunkX: Int, startChunkZ: Int, radiusChunks: Int): MaterializationSite? {
        return deterministicDrySitesInLoadedChunks(level, startChunkX, startChunkZ, radiusChunks).firstOrNull()
    }

    private fun deterministicDrySitesInLoadedChunks(level: ServerLevel, startChunkX: Int, startChunkZ: Int, radiusChunks: Int): List<MaterializationSite> {
        val sites = mutableListOf<MaterializationSite>()
        deterministicChunkOffsets(radiusChunks).forEach { (dx, dz) ->
            val x = startChunkX + dx
            val z = startChunkZ + dz
            val chunk = level.chunkSource.getChunkNow(x, z) ?: return@forEach
            val pos = deterministicDrySurface(level, chunk, x, z) ?: return@forEach
            sites += MaterializationSite(pos)
        }
        return sites
    }

    internal fun deterministicChunkOffsets(radiusChunks: Int): List<Pair<Int, Int>> {
        val radius = radiusChunks.coerceAtLeast(0)
        return (-radius..radius).flatMap { dx ->
            (-radius..radius).map { dz -> dx to dz }
        }
    }

    private fun deterministicDrySurface(level: ServerLevel, chunk: LevelChunk, chunkX: Int, chunkZ: Int): BlockPos? {
        deterministicInChunkOffsets().forEach { (localX, localZ) ->
            val x = chunkX * 16 + localX
            val z = chunkZ * 16 + localZ
            // Heightmaps already return the first free block above the surface. Adding one here
            // materialized officers a full block in the air and the anchor logic kept them there.
            val y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ)
            if (y <= level.minBuildHeight || y >= level.maxBuildHeight) return@forEach
            val pos = BlockPos(x, y, z)
            if (isDryLandSpawn(level, chunk, pos)) return pos
        }
        return null
    }

    private fun deterministicInChunkOffsets(): List<Pair<Int, Int>> = listOf(
        8 to 8,
        4 to 4,
        12 to 4,
        4 to 12,
        12 to 12,
        8 to 4,
        4 to 8,
        12 to 8,
        8 to 12,
    )

    private fun isDryLandSpawn(level: ServerLevel, chunk: LevelChunk, pos: BlockPos): Boolean {
        val below = pos.below()
        val floor: BlockState = chunk.getBlockState(below)
        if (!floor.fluidState.isEmpty) return false
        if (!floor.isSolidRender(level, below)) return false
        val body = level.getBlockState(pos)
        val head = level.getBlockState(pos.above())
        return body.fluidState.isEmpty &&
            head.fluidState.isEmpty &&
            body.getCollisionShape(level, pos).isEmpty &&
            head.getCollisionShape(level, pos.above()).isEmpty
    }
}

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}
