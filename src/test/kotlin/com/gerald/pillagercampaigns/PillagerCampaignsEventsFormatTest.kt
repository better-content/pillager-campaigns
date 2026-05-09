package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class PillagerCampaignsEventsFormatTest {
    @Test
    fun `base list line always includes coordinates state and failure`() {
        val base = PillagerBase(
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation.tryParse("minecraft:overworld")!!,
            structureId = ResourceLocation.tryParse("minecraft:pillager_outpost")!!,
            bannerSeed = 0,
            difficulty = 0,
            defeated = false,
            state = BaseState.PLANNED,
            form = BaseForm.UNKNOWN,
            anchorChunkX = 12,
            anchorChunkZ = -3,
            chunkX = 13,
            chunkZ = -2,
            center = BlockPos(216, 72, -24),
            lastSeenTick = 0L,
            materializationAttempts = 2,
            materializationFailure = BaseMaterializationFailure.IN_PROGRESS,
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

        val line = PillagerCampaignsEvents.formatBaseLine(base)
        assertTrue("anchor_chunk=12,-3" in line)
        assertTrue("chunk=13,-2" in line)
        assertTrue("center_xyz=216,72,-24" in line)
        assertTrue("state=planned" in line)
        assertTrue("failure=in_progress" in line)
    }
}

