package com.gerald.pillagerpressure.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class PillagerRecordsTest {
    @Test
    fun chunkRefStepsAlongDominantAxisTowardTarget() {
        assertEquals(ChunkRef(1, 0), ChunkRef(0, 0).stepToward(ChunkRef(3, 1)))
        assertEquals(ChunkRef(3, -1), ChunkRef(3, 0).stepToward(ChunkRef(2, -5)))
        assertEquals(ChunkRef(0, 0), ChunkRef(0, 0).stepToward(ChunkRef(0, 0)))
    }

    @Test
    fun chunkRefUsesManhattanDistanceAndBlockCenters() {
        val ref = ChunkRef(-2, 5)
        assertEquals(10, ref.distanceManhattan(ChunkRef(3, 0)))
        assertEquals(BlockPos(-24, 72, 88), ref.centerBlock(72))
    }

    @Test
    fun regionKeysUseFloorDivisionForNegativeChunks() {
        assertEquals(RegionKey(0, 0), RegionKey.fromChunk(ChunkRef(7, 0), 8))
        assertEquals(RegionKey(-1, -1), RegionKey.fromChunk(ChunkRef(-1, -8), 8))
        assertEquals(RegionKey(-2, 1), RegionKey.fromChunk(ChunkRef(-9, 12), 8))
    }

    @Test
    fun uuidHelpersRejectMissingRequiredAndIgnoreBadOptionalValues() {
        val tag = CompoundTag()
        assertFailsWith<IllegalArgumentException> { tag.getRequiredUuidString("id") }
        tag.putString("maybe", "not-a-uuid")
        assertNull(tag.getOptionalUuidString("maybe"))
        val id = UUID.randomUUID()
        tag.putUuidString("id", id)
        assertEquals(id, tag.getRequiredUuidString("id"))
    }

    @Test
    fun enumAndResourceLocationHelpersFallBackSafely() {
        val tag = CompoundTag()
        tag.putString("state", "GARBAGE")
        tag.putString("dimension", "not a location")
        assertEquals(CampaignState.DISBANDED, tag.getEnumString("state", CampaignState.DISBANDED))
        assertEquals(ResourceLocation("minecraft", "overworld"), tag.getResourceLocationString("dimension", ResourceLocation("minecraft", "overworld")))
    }

    @Test
    fun factionRoundTripsAndFallsBackForInvalidColors() {
        val faction = PillagerFaction(UUID.randomUUID(), "Red Ash Compact", "red", "black", 17, 2, 3)
        val loaded = PillagerFaction.load(faction.save())
        assertEquals(faction, loaded)

        val bad = faction.save()
        bad.putString("baseColor", "NOPE")
        bad.putString("accentColor", "ALSO_NOPE")
        val repaired = PillagerFaction.load(bad)
        assertEquals("black", repaired.baseColor)
        assertEquals("red", repaired.accentColor)
    }

    @Test
    fun factionWarMemoryRoundTripsAndDefaultsWhenMissing() {
        val faction = PillagerFaction(
            UUID.randomUUID(),
            "Red Ash Compact",
            "red",
            "black",
            17,
            2,
            3,
            FactionWarMemory(
                successfulGenes = OfficerGeneProfile.neutral(20).copy(range = 80),
                failedGenes = OfficerGeneProfile.neutral(10).copy(fire = 70),
                mutationSeed = 77L,
                generation = 3,
            ),
        )
        val loaded = PillagerFaction.load(faction.save())
        assertEquals(80, loaded.warMemory.successfulGenes.range)
        assertEquals(70, loaded.warMemory.failedGenes.fire)
        assertEquals(3, loaded.warMemory.generation)

        val legacy = faction.save()
        legacy.remove("warMemory")
        val legacyLoaded = PillagerFaction.load(legacy)
        assertEquals(FactionWarMemory(), legacyLoaded.warMemory)
    }

    @Test
    fun baseRoundTripsCompleteRecordIncludingIntelAndBounds() {
        val factionId = UUID.randomUUID()
        val officerId = UUID.randomUUID()
        val base = PillagerBase(
            id = UUID.randomUUID(),
            factionId = factionId,
            parentBaseId = null,
            type = BaseType.MAJOR,
            dimension = ResourceLocation("minecraft", "overworld"),
            structureId = ResourceLocation("minecraft", "pillager_outpost"),
            center = BlockPos(100, 72, -40),
            chunk = ChunkRef(6, -3),
            bounds = BoundingBox(90, 60, -50, 120, 90, -20),
            state = BaseState.ACTIVE,
            manpower = 72,
            supplies = 140,
            morale = 80,
            aggression = 20,
            loyalty = 100,
            influence = 80,
            lastValidatedTick = 500L,
        )
        base.intel += PlayerIntel(UUID.randomUUID(), "gerald", ChunkRef(8, -1), 600L, 9, officerId)

        val loaded = PillagerBase.load(base.save())
        assertEquals(base.id, loaded.id)
        assertEquals(BaseType.MAJOR, loaded.type)
        assertEquals(BaseState.ACTIVE, loaded.state)
        assertEquals(base.bounds?.minX(), loaded.bounds?.minX())
        assertEquals(1, loaded.intel.size)
        assertEquals(officerId, loaded.intel.single().sourceOfficerId)
    }

    @Test
    fun baseLoadIgnoresMalformedBoundsAndEnumStrings() {
        val tag = minimalBaseTag()
        tag.putIntArray("bounds", intArrayOf(1, 2, 3))
        tag.putString("type", "NOT_A_TYPE")
        tag.putString("state", "NOT_A_STATE")
        val loaded = PillagerBase.load(tag)
        assertNull(loaded.bounds)
        assertEquals(BaseType.MAJOR, loaded.type)
        assertEquals(BaseState.ACTIVE, loaded.state)
    }

    @Test
    fun baseLoadSkipsCorruptIntelEntries() {
        val tag = minimalBaseTag()
        val intel = ListTag()
        intel.add(CompoundTag().also { it.putString("player", "bad-uuid") })
        intel.add(PlayerIntel(UUID.randomUUID(), "valid", ChunkRef(1, 2), 3L, 4, null).save())
        tag.put("intel", intel)
        val loaded = PillagerBase.load(tag)
        assertEquals(1, loaded.intel.size)
        assertEquals("valid", loaded.intel.single().playerName)
    }

    @Test
    fun officerRoundTripsTraitsGrudgesAndEnumFallbacks() {
        val officer = PillagerOfficer(UUID.randomUUID(), "Ghor", "the Finder", UUID.randomUUID(), UUID.randomUUID(), OfficerRank.WARLORD, OfficerRole.HUNTER, OfficerState.WOUNDED, 4, 2, 1, 3)
        val player = UUID.randomUUID()
        officer.traits += "burned"
        officer.grudges[player] = 7
        val loaded = PillagerOfficer.load(officer.save())
        assertEquals(officer.id, loaded.id)
        assertTrue("burned" in loaded.traits)
        assertEquals(7, loaded.grudges[player])

        val bad = officer.save()
        bad.putString("rank", "INVALID")
        bad.putString("role", "INVALID")
        bad.putString("state", "INVALID")
        val repaired = PillagerOfficer.load(bad)
        assertEquals(OfficerRank.CAPTAIN, repaired.rank)
        assertEquals(OfficerRole.SKIRMISHER, repaired.role)
        assertEquals(OfficerState.ACTIVE, repaired.state)
    }

    @Test
    fun officerGeneticsAndLineageRoundTripAndLegacyDefaultsWork() {
        val predecessor = UUID.randomUUID()
        val officer = PillagerOfficer(
            UUID.randomUUID(),
            "Ghor",
            "the Finder",
            UUID.randomUUID(),
            UUID.randomUUID(),
            OfficerRank.WARLORD,
            OfficerRole.HUNTER,
            OfficerState.WOUNDED,
            4,
            2,
            1,
            3,
            OfficerGeneProfile(15, 90, 80, 70, 60, 50, 40, 30, 20, 10),
            OfficerDoctrine.HUNTER,
            mutableSetOf(OfficerAffix.LONGSHOT),
            OfficerLineage(predecessor, OfficerRank.WARLORD, 11, "killed by player"),
        )
        val loaded = PillagerOfficer.load(officer.save())
        assertEquals(90, loaded.genes.melee)
        assertEquals(OfficerDoctrine.HUNTER, loaded.doctrine)
        assertEquals(setOf(OfficerAffix.LONGSHOT), loaded.affixes)
        assertEquals(predecessor, loaded.lineage.predecessorOfficerId)
        assertEquals(OfficerRank.WARLORD, loaded.lineage.inheritedRank)
        assertEquals(11, loaded.lineage.inheritedBannerSeed)
        assertEquals("killed by player", loaded.lineage.causeOfSuccession)

        val legacy = officer.save()
        legacy.remove("genes")
        legacy.remove("lineage")
        val legacyLoaded = PillagerOfficer.load(legacy)
        assertEquals(OfficerGeneProfile.neutral(), legacyLoaded.genes)
        assertEquals(OfficerLineage.none(officer.rank), legacyLoaded.lineage)
    }

    @Test
    fun geneticRecordsClampOutOfRangeOrNegativeValues() {
        val genes = OfficerGeneProfile.load(CompoundTag().also {
            it.putInt("range", -10)
            it.putInt("melee", 130)
            it.putInt("speed", 55)
            it.putInt("armor", 400)
        })
        assertEquals(0, genes.range)
        assertEquals(100, genes.melee)
        assertEquals(55, genes.speed)
        assertEquals(100, genes.armor)

        val memory = FactionWarMemory.load(CompoundTag().also {
            it.putInt("generation", -9)
        })
        assertEquals(-9, memory.generation)
        assertEquals(OfficerGeneProfile.neutral(0), memory.successfulGenes)
        assertEquals(OfficerGeneProfile.neutral(0), memory.failedGenes)

        val lineage = OfficerLineage.load(CompoundTag(), OfficerRank.CAPTAIN)
        assertEquals(OfficerLineage.none(OfficerRank.CAPTAIN), lineage)
    }

    @Test
    fun campaignRoundTripsAndRepairsBadStateAndSpeed() {
        val campaign = PillagerCampaign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CampaignState.APPROACHING_INTEL, ChunkRef(0, 0), ChunkRef(8, -2), 80, 12, 7, 1, 100L, 50L)
        val loaded = PillagerCampaign.load(campaign.save())
        assertEquals(campaign, loaded)

        val bad = campaign.save()
        bad.putString("state", "BROKEN")
        bad.putInt("speed", 0)
        val repaired = PillagerCampaign.load(bad)
        assertEquals(CampaignState.DISBANDED, repaired.state)
        assertEquals(1, repaired.speedTicksPerChunk)
    }

    @Test
    fun pendingMarkersRoundTripAndDimensionFallbackWorks() {
        val marker = PendingFlagMarker(UUID.randomUUID(), UUID.randomUUID(), ResourceLocation("minecraft", "overworld"), BlockPos(1, 70, 2), 99L, 3)
        val loaded = PendingFlagMarker.load(marker.save())
        assertEquals(marker, loaded)

        val bad = marker.save()
        bad.putString("dimension", "not a location")
        assertEquals(ResourceLocation("minecraft", "overworld"), PendingFlagMarker.load(bad).dimension)
    }

    @Test
    fun stringTagsWithWrongTypeAreNotTreatedAsOptionalUuids() {
        val tag = CompoundTag()
        tag.put("maybe", StringTag.valueOf("bad"))
        assertNull(tag.getOptionalUuidString("maybe"))
    }

    private fun minimalBaseTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putUuidString("id", UUID.randomUUID())
        tag.putUuidString("faction", UUID.randomUUID())
        tag.putString("type", BaseType.MAJOR.name)
        tag.putString("dimension", "minecraft:overworld")
        tag.putInt("cx", 0)
        tag.putInt("cy", 70)
        tag.putInt("cz", 0)
        tag.put("chunk", ChunkRef(0, 0).save())
        tag.putString("state", BaseState.ACTIVE.name)
    }
}
