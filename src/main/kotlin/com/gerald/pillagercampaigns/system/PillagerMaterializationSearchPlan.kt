package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerBase
import net.minecraft.core.BlockPos

internal object PillagerMaterializationSearchPlan {
    data class StepResult(
        val complete: Boolean,
        val inspectedChunks: Int,
        val scoredChunks: Int,
    )

    fun totalChunks(radius: Int): Int {
        val side = radius.coerceAtLeast(0) * 2 + 1
        return side * side
    }

    fun chunkAt(anchorChunkX: Int, anchorChunkZ: Int, radius: Int, index: Int): Pair<Int, Int> {
        val clampedRadius = radius.coerceAtLeast(0)
        val side = clampedRadius * 2 + 1
        val dx = index % side - clampedRadius
        val dz = index / side - clampedRadius
        return anchorChunkX + dx to anchorChunkZ + dz
    }

    fun advance(
        base: PillagerBase,
        radius: Int,
        budget: Int,
        evaluate: (chunkX: Int, chunkZ: Int) -> PillagerBaseMaterializer.Site?,
    ): StepResult {
        val clampedRadius = radius.coerceAtLeast(0)
        if (base.materializationSearchRadius != clampedRadius) {
            PillagerBaseDiscoveryService.resetMaterializationSearch(base)
            base.materializationSearchRadius = clampedRadius
        }

        val total = totalChunks(clampedRadius)
        var inspected = 0
        var scored = 0
        while (base.materializationCursorIndex < total && inspected < budget.coerceAtLeast(1)) {
            val (chunkX, chunkZ) = chunkAt(base.anchorChunkX, base.anchorChunkZ, clampedRadius, base.materializationCursorIndex)
            base.materializationCursorIndex++
            inspected++

            val site = evaluate(chunkX, chunkZ) ?: continue
            scored++
            if (site.score > base.materializationBestScore) {
                base.materializationBestScore = site.score
                base.materializationBestChunkX = site.chunkX
                base.materializationBestChunkZ = site.chunkZ
                base.materializationBestX = site.center.x
                base.materializationBestY = site.center.y
                base.materializationBestZ = site.center.z
            }
        }
        return StepResult(
            complete = base.materializationCursorIndex >= total,
            inspectedChunks = inspected,
            scoredChunks = scored,
        )
    }

    fun bestSite(base: PillagerBase): PillagerBaseMaterializer.Site? {
        if (base.materializationBestScore == Int.MIN_VALUE) return null
        return PillagerBaseMaterializer.Site(
            chunkX = base.materializationBestChunkX,
            chunkZ = base.materializationBestChunkZ,
            center = BlockPos(base.materializationBestX, base.materializationBestY, base.materializationBestZ),
            score = base.materializationBestScore,
        )
    }
}
