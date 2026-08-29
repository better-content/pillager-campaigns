package com.bettercontent.pillagercampaigns.core

import java.util.PriorityQueue
import kotlin.math.abs

/** Deterministic routing over recorded terrain only. Missing cells are never inferred or generated. */
object StrategicRoutePlanner {
    data class Result(val route: List<SurfaceCell>, val frontier: StrategicFrontier)

    fun plan(
        cells: Collection<SurfaceCell>, target: BlockPoint, rules: InvasionRules, seed: Long,
        start: BlockPoint? = null,
    ): Result? {
        val known = cells.associateBy { it.x to it.z }
        val passable = known.values.filter(SurfaceCell::passable).associateBy { it.x to it.z }
        val origins = if (start != null) {
            listOfNotNull(passable[start.x to start.z])
        } else passable.values.filter {
            distance(it, target) in rules.strategicOriginMinimumBlocks..rules.strategicOriginMaximumBlocks
        }.sortedWith(compareBy<SurfaceCell> { stableRank(it, seed) }.thenBy { it.x }.thenBy { it.z })
        for (origin in origins) {
            data class Node(val cell: SurfaceCell, val steps: Int)
            val queue = PriorityQueue(compareBy<Node> { it.steps + manhattan(it.cell, target) }
                .thenBy { manhattan(it.cell, target) }.thenBy { stableRank(it.cell, seed) })
            val previous = hashMapOf<Pair<Int, Int>, Pair<Int, Int>?>()
            val originKey = origin.x to origin.z
            queue += Node(origin, 0)
            previous[originKey] = null
            var expansions = 0
            var best = origin
            while (queue.isNotEmpty() && expansions++ < rules.strategicMaximumSearchExpansions) {
                val node = queue.remove()
                val cell = node.cell
                if (distance(cell, target) < distance(best, target)) best = cell
                if (distance(cell, target) <= rules.approachTargetRadiusBlocks) {
                    val full = reconstruct(cell, previous, passable)
                    val materializationIndex = full.indexOfFirst { distance(it, target) <= rules.approachMaximumBlocks }
                    if (materializationIndex >= 0) return Result(full.take(materializationIndex + 1), StrategicFrontier.OPEN)
                }
                CARDINALS.forEach { (dx, dz) ->
                    val key = cell.x + dx to cell.z + dz
                    val candidate = known[key]
                    if (candidate != null && candidate.passable && candidate.bodyY - cell.bodyY in -2..1 &&
                        !previous.containsKey(key)) {
                        previous[key] = cell.x to cell.z
                        queue += Node(candidate, node.steps + 1)
                    }
                }
            }
            val closer = CARDINALS.map { (dx, dz) -> known[best.x + dx to best.z + dz] }
                .filter { it == null || distance(it, target) < distance(best, target) }
            val frontier = when {
                closer.any { it == null } -> StrategicFrontier.UNKNOWN
                closer.any { it != null && (!it.passable || it.bodyY - best.bodyY !in -2..1) } -> StrategicFrontier.DEFENSE
                else -> StrategicFrontier.UNKNOWN
            }
            if (frontier == StrategicFrontier.DEFENSE && distance(best, target) <= rules.approachMaximumBlocks) {
                return Result(reconstruct(best, previous, passable), frontier)
            }
        }
        return null
    }

    private fun reconstruct(
        end: SurfaceCell,
        previous: Map<Pair<Int, Int>, Pair<Int, Int>?>,
        passable: Map<Pair<Int, Int>, SurfaceCell>,
    ): List<SurfaceCell> {
        val result = mutableListOf<SurfaceCell>()
        var cursor: Pair<Int, Int>? = end.x to end.z
        while (cursor != null) {
            result += passable.getValue(cursor)
            cursor = previous[cursor]
        }
        return result.asReversed()
    }

    private fun distance(cell: SurfaceCell, target: BlockPoint) = maxOf(abs(cell.x - target.x), abs(cell.z - target.z))
    private fun manhattan(cell: SurfaceCell, target: BlockPoint) = abs(cell.x - target.x) + abs(cell.z - target.z)
    private fun stableRank(cell: SurfaceCell, seed: Long) =
        (seed xor (cell.x.toLong() shl 32) xor cell.z.toLong()) * -7046029254386353131L
    private val CARDINALS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
}
