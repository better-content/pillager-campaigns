package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.WarbandArchetype
import com.gerald.pillagercampaigns.data.WarbandRole
import net.minecraft.resources.ResourceLocation
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PillagerWarbandArchetypeRulesTest {
    @Test
    fun `archetype selection is deterministic for seed and warband`() {
        val warbandId = UUID.fromString("11111111-2222-3333-4444-555555555555")

        val first = PillagerWarbandArchetypeRules.select(1234L, warbandId)
        val second = PillagerWarbandArchetypeRules.select(1234L, warbandId)

        assertEquals(first, second)
    }

    @Test
    fun `first slice role mappings match intended mob palettes`() {
        assertEquals(listOf(id("takesapillage:archer")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.SKIRMISHER, WarbandRole.WARLORD).mobs)
        assertEquals(listOf(id("savage_and_ravage:executioner")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.BLACKGUARD, WarbandRole.WARLORD).mobs)
        assertEquals(listOf(id("minecraft:evoker")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.HEX, WarbandRole.WARLORD).mobs)
        assertEquals(listOf(id("savage_and_ravage:griefer")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.SABOTEUR, WarbandRole.WARLORD).mobs)
        assertEquals(listOf(id("minecraft:pillager")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.HEX, WarbandRole.LINE).mobs)
        assertEquals(listOf(id("takesapillage:legioner"), id("savage_and_ravage:executioner")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.BLACKGUARD, WarbandRole.SPECIALIST).mobs)
        assertEquals(listOf(id("minecraft:illusioner")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.HEX, WarbandRole.SPECIALIST).rareMobs)
        assertEquals(listOf(id("aquamirae:pillagers_patrol")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.SKIRMISHER, WarbandRole.SPECIALIST).rareMobs)
        assertEquals(listOf(id("companions:illager_golem")), PillagerWarbandArchetypeRules.rules(WarbandArchetype.BLACKGUARD, WarbandRole.SPECIALIST).rareMobs)
    }

    @Test
    fun `gear families are constrained by archetype and role`() {
        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.RANGED, PillagerWarbandArchetypeRules.rules(WarbandArchetype.SKIRMISHER, WarbandRole.LINE).weaponFamily)
        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.MELEE, PillagerWarbandArchetypeRules.rules(WarbandArchetype.BLACKGUARD, WarbandRole.LINE).weaponFamily)
        assertEquals(PillagerWarbandArchetypeRules.WeaponFamily.CASTER, PillagerWarbandArchetypeRules.rules(WarbandArchetype.HEX, WarbandRole.SPECIALIST).weaponFamily)
        assertEquals(PillagerWarbandArchetypeRules.ArmorProfile.WARLORD, PillagerWarbandArchetypeRules.rules(WarbandArchetype.SABOTEUR, WarbandRole.WARLORD).armorProfile)
    }

    @Test
    fun `low pressure followers stay line troops and higher pressure gains specialists deterministically`() {
        val low = PillagerWarbandArchetypeRules.chooseFollowerRole(3, 0, 3, Random(1L))
        val highLast = PillagerWarbandArchetypeRules.chooseFollowerRole(4, 2, 3, Random(1L))

        assertEquals(WarbandRole.LINE, low)
        assertEquals(WarbandRole.SPECIALIST, highLast)
    }

    @Test
    fun `chosen mobs stay inside role palette except rare specialist expansion`() {
        val random = Random(5L)
        val rules = PillagerWarbandArchetypeRules.rules(WarbandArchetype.SABOTEUR, WarbandRole.SPECIALIST)
        repeat(20) {
            val chosen = PillagerWarbandArchetypeRules.chooseMob(WarbandArchetype.SABOTEUR, WarbandRole.SPECIALIST, 12, random)
            assertTrue(chosen in rules.mobs || chosen in rules.rareMobs)
        }
    }

    @Test
    fun `rare outliers stay out of early campaigns`() {
        val lowRules = PillagerWarbandArchetypeRules.rules(WarbandArchetype.BLACKGUARD, WarbandRole.SPECIALIST)
        val lowRandom = Random(0L)
        repeat(20) {
            val chosen = PillagerWarbandArchetypeRules.chooseMob(WarbandArchetype.BLACKGUARD, WarbandRole.SPECIALIST, 4, lowRandom)
            assertTrue(chosen in lowRules.mobs)
        }
    }

    private fun id(value: String): ResourceLocation = ResourceLocation.tryParse(value)!!
}
