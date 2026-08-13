package com.bettercontent.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarbandTerritoryRulesTest {
    @Test fun `outer four chunks warn and inner territory is hostile`() {
        assertEquals(TerritorialRelation.UNCONTACTED, WarbandTerritoryRules.relation(32.01))
        assertEquals(TerritorialRelation.WARNED, WarbandTerritoryRules.relation(32.0))
        assertEquals(TerritorialRelation.WARNED, WarbandTerritoryRules.relation(28.0))
        assertEquals(TerritorialRelation.HOSTILE, WarbandTerritoryRules.relation(27.99))
        assertEquals(TerritorialRelation.HOSTILE, WarbandTerritoryRules.relation(100.0, attacked = true))
    }

    @Test fun `radius cannot exceed thirty two chunks`() {
        assertTrue(WarbandTerritoryRules.contains(0, 0, 32, 0, 100))
        assertFalse(WarbandTerritoryRules.contains(0, 0, 33, 0, 100))
    }
}
