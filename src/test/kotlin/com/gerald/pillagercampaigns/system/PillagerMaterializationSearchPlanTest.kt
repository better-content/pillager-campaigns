package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PillagerMaterializationSearchPlanTest {
    @Test
    fun `chunk order starts at northwest corner and walks rows around anchor`() {
        val chunks = (0 until PillagerMaterializationSearchPlan.totalChunks(radius = 1)).map {
            PillagerMaterializationSearchPlan.chunkAt(anchorChunkX = 10, anchorChunkZ = -4, radius = 1, index = it)
        }

        assertEquals(
            listOf(
                9 to -5,
                10 to -5,
                11 to -5,
                9 to -4,
                10 to -4,
                11 to -4,
                9 to -3,
                10 to -3,
                11 to -3,
            ),
            chunks,
        )
    }

    @Test
    fun `advance consumes only budgeted chunks and resumes from persisted cursor`() {
        val base = plannedBase(anchorChunkX = 0, anchorChunkZ = 0)
        val visited = mutableListOf<Pair<Int, Int>>()

        val first = PillagerMaterializationSearchPlan.advance(base, radius = 2, budget = 3) { chunkX, chunkZ ->
            visited += chunkX to chunkZ
            null
        }

        assertFalse(first.complete)
        assertEquals(3, first.inspectedChunks)
        assertEquals(3, base.materializationCursorIndex)
        assertEquals(listOf(-2 to -2, -1 to -2, 0 to -2), visited)

        val second = PillagerMaterializationSearchPlan.advance(base, radius = 2, budget = 2) { chunkX, chunkZ ->
            visited += chunkX to chunkZ
            null
        }

        assertFalse(second.complete)
        assertEquals(5, base.materializationCursorIndex)
        assertEquals(listOf(-2 to -2, -1 to -2, 0 to -2, 1 to -2, 2 to -2), visited)
    }

    @Test
    fun `advance keeps best scored site without rescanning previous chunks`() {
        val base = plannedBase(anchorChunkX = 8, anchorChunkZ = 9)
        val first = PillagerMaterializationSearchPlan.advance(base, radius = 1, budget = 5) { chunkX, chunkZ ->
            when (chunkX to chunkZ) {
                7 to 8 -> site(chunkX, chunkZ, score = 5)
                8 to 8 -> site(chunkX, chunkZ, score = 50)
                9 to 8 -> site(chunkX, chunkZ, score = 10)
                else -> null
            }
        }

        assertFalse(first.complete)
        assertEquals(3, first.scoredChunks)
        assertEquals(50, base.materializationBestScore)
        assertEquals(8, base.materializationBestChunkX)
        assertEquals(8, base.materializationBestChunkZ)

        val second = PillagerMaterializationSearchPlan.advance(base, radius = 1, budget = 99) { chunkX, chunkZ ->
            if (chunkX == 9 && chunkZ == 10) site(chunkX, chunkZ, score = 75) else null
        }

        assertTrue(second.complete)
        val best = PillagerMaterializationSearchPlan.bestSite(base)
        assertNotNull(best)
        assertEquals(75, best.score)
        assertEquals(9, best.chunkX)
        assertEquals(10, best.chunkZ)
    }

    @Test
    fun `radius change resets search state so stricter retries do not use stale site`() {
        val base = plannedBase(anchorChunkX = 0, anchorChunkZ = 0).apply {
            materializationSearchRadius = 1
            materializationCursorIndex = 9
            materializationBestChunkX = 1
            materializationBestChunkZ = 1
            materializationBestX = 24
            materializationBestY = 80
            materializationBestZ = 24
            materializationBestScore = 100
        }

        val result = PillagerMaterializationSearchPlan.advance(base, radius = 2, budget = 1) { _, _ -> null }

        assertFalse(result.complete)
        assertEquals(2, base.materializationSearchRadius)
        assertEquals(1, base.materializationCursorIndex)
        assertEquals(Int.MIN_VALUE, base.materializationBestScore)
        assertNull(PillagerMaterializationSearchPlan.bestSite(base))
    }

    private fun site(chunkX: Int, chunkZ: Int, score: Int): PillagerBaseMaterializer.Site =
        PillagerBaseMaterializer.Site(chunkX, chunkZ, BlockPos(chunkX shl 4, 70, chunkZ shl 4), score)

    private fun plannedBase(anchorChunkX: Int, anchorChunkZ: Int): PillagerBase = PillagerBase(
        id = UUID.randomUUID(),
        factionId = UUID.randomUUID(),
        dimension = ResourceLocation("minecraft", "overworld"),
        structureId = ResourceLocation("minecraft", "pillager_outpost"),
        bannerSeed = 0,
        difficulty = 0,
        defeated = false,
        state = BaseState.PLANNED,
        form = BaseForm.UNKNOWN,
        anchorChunkX = anchorChunkX,
        anchorChunkZ = anchorChunkZ,
        chunkX = anchorChunkX,
        chunkZ = anchorChunkZ,
        center = BlockPos(anchorChunkX shl 4, 65, anchorChunkZ shl 4),
        lastSeenTick = 0L,
        materializationAttempts = 0,
        materializationFailure = BaseMaterializationFailure.NONE,
        lastMaterializationAttemptTick = 0L,
        materializationSearchRadius = -1,
        materializationCursorIndex = 0,
        materializationBestChunkX = 0,
        materializationBestChunkZ = 0,
        materializationBestX = 0,
        materializationBestY = 0,
        materializationBestZ = 0,
        materializationBestScore = Int.MIN_VALUE,
    )
}
