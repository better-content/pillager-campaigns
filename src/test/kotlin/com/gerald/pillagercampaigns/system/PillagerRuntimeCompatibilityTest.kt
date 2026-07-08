package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class PillagerRuntimeCompatibilityTest {
    @Test
    fun `vindicator ranged requests fall back to melee`() {
        val resolved = PillagerRuntime.resolveWeaponFamily(
            ResourceLocation.tryParse("minecraft:vindicator"),
            PillagerWarbandArchetypeRules.WeaponFamily.RANGED,
        )

        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.MELEE, resolved)
    }

    @Test
    fun `pillager melee requests fall back to ranged`() {
        val resolved = PillagerRuntime.resolveWeaponFamily(
            ResourceLocation.tryParse("minecraft:pillager"),
            PillagerWarbandArchetypeRules.WeaponFamily.MELEE,
        )

        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.RANGED, resolved)
    }

    @Test
    fun `caster mobs preserve caster requests`() {
        val resolved = PillagerRuntime.resolveWeaponFamily(
            ResourceLocation.tryParse("minecraft:witch"),
            PillagerWarbandArchetypeRules.WeaponFamily.CASTER,
        )

        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.CASTER, resolved)
    }

    @Test
    fun `unknown mobs keep authored family`() {
        val resolved = PillagerRuntime.resolveWeaponFamily(
            ResourceLocation.tryParse("example:unknown_illager"),
            PillagerWarbandArchetypeRules.WeaponFamily.RANGED,
        )

        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.RANGED, resolved)
    }
}
