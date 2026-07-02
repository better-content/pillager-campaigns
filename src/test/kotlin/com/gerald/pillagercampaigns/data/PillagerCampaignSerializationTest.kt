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
            structureId = ResourceLocation("minecraft", "pillager_outpost"),
            bannerSeed = 17,
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
            archetype = WarbandArchetype.BLACKGUARD,
        )

        val loaded = PillagerWarband.load(warband.save())

        assertEquals(warband.id, loaded.id)
        assertEquals(warband.factionId, loaded.factionId)
        assertEquals(ResourceLocation("minecraft", "overworld"), loaded.dimension)
        assertEquals(ResourceLocation("minecraft", "pillager_outpost"), loaded.structureId)
        assertEquals(17, loaded.bannerSeed)
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
        assertEquals(WarbandArchetype.BLACKGUARD, loaded.archetype)
        assertEquals(200, loaded.rallyBlockPos(70).x)
        assertEquals(-136, loaded.rallyBlockPos(70).z)
    }

    @Test
    fun `warband load deterministically assigns legacy archetype when absent`() {
        val warband = PillagerWarband(
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            factionId = UUID.randomUUID(),
            dimension = ResourceLocation("minecraft", "overworld"),
            structureId = ResourceLocation("minecraft", "pillager_outpost"),
            bannerSeed = 17,
            rallyChunkX = 12,
            rallyChunkZ = -9,
            strength = 5,
            defeated = false,
            warlordOfficerId = UUID.randomUUID(),
            warlordEntityId = null,
            nextRaidTick = 200L,
            cooldownUntilTick = 400L,
            lastIntelTick = 123L,
            lastPresenceFailure = PresenceMaterializationResult.NOT_LOADED,
        )
        val tag = warband.save()
        tag.remove("archetype")

        val first = PillagerWarband.load(tag)
        val second = PillagerWarband.load(tag)

        assertEquals(first.archetype, second.archetype)
    }

    @Test
    fun `campaign save load roundtrip preserves transactional materialization fields`() {
        val campaignId = UUID.randomUUID()
        val factionId = UUID.randomUUID()
        val warbandId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val memberA = UUID.randomUUID()
        val memberB = UUID.randomUUID()

        val campaign = PillagerCampaign(
            id = campaignId,
            factionId = factionId,
            originWarbandId = warbandId,
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
        assertEquals(warbandId, loaded.originWarbandId)
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
            originWarbandId = UUID.randomUUID(),
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
