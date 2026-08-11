package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.engine.EnvironmentTraits
import com.gerald.pillagercampaigns.engine.ResourceDefinition
import com.gerald.pillagercampaigns.engine.ResourceVector
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.Tags
import net.minecraftforge.registries.ForgeRegistries

/** Derives logistics uses from registry-visible item behavior and tags. */
object WarbandResourceCatalog {
    fun definitions(): List<ResourceDefinition> = ForgeRegistries.ITEMS.values.asSequence().mapNotNull { item ->
        val id = ForgeRegistries.ITEMS.getKey(item)?.toString() ?: return@mapNotNull null
        val stack = ItemStack(item)
        val food = item.foodProperties
        val sustenance = food?.let { it.nutrition + it.saturationModifier * it.nutrition * 2.0 } ?: 0.0
        val munitions = if (item is ArrowItem) 1.0 else 0.0
        val woody = stack.`is`(ItemTags.PLANKS) || stack.`is`(ItemTags.LOGS)
        val mineral = stack.`is`(Tags.Items.INGOTS) || stack.`is`(Tags.Items.NUGGETS)
        val maintenance = when {
            mineral -> 2.0
            woody -> 1.0
            else -> 0.0
        }
        val recovery = food?.nutrition?.times(food.saturationModifier)?.times(0.2) ?: 0.0
        val units = ResourceVector(sustenance, munitions, maintenance, recovery)
        if (units.sum() <= 0.0) return@mapNotNull null
        ResourceDefinition(
            itemId = id,
            unitsPerItem = units,
            mass = (1.0 + maintenance * 0.5 + munitions * 0.1).coerceAtLeast(0.1),
            environmentalAffinity = EnvironmentTraits(
                habitability = if (food != null) 0.8 else 0.1,
                biomass = if (food != null || woody) 1.0 else 0.1,
                mineralPotential = if (mineral || item is ArrowItem) 1.0 else 0.1,
                exoticPotential = if (stack.rarity.ordinal > 1) 0.8 else 0.1,
                travelFriction = if (mineral) 0.7 else 0.2,
            ),
            maximumStackSize = stack.maxStackSize.coerceAtLeast(1),
        )
    }.sortedBy(ResourceDefinition::itemId).toList()
}
