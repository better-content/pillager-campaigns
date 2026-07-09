package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.CampaignOutcome
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.CombatStyle
import com.gerald.pillagercampaigns.data.NemesisEventType
import com.gerald.pillagercampaigns.data.OfficerClass
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerFaction
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceRecord
import com.gerald.pillagercampaigns.data.RallyPresenceState
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.GameType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PillagerCampaignEngineTest {
    @Test
    fun `resolving campaign defeat sends captain into recovery with history`() {
        val fixture = campaignFixture(strength = 3)
        fixture.campaign.state = CampaignState.ACTIVE

        PillagerCampaignEngine.resolveCampaign(fixture.data, fixture.campaign.id, observedTick = 500L, outcome = CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT)

        assertEquals(CampaignState.RESOLVED, fixture.campaign.state)
        assertEquals(OfficerState.RECOVERING, fixture.officer.state)
        assertEquals(2, fixture.warband.strength)
        assertEquals(500L, fixture.warband.lastIntelTick)
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.LOST_CAMPAIGN })
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.SURVIVED_RETREAT })
    }

    @Test
    fun `captain victory strengthens warband and preserves captain identity`() {
        val fixture = campaignFixture(strength = 2)

        PillagerCampaignEngine.abortCampaignAfterPlayerKill(fixture.data, fixture.campaign.id, observedTick = 1_000L)

        assertEquals(3, fixture.warband.strength)
        assertEquals(CampaignState.RESOLVED, fixture.campaign.state)
        assertEquals(OfficerState.RECOVERING, fixture.officer.state)
        assertEquals(1, fixture.officer.kills)
        assertEquals(1, fixture.officer.campaignVictories)
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.KILLED_PLAYER })
    }

    @Test
    fun `captain death enters dead and removes future eligibility`() {
        val fixture = campaignFixture(strength = 3)

        PillagerCampaignEngine.resolveCampaign(fixture.data, fixture.campaign.id, observedTick = 700L, outcome = CampaignOutcome.CAPTAIN_KILLED)

        assertEquals(OfficerState.DEAD, fixture.officer.state)
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.WAS_DEFEATED_BY_PLAYER })
        assertTrue(PillagerCampaignEngine.availableCaptains(fixture.data, fixture.warband, 10_000L).isEmpty())
    }

    @Test
    fun `promotion occurs after repeated success thresholds`() {
        val fixture = campaignFixture(strength = 4)
        fixture.officer.campaignVictories = 1

        PillagerCampaignEngine.resolveCampaign(fixture.data, fixture.campaign.id, observedTick = 1_200L, outcome = CampaignOutcome.CAPTAIN_VICTORY, defeatedByPlayer = false)

        assertEquals(OfficerRank.DREAD_CAPTAIN, fixture.officer.rank)
        assertTrue(fixture.officer.title.isNotBlank())
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.PROMOTED })
    }

    @Test
    fun `collapse warband marks home officers dead without rewriting captain history as player defeat`() {
        val fixture = campaignFixture(strength = 2)
        fixture.campaign.state = CampaignState.TRAVELING

        PillagerCampaignEngine.collapseWarband(fixture.data, fixture.warband.id, observedTick = 300L)

        assertTrue(fixture.warband.defeated)
        assertEquals(0, fixture.warband.strength)
        assertNull(fixture.warband.warlordEntityId)
        assertEquals(RallyPresenceState.LOST, fixture.warband.rallyPresence?.state)
        assertEquals(CampaignState.RESOLVED, fixture.campaign.state)
        assertEquals(OfficerState.DEAD, fixture.officer.state)
        assertFalse(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.WAS_DEFEATED_BY_PLAYER })
        assertTrue(fixture.officer.nemesisHistory.any { it.type == NemesisEventType.WARBAND_COLLAPSED })
    }

    @Test
    fun `collapse faction removes all owned campaign state`() {
        val fixture = campaignFixture()

        PillagerCampaignEngine.collapseFaction(fixture.data, fixture.faction.id)

        assertTrue(fixture.data.factions.isEmpty())
        assertTrue(fixture.data.warbands.isEmpty())
        assertTrue(fixture.data.officers.isEmpty())
        assertTrue(fixture.data.campaigns.isEmpty())
    }

    @Test
    fun `assignment weight prefers grudges and rejects protected targets`() {
        val captain = PillagerOfficer(
            id = UUID.randomUUID(),
            factionId = UUID.randomUUID(),
            homeWarbandId = UUID.randomUUID(),
            name = "Ghor",
            title = "the Hound",
            role = OfficerRole.CAPTAIN,
            rank = OfficerRank.CAPTAIN,
            officerClass = OfficerClass.PILLAGER,
            state = OfficerState.IDLE,
            combatStyle = CombatStyle.HUNTER,
            preferenceGraph = mutableMapOf(),
            lastTargetPlayerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        )
        val grudge = PillagerCampaignEngine.assignmentWeight(captain, UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), distance = 8, isProtected = false)
        val neutral = PillagerCampaignEngine.assignmentWeight(captain, UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), distance = 8, isProtected = false)
        val protected = PillagerCampaignEngine.assignmentWeight(captain, UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), distance = 8, isProtected = true)

        assertTrue(grudge > neutral)
        assertTrue(protected < 0)
    }

    @Test
    fun `only survival players are eligible campaign targets`() {
        assertTrue(PillagerCampaignEngine.isCampaignTargetGameMode(GameType.SURVIVAL))
        assertFalse(PillagerCampaignEngine.isCampaignTargetGameMode(GameType.CREATIVE))
        assertFalse(PillagerCampaignEngine.isCampaignTargetGameMode(GameType.SPECTATOR))
        assertFalse(PillagerCampaignEngine.isCampaignTargetGameMode(GameType.ADVENTURE))
    }

    @Test
    fun `pausing active campaign resumes from ready to materialize`() {
        val fixture = campaignFixture()
        fixture.campaign.state = CampaignState.ACTIVE
        fixture.campaign.materializeAttemptId = UUID.randomUUID()
        fixture.campaign.materializingUntilTick = 99L
        fixture.campaign.squadMemberIds += UUID.randomUUID()

        PillagerCampaignEngine.pauseCampaignRecord(fixture.campaign)

        assertEquals(CampaignState.PAUSED, fixture.campaign.state)
        assertEquals(CampaignState.READY_TO_MATERIALIZE, fixture.campaign.resumeState)
        assertNull(fixture.campaign.materializeAttemptId)
        assertEquals(0L, fixture.campaign.materializingUntilTick)
        assertTrue(fixture.campaign.squadMemberIds.isEmpty())
    }

    private fun campaignFixture(strength: Int = 3): CampaignFixture {
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
        val warband = PillagerWarband(
            id = warbandId,
            factionId = factionId,
            dimension = OVERWORLD,
            structureId = PILLAGER_OUTPOST,
            bannerSeed = 42,
            rallyChunkX = 4,
            rallyChunkZ = -3,
            strength = strength,
            defeated = false,
            warlordOfficerId = UUID.randomUUID(),
            warlordEntityId = UUID.randomUUID(),
            nextRaidTick = 0L,
            cooldownUntilTick = 0L,
            lastIntelTick = 0L,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
            rallyPresence = RallyPresenceRecord(RallyPresenceState.MATERIALIZED, UUID.randomUUID(), UUID.randomUUID()),
        )
        val officer = PillagerOfficer(
            id = officerId,
            factionId = factionId,
            homeWarbandId = warbandId,
            name = "Captain Test",
            title = "the Hound",
            role = OfficerRole.CAPTAIN,
            rank = OfficerRank.CAPTAIN,
            officerClass = OfficerClass.PILLAGER,
            state = OfficerState.DEPLOYED,
            combatStyle = CombatStyle.HUNTER,
            preferenceGraph = mutableMapOf("raid" to 1.0),
        )
        val campaign = PillagerCampaign(
            id = campaignId,
            factionId = factionId,
            originWarbandId = warbandId,
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
            resumeState = null,
            materializeAttemptId = null,
            materializingUntilTick = 0L,
            squadMemberIds = mutableListOf(),
        )

        data.factions[faction.id] = faction
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
