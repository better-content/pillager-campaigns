package com.gerald.pillagerpressure.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID

class PillagerWorldDataTest {
    @Test
    fun worldDataRoundTripsAllCollections() {
        val data = PillagerWorldData()
        val faction = faction()
        val base = base(faction.id)
        val officer = officer(faction.id, base.id)
        val campaign = campaign(faction.id, base.id, officer.id)
        val region = RegionActivity(RegionKey(-1, 2), 120L)
        val marker = PendingFlagMarker(faction.id, officer.id, ResourceLocation("minecraft", "overworld"), BlockPos(4, 70, 5), 7L, 2)
        val engineered = EngineeredBlockMarker(ResourceLocation("minecraft", "overworld"), BlockPos(6, 70, 9), ResourceLocation("minecraft", "scaffolding"), "minecraft:scaffolding[bottom=true,distance=0,waterlogged=false]", 99L, 1)

        data.factions[faction.id] = faction
        data.bases[base.id] = base
        data.officers[officer.id] = officer
        data.campaigns[campaign.id] = campaign
        data.regions[PillagerWorldData.regionKey(region.key)] = region
        data.pendingMarkers += marker
        data.engineeredBlocks += engineered
        data.lastCampaignTick = 11L
        data.lastBaseScanTick = 12L
        data.lastRegionTick = 13L

        val loaded = PillagerWorldData.load(data.save(CompoundTag()))
        assertEquals(1, loaded.factions.size)
        assertEquals(1, loaded.bases.size)
        assertEquals(1, loaded.officers.size)
        assertEquals(1, loaded.campaigns.size)
        assertEquals(1, loaded.regions.size)
        assertEquals(1, loaded.pendingMarkers.size)
        assertEquals(listOf(engineered), loaded.engineeredBlocks)
        assertEquals(11L, loaded.lastCampaignTick)
        assertEquals(12L, loaded.lastBaseScanTick)
        assertEquals(13L, loaded.lastRegionTick)
    }

    @Test
    fun loadSkipsCorruptEntriesWithoutDroppingValidEntries() {
        val validFaction = faction()
        val root = CompoundTag()
        root.put("factions", ListTag().also { list ->
            list.add(validFaction.save())
            list.add(CompoundTag().also { it.putString("id", "not-a-uuid") })
        })

        val loaded = PillagerWorldData.load(root)
        assertEquals(setOf(validFaction.id), loaded.factions.keys)
    }

    @Test
    fun loadRepairsDanglingBaseReferences() {
        val validFaction = faction()
        val root = CompoundTag()
        root.put("factions", ListTag().also { it.add(validFaction.save()) })
        root.put("bases", ListTag().also { list ->
            list.add(base(validFaction.id).save())
            list.add(base(UUID.randomUUID()).save())
        })
        val loaded = PillagerWorldData.load(root)
        assertEquals(1, loaded.bases.size)
        assertTrue(loaded.bases.values.all { it.factionId == validFaction.id })
    }

    @Test
    fun loadRepairsDanglingOfficersCampaignsAndPendingMarkers() {
        val validFaction = faction()
        val validBase = base(validFaction.id)
        val validOfficer = officer(validFaction.id, validBase.id)
        val badOfficer = officer(validFaction.id, UUID.randomUUID())
        val validCampaign = campaign(validFaction.id, validBase.id, validOfficer.id)
        val badCampaign = campaign(validFaction.id, UUID.randomUUID(), validOfficer.id)
        val badMarker = PendingFlagMarker(validFaction.id, badOfficer.id, ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, 0L, 0)

        val root = CompoundTag()
        root.put("factions", ListTag().also { it.add(validFaction.save()) })
        root.put("bases", ListTag().also { it.add(validBase.save()) })
        root.put("officers", ListTag().also { it.add(validOfficer.save()); it.add(badOfficer.save()) })
        root.put("campaigns", ListTag().also { it.add(validCampaign.save()); it.add(badCampaign.save()) })
        root.put("pendingMarkers", ListTag().also { it.add(badMarker.save()) })

        val loaded = PillagerWorldData.load(root)
        assertEquals(setOf(validOfficer.id), loaded.officers.keys)
        assertEquals(setOf(validCampaign.id), loaded.campaigns.keys)
        assertTrue(loaded.pendingMarkers.isEmpty())
    }

    @Test
    fun regionKeyIsStableStringFormat() {
        assertEquals("-3,9", PillagerWorldData.regionKey(RegionKey(-3, 9)))
    }

    @Test
    fun savedDataDirtyFlagStartsFalseAndCanBeMarked() {
        val data = PillagerWorldData()
        assertFalse(data.isDirty)
        data.markChanged()
        assertTrue(data.isDirty)
    }

    companion object {
        fun faction(id: UUID = UUID.randomUUID()) = PillagerFaction(id, "Blackroot Standard", "black", "red", 1, 2, 3)

        fun base(factionId: UUID, id: UUID = UUID.randomUUID()) = PillagerBase(id, factionId, null, BaseType.MAJOR, ResourceLocation("minecraft", "overworld"), ResourceLocation("minecraft", "pillager_outpost"), BlockPos(0, 70, 0), ChunkRef(0, 0), null, BaseState.ACTIVE, 10, 20, 30, 40, 50, 60, 70L)

        fun officer(factionId: UUID, baseId: UUID, id: UUID = UUID.randomUUID()) = PillagerOfficer(id, "Ghor", "the Finder", factionId, baseId, OfficerRank.CAPTAIN, OfficerRole.SCOUTMASTER, OfficerState.ACTIVE, 0, 0, 0, 0)

        fun campaign(factionId: UUID, baseId: UUID, officerId: UUID?, id: UUID = UUID.randomUUID()) = PillagerCampaign(id, factionId, baseId, officerId, CampaignState.SCOUTING, ChunkRef(0, 0), ChunkRef(1, 1), 80, 0, 3, 0, 1L, 0L)
    }
}
