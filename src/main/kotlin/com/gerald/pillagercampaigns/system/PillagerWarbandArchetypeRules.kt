package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.WarbandArchetype
import com.gerald.pillagercampaigns.data.WarbandRole
import net.minecraft.resources.ResourceLocation
import java.util.Random
import java.util.UUID

object PillagerWarbandArchetypeRules {
    enum class WeaponFamily {
        RANGED,
        MELEE,
        CASTER,
    }

    enum class ArmorProfile {
        LIGHT,
        MEDIUM,
        HEAVY,
        WARLORD,
    }

    data class RoleRules(
        val mobs: List<ResourceLocation>,
        val rareMobs: List<ResourceLocation> = emptyList(),
        val rareMobMinDifficulty: Int = Int.MAX_VALUE,
        val weaponFamily: WeaponFamily,
        val armorProfile: ArmorProfile,
    )

    fun select(seed: Long, warbandId: UUID): WarbandArchetype {
        val values = WarbandArchetype.entries
        val mixed = seed xor warbandId.mostSignificantBits xor java.lang.Long.rotateLeft(warbandId.leastSignificantBits, 17)
        return values[Math.floorMod(mixed.toInt(), values.size)]
    }

    fun rules(archetype: WarbandArchetype, role: WarbandRole): RoleRules = table.getValue(archetype).getValue(role)

    fun chooseMob(archetype: WarbandArchetype, role: WarbandRole, difficulty: Int, random: Random): ResourceLocation {
        val rules = rules(archetype, role)
        if (difficulty >= rules.rareMobMinDifficulty && rules.rareMobs.isNotEmpty() && random.nextInt(8) == 0) {
            return rules.rareMobs[random.nextInt(rules.rareMobs.size)]
        }
        return rules.mobs[random.nextInt(rules.mobs.size)]
    }

    fun chooseFollowerRole(difficulty: Int, memberIndex: Int, memberCount: Int, random: Random): WarbandRole {
        if (difficulty < 4) return WarbandRole.LINE
        if (memberIndex == memberCount - 1) return WarbandRole.SPECIALIST
        return if (random.nextInt(4) == 0) WarbandRole.SPECIALIST else WarbandRole.LINE
    }

    private val table: Map<WarbandArchetype, Map<WarbandRole, RoleRules>> = mapOf(
        WarbandArchetype.SKIRMISHER to mapOf(
            WarbandRole.WARLORD to RoleRules(mobs = mobs("takesapillage:archer"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.WARLORD),
            WarbandRole.CAPTAIN to RoleRules(mobs = mobs("minecraft:pillager"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.LINE to RoleRules(mobs = mobs("minecraft:pillager", "takesapillage:archer"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.SPECIALIST to RoleRules(
                mobs = mobs("takesapillage:skirmisher"),
                rareMobs = mobs("aquamirae:pillagers_patrol"),
                rareMobMinDifficulty = 8,
                weaponFamily = WeaponFamily.RANGED,
                armorProfile = ArmorProfile.LIGHT,
            ),
        ),
        WarbandArchetype.BLACKGUARD to mapOf(
            WarbandRole.WARLORD to RoleRules(mobs = mobs("savage_and_ravage:executioner"), weaponFamily = WeaponFamily.MELEE, armorProfile = ArmorProfile.WARLORD),
            WarbandRole.CAPTAIN to RoleRules(mobs = mobs("takesapillage:legioner"), weaponFamily = WeaponFamily.MELEE, armorProfile = ArmorProfile.HEAVY),
            WarbandRole.LINE to RoleRules(mobs = mobs("minecraft:vindicator", "takesapillage:legioner"), weaponFamily = WeaponFamily.MELEE, armorProfile = ArmorProfile.MEDIUM),
            WarbandRole.SPECIALIST to RoleRules(
                mobs = mobs("takesapillage:legioner", "savage_and_ravage:executioner"),
                rareMobs = mobs("companions:illager_golem"),
                rareMobMinDifficulty = 10,
                weaponFamily = WeaponFamily.MELEE,
                armorProfile = ArmorProfile.HEAVY,
            ),
        ),
        WarbandArchetype.HEX to mapOf(
            WarbandRole.WARLORD to RoleRules(mobs = mobs("minecraft:evoker"), weaponFamily = WeaponFamily.CASTER, armorProfile = ArmorProfile.WARLORD),
            WarbandRole.CAPTAIN to RoleRules(mobs = mobs("minecraft:witch"), weaponFamily = WeaponFamily.CASTER, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.LINE to RoleRules(mobs = mobs("minecraft:pillager", "minecraft:vindicator"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.SPECIALIST to RoleRules(
                mobs = mobs("minecraft:witch", "savage_and_ravage:trickster", "savage_and_ravage:iceologer"),
                rareMobs = mobs("minecraft:illusioner"),
                rareMobMinDifficulty = 12,
                weaponFamily = WeaponFamily.CASTER,
                armorProfile = ArmorProfile.LIGHT,
            ),
        ),
        WarbandArchetype.SABOTEUR to mapOf(
            WarbandRole.WARLORD to RoleRules(mobs = mobs("savage_and_ravage:griefer"), weaponFamily = WeaponFamily.MELEE, armorProfile = ArmorProfile.WARLORD),
            WarbandRole.CAPTAIN to RoleRules(mobs = mobs("takesapillage:skirmisher"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.LINE to RoleRules(mobs = mobs("minecraft:pillager", "takesapillage:skirmisher"), weaponFamily = WeaponFamily.RANGED, armorProfile = ArmorProfile.LIGHT),
            WarbandRole.SPECIALIST to RoleRules(mobs = mobs("savage_and_ravage:griefer", "savage_and_ravage:trickster"), weaponFamily = WeaponFamily.MELEE, armorProfile = ArmorProfile.LIGHT),
        ),
    )

    private fun mobs(vararg ids: String): List<ResourceLocation> = ids.map { ResourceLocation.tryParse(it) ?: ResourceLocation("minecraft", "pillager") }
}
