package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.OfficerDoctrine
import com.gerald.pillagerpressure.data.OfficerEngineeringTalent
import com.gerald.pillagerpressure.data.OfficerRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SquadCompositionRulesTest {
    @Test
    fun hunterCompositionPrefersArchersAndSkirmishers() {
        val plan = SquadCompositionRules.plan(
            doctrine = OfficerDoctrine.HUNTER,
            rank = OfficerRank.LIEUTENANT,
            pressure = SquadCompositionPressure.neutral().copy(range = 90, speed = 70),
        )

        assertEquals(8, plan.manifest.values.sum())
        assertTrue((plan.manifest[SquadCompositionRules.ARCHER] ?: 0) > (plan.manifest[SquadCompositionRules.SKIRMISHER] ?: 0))
        assertTrue("hunter lieutenant" in plan.summary)
    }

    @Test
    fun breakerCompositionPrefersVindicatorsAndLegioners() {
        val plan = SquadCompositionRules.plan(
            doctrine = OfficerDoctrine.BREAKER,
            rank = OfficerRank.CAPTAIN,
            pressure = SquadCompositionPressure.neutral().copy(melee = 80, armor = 90),
        )

        assertEquals(6, plan.manifest.values.sum())
        assertTrue(SquadCompositionRules.VINDICATOR in plan.manifest)
        assertTrue(SquadCompositionRules.LEGIONER in plan.manifest)
        assertFalse(SquadCompositionRules.ARCHER in plan.manifest)
    }

    @Test
    fun siegeCaptainUsesInfantryAndExtraEngineersFromTalent() {
        val plan = SquadCompositionRules.plan(
            doctrine = OfficerDoctrine.SIEGE_CAPTAIN,
            rank = OfficerRank.WARLORD,
            engineeringTalent = OfficerEngineeringTalent.FIELD_ENGINEER,
            pressure = SquadCompositionPressure.neutral().copy(siege = 90),
        )

        assertEquals(10, plan.manifest.values.sum())
        assertTrue((plan.manifest[SquadCompositionRules.ENGINEER] ?: 0) >= 3)
        assertTrue((plan.manifest[SquadCompositionRules.INFANTRY] ?: 0) >= 4)
    }

    @Test
    fun highRankHexerAddsEvoker() {
        val plan = SquadCompositionRules.plan(
            doctrine = OfficerDoctrine.HEXER,
            rank = OfficerRank.WARLORD,
            pressure = SquadCompositionPressure.neutral().copy(magic = 90),
        )

        assertTrue((plan.manifest[SquadCompositionRules.WITCH] ?: 0) >= 3)
        assertEquals(1, plan.manifest[SquadCompositionRules.EVOKER])
    }

    @Test
    fun beastmasterOnlyGetsRavagerAtHighRankAndHighBeastPressure() {
        val captain = SquadCompositionRules.plan(OfficerDoctrine.BEASTMASTER, OfficerRank.CAPTAIN, pressure = SquadCompositionPressure.neutral().copy(beast = 90))
        val warlord = SquadCompositionRules.plan(OfficerDoctrine.BEASTMASTER, OfficerRank.WARLORD, pressure = SquadCompositionPressure.neutral().copy(beast = 90))

        assertFalse(SquadCompositionRules.RAVAGER in captain.manifest)
        assertEquals(1, warlord.manifest[SquadCompositionRules.RAVAGER])
    }

    @Test
    fun fallbackReplacesUnavailableModdedIdsWithAppropriateVanillaIds() {
        val manifest = linkedMapOf(
            SquadCompositionRules.ARCHER to 2,
            SquadCompositionRules.LEGIONER to 3,
            SquadCompositionRules.EVOKER to 1,
            SquadCompositionRules.RAVAGER to 1,
        )
        val available = setOf(SquadCompositionRules.PILLAGER, SquadCompositionRules.VINDICATOR, SquadCompositionRules.WITCH)

        val fallback = SquadCompositionRules.fallbackManifest(manifest, available)

        assertEquals(mapOf(
            SquadCompositionRules.PILLAGER to 2,
            SquadCompositionRules.VINDICATOR to 3,
            SquadCompositionRules.WITCH to 1,
        ), fallback.manifest)
        assertFalse(SquadCompositionRules.RAVAGER in fallback.manifest)
        assertTrue("available roster: 6" in fallback.summary)
    }

    @Test
    fun standardStalkerAndSurvivorHaveDistinctShapes() {
        val standard = SquadCompositionRules.plan(OfficerDoctrine.STANDARD, OfficerRank.LIEUTENANT)
        val stalker = SquadCompositionRules.plan(OfficerDoctrine.STALKER, OfficerRank.SCOUT, pressure = SquadCompositionPressure.neutral().copy(speed = 90))
        val survivor = SquadCompositionRules.plan(OfficerDoctrine.SURVIVOR, OfficerRank.LIEUTENANT, pressure = SquadCompositionPressure.neutral().copy(survival = 90, armor = 70))

        assertTrue((standard.manifest[SquadCompositionRules.BANNER_GUARD] ?: 0) >= 3)
        assertEquals(4, stalker.manifest.values.sum())
        assertTrue((stalker.manifest[SquadCompositionRules.SKIRMISHER] ?: 0) >= 2)
        assertTrue(SquadCompositionRules.INFANTRY in survivor.manifest)
        assertTrue(SquadCompositionRules.WITCH in survivor.manifest)
    }
}
