package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.*
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.UUID

class PillagerBaseServiceTest {
    @Test
    fun officerForBaseReusesLivingOfficerAndReplacesDeadOfficer() {
        val data = PillagerWorldData()
        val faction = PillagerFaction(UUID.randomUUID(), "Faction", "black", "red", 1, 1, 1)
        val base = base(faction.id)
        data.factions[faction.id] = faction
        data.bases[base.id] = base

        val first = PillagerBaseService.officerForBase(data, base)
        val second = PillagerBaseService.officerForBase(data, base)
        assertSame(first, second)

        first.state = OfficerState.DEAD
        val replacement = PillagerBaseService.officerForBase(data, base)
        assertNotEquals(first.id, replacement.id)
        assertEquals(2, data.officers.size)
    }

    @Test
    fun factionForNewMajorBaseIsDeterministicBySeedAndDeduplicatesNameColor() {
        val data = PillagerWorldData()
        val first = PillagerBaseService.factionForNewMajorBase(data, 42L)
        val second = PillagerBaseService.factionForNewMajorBase(data, 42L)
        assertEquals(first.id, second.id)
        assertEquals(1, data.factions.size)
    }

    @Test
    fun tickEconomyCapsMajorAndSatelliteResources() {
        val data = PillagerWorldData()
        val factionId = UUID.randomUUID()
        val major = base(factionId).also { it.manpower = 79; it.supplies = 159; it.morale = 99 }
        val satellite = base(factionId, BaseType.SATELLITE).also { it.manpower = 27; it.supplies = 53; it.morale = 100 }
        data.bases[major.id] = major
        data.bases[satellite.id] = satellite

        PillagerBaseService.tickEconomy(data)

        assertEquals(80, major.manpower)
        assertEquals(160, major.supplies)
        assertEquals(100, major.morale)
        assertEquals(28, satellite.manpower)
        assertEquals(54, satellite.supplies)
        assertEquals(100, satellite.morale)
    }

    @Test
    fun tickEconomyIgnoresDestroyedAndReclaimableBases() {
        val data = PillagerWorldData()
        val factionId = UUID.randomUUID()
        val destroyed = base(factionId).also { it.state = BaseState.DESTROYED; it.manpower = 0; it.supplies = 0 }
        val reclaimable = base(factionId).also { it.state = BaseState.RECLAIMABLE; it.manpower = 0; it.supplies = 0 }
        data.bases[destroyed.id] = destroyed
        data.bases[reclaimable.id] = reclaimable

        PillagerBaseService.tickEconomy(data)

        assertEquals(0, destroyed.manpower)
        assertEquals(0, destroyed.supplies)
        assertEquals(0, reclaimable.manpower)
        assertEquals(0, reclaimable.supplies)
    }

    @Test
    fun baseActivityMatchesActiveAndDamagedOnly() {
        assertTrue(base(UUID.randomUUID()).also { it.state = BaseState.ACTIVE }.isActive())
        assertTrue(base(UUID.randomUUID()).also { it.state = BaseState.DAMAGED }.isActive())
        assertEquals(false, base(UUID.randomUUID()).also { it.state = BaseState.DESTROYED }.isActive())
        assertEquals(false, base(UUID.randomUUID()).also { it.state = BaseState.RECLAIMABLE }.isActive())
    }

    private fun base(factionId: UUID, type: BaseType = BaseType.MAJOR) = PillagerBase(UUID.randomUUID(), factionId, null, type, ResourceLocation("minecraft", "overworld"), null, BlockPos.ZERO, ChunkRef(0, 0), null, BaseState.ACTIVE, 10, 10, 10, 10, 10, 10, 0L)
}
