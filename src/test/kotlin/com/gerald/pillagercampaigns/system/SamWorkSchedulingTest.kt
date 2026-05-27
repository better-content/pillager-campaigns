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
import kotlin.test.assertTrue

class SamWorkSchedulingTest {
    @Test
    fun `deduplicating queue preserves order and ignores duplicate queued work`() {
        val queue = DeduplicatingWorkQueue<String>()

        assertTrue(queue.add("a"))
        assertTrue(queue.add("b"))
        assertFalse(queue.add("a"))

        assertEquals(listOf("a", "b"), queue.snapshot())
        assertEquals("a", queue.poll())
        assertTrue(queue.add("a"))
        assertEquals(listOf("b", "a"), queue.snapshot())
    }

    @Test
    fun `forced queue insertion goes to front but still deduplicates already pending base`() {
        val queue = DeduplicatingWorkQueue<String>()

        queue.add("slow")
        queue.add("urgent", front = true)
        queue.add("slow", front = true)

        assertEquals(listOf("urgent", "slow"), queue.snapshot())
    }

    @Test
    fun `removed queued work is not later polled or duplicated after requeue`() {
        val queue = DeduplicatingWorkQueue<String>()

        queue.add("base-a")
        queue.add("base-b")

        assertTrue(queue.remove("base-a"))
        assertEquals(listOf("base-b"), queue.snapshot())
        assertTrue(queue.add("base-a", front = true))

        assertEquals(listOf("base-a", "base-b"), queue.snapshot())
        assertEquals("base-a", queue.poll())
        assertEquals("base-b", queue.poll())
        assertEquals(null, queue.poll())
    }

    @Test
    fun `settlement chunk index only returns live materialized bases for exact dimension and chunk`() {
        val index = PillagerSettlementChunkIndex()
        val overworld = id("minecraft:overworld")
        val nether = id("minecraft:the_nether")
        val live = base(overworld, 4, -7, state = BaseState.MATERIALIZED, defeated = false)
        val defeated = base(overworld, 4, -7, state = BaseState.MATERIALIZED, defeated = true)
        val planned = base(overworld, 4, -7, state = BaseState.PLANNED, defeated = false)
        val otherDimension = base(nether, 4, -7, state = BaseState.MATERIALIZED, defeated = false)

        index.index(live)
        index.index(defeated)
        index.index(planned)
        index.index(otherDimension)
        index.index(live)

        assertEquals(setOf(live.id), index.idsAt(overworld, 4, -7))
        assertEquals(setOf(otherDimension.id), index.idsAt(nether, 4, -7))
        assertEquals(emptySet(), index.idsAt(overworld, 5, -7))
        assertEquals(listOf(live.id, otherDimension.id), index.materializedIds)
    }

    private fun base(dimension: ResourceLocation, chunkX: Int, chunkZ: Int, state: BaseState, defeated: Boolean): PillagerBase = PillagerBase(
        id = UUID.randomUUID(),
        factionId = UUID.randomUUID(),
        dimension = dimension,
        structureId = id("minecraft:pillager_outpost"),
        bannerSeed = 0,
        difficulty = 0,
        defeated = defeated,
        state = state,
        form = if (state == BaseState.MATERIALIZED) BaseForm.JIGSAW_OUTPOST else BaseForm.UNKNOWN,
        anchorChunkX = chunkX,
        anchorChunkZ = chunkZ,
        chunkX = chunkX,
        chunkZ = chunkZ,
        center = BlockPos(chunkX shl 4, 65, chunkZ shl 4),
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

    private fun id(value: String): ResourceLocation = ResourceLocation.tryParse(value)!!
}
