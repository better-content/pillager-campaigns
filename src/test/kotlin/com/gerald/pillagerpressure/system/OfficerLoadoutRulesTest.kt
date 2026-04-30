package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.OfficerAffix
import com.gerald.pillagerpressure.data.OfficerDoctrine
import com.gerald.pillagerpressure.data.OfficerEngineeringTalent
import com.gerald.pillagerpressure.data.OfficerGeneProfile
import com.gerald.pillagerpressure.data.OfficerRank
import com.gerald.pillagerpressure.data.OfficerRole
import com.gerald.pillagerpressure.data.OfficerState
import com.gerald.pillagerpressure.data.PillagerOfficer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficerLoadoutRulesTest {
    @Test
    fun hunterCarriesCrossbowShieldAndReadableLongshotNotes() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.CAPTAIN,
            doctrine = OfficerDoctrine.HUNTER,
            affixes = setOf(OfficerAffix.LONGSHOT),
        )

        assertEquals("minecraft:crossbow", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertEquals("minecraft:iron_chestplate", loadout.armor.chestplate)
        assertTrue(loadout.notes.any { it.contains("hunter") })
        assertTrue(loadout.notes.any { it.contains("longshot") })
    }

    @Test
    fun breakerGetsAxeShieldAndIronboundArmor() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.LIEUTENANT,
            doctrine = OfficerDoctrine.BREAKER,
            affixes = setOf(OfficerAffix.IRONBOUND),
            engineeringTalent = OfficerEngineeringTalent.LADDERMASTER,
        )

        assertEquals("minecraft:iron_axe", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertEquals("minecraft:iron_helmet", loadout.armor.helmet)
        assertEquals("minecraft:iron_leggings", loadout.armor.leggings)
        assertTrue(loadout.notes.any { it.contains("ladder") })
    }

    @Test
    fun siegeCaptainUsesCrossbowShieldAndFieldEngineerIntentFromOfficer() {
        val officer = officer(
            rank = OfficerRank.CAPTAIN,
            doctrine = OfficerDoctrine.SIEGE_CAPTAIN,
            genes = OfficerGeneProfile.neutral(20).copy(siege = 95, survival = 60),
        )

        val loadout = OfficerLoadoutRules.forOfficer(officer)

        assertEquals("minecraft:crossbow", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertTrue(loadout.notes.any { it.contains("siege captain") })
        assertTrue(loadout.notes.any { it.contains("field engineer") })
    }

    @Test
    fun hexerIsMarkedButNotGivenExoticBossPowers() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.CAPTAIN,
            doctrine = OfficerDoctrine.HEXER,
            affixes = setOf(OfficerAffix.WITCH_TOUCHED),
        )

        assertEquals("minecraft:iron_sword", loadout.mainhand)
        assertEquals(null, loadout.offhand)
        assertTrue(loadout.notes.any { it.contains("witch-touched markings only") })
        assertTrue(loadout.notes.any { it.contains("no enchantments, effects, or exotic boss powers") })
    }

    @Test
    fun standardBannerlordCarriesVisibleBannerIntent() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.BANNERLORD,
            doctrine = OfficerDoctrine.STANDARD,
            affixes = setOf(OfficerAffix.BANNERED),
        )

        assertEquals("minecraft:iron_sword", loadout.mainhand)
        assertEquals("minecraft:white_banner", loadout.offhand)
        assertEquals("minecraft:iron_chestplate", loadout.armor.chestplate)
        assertTrue(loadout.notes.any { it.contains("banner intent") })
        assertTrue(loadout.notes.any { it.contains("visible rally marker") })
    }

    @Test
    fun scoutStaysLightAndReadable() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.SCOUT,
            doctrine = OfficerDoctrine.STALKER,
            affixes = setOf(OfficerAffix.SWIFT),
        )

        assertEquals("minecraft:crossbow", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertEquals("minecraft:leather_helmet", loadout.armor.helmet)
        assertEquals("minecraft:chainmail_chestplate", loadout.armor.chestplate)
        assertEquals("minecraft:leather_boots", loadout.armor.boots)
    }

    @Test
    fun survivorPrioritizesDurableArmorAndShield() {
        val loadout = OfficerLoadoutRules.plan(
            rank = OfficerRank.WARLORD,
            doctrine = OfficerDoctrine.SURVIVOR,
            affixes = setOf(OfficerAffix.GRAVE_MARKED),
        )

        assertEquals("minecraft:iron_sword", loadout.mainhand)
        assertEquals("minecraft:shield", loadout.offhand)
        assertEquals("minecraft:iron_helmet", loadout.armor.helmet)
        assertEquals("minecraft:iron_chestplate", loadout.armor.chestplate)
        assertTrue(loadout.notes.any { it.contains("survivor") })
        assertTrue(loadout.notes.any { it.contains("grave-marked") })
    }

    private fun officer(
        rank: OfficerRank,
        doctrine: OfficerDoctrine,
        genes: OfficerGeneProfile = OfficerGeneProfile.neutral(),
        affixes: Set<OfficerAffix> = emptySet(),
    ) = PillagerOfficer(
        id = UUID.randomUUID(),
        name = "Krag",
        title = "the Planned",
        factionId = UUID.randomUUID(),
        homeBaseId = UUID.randomUUID(),
        rank = rank,
        role = OfficerRole.SKIRMISHER,
        state = OfficerState.ACTIVE,
        victories = 0,
        defeats = 0,
        killedPlayers = 0,
        escapedEncounters = 0,
        genes = genes,
        doctrine = doctrine,
        affixes = affixes.toMutableSet(),
    )
}
