package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.core.SurfaceCell
import com.bettercontent.pillagercampaigns.core.SurfaceGridObservation
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object SurfaceGridSampler {
    private data class Cursor(
        val invasionId: String,
        var centerX: Int,
        var centerZ: Int,
        var index: Int = 0,
        val cells: MutableMap<Pair<Int, Int>, SurfaceCell> = linkedMapOf(),
    )
    private val cursors = ConcurrentHashMap<UUID, Cursor>()
    private val offsetCache = ConcurrentHashMap<Int, List<Pair<Int, Int>>>()

    fun sample(level: ServerLevel, player: ServerPlayer, invasionId: String, radius: Int, budget: Int): SurfaceGridObservation {
        val cursor = cursors.compute(player.uuid) { _, existing ->
            existing?.takeIf {
                it.invasionId == invasionId && abs(it.centerX - player.blockX) <= 16 && abs(it.centerZ - player.blockZ) <= 16
            } ?: Cursor(invasionId, player.blockX, player.blockZ)
        }!!
        val offsets = offsetCache.computeIfAbsent(radius.coerceAtLeast(1), ::orderedOffsets)
        var inspected = 0
        while (inspected < budget && cursor.index < offsets.size) {
            val (dx, dz) = offsets[cursor.index++]
            inspected++
            val x = cursor.centerX + dx
            val z = cursor.centerZ + dz
            val key = x to z
            val cell = surfaceCell(level, x, z)
            if (cell == null) cursor.cells.remove(key) else cursor.cells[key] = cell
        }
        if (cursor.index < offsets.size) return SurfaceGridObservation(player.uuid.toString(), emptyList(), complete = false)
        val complete = cursor.cells.values.toList()
        cursor.index = 0
        cursor.cells.clear()
        return SurfaceGridObservation(player.uuid.toString(), complete, complete = true)
    }

    fun isNearSurface(player: ServerPlayer): Boolean {
        val level = player.serverLevel()
        val cell = surfaceCell(level, player.blockX, player.blockZ) ?: return false
        return player.blockY >= cell.bodyY - 8
    }

    fun memberPositions(level: ServerLevel, anchor: BlockPos, count: Int): List<BlockPos> {
        val positions = mutableListOf<BlockPos>()
        memberOffsets(5).forEach { (dx, dz) ->
            if (positions.size >= count) return@forEach
            val x = anchor.x + dx
            val z = anchor.z + dz
            (1 downTo -2).asSequence().map { dy -> BlockPos(x, anchor.y + dy, z) }
                .firstOrNull { exactColumn(level, it) }
                ?.let(positions::add)
        }
        return positions.distinct().takeIf { it.size == count }.orEmpty()
    }

    private fun exactColumn(level: ServerLevel, body: BlockPos): Boolean {
        if (level.chunkSource.getChunkNow(body.x shr 4, body.z shr 4) == null) return false
        val floor = body.below()
        val floorState = level.getBlockState(floor)
        return floorState.fluidState.isEmpty && floorState.isSolidRender(level, floor) &&
            level.getFluidState(body).isEmpty && level.getFluidState(body.above()).isEmpty &&
            level.getBlockState(body).getCollisionShape(level, body).isEmpty &&
            level.getBlockState(body.above()).getCollisionShape(level, body.above()).isEmpty
    }

    fun loadedRectangle(level: ServerLevel, first: BlockPos, second: BlockPos, margin: Int = 6): Boolean {
        val minChunkX = (minOf(first.x, second.x) - margin) shr 4
        val maxChunkX = (maxOf(first.x, second.x) + margin) shr 4
        val minChunkZ = (minOf(first.z, second.z) - margin) shr 4
        val maxChunkZ = (maxOf(first.z, second.z) + margin) shr 4
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return false
        }
        return true
    }

    internal fun surfaceCell(level: ServerLevel, x: Int, z: Int): SurfaceCell? {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        return surfaceCell(level, chunk, x, z)
    }

    internal fun surfaceCell(level: ServerLevel, chunk: LevelChunk, x: Int, z: Int): SurfaceCell {
        val bodyY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
        val body = BlockPos(x, bodyY, z)
        val floor = body.below()
        val floorState = chunk.getBlockState(floor)
        val bodyState = chunk.getBlockState(body)
        val headState = chunk.getBlockState(body.above())
        val hazardous = floorState.`is`(Blocks.CACTUS) || floorState.`is`(Blocks.MAGMA_BLOCK) ||
            floorState.`is`(Blocks.CAMPFIRE) || floorState.`is`(Blocks.SOUL_CAMPFIRE) || floorState.`is`(Blocks.POWDER_SNOW)
        val passable = bodyY > level.minBuildHeight && bodyY + 1 < level.maxBuildHeight && !hazardous &&
            floorState.fluidState.isEmpty && floorState.isSolidRender(level, floor) &&
            bodyState.fluidState.isEmpty && headState.fluidState.isEmpty &&
            bodyState.getCollisionShape(level, body).isEmpty && headState.getCollisionShape(level, body.above()).isEmpty
        return SurfaceCell(x, bodyY, z, passable)
    }

    internal fun orderedOffsets(radius: Int): List<Pair<Int, Int>> {
        val r = radius.coerceAtLeast(1)
        val corridors = buildList {
            for (axis in -r..r) for (width in -2..2) {
                add(axis to width)
                add(width to axis)
            }
        }
        val remainder = (-r..r).flatMap { dx -> (-r..r).map { dz -> dx to dz } }
            .sortedWith(compareByDescending<Pair<Int, Int>> { maxOf(abs(it.first), abs(it.second)) }.thenBy { it.first }.thenBy { it.second })
        return (corridors + remainder).distinct()
    }

    internal fun memberOffsets(radius: Int): List<Pair<Int, Int>> = (0..radius.coerceAtLeast(0)).flatMap { ring ->
        (-ring..ring).flatMap { dx -> (-ring..ring).mapNotNull { dz ->
            (dx to dz).takeIf { maxOf(abs(dx), abs(dz)) == ring }
        } }
    }

    fun clear(playerId: UUID? = null) {
        if (playerId == null) cursors.clear() else cursors.remove(playerId)
    }
}
