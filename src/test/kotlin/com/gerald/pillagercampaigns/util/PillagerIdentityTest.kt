package com.gerald.pillagercampaigns.util

import com.gerald.pillagercampaigns.data.OfficerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import java.util.UUID

class PillagerIdentityTest {
    @Test
    fun factionsAreDeterministicForSeed() {
        val first = PillagerIdentity.makeFaction(1234L)
        val second = PillagerIdentity.makeFaction(1234L)
        assertEquals(first, second)
    }

    @Test
    fun differentSeedsCanProduceDifferentFactionIdentities() {
        val first = PillagerIdentity.makeFaction(1L)
        val second = PillagerIdentity.makeFaction(2L)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun officerIdentityIsDeterministicForFactionBaseSeedAndRole() {
        val factionId = UUID.randomUUID()
        val baseId = UUID.randomUUID()
        val first = PillagerIdentity.makeOfficer(factionId, baseId, 99L, OfficerRole.HUNTER)
        val second = PillagerIdentity.makeOfficer(factionId, baseId, 99L, OfficerRole.HUNTER)
        assertEquals(first, second)
        assertEquals(OfficerRole.HUNTER, first.role)
    }



}
