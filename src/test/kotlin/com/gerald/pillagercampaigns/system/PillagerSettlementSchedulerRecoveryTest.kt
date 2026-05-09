package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerWorldData
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PillagerSettlementSchedulerRecoveryTest {
    @Test
    fun `rebuild after load restores planned queue and materialized index`() {
        val data = PillagerWorldData()
        val dim = ResourceLocation.tryParse("minecraft:overworld")!!
        val factionId = UUID.randomUUID()
        data.factions[factionId] = PillagerFaction(factionId, "test-faction", 0, bossOfficerId = null)
        val plannedA = base(dim, factionId, 2, 3, BaseState.PLANNED, BaseForm.UNKNOWN)
        val plannedB = base(dim, factionId, 4, 5, BaseState.PLANNED, BaseForm.UNKNOWN)
        val materialized = base(dim, factionId, 7, 8, BaseState.MATERIALIZED, BaseForm.JIGSAW_OUTPOST)
        data.bases[plannedA.id] = plannedA
        data.bases[plannedB.id] = plannedB
        data.bases[materialized.id] = materialized

        val loaded = PillagerWorldData.load(data.save(net.minecraft.nbt.CompoundTag()))

        PillagerSettlementScheduler.reset()
        PillagerSettlementScheduler.rebuild(loaded)
        val status = PillagerSettlementScheduler.statusLine()
        assertTrue(status.contains("sam_pending_materialization=2"), status)

        // Ensure materialized chunk index was rebuilt too.
        val index = PillagerSettlementChunkIndex().apply { index(materialized) }
        assertEquals(setOf(materialized.id), index.idsAt(dim, materialized.chunkX, materialized.chunkZ))
    }

    private fun base(dimension: ResourceLocation, factionId: UUID, chunkX: Int, chunkZ: Int, state: BaseState, form: BaseForm): PillagerBase =
        PillagerBase(
            id = UUID.randomUUID(),
            factionId = factionId,
            dimension = dimension,
            structureId = ResourceLocation.tryParse("minecraft:pillager_outpost")!!,
            bannerSeed = 0,
            difficulty = 0,
            defeated = false,
            state = state,
            form = form,
            anchorChunkX = chunkX,
            anchorChunkZ = chunkZ,
            chunkX = chunkX,
            chunkZ = chunkZ,
            center = BlockPos(chunkX shl 4, 64, chunkZ shl 4),
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
