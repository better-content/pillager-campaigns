package com.gerald.pillagercampaigns.data

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

class PillagerWorldDataRepairTest {
    @Test
    fun `load repairs dangling officer and campaign references`() {
        val factionId = UUID.randomUUID()
        val goodWarbandId = UUID.randomUUID()
        val badWarbandId = UUID.randomUUID()
        val goodOfficerId = UUID.randomUUID()
        val badOfficerId = UUID.randomUUID()

        val faction = PillagerFaction(
            id = factionId,
            name = "Test Faction",
            bannerSeed = 4,
            bossOfficerId = null,
        )

        val warband = PillagerWarband(
            id = goodWarbandId,
            factionId = factionId,
            dimension = ResourceLocation("minecraft", "overworld"),
            bannerSeed = 2,
            rallyChunkX = 0,
            rallyChunkZ = 0,
            reserve = 18,
            defeated = false,
            warlordOfficerId = goodOfficerId,
            warlordEntityId = null,
            nextRaidTick = 0L,
            cooldownUntilTick = 0L,
            lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )

        val goodOfficer = PillagerOfficer(
            id = goodOfficerId,
            factionId = factionId,
            homeWarbandId = goodWarbandId,
            name = "Good",
            title = "Captain",
            rank = OfficerRank.CAPTAIN,
            state = OfficerState.IDLE,
            preferenceGraph = mutableMapOf(),
        )

        val badOfficer = PillagerOfficer(
            id = badOfficerId,
            factionId = factionId,
            homeWarbandId = badWarbandId,
            name = "Bad",
            title = "Dangling",
            rank = OfficerRank.CAPTAIN,
            state = OfficerState.IDLE,
            preferenceGraph = mutableMapOf(),
        )

        val goodCampaign = PillagerCampaign(
            id = UUID.randomUUID(),
            factionId = factionId,
            originWarbandId = goodWarbandId,
            officerId = goodOfficerId,
            targetPlayerId = UUID.randomUUID(),
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 0,
            loadoutSeed = 1L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            resumeState = null,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        val badCampaign = PillagerCampaign(
            id = UUID.randomUUID(),
            factionId = factionId,
            originWarbandId = goodWarbandId,
            officerId = badOfficerId,
            targetPlayerId = UUID.randomUUID(),
            targetDimension = ResourceLocation("minecraft", "overworld"),
            currentChunkX = 0,
            currentChunkZ = 0,
            targetChunkX = 1,
            targetChunkZ = 1,
            difficultySnapshot = 0,
            loadoutSeed = 2L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            resumeState = null,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        val raw = PillagerWorldData().apply {
            factions[faction.id] = faction
            warbands[warband.id] = warband
            officers[goodOfficer.id] = goodOfficer
            officers[badOfficer.id] = badOfficer
            campaigns[goodCampaign.id] = goodCampaign
            campaigns[badCampaign.id] = badCampaign
        }.save(net.minecraft.nbt.CompoundTag())

        val repaired = PillagerWorldData.load(raw)

        assertEquals(1, repaired.officers.size)
        assertEquals(true, repaired.officers.containsKey(goodOfficerId))
        assertEquals(1, repaired.campaigns.size)
        assertEquals(true, repaired.campaigns.containsKey(goodCampaign.id))
    }

    @Test
    fun `load preserves initialized and protected players`() {
        val playerId = UUID.randomUUID()
        val raw = PillagerWorldData().apply {
            initializedPlayers += playerId
            protectedPlayersUntilTick[playerId] = 6_000L
        }.save(net.minecraft.nbt.CompoundTag())

        val loaded = PillagerWorldData.load(raw)

        assertTrue(playerId in loaded.initializedPlayers)
        assertEquals(6_000L, loaded.protectedPlayersUntilTick[playerId])
    }

    @Test
    fun `engine sequence persists and legacy manifests advance it without collisions`() {
        val current = PillagerWorldData().apply { engineSequence = 91L }
        assertEquals(91L, PillagerWorldData.load(current.save(net.minecraft.nbt.CompoundTag())).engineSequence)

        val legacy = current.save(net.minecraft.nbt.CompoundTag()).also {
            it.remove("engineSequence")
            val legacyCampaign = PillagerCampaign(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation("minecraft", "overworld"), 0, 0, 1, 0, 1, 0L, 0,
                CampaignState.TRAVELING, null, null, 0L, mutableListOf(),
                plannedMembers = mutableListOf(PlannedCampaignMember(
                    ResourceLocation("minecraft", "pillager"), 5.0, manifestId = "engine:member:41",
                )),
            )
            it.put("campaigns", net.minecraft.nbt.ListTag().also { campaigns -> campaigns.add(legacyCampaign.save()) })
        }
        assertEquals(42L, PillagerWorldData.load(legacy).engineSequence)
    }
}
