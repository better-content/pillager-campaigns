package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.OfficerClass
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PillagerCampaignEngineTest {
    @Test
    fun `resolving campaign penalizes live warband and releases officer`() {
        val fixture = campaignFixture(strength = 3, nextRaidTick = 100L)
        val campaign = fixture.campaign
        campaign.state = CampaignState.ACTIVE
        campaign.materializeAttemptId = UUID.randomUUID()
        campaign.materializingUntilTick = 200L
        campaign.squadMemberIds += UUID.randomUUID()

        PillagerCampaignEngine.resolveCampaign(fixture.data, campaign.id, defeatedByPlayer = true, observedTick = 500L)

        assertEquals(CampaignState.RESOLVED, campaign.state)
        assertNull(campaign.materializeAttemptId)
        assertEquals(0L, campaign.materializingUntilTick)
        assertTrue(campaign.squadMemberIds.isEmpty())
        assertEquals(2, fixture.warband.strength)
        assertFalse(fixture.warband.defeated)
        assertEquals(12_100L, fixture.warband.cooldownUntilTick)
        assertEquals(500L, fixture.warband.lastIntelTick)
        assertEquals(OfficerState.AVAILABLE, fixture.officer.state)
    }

    @Test
    fun `collapse warband marks home officers dead without counting as player defeat`() {
        val fixture = campaignFixture(strength = 2)
        fixture.campaign.state = CampaignState.TRAVELING

        PillagerCampaignEngine.collapseWarband(fixture.data, fixture.warband.id)

        assertTrue(fixture.warband.defeated)
        assertEquals(0, fixture.warband.strength)
        assertNull(fixture.warband.warlordEntityId)
        assertNull(fixture.faction.bossEntityId)
        assertEquals(CampaignState.RESOLVED, fixture.campaign.state)
        assertEquals(OfficerState.DEAD, fixture.officer.state)
    }

    @Test
    fun `collapse faction removes all owned campaign state`() {
        val fixture = campaignFixture()

        PillagerCampaignEngine.collapseFaction(fixture.data, fixture.faction.id)

        assertTrue(fixture.data.factions.isEmpty())
        assertTrue(fixture.data.bases.isEmpty())
        assertTrue(fixture.data.warbands.isEmpty())
        assertTrue(fixture.data.officers.isEmpty())
        assertTrue(fixture.data.campaigns.isEmpty())
    }

    private fun campaignFixture(strength: Int = 3, nextRaidTick: Long = 0L): CampaignFixture {
        val data = PillagerWorldData()
        val factionId = UUID.randomUUID()
        val warbandId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val targetPlayerId = UUID.randomUUID()
        val faction = PillagerFaction(
            id = factionId,
            name = "Test Banner",
            bannerSeed = 42,
            bossOfficerId = officerId,
            bossEntityId = UUID.randomUUID(),
        )
        val base = PillagerBase(
            id = warbandId,
            factionId = factionId,
            dimension = OVERWORLD,
            structureId = PILLAGER_OUTPOST,
            bannerSeed = 42,
            difficulty = strength,
            defeated = false,
            state = BaseState.MATERIALIZED,
            form = BaseForm.JIGSAW_OUTPOST,
            anchorChunkX = 4,
            anchorChunkZ = -3,
            chunkX = 4,
            chunkZ = -3,
            center = BlockPos(72, 64, -40),
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
        val warband = PillagerWarband(
            id = warbandId,
            factionId = factionId,
            dimension = OVERWORLD,
            rallyChunkX = 4,
            rallyChunkZ = -3,
            strength = strength,
            defeated = false,
            warlordOfficerId = officerId,
            warlordEntityId = UUID.randomUUID(),
            nextRaidTick = nextRaidTick,
            cooldownUntilTick = 0L,
            lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )
        val officer = PillagerOfficer(
            id = officerId,
            factionId = factionId,
            homeBaseId = warbandId,
            name = "Captain Test",
            title = "of Tests",
            rank = OfficerRank.CAPTAIN,
            officerClass = OfficerClass.PILLAGER,
            state = OfficerState.DEPLOYED,
            preferenceGraph = mutableMapOf("raid" to 1.0),
        )
        val campaign = PillagerCampaign(
            id = campaignId,
            factionId = factionId,
            originBaseId = warbandId,
            officerId = officerId,
            targetPlayerId = targetPlayerId,
            targetDimension = OVERWORLD,
            currentChunkX = 4,
            currentChunkZ = -3,
            targetChunkX = 6,
            targetChunkZ = -3,
            difficultySnapshot = strength,
            loadoutSeed = 123L,
            tickDebt = 0,
            state = CampaignState.TRAVELING,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        data.factions[faction.id] = faction
        data.bases[base.id] = base
        data.warbands[warband.id] = warband
        data.officers[officer.id] = officer
        data.campaigns[campaign.id] = campaign
        return CampaignFixture(data, faction, warband, officer, campaign)
    }

    private data class CampaignFixture(
        val data: PillagerWorldData,
        val faction: PillagerFaction,
        val warband: PillagerWarband,
        val officer: PillagerOfficer,
        val campaign: PillagerCampaign,
    )

    private companion object {
        val OVERWORLD: ResourceLocation = ResourceLocation.tryParse("minecraft:overworld")!!
        val PILLAGER_OUTPOST: ResourceLocation = ResourceLocation.tryParse("minecraft:pillager_outpost")!!
    }
}
