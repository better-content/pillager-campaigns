package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.OfficerAffix
import com.gerald.pillagercampaigns.data.OfficerDoctrine
import com.gerald.pillagercampaigns.data.OfficerEngineeringTalent
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.PillagerOfficer

data class OfficerArmorSlots(
    val helmet: String,
    val chestplate: String,
    val leggings: String,
    val boots: String,
)

data class OfficerLoadout(
    val armor: OfficerArmorSlots,
    val mainhand: String,
    val offhand: String?,
    val notes: List<String>,
) {
    val carriesBannerInOffhand: Boolean = offhand?.endsWith("_banner") == true
    val requiresBannerHelmetFallback: Boolean = !carriesBannerInOffhand
}

object OfficerLoadoutRules {
    fun forOfficer(officer: PillagerOfficer): OfficerLoadout =
        plan(officer.rank, officer.doctrine, officer.affixes, OfficerEngineeringRules.talentFor(officer))

    fun plan(
        rank: OfficerRank,
        doctrine: OfficerDoctrine,
        affixes: Set<OfficerAffix> = emptySet(),
        engineeringTalent: OfficerEngineeringTalent = OfficerEngineeringTalent.NONE,
    ): OfficerLoadout {
        val bannerCarrier = true
        val mainhand = mainhandFor(doctrine, affixes, bannerCarrier)
        val offhand = offhandFor(doctrine, affixes, bannerCarrier)
        val notes = buildList {
            add("rank: ${rank.readable()}")
            add("doctrine: ${doctrine.readable()}")
            add("banner intent: every officer is a visible named rally marker, not a boss aura")
            affixNotes(affixes).forEach(::add)
            engineeringNote(engineeringTalent)?.let(::add)
            add("runtime intent: vanilla item ids only; no enchantments, effects, or exotic boss powers")
        }

        return OfficerLoadout(
            armor = armorFor(rank, doctrine, affixes),
            mainhand = mainhand,
            offhand = offhand,
            notes = notes,
        )
    }

    private fun armorFor(rank: OfficerRank, doctrine: OfficerDoctrine, affixes: Set<OfficerAffix>): OfficerArmorSlots {
        if (OfficerAffix.IRONBOUND in affixes || doctrine == OfficerDoctrine.SURVIVOR) {
            return OfficerArmorSlots(
                helmet = "minecraft:iron_helmet",
                chestplate = "minecraft:iron_chestplate",
                leggings = "minecraft:iron_leggings",
                boots = "minecraft:iron_boots",
            )
        }

        return when (rank) {
            OfficerRank.SCOUT -> OfficerArmorSlots(
                helmet = "minecraft:leather_helmet",
                chestplate = "minecraft:chainmail_chestplate",
                leggings = "minecraft:leather_leggings",
                boots = if (OfficerAffix.SWIFT in affixes) "minecraft:leather_boots" else "minecraft:chainmail_boots",
            )
            OfficerRank.CAPTAIN -> OfficerArmorSlots(
                helmet = "minecraft:chainmail_helmet",
                chestplate = "minecraft:iron_chestplate",
                leggings = "minecraft:chainmail_leggings",
                boots = "minecraft:iron_boots",
            )
            OfficerRank.LIEUTENANT -> OfficerArmorSlots(
                helmet = "minecraft:iron_helmet",
                chestplate = "minecraft:iron_chestplate",
                leggings = "minecraft:chainmail_leggings",
                boots = "minecraft:iron_boots",
            )
            OfficerRank.WARLORD, OfficerRank.BANNERLORD -> OfficerArmorSlots(
                helmet = "minecraft:iron_helmet",
                chestplate = "minecraft:iron_chestplate",
                leggings = "minecraft:iron_leggings",
                boots = "minecraft:iron_boots",
            )
        }
    }

    private fun mainhandFor(doctrine: OfficerDoctrine, affixes: Set<OfficerAffix>, bannerCarrier: Boolean): String = when {
        doctrine == OfficerDoctrine.BREAKER -> "minecraft:iron_axe"
        doctrine == OfficerDoctrine.SIEGE_CAPTAIN -> "minecraft:crossbow"
        doctrine == OfficerDoctrine.HUNTER || doctrine == OfficerDoctrine.STALKER || OfficerAffix.LONGSHOT in affixes -> "minecraft:crossbow"
        doctrine == OfficerDoctrine.ARSONIST -> "minecraft:iron_axe"
        bannerCarrier -> "minecraft:iron_sword"
        doctrine == OfficerDoctrine.HEXER -> "minecraft:iron_sword"
        doctrine == OfficerDoctrine.SURVIVOR -> "minecraft:iron_sword"
        else -> "minecraft:iron_sword"
    }

    private fun offhandFor(doctrine: OfficerDoctrine, affixes: Set<OfficerAffix>, bannerCarrier: Boolean): String? = when {
        doctrine == OfficerDoctrine.HUNTER || doctrine == OfficerDoctrine.STALKER -> "minecraft:shield"
        doctrine == OfficerDoctrine.BREAKER -> "minecraft:shield"
        doctrine == OfficerDoctrine.SIEGE_CAPTAIN -> "minecraft:shield"
        doctrine == OfficerDoctrine.SURVIVOR -> "minecraft:shield"
        OfficerAffix.IRONBOUND in affixes -> "minecraft:shield"
        bannerCarrier -> "minecraft:white_banner"
        else -> null
    }

    private fun affixNotes(affixes: Set<OfficerAffix>): List<String> = affixes.sortedBy { it.name }.map { affix ->
        when (affix) {
            OfficerAffix.SWIFT -> "affix: swift scout pacing"
            OfficerAffix.LONGSHOT -> "affix: longshot crossbow preference"
            OfficerAffix.IRONBOUND -> "affix: ironbound durable armor"
            OfficerAffix.WITCH_TOUCHED -> "affix: witch-touched markings only"
            OfficerAffix.BANNERED -> "affix: bannered formation signal"
            OfficerAffix.ASHEN -> "affix: ashen firebreak colors"
            OfficerAffix.BEAST_CALLER -> "affix: beast-handler markings only"
            OfficerAffix.GRAVE_MARKED -> "affix: grave-marked trophy paint"
        }
    }

    private fun engineeringNote(talent: OfficerEngineeringTalent): String? = when (talent) {
        OfficerEngineeringTalent.NONE -> null
        OfficerEngineeringTalent.BRIDGER -> "engineering: bridge kit orders"
        OfficerEngineeringTalent.LADDERMASTER -> "engineering: ladder kit orders"
        OfficerEngineeringTalent.FIELD_ENGINEER -> "engineering: field engineer bridge and ladder orders"
    }

    private fun OfficerRank.readable(): String = name.lowercase().replace('_', ' ')

    private fun OfficerDoctrine.readable(): String = name.lowercase().replace('_', ' ')
}
