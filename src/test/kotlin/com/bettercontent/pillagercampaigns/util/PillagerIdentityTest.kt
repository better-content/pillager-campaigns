package com.bettercontent.pillagercampaigns.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PillagerIdentityTest {
    @Test
    fun `faction generation is deterministic`() {
        val a = PillagerIdentity.makeFaction(42L)
        val b = PillagerIdentity.makeFaction(42L)
        assertEquals(a.id, b.id)
        assertEquals(a.name, b.name)
    }

    @Test
    fun `officer generation changes with warband`() {
        val faction = PillagerIdentity.makeFaction(99L)
        val a = PillagerIdentity.makeOfficer(faction, java.util.UUID.randomUUID(), 1L)
        val b = PillagerIdentity.makeOfficer(faction, java.util.UUID.randomUUID(), 1L)
        assertNotEquals(a.id, b.id)
    }
}
