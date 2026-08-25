package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.core.RecruitRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvasionForgeRulesTest {
    @Test fun `authored roster has bounded explicit progression and vanilla fallback`() {
        val roster = InvasionRoster.authored()
        assertTrue(roster.any { it.entityId == "minecraft:pillager" && !it.optional && it.unlockIntensity == 0 })
        assertTrue(roster.any { it.entityId == "minecraft:ravager" && it.unlockIntensity == 5 && it.maximumPerSquad == 1 })
        assertTrue(roster.filter { it.role in setOf(RecruitRole.SUPPORT, RecruitRole.ELITE) }.all { it.maximumPerSquad == 1 })
        assertEquals(roster.size, roster.map { it.entityId }.distinct().size)
        assertTrue(roster.all { it.cost > 0 && it.unlockIntensity in 0..5 })
    }

    @Test fun `surface work begins with connected cardinal corridors`() {
        val offsets = SurfaceGridSampler.orderedOffsets(4)
        assertEquals(81, offsets.size)
        assertEquals(-4 to -2, offsets.first())
        assertTrue((-4 to 0) in offsets)
        assertTrue((4 to 0) in offsets)
        assertTrue((0 to -4) in offsets)
        assertTrue((0 to 4) in offsets)
    }

    @Test fun `member placement expands in stable square rings`() {
        val offsets = SurfaceGridSampler.memberOffsets(2)
        assertEquals(0 to 0, offsets.first())
        assertEquals(25, offsets.size)
        assertEquals(9, offsets.takeWhile { maxOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) <= 1 }.size)
    }
}
