package com.gerald.warband.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormulaMathTest {
    @Test fun `formula baseline is deterministic bounded and resource aware`() {
        val traits = EnvironmentTraits(2.0, -1.0, 0.8, 0.9, 0.2).bounded()
        assertEquals(1.0, traits.habitability)
        assertEquals(0.0, traits.biomass)
        assertEquals(FormulaMath.initialPreferences(42L, traits), FormulaMath.initialPreferences(42L, traits))
        assertEquals(12.0, FormulaMath.extractionThreshold(1, 0))
        assertEquals(60.0, FormulaMath.extractionThreshold(2, 1))
        assertEquals(0, FormulaMath.escortCount(17.0))
        assertEquals(8, FormulaMath.escortCount(10_000.0))
        assertTrue(FormulaMath.retreatThreshold(1.0, 6) > FormulaMath.retreatThreshold(0.0, 18))
        assertEquals(0.1, FormulaMath.updatePreference(0.0, 1.0, 0.1))
        assertEquals(14.0, FormulaMath.updateThreat(10.0, 50.0), 0.0001)

        val cheap = FormulaCandidate("cheap", 2.0, mapOf("range" to 0.4), mapOf("metal" to 1.0))
        val strong = FormulaCandidate("strong", 3.0, mapOf("range" to 1.0), mapOf("metal" to 2.0))
        assertEquals("strong", FormulaMath.choose(listOf(cheap, strong), mapOf("range" to 2.0), mapOf("metal" to 10.0))?.id)
        assertEquals("cheap", FormulaMath.choose(listOf(cheap, strong), mapOf("range" to 2.0), mapOf("metal" to 1.0))?.id)
        assertNull(FormulaMath.choose(listOf(strong), emptyMap(), emptyMap()))
    }

    @Test fun `campaign geometry and active decisions are authoritative`() {
        assertEquals(1 to 0, CampaignGeometry.stepToward(0, 0, 5, 2))
        assertEquals(0 to -1, CampaignGeometry.stepToward(0, 0, 0, -2))
        assertEquals(0 to 0, CampaignGeometry.stepToward(0, 0, 0, 0))
        assertEquals(7, CampaignGeometry.manhattan(0, 0, 3, -4))
        assertEquals(ActiveCampaignDecision.DEFEATED, CampaignDecisions.activeDecision(0, 0.0, 5.0, 0.5, 6, 0, 0))
        assertEquals(ActiveCampaignDecision.MORALE_RETURN, CampaignDecisions.activeDecision(1, 1.0, 10.0, 0.5, 6, 0, 0))
        assertEquals(ActiveCampaignDecision.IDLE_RETURN, CampaignDecisions.activeDecision(1, 10.0, 10.0, 0.5, 6, 12_000, 0))
        assertEquals(ActiveCampaignDecision.CONTINUE, CampaignDecisions.activeDecision(1, 10.0, 10.0, 0.5, 6, 20, 0))
    }

    @Test fun `recruit inspection exposes the same score used for selection`() {
        val warband = WarbandState(
            "warband", "faction", ChunkPosition("overworld", 0, 0), 100.0, reserveThreat = 0.0,
            preferences = mutableMapOf("damage" to 2.0),
        )
        val recruit = RecruitDefinition("recruit", 4.0, CapabilityVector(damage = 3.0))
        assertEquals(1.5, WarbandCore.recruitScore(warband, null, recruit))
    }
}
