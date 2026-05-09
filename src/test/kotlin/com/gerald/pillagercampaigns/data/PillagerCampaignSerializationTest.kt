package com.gerald.pillagercampaigns.data

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.UUID

class PillagerCampaignSerializationTest {
    @Test
    fun `base save load roundtrip preserves planned base fields`() {
        val base = PillagerBase(
            id = UUID.randomUUID(),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation("minecraft", "overworld"),
            structureId = ResourceLocation("minecraft", "pillager_outpost"),
            bannerSeed = 12,
            difficulty = 3,
            defeated = false,
            state = BaseState.PLANNED,
            form = BaseForm.UNKNOWN,
            anchorChunkX = 40,
            anchorChunkZ = -16,
            chunkX = 40,
            chunkZ = -16,
            center = net.minecraft.core.BlockPos(648, 65, -248),
            lastSeenTick = 123L,
            materializationAttempts = 4,
            materializationFailure = BaseMaterializationFailure.NO_SITE,
            lastMaterializationAttemptTick = 120L,
            materializationSearchRadius = 12,
            materializationCursorIndex = 99,
            materializationBestChunkX = 41,
            materializationBestChunkZ = -15,
            materializationBestX = 664,
            materializationBestY = 72,
            materializationBestZ = -232,
            materializationBestScore = 88,
        )

        val loaded = PillagerBase.load(base.save())

        assertEquals(base.id, loaded.id)
        assertEquals(BaseState.PLANNED, loaded.state)
        assertEquals(BaseForm.UNKNOWN, loaded.form)
        assertEquals(ResourceLocation("minecraft", "pillager_outpost"), loaded.structureId)
        assertEquals(40, loaded.anchorChunkX)
        assertEquals(-16, loaded.anchorChunkZ)
        assertEquals(4, loaded.materializationAttempts)
        assertEquals(BaseMaterializationFailure.NO_SITE, loaded.materializationFailure)
        assertEquals(120L, loaded.lastMaterializationAttemptTick)
        assertEquals(12, loaded.materializationSearchRadius)
        assertEquals(99, loaded.materializationCursorIndex)
        assertEquals(88, loaded.materializationBestScore)
    }

    @Test
    fun `campaign save load roundtrip preserves transactional materialization fields`() {
        val campaignId = UUID.randomUUID()
        val factionId = UUID.randomUUID()
        val baseId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val memberA = UUID.randomUUID()
        val memberB = UUID.randomUUID()

        val campaign = PillagerCampaign(
            id = campaignId,
            factionId = factionId,
            originBaseId = baseId,
            officerId = officerId,
            targetPlayerId = playerId,
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 10,
            currentChunkZ = -4,
            targetChunkX = 33,
            targetChunkZ = 9,
            difficultySnapshot = 12,
            loadoutSeed = 998877L,
            tickDebt = 41,
            state = CampaignState.MATERIALIZING,
            materializeAttemptId = attemptId,
            materializingUntilTick = 4242L,
            squadMemberIds = mutableListOf(memberA, memberB),
        )

        val loaded = PillagerCampaign.load(campaign.save())

        assertEquals(campaignId, loaded.id)
        assertEquals(factionId, loaded.factionId)
        assertEquals(baseId, loaded.originBaseId)
        assertEquals(officerId, loaded.officerId)
        assertEquals(playerId, loaded.targetPlayerId)
        assertEquals(CampaignState.MATERIALIZING, loaded.state)
        assertEquals(attemptId, loaded.materializeAttemptId)
        assertEquals(4242L, loaded.materializingUntilTick)
        assertEquals(listOf(memberA, memberB), loaded.squadMemberIds)
    }

    @Test
    fun `campaign load defaults transactional fields when absent`() {
        val id = UUID.randomUUID()
        val campaign = PillagerCampaign(
            id = id,
            factionId = UUID.randomUUID(),
            originBaseId = UUID.randomUUID(),
            officerId = UUID.randomUUID(),
            targetPlayerId = UUID.randomUUID(),
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 0,
            loadoutSeed = 7L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            materializeAttemptId = UUID.randomUUID(),
            materializingUntilTick = 99L,
            squadMemberIds = mutableListOf(UUID.randomUUID()),
        )

        val tag = campaign.save()
        tag.remove("materializeAttemptId")
        tag.remove("materializingUntilTick")
        tag.remove("squadMemberIds")

        val loaded = PillagerCampaign.load(tag)

        assertEquals(CampaignState.TRAVELING, loaded.state)
        assertNull(loaded.materializeAttemptId)
        assertEquals(0L, loaded.materializingUntilTick)
        assertNotNull(loaded.squadMemberIds)
        assertEquals(0, loaded.squadMemberIds.size)
    }
}
