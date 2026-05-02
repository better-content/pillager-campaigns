package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.OfficerClass
import java.util.Random
import kotlin.random.Random as KRandom

object CampaignDifficultyRules {
    private val memberThresholds = listOf(2, 6, 10, 14)

    fun officerClassForDifficulty(difficulty: Int): OfficerClass = when {
        difficulty >= 16 -> OfficerClass.ILLUSIONER
        difficulty >= 12 -> OfficerClass.EVOKER
        difficulty >= 8 -> OfficerClass.WITCH
        difficulty >= 4 -> OfficerClass.VINDICATOR
        else -> OfficerClass.PILLAGER
    }

    fun memberCountForDifficulty(difficulty: Int): Int {
        return 1 + memberThresholds.count { difficulty >= it }
    }

    fun enchantTierForDifficulty(difficulty: Int): Int {
        return (difficulty / 4).coerceAtMost(3).coerceAtLeast(0)
    }

    fun armorPieceCountForTier(difficulty: Int, every: Int): Int {
        if (difficulty < every) return 0
        return ((difficulty - every) / every) + 1
    }

    fun chooseMemberType(difficulty: Int, preferenceGraph: Map<String, Double>, random: Random): String {
        if (difficulty < 2) return "pillager"
        return weightedChoice(preferenceGraph, random, listOf("member_pillager", "member_vindicator")).removePrefix("member_")
    }

    fun defaultPreferenceGraph(seed: Long): MutableMap<String, Double> {
        val random = KRandom(seed)
        val keys = listOf(
            "weapon_crossbow", "weapon_bow", "weapon_sword", "weapon_axe", "weapon_trident",
            "member_pillager", "member_vindicator",
            "slot_head", "slot_chest", "slot_legs", "slot_feet",
            "enchant_sharpness", "enchant_smite", "enchant_bane", "enchant_protection",
            "enchant_proj_prot", "enchant_blast_prot", "enchant_fire_prot", "enchant_unbreaking", "enchant_power", "enchant_quick_charge",
        )
        return keys.associateWith { random.nextDouble(0.05, 1.0) }.toMutableMap()
    }

    fun weightedChoice(graph: Map<String, Double>, random: Random, keys: List<String>): String {
        val sum = keys.sumOf { graph[it] ?: 0.1 }
        var pick = random.nextDouble() * sum
        keys.forEach { key ->
            pick -= graph[key] ?: 0.1
            if (pick <= 0.0) return key
        }
        return keys.last()
    }
}
