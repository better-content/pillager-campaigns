package com.gerald.pillagercampaigns.data

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.UUID

class PillagerCampaignSerializationTest {
    @Test
    fun `warband save load roundtrip preserves pressure and presence fields`() {
        val warband = PillagerWarband(
            id = UUID.randomUUID(),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation("minecraft", "overworld"),
            rallyChunkX = 12,
            rallyChunkZ = -9,
            strength = 5,
            defeated = false,
            warlordOfficerId = UUID.randomUUID(),
            warlordEntityId = UUID.randomUUID(),
            nextRaidTick = 200L,
            cooldownUntilTick = 400L,
            lastIntelTick = 123L,
            lastPresenceFailure = PresenceMaterializationResult.NOT_LOADED,
            lastPresenceAttemptTick = 111L,
            activeCampaignLimit = 2,
        )

        val loaded = PillagerWarband.load(warband.save())

        assertEquals(warband.id, loaded.id)
        assertEquals(warband.factionId, loaded.factionId)
        assertEquals(ResourceLocation("minecraft", "overworld"), loaded.dimension)
        assertEquals(12, loaded.rallyChunkX)
        assertEquals(-9, loaded.rallyChunkZ)
        assertEquals(5, loaded.strength)
        assertEquals(false, loaded.defeated)
        assertEquals(warband.warlordOfficerId, loaded.warlordOfficerId)
        assertEquals(warband.warlordEntityId, loaded.warlordEntityId)
        assertEquals(200L, loaded.nextRaidTick)
        assertEquals(400L, loaded.cooldownUntilTick)
        assertEquals(123L, loaded.lastIntelTick)
        assertEquals(PresenceMaterializationResult.NOT_LOADED, loaded.lastPresenceFailure)
        assertEquals(111L, loaded.lastPresenceAttemptTick)
        assertEquals(2, loaded.activeCampaignLimit)
        assertEquals(200, loaded.rallyBlockPos(70).x)
        assertEquals(-136, loaded.rallyBlockPos(70).z)
    }

    @Test
    fun `old non defeated base migrates into warband at anchor chunk`() {
        val base = PillagerBase(
            id = UUID.randomUUID(),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation("minecraft", "overworld"),
            structureId = ResourceLocation("minecraft", "pillager_outpost"),
            bannerSeed = 12,
            difficulty = 3,
            defeated = false,
            state = BaseState.MATERIALIZED,
            form = BaseForm.JIGSAW_OUTPOST,
            anchorChunkX = 40,
            anchorChunkZ = -16,
            chunkX = 41,
            chunkZ = -15,
            center = net.minecraft.core.BlockPos(648, 65, -248),
            lastSeenTick = 123L,
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
        val warlordId = UUID.randomUUID()

        val warband = PillagerWarband.migrate(base, warlordId)

        assertEquals(base.id, warband.id)
        assertEquals(40, warband.rallyChunkX)
        assertEquals(-16, warband.rallyChunkZ)
        assertEquals(6, warband.strength)
        assertEquals(false, warband.defeated)
        assertEquals(warlordId, warband.warlordOfficerId)
        assertEquals(123L, warband.nextRaidTick)
        assertEquals(PresenceMaterializationResult.SUCCESS, warband.lastPresenceFailure)
    }

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
