package com.gerald.pillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
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

    @Test
    fun `guaranteed drop slots cover all carried equipment`() {
        val expected = listOf(
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
        )

        assertEquals(expected, PillagerRuntime.guaranteedDropSlots())
    }

    @Test
    fun `campaign followers scale coin rarity and quantity with strength`() {
        assertEquals(
            listOf(PillagerRuntime.CoinRewardPlan("createdeco:copper_coin", 1)),
            PillagerRuntime.coinRewardPlan(1, PillagerRuntime.CoinRewardRole.FOLLOWER),
        )
        assertEquals(
            listOf(PillagerRuntime.CoinRewardPlan("createdeco:industrial_iron_coin", 3)),
            PillagerRuntime.coinRewardPlan(5, PillagerRuntime.CoinRewardRole.FOLLOWER),
        )
        assertEquals(
            listOf(PillagerRuntime.CoinRewardPlan("createdeco:gold_coin", 5)),
            PillagerRuntime.coinRewardPlan(9, PillagerRuntime.CoinRewardRole.FOLLOWER),
        )
    }

    @Test
    fun `captains and warlords award larger higher tier coin bundles`() {
        assertEquals(
            listOf(PillagerRuntime.CoinRewardPlan("createdeco:zinc_coin", 2)),
            PillagerRuntime.coinRewardPlan(1, PillagerRuntime.CoinRewardRole.CAPTAIN),
        )
        assertEquals(
            listOf(PillagerRuntime.CoinRewardPlan("createdeco:gold_coin", 7)),
            PillagerRuntime.coinRewardPlan(7, PillagerRuntime.CoinRewardRole.WARLORD),
        )
    }
}
