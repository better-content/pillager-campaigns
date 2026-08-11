package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormulaicWarbandRulesTest {
    @Test fun `capacity is bounded and rounded to six`() {
        assertEquals(96, FormulaicWarbandRules.capacity(EnvironmentTraits(habitability = 0.0)))
        assertEquals(156, FormulaicWarbandRules.capacity(EnvironmentTraits(habitability = 0.5)))
        assertEquals(216, FormulaicWarbandRules.capacity(EnvironmentTraits(habitability = 1.0)))
    }

    @Test fun `unmaterialized economy grows without a loaded chunk multiplier`() {
        val poor = EnvironmentTraits(habitability = 0.0)
        val rich = EnvironmentTraits(habitability = 1.0)
        assertEquals(420.0, FormulaicWarbandRules.grossRecruitTicksPerStrength(poor))
        assertEquals(180.0, FormulaicWarbandRules.grossRecruitTicksPerStrength(rich))
        assertTrue(FormulaicWarbandRules.mobilizationTicksPerStrength(poor, -999.0) >= 30.0)
        assertTrue(FormulaicWarbandRules.mobilizationTicksPerStrength(poor, 999.0) <= 90.0)
    }

    @Test fun `resource scarcity and preference choose the result`() {
        val cheap = FormulaCandidate("cheap", 2.0, mapOf("range" to 0.4), mapOf("metal" to 1.0))
        val strong = FormulaCandidate("strong", 3.0, mapOf("range" to 1.0), mapOf("metal" to 2.0))
        assertEquals("strong", FormulaicWarbandRules.choose(listOf(cheap, strong), mapOf("range" to 2.0), mapOf("metal" to 10.0))?.id)
        assertEquals("cheap", FormulaicWarbandRules.choose(listOf(cheap, strong), mapOf("range" to 2.0), mapOf("metal" to 1.0))?.id)
        assertNull(FormulaicWarbandRules.choose(listOf(strong), emptyMap(), emptyMap()))
    }

    @Test fun `learning is continuous and unbounded`() {
        var preference = 0.0
        repeat(100) { preference = FormulaicWarbandRules.updatePreference(preference, 1.0, 0.10) }
        assertTrue(preference > 9.9)
        assertEquals(14.0, FormulaicWarbandRules.updateThreat(10.0, 50.0), 0.0001)
    }

    @Test fun `retreat and escort formulas respect bounds`() {
        assertEquals(0, FormulaicWarbandRules.escortCount(17.99))
        assertEquals(8, FormulaicWarbandRules.escortCount(10_000.0))
        assertTrue(FormulaicWarbandRules.retreatThreshold(0.0, 18) in 0.20..0.60)
        assertTrue(FormulaicWarbandRules.retreatThreshold(1.0, 6) > FormulaicWarbandRules.retreatThreshold(0.0, 18))
    }

    @Test fun `initial preferences are environmental deterministic and extraction is tiered`() {
        val traits = EnvironmentTraits(2.0, -1.0, .75, .9, .25).bounded()
        assertEquals(1.0, traits.habitability)
        assertEquals(0.0, traits.biomass)
        val first = FormulaicWarbandRules.initialPreferences(42, traits)
        val second = FormulaicWarbandRules.initialPreferences(42, traits)
        assertEquals(first, second)
        assertTrue(first.keys.containsAll(listOf("durability", "damage", "mobility", "range", "conservation", "exotic")))
        assertEquals(12.0, FormulaicWarbandRules.extractionThreshold(1, 0))
        assertEquals(60.0, FormulaicWarbandRules.extractionThreshold(2, 1))
    }
}
