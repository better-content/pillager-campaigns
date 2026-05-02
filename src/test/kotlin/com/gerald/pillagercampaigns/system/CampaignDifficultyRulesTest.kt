package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.OfficerClass
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CampaignDifficultyRulesTest {
    @Test
    fun `member count thresholds match spec`() {
        assertEquals(1, CampaignDifficultyRules.memberCountForDifficulty(0))
        assertEquals(1, CampaignDifficultyRules.memberCountForDifficulty(1))
        assertEquals(2, CampaignDifficultyRules.memberCountForDifficulty(2))
        assertEquals(3, CampaignDifficultyRules.memberCountForDifficulty(6))
        assertEquals(4, CampaignDifficultyRules.memberCountForDifficulty(10))
        assertEquals(5, CampaignDifficultyRules.memberCountForDifficulty(14))
    }

    @Test
    fun `officer class thresholds match spec`() {
        assertEquals(OfficerClass.PILLAGER, CampaignDifficultyRules.officerClassForDifficulty(0))
        assertEquals(OfficerClass.VINDICATOR, CampaignDifficultyRules.officerClassForDifficulty(4))
        assertEquals(OfficerClass.WITCH, CampaignDifficultyRules.officerClassForDifficulty(8))
        assertEquals(OfficerClass.EVOKER, CampaignDifficultyRules.officerClassForDifficulty(12))
        assertEquals(OfficerClass.ILLUSIONER, CampaignDifficultyRules.officerClassForDifficulty(16))
    }

    @Test
    fun `enchant tier caps at three`() {
        assertEquals(0, CampaignDifficultyRules.enchantTierForDifficulty(0))
        assertEquals(1, CampaignDifficultyRules.enchantTierForDifficulty(4))
        assertEquals(2, CampaignDifficultyRules.enchantTierForDifficulty(8))
        assertEquals(3, CampaignDifficultyRules.enchantTierForDifficulty(12))
        assertEquals(3, CampaignDifficultyRules.enchantTierForDifficulty(40))
    }

    @Test
    fun `armor piece progression counts correctly`() {
        assertEquals(0, CampaignDifficultyRules.armorPieceCountForTier(2, 3))
        assertEquals(1, CampaignDifficultyRules.armorPieceCountForTier(3, 3))
        assertEquals(2, CampaignDifficultyRules.armorPieceCountForTier(6, 3))
        assertEquals(1, CampaignDifficultyRules.armorPieceCountForTier(6, 6))
        assertEquals(1, CampaignDifficultyRules.armorPieceCountForTier(9, 9))
        assertEquals(1, CampaignDifficultyRules.armorPieceCountForTier(12, 12))
    }

    @Test
    fun `difficulty below two forces pillager members`() {
        val graph = mapOf("member_pillager" to 0.0, "member_vindicator" to 1.0)
        val random = Random(42L)
        repeat(20) {
            assertEquals("pillager", CampaignDifficultyRules.chooseMemberType(1, graph, random))
        }
    }

    @Test
    fun `difficulty two plus is preference driven`() {
        val graph = mapOf("member_pillager" to 0.0, "member_vindicator" to 1.0)
        val random = Random(7L)
        repeat(20) {
            assertEquals("vindicator", CampaignDifficultyRules.chooseMemberType(2, graph, random))
        }
    }
}
