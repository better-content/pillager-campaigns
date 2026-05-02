package com.gerald.pillagercampaigns.data

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.UUID

class PillagerCampaignSerializationTest {
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
