package com.gerald.pillagercampaigns.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.UUID

class PillagerCampaignSerializationTest {
    @Test
    fun `faction save load roundtrip preserves optional boss fields`() {
        val faction = PillagerFaction(
            id = UUID.randomUUID(),
            name = "Black Banner",
            bannerSeed = 31,
            bossOfficerId = UUID.randomUUID(),
            bossEntityId = UUID.randomUUID(),
        )

        val loaded = PillagerFaction.load(faction.save())

        assertEquals(faction, loaded)
    }

    @Test
    fun `faction load defaults optional boss fields when absent`() {
        val tag = CompoundTag().also {
            it.putUUID("id", UUID.randomUUID())
            it.putString("name", "Ashen Banner")
            it.putInt("bannerSeed", 9)
        }

        val loaded = PillagerFaction.load(tag)

        assertNull(loaded.bossOfficerId)
        assertNull(loaded.bossEntityId)
    }

    @Test
    fun `rally presence save load roundtrip preserves anchor and entity fields`() {
        val record = RallyPresenceRecord(
            state = RallyPresenceState.MATERIALIZED,
            warlordId = UUID.randomUUID(),
            entityId = UUID.randomUUID(),
            anchorX = 44,
            anchorY = 70,
            anchorZ = -11,
            lastMaterializedTick = 999L,
        )

        val loaded = RallyPresenceRecord.load(record.save())

        assertEquals(record, loaded)
    }

    @Test
    fun `rally presence load falls back safely when legacy data is malformed or absent`() {
        val loaded = RallyPresenceRecord.load(CompoundTag().also { it.putString("state", "BROKEN") })

        assertEquals(RallyPresenceState.DORMANT, loaded.state)
        assertEquals(UUID(0L, 0L), loaded.warlordId)
        assertNull(loaded.entityId)
        assertNull(loaded.anchorX)
        assertNull(loaded.anchorY)
        assertNull(loaded.anchorZ)
        assertEquals(0L, loaded.lastMaterializedTick)
    }

    @Test
    fun `nemesis event save load roundtrip preserves all optional references`() {
        val event = NemesisEvent(
            tick = 1234L,
            type = NemesisEventType.PROMOTED,
            playerId = UUID.randomUUID(),
            warbandId = UUID.randomUUID(),
            campaignId = UUID.randomUUID(),
            severity = "major",
        )

        val loaded = NemesisEvent.load(event.save())

        assertEquals(event, loaded)
    }

    @Test
    fun `nemesis event load falls back to defaults when fields are absent or invalid`() {
        val loaded = NemesisEvent.load(CompoundTag().also { it.putString("type", "INVALID") })

        assertEquals(0L, loaded.tick)
        assertEquals(NemesisEventType.LOST_CAMPAIGN, loaded.type)
        assertNull(loaded.playerId)
        assertNull(loaded.warbandId)
        assertNull(loaded.campaignId)
        assertNull(loaded.severity)
    }

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
    fun `warband load rebuilds rally presence from legacy warlord fields`() {
        val warlordId = UUID.randomUUID()
        val entityId = UUID.randomUUID()
        val tag = CompoundTag().also {
            it.putUUID("id", UUID.fromString("11111111-1111-1111-1111-111111111111"))
            it.putUUID("factionId", UUID.randomUUID())
            it.putString("dimension", "not a resource id")
            it.putString("structureId", "still not valid")
            it.putInt("rallyChunkX", 4)
            it.putInt("rallyChunkZ", -2)
            it.putUUID("warlordOfficerId", warlordId)
            it.putUUID("warlordEntityId", entityId)
            it.putString("lastPresenceFailure", "BAD")
            it.putString("archetype", "BAD")
        }

        val loaded = PillagerWarband.load(tag)

        assertEquals(ResourceLocation("minecraft", "overworld"), loaded.dimension)
        assertEquals(ResourceLocation("minecraft", "pillager_outpost"), loaded.structureId)
        assertEquals(1, loaded.strength)
        assertEquals(false, loaded.defeated)
        assertEquals(PresenceMaterializationResult.SUCCESS, loaded.lastPresenceFailure)
        assertEquals(1, loaded.activeCampaignLimit)
        assertNotNull(loaded.rallyPresence)
        assertEquals(RallyPresenceState.MATERIALIZED, loaded.rallyPresence?.state)
        assertEquals(warlordId, loaded.rallyPresence?.warlordId)
        assertEquals(entityId, loaded.rallyPresence?.entityId)
    }

    @Test
    fun `warband load prefers explicit rally presence over legacy warlord fields`() {
        val explicitWarlord = UUID.randomUUID()
        val explicitEntity = UUID.randomUUID()
        val tag = CompoundTag().also {
            it.putUUID("id", UUID.fromString("22222222-2222-2222-2222-222222222222"))
            it.putUUID("factionId", UUID.randomUUID())
            it.putString("dimension", "minecraft:overworld")
            it.putString("structureId", "minecraft:pillager_outpost")
            it.putInt("rallyChunkX", 1)
            it.putInt("rallyChunkZ", 1)
            it.putUUID("warlordOfficerId", UUID.randomUUID())
            it.put(
                "rallyPresence",
                RallyPresenceRecord(
                    state = RallyPresenceState.LOST,
                    warlordId = explicitWarlord,
                    entityId = explicitEntity,
                    anchorX = 4,
                    anchorY = 72,
                    anchorZ = 9,
                    lastMaterializedTick = 44L,
                ).save(),
            )
        }

        val loaded = PillagerWarband.load(tag)

        assertNotNull(loaded.rallyPresence)
        assertEquals(RallyPresenceState.LOST, loaded.rallyPresence?.state)
        assertEquals(explicitWarlord, loaded.rallyPresence?.warlordId)
        assertEquals(explicitEntity, loaded.rallyPresence?.entityId)
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
    fun `officer save load roundtrip preserves nemesis history and targeting fields`() {
        val historyEntry = NemesisEvent(
            tick = 91L,
            type = NemesisEventType.KILLED_PLAYER,
            playerId = UUID.randomUUID(),
            warbandId = UUID.randomUUID(),
            campaignId = UUID.randomUUID(),
            severity = "fatal",
        )
        val officer = PillagerOfficer(
            id = UUID.randomUUID(),
            factionId = UUID.randomUUID(),
            homeWarbandId = UUID.randomUUID(),
            name = "Ruk",
            title = "the Hunter",
            role = OfficerRole.CAPTAIN,
            rank = OfficerRank.DREAD_CAPTAIN,
            officerClass = OfficerClass.WITCH,
            state = OfficerState.RECOVERING,
            combatStyle = CombatStyle.HEXER,
            preferenceGraph = mutableMapOf("member_magic" to 2.5),
            kills = 3,
            deathsInflicted = 1,
            campaignVictories = 2,
            campaignDefeats = 4,
            lastTargetPlayerId = UUID.randomUUID(),
            lastSeenTick = 500L,
            injuryOrRecoveryUntilTick = 650L,
            promotionTier = 2,
            nemesisHistory = mutableListOf(historyEntry),
        )

        val loaded = PillagerOfficer.load(officer.save())

        assertEquals(officer.id, loaded.id)
        assertEquals(officer.role, loaded.role)
        assertEquals(officer.rank, loaded.rank)
        assertEquals(officer.state, loaded.state)
        assertEquals(officer.combatStyle, loaded.combatStyle)
        assertEquals(officer.lastTargetPlayerId, loaded.lastTargetPlayerId)
        assertEquals(officer.nemesisHistory, loaded.nemesisHistory)
    }

    @Test
    fun `officer load repairs legacy warlord rank state and inferred combat style`() {
        val tag = CompoundTag().also {
            it.putUUID("id", UUID.randomUUID())
            it.putUUID("factionId", UUID.randomUUID())
            it.putUUID("homeWarbandId", UUID.randomUUID())
            it.putString("name", "Drog")
            it.putString("title", "the Old")
            it.putString("rank", "WARLORD")
            it.putString("officerClass", "UNKNOWN")
            it.putString("state", "AVAILABLE")
            val prefs = CompoundTag()
            prefs.putDouble("member_magic", 4.0)
            prefs.putDouble("member_ranged", 1.0)
            it.put("preferenceGraph", prefs)
        }

        val loaded = PillagerOfficer.load(tag)

        assertEquals(OfficerRole.WARLORD, loaded.role)
        assertEquals(OfficerRank.DREAD_CAPTAIN, loaded.rank)
        assertEquals(OfficerClass.PILLAGER, loaded.officerClass)
        assertEquals(OfficerState.IDLE, loaded.state)
        assertEquals(CombatStyle.HEXER, loaded.combatStyle)
    }

    @Test
    fun `officer load falls back to defaults for invalid explicit role state and combat style`() {
        val tag = CompoundTag().also {
            it.putUUID("id", UUID.randomUUID())
            it.putUUID("factionId", UUID.randomUUID())
            it.putUUID("homeWarbandId", UUID.randomUUID())
            it.putString("name", "Skeg")
            it.putString("title", "the Blank")
            it.putString("role", "INVALID")
            it.putString("rank", "INVALID")
            it.putString("officerClass", "INVALID")
            it.putString("state", "INVALID")
            it.putString("combatStyle", "INVALID")
        }

        val loaded = PillagerOfficer.load(tag)

        assertEquals(OfficerRole.CAPTAIN, loaded.role)
        assertEquals(OfficerRank.CAPTAIN, loaded.rank)
        assertEquals(OfficerClass.PILLAGER, loaded.officerClass)
        assertEquals(OfficerState.IDLE, loaded.state)
        assertEquals(CombatStyle.HUNTER, loaded.combatStyle)
        assertEquals(emptyList(), loaded.nemesisHistory)
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
            resumeState = CampaignState.READY_TO_MATERIALIZE,
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
        assertEquals(CampaignState.READY_TO_MATERIALIZE, loaded.resumeState)
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
            resumeState = null,
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
        assertNull(loaded.resumeState)
        assertNull(loaded.materializeAttemptId)
        assertEquals(0L, loaded.materializingUntilTick)
        assertNotNull(loaded.squadMemberIds)
        assertEquals(0, loaded.squadMemberIds.size)
    }

    @Test
    fun `campaign load repairs invalid enums and ignores malformed squad entries`() {
        val campaignId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val validMember = UUID.randomUUID()
        val tag = CompoundTag().also {
            it.putUUID("id", campaignId)
            it.putUUID("factionId", UUID.randomUUID())
            it.putUUID("originWarbandId", UUID.randomUUID())
            it.putUUID("officerId", UUID.randomUUID())
            it.putUUID("targetPlayerId", UUID.randomUUID())
            it.putString("targetDimension", "still not valid")
            it.putInt("currentChunkX", 0)
            it.putInt("currentChunkZ", 0)
            it.putInt("targetChunkX", 1)
            it.putInt("targetChunkZ", 1)
            it.putInt("tickDebt", 2)
            it.putString("state", "BAD")
            it.putString("resumeState", "ALSO_BAD")
            val members = saveRecordList(
                listOf(
                    CompoundTag().also { it.putUUID("id", validMember) },
                    CompoundTag(),
                ),
            )
            it.put("squadMemberIds", members)
        }

        val loaded = PillagerCampaign.load(tag)

        assertEquals(ResourceLocation("minecraft", "overworld"), loaded.targetDimension)
        assertEquals(CampaignState.TRAVELING, loaded.state)
        assertNull(loaded.resumeState)
        assertEquals(campaignId.mostSignificantBits xor campaignId.leastSignificantBits, loaded.loadoutSeed)
        assertEquals(listOf(validMember), loaded.squadMemberIds)
    }

    @Test
    fun `save and load record list preserve ordering`() {
        val root = CompoundTag()
        root.put(
            "history",
            saveRecordList(
                listOf(
                    CompoundTag().also { it.putString("value", "one") },
                    CompoundTag().also { it.putString("value", "two") },
                ),
            ),
        )

        val values = mutableListOf<String>()
        loadRecordList(root, "history") { values += it.getString("value") }

        assertEquals(listOf("one", "two"), values)
    }
}
