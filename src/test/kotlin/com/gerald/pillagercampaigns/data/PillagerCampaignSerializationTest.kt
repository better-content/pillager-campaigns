package com.gerald.pillagercampaigns.data

import com.gerald.pillagercampaigns.system.EnvironmentTraits
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PillagerCampaignSerializationTest {
    @Test fun `warband formula state round trips without origin labels`() {
        val warband = PillagerWarband(
            UUID.randomUUID(), UUID.randomUUID(), ResourceLocation("minecraft", "overworld"), 7, 3, 4,
            reserve = 42, capacity = 180, raidPool = 3.5, aggression = 11,
            environment = EnvironmentTraits(.7, .6, .4, .2, .8), preferences = mutableMapOf("range" to 1.25),
            playerRelations = mutableMapOf(UUID(1, 2) to "HOSTILE"), materialLedger = mutableMapOf("tconstruct:flint" to 8.0),
            empiricalThreat = mutableMapOf("minecraft:pillager" to 9.5), stockpile = mutableMapOf("minecraft:bread" to 12),
            recruitSelectionMemory = mutableMapOf("minecraft:pillager" to 3.5),
            materialSelectionMemory = mutableMapOf("tconstruct:flint" to 2.5),
            equipmentSelectionMemory = mutableMapOf("tconstruct:crossbow" to 1.5), selectionMemoryLastTick = 1234L,
            extractionTickDebt = 44.0, defeated = false,
            warlordOfficerId = UUID.randomUUID(), warlordEntityId = null, nextRaidTick = 10,
            cooldownUntilTick = 20, lastIntelTick = 30, lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
        )
        val saved = warband.save()
        val loaded = PillagerWarband.load(saved)
        assertEquals(42, loaded.reserve)
        assertEquals(180, loaded.capacity)
        assertEquals(3.5, loaded.raidPool)
        assertEquals(11, loaded.aggression)
        assertEquals(.7, loaded.environment.habitability)
        assertEquals(1.25, loaded.preferences["range"])
        assertEquals(8.0, loaded.materialLedger["tconstruct:flint"])
        assertEquals(9.5, loaded.empiricalThreat["minecraft:pillager"])
        assertEquals(12, loaded.stockpile["minecraft:bread"])
        assertEquals(3.5, loaded.recruitSelectionMemory["minecraft:pillager"])
        assertEquals(2.5, loaded.materialSelectionMemory["tconstruct:flint"])
        assertEquals(1.5, loaded.equipmentSelectionMemory["tconstruct:crossbow"])
        assertEquals(1234L, loaded.selectionMemoryLastTick)
        assertEquals(44.0, loaded.extractionTickDebt)
        assertFalse(saved.contains("structureId"))
        assertFalse(saved.contains("archetype"))
    }

    @Test fun `legacy pressure migrates to reserve without retaining labels`() {
        val tag = minimumWarbandTag().also { it.putInt("strength", 5); it.putString("structureId", "minecraft:pillager_outpost"); it.putString("archetype", "HEX") }
        val loaded = PillagerWarband.load(tag)
        assertEquals(30, loaded.reserve)
        assertFalse(loaded.save().contains("structureId"))
        assertFalse(loaded.save().contains("archetype"))
    }

    @Test fun `campaign idle and conservation state round trips`() {
        val campaign = PillagerCampaign(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ResourceLocation("minecraft", "overworld"), 1, 2, 3, 4, 7, 99, 0,
            CampaignState.ACTIVE, null, null, 0, mutableListOf(UUID.randomUUID()), 123, 0, 7.0,
            plannedMembers = mutableListOf(PlannedCampaignMember(
                ResourceLocation("minecraft", "vindicator"), 7.0, cargo = mutableMapOf("minecraft:bread" to 3),
                manifestId = "engine:member:1", healthFraction = 0.75,
            )),
            memberSnapshots = mutableListOf(CompoundTag().also { it.putString("id", "minecraft:pillager") }),
            returnOutcome = CampaignOutcome.CAPTAIN_VICTORY, returnReason = "test", returnStartedTick = 456, returnAggressionDelta = 1,
            pendingEngineOutcome = CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT, pendingEngineOutcomeReason = "queued",
            route = mutableListOf(CampaignRouteStep(2, 3), CampaignRouteStep(3, 4)), routeIndex = 1,
            supplySatisfaction = 0.6, deficitExposure = 2.5, forageDebt = 0.4,
            lostAssetCaches = mutableListOf(LostAssetCache(8, 9, mutableListOf(CompoundTag().also { it.putString("id", "minecraft:bread") }))),
            pendingCampaignDamage = 4.0, pendingPlayerDamage = 6.0, pendingEffectiveRange = 11.0,
            pendingCasualtyManifestIds = mutableSetOf("engine:member:1"),
        )
        val loaded = PillagerCampaign.load(campaign.save())
        assertEquals(123, loaded.lastCombatTick)
        assertEquals(7.0, loaded.committedThreat)
        assertEquals("minecraft:vindicator", loaded.plannedMembers.single().recruitId.toString())
        assertEquals(3, loaded.plannedMembers.single().cargo["minecraft:bread"])
        assertEquals("engine:member:1", loaded.plannedMembers.single().manifestId)
        assertEquals(0.75, loaded.plannedMembers.single().healthFraction)
        assertEquals(campaign.squadMemberIds, loaded.squadMemberIds)
        assertEquals("minecraft:pillager", loaded.memberSnapshots.single().getString("id"))
        assertEquals(CampaignOutcome.CAPTAIN_VICTORY, loaded.returnOutcome)
        assertEquals(CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT, loaded.pendingEngineOutcome)
        assertEquals("queued", loaded.pendingEngineOutcomeReason)
        assertEquals("test", loaded.returnReason)
        assertEquals(456, loaded.returnStartedTick)
        assertEquals(1, loaded.returnAggressionDelta)
        assertEquals(listOf(CampaignRouteStep(2, 3), CampaignRouteStep(3, 4)), loaded.route)
        assertEquals(1, loaded.routeIndex)
        assertEquals(0.6, loaded.supplySatisfaction)
        assertEquals(2.5, loaded.deficitExposure)
        assertEquals(0.4, loaded.forageDebt)
        assertEquals("minecraft:bread", loaded.lostAssetCaches.single().stacks.single().getString("id"))
        assertEquals(4.0, loaded.pendingCampaignDamage)
        assertEquals(6.0, loaded.pendingPlayerDamage)
        assertEquals(11.0, loaded.pendingEffectiveRange)
        assertEquals(setOf("engine:member:1"), loaded.pendingCasualtyManifestIds)
    }

    private fun minimumWarbandTag() = CompoundTag().also {
        it.putUUID("id", UUID.randomUUID()); it.putUUID("factionId", UUID.randomUUID())
        it.putString("dimension", "minecraft:overworld"); it.putInt("rallyChunkX", 0); it.putInt("rallyChunkZ", 0)
        it.putUUID("warlordOfficerId", UUID.randomUUID())
    }
}
