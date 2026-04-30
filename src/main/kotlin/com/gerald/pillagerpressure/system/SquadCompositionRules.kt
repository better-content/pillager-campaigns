package com.gerald.pillagerpressure.system

import com.gerald.pillagerpressure.data.OfficerDoctrine
import com.gerald.pillagerpressure.data.OfficerEngineeringTalent
import com.gerald.pillagerpressure.data.OfficerRank

/** Pure squad composition planner. Entity ids are strings so runtime spawn integration stays separate. */
object SquadCompositionRules {
    const val PILLAGER = "minecraft:pillager"
    const val VINDICATOR = "minecraft:vindicator"
    const val WITCH = "minecraft:witch"
    const val EVOKER = "minecraft:evoker"
    const val RAVAGER = "minecraft:ravager"

    const val ARCHER = "takesapillage:archer"
    const val SKIRMISHER = "takesapillage:skirmisher"
    const val LEGIONER = "takesapillage:legioner"
    const val INFANTRY = "pillager_pressure:infantry"
    const val ENGINEER = "pillager_pressure:engineer"
    const val BANNER_GUARD = "pillager_pressure:banner_guard"

    fun plan(
        doctrine: OfficerDoctrine,
        rank: OfficerRank,
        engineeringTalent: OfficerEngineeringTalent = OfficerEngineeringTalent.NONE,
        pressure: SquadCompositionPressure = SquadCompositionPressure.neutral(),
    ): SquadCompositionPlan {
        val manifest = linkedMapOf<String, Int>()
        val size = rankSize(rank)
        add(manifest, PILLAGER, 1)

        when (doctrine) {
            OfficerDoctrine.HUNTER -> {
                add(manifest, ARCHER, 2 + rankBonus(rank) + pressure.rangeStep())
                add(manifest, SKIRMISHER, 1 + pressure.speedStep())
            }
            OfficerDoctrine.BREAKER -> {
                add(manifest, VINDICATOR, 1 + rankBonus(rank) + pressure.meleeStep())
                add(manifest, LEGIONER, 2 + pressure.armorStep())
            }
            OfficerDoctrine.SIEGE_CAPTAIN -> {
                add(manifest, INFANTRY, 2 + rankBonus(rank))
                add(manifest, ENGINEER, 1 + engineeringBonus(engineeringTalent) + pressure.siegeStep())
            }
            OfficerDoctrine.HEXER -> {
                add(manifest, WITCH, 1 + rankBonus(rank).coerceAtMost(2) + pressure.magicStep())
                if (rank >= OfficerRank.WARLORD || pressure.magic >= 85) add(manifest, EVOKER, 1)
            }
            OfficerDoctrine.BEASTMASTER -> {
                add(manifest, SKIRMISHER, 1 + pressure.speedStep())
                add(manifest, VINDICATOR, 1 + rankBonus(rank).coerceAtMost(1))
                if (rank >= OfficerRank.WARLORD && pressure.beast >= 80) add(manifest, RAVAGER, 1)
            }
            OfficerDoctrine.STANDARD -> {
                add(manifest, BANNER_GUARD, 2 + rankBonus(rank))
                add(manifest, VINDICATOR, 1 + pressure.meleeStep().coerceAtMost(1))
                add(manifest, ARCHER, 1 + pressure.rangeStep().coerceAtMost(1))
            }
            OfficerDoctrine.STALKER -> {
                add(manifest, SKIRMISHER, 2 + rankBonus(rank).coerceAtMost(1) + pressure.speedStep())
                add(manifest, ARCHER, 1)
            }
            OfficerDoctrine.SURVIVOR -> {
                add(manifest, INFANTRY, 2 + pressure.survivalStep())
                add(manifest, LEGIONER, 1 + pressure.armorStep())
                add(manifest, WITCH, if (rank >= OfficerRank.LIEUTENANT) 1 else 0)
            }
            OfficerDoctrine.ARSONIST -> {
                add(manifest, PILLAGER, 1 + pressure.fireStep())
                add(manifest, SKIRMISHER, 1)
                add(manifest, ENGINEER, if (engineeringTalent != OfficerEngineeringTalent.NONE) 1 else 0)
            }
            OfficerDoctrine.RAIDER -> {
                add(manifest, VINDICATOR, 1 + pressure.meleeStep().coerceAtMost(1))
                add(manifest, ARCHER, 1 + pressure.rangeStep().coerceAtMost(1))
                add(manifest, SKIRMISHER, 1 + pressure.speedStep().coerceAtMost(1))
            }
        }

        if (engineeringTalent != OfficerEngineeringTalent.NONE && doctrine != OfficerDoctrine.SIEGE_CAPTAIN) {
            add(manifest, ENGINEER, engineeringBonus(engineeringTalent).coerceAtLeast(1))
        }

        trimToSize(manifest, size)
        return SquadCompositionPlan(manifest.toMap(), summarize(doctrine, rank, manifest))
    }

    fun fallbackManifest(manifest: Map<String, Int>, availableEntityIds: Set<String>): SquadCompositionPlan {
        val resolved = linkedMapOf<String, Int>()
        manifest.forEach { (entityId, count) ->
            if (count <= 0) return@forEach
            val target = if (entityId in availableEntityIds) entityId else fallbackFor(entityId)
            if (target in availableEntityIds) add(resolved, target, count)
        }
        return SquadCompositionPlan(resolved.toMap(), "available roster: ${resolved.values.sum()} members across ${resolved.size} entity types")
    }

    fun summarize(doctrine: OfficerDoctrine, rank: OfficerRank, manifest: Map<String, Int>): String {
        val total = manifest.values.sum()
        val headline = manifest.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3)
            .joinToString(", ") { "${it.value} ${it.key.substringAfter(':')}" }
        return "${doctrine.name.lowercase()} ${rank.name.lowercase()}: $total members${if (headline.isBlank()) "" else " ($headline)"}"
    }

    private fun fallbackFor(entityId: String): String = when (entityId) {
        ARCHER, SKIRMISHER, BANNER_GUARD, INFANTRY, ENGINEER -> PILLAGER
        LEGIONER -> VINDICATOR
        EVOKER -> WITCH
        else -> entityId
    }

    private fun rankSize(rank: OfficerRank): Int = when (rank) {
        OfficerRank.SCOUT -> 4
        OfficerRank.CAPTAIN -> 6
        OfficerRank.LIEUTENANT -> 8
        OfficerRank.WARLORD -> 10
        OfficerRank.BANNERLORD -> 12
    }

    private fun rankBonus(rank: OfficerRank): Int = when (rank) {
        OfficerRank.SCOUT -> 0
        OfficerRank.CAPTAIN -> 1
        OfficerRank.LIEUTENANT -> 2
        OfficerRank.WARLORD -> 3
        OfficerRank.BANNERLORD -> 4
    }

    private fun engineeringBonus(talent: OfficerEngineeringTalent): Int = when (talent) {
        OfficerEngineeringTalent.NONE -> 0
        OfficerEngineeringTalent.BRIDGER, OfficerEngineeringTalent.LADDERMASTER -> 1
        OfficerEngineeringTalent.FIELD_ENGINEER -> 2
    }

    private fun trimToSize(manifest: MutableMap<String, Int>, maxSize: Int) {
        while (manifest.values.sum() > maxSize) {
            val entry = manifest.entries.lastOrNull { it.value > 0 } ?: return
            if (entry.value == 1) manifest.remove(entry.key) else manifest[entry.key] = entry.value - 1
        }
    }

    private fun add(manifest: MutableMap<String, Int>, entityId: String, count: Int) {
        if (count > 0) manifest[entityId] = (manifest[entityId] ?: 0) + count
    }
}

data class SquadCompositionPlan(
    val manifest: Map<String, Int>,
    val summary: String,
)

data class SquadCompositionPressure(
    val range: Int,
    val melee: Int,
    val speed: Int,
    val armor: Int,
    val magic: Int,
    val beast: Int,
    val fire: Int,
    val siege: Int,
    val survival: Int,
) {
    fun rangeStep(): Int = step(range)
    fun meleeStep(): Int = step(melee)
    fun speedStep(): Int = step(speed)
    fun armorStep(): Int = step(armor)
    fun magicStep(): Int = step(magic)
    fun fireStep(): Int = step(fire)
    fun siegeStep(): Int = step(siege)
    fun survivalStep(): Int = step(survival)

    companion object {
        fun neutral(value: Int = 35): SquadCompositionPressure = SquadCompositionPressure(value, value, value, value, value, value, value, value, value)
        fun fromGenes(genes: com.gerald.pillagerpressure.data.OfficerGeneProfile): SquadCompositionPressure =
            SquadCompositionPressure(genes.range, genes.melee, genes.speed, genes.armor, genes.magic, genes.beast, genes.fire, genes.siege, genes.survival)
        private fun step(value: Int): Int = when {
            value >= 85 -> 2
            value >= 65 -> 1
            else -> 0
        }
    }
}
