package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerWarband
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.library.materials.MaterialRegistry
import slimeknights.tconstruct.library.materials.definition.IMaterial
import slimeknights.tconstruct.library.materials.definition.MaterialVariant
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes
import slimeknights.tconstruct.library.tools.definition.module.material.ToolPartsHook
import slimeknights.tconstruct.library.tools.item.IModifiable
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT
import slimeknights.tconstruct.library.tools.nbt.ToolStack
import slimeknights.tconstruct.library.tools.stat.ToolStats

/**
 * Live TConstruct adapter. Selection is formulaic; all selected part material
 * units are charged before the finished stack enters the armory.
 */
object TinkersArmoryOptimizer {
    private const val COST_TAG = "PillagerMaterialCost"
    private const val FORMULATION_TAG = "PillagerTconFormulation"

    fun seedLedger(warband: PillagerWarband, amount: Double = 24.0) {
        if (warband.materialLedger.isNotEmpty()) return
        val materials = extractableMaterials(warband).take(3)
        if (materials.isEmpty()) return
        materials.forEach { material -> warband.materialLedger[material.identifier.toString()] = amount / materials.size }
    }

    fun extract(warband: PillagerWarband, amount: Double = 1.0): Boolean {
        val material = extractableMaterials(warband).maxByOrNull {
            it.tier * (warband.environment.mineralPotential + warband.environment.exoticPotential * it.tier) +
                deterministicFraction(warband, it.identifier.toString(), warband.materialLedger.size)
        } ?: return false
        val id = material.identifier.toString()
        warband.materialLedger[id] = warband.materialLedger.getOrDefault(id, 0.0) + amount.coerceAtLeast(0.0)
        return true
    }

    fun create(warband: PillagerWarband, server: MinecraftServer): ItemStack? {
        if (!MaterialRegistry.isFullyLoaded()) return null
        val materials = MaterialRegistry.getInstance().visibleMaterials.asSequence()
            .filter { it.isCraftable && !it.isHidden && warband.materialLedger.getOrDefault(it.identifier.toString(), 0.0) > 0.0 }
            .sortedBy { it.identifier.toString() }.toList()
        if (materials.isEmpty()) return null

        val partCosts = server.recipeManager.getAllRecipesFor(TinkerRecipeTypes.PART_BUILDER.get())
            .groupBy { recipe -> ForgeRegistries.ITEMS.getKey(recipe.getResultItem(server.registryAccess()).item)?.toString().orEmpty() }
            .mapValues { (_, recipes) -> recipes.minOf { it.cost }.coerceAtLeast(1) }

        val candidates = ForgeRegistries.ITEMS.values.asSequence().mapNotNull { item ->
            val modifiable = item as? IModifiable ?: return@mapNotNull null
            val definition = modifiable.toolDefinition
            if (!definition.hasMaterials() || !definition.isDataLoaded) return@mapNotNull null
            val parts = ToolPartsHook.parts(definition)
            if (parts.isEmpty()) return@mapNotNull null
            val available = warband.materialLedger.toMutableMap()
            val cost = linkedMapOf<String, Double>()
            val chosen = parts.mapIndexedNotNull { index, part ->
                val partId = ForgeRegistries.ITEMS.getKey(part.asItem())?.toString() ?: return@mapIndexedNotNull null
                val units = partCosts[partId]?.toDouble() ?: return@mapIndexedNotNull null
                materials.asSequence()
                    .filter { part.canUseMaterial(it.identifier) && available.getOrDefault(it.identifier.toString(), 0.0) >= units }
                    .maxByOrNull { material -> materialScore(warband, material, index) }
                    ?.also { material ->
                        val id = material.identifier.toString()
                        available[id] = available.getOrDefault(id, 0.0) - units
                        cost[id] = cost.getOrDefault(id, 0.0) + units
                    }?.let(MaterialVariant::of)
            }
            if (chosen.size != parts.size || cost.isEmpty()) return@mapNotNull null
            val tool = runCatching { ToolStack.createTool(item, definition, MaterialNBT(chosen)).also { it.rebuildStats() } }.getOrNull() ?: return@mapNotNull null
            val stats = tool.stats
            val attributes = mapOf(
                "durability" to stats.get(ToolStats.DURABILITY).toDouble() / 1000.0,
                "damage" to stats.get(ToolStats.ATTACK_DAMAGE).toDouble() / 10.0,
                "mobility" to stats.get(ToolStats.ATTACK_SPEED).toDouble() / 4.0,
                "range" to (stats.get(ToolStats.VELOCITY).toDouble() + stats.get(ToolStats.DRAW_SPEED).toDouble()) / 4.0,
            )
            val id = ForgeRegistries.ITEMS.getKey(item)?.toString() ?: return@mapNotNull null
            Triple(tool.createStack(), FormulaicWarbandRules.score(FormulaCandidate(id, 1.0, attributes), warband.preferences, emptyMap()), cost)
        }.toList()

        val selected = candidates.maxWithOrNull(
            compareBy<Triple<ItemStack, Double, Map<String, Double>>> { it.second }
                .thenByDescending { ForgeRegistries.ITEMS.getKey(it.first.item).toString() },
        ) ?: return null
        if (selected.third.any { (id, amount) -> warband.materialLedger.getOrDefault(id, 0.0) < amount }) return null
        selected.third.forEach { (id, amount) -> warband.materialLedger[id] = (warband.materialLedger.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0) }
        selected.first.orCreateTag.put(COST_TAG, CompoundTag().also { tag -> selected.third.forEach(tag::putDouble) })
        selected.first.orCreateTag.putString(FORMULATION_TAG, selected.first.tag?.getString("tic_materials").orEmpty())
        return selected.first
    }

    internal fun cost(stack: ItemStack): Map<String, Double> = stack.tag?.getCompound(COST_TAG)?.let { tag -> tag.allKeys.associateWith(tag::getDouble) }.orEmpty()

    private fun extractableMaterials(warband: PillagerWarband): List<IMaterial> {
        if (!MaterialRegistry.isFullyLoaded()) return emptyList()
        val available = warband.reserve * (0.5 + warband.environment.mineralPotential + warband.environment.exoticPotential)
        return MaterialRegistry.getInstance().visibleMaterials.asSequence().filter { it.isCraftable && !it.isHidden }
            .filter { material -> FormulaicWarbandRules.extractionThreshold(material.tier, WarbandFormulaData.ingredientWeights.keys.count { it.startsWith(material.identifier.toString()) }) <= available }
            .sortedBy { it.identifier.toString() }.toList()
    }

    private fun materialScore(warband: PillagerWarband, material: IMaterial, salt: Int): Double =
        material.tier * (1.0 + warband.preferences.getOrDefault("exotic", 0.0) * warband.environment.exoticPotential) +
            deterministicFraction(warband, material.identifier.toString(), salt)

    private fun deterministicFraction(warband: PillagerWarband, id: String, salt: Int): Double =
        ((warband.id.mostSignificantBits xor id.hashCode().toLong() xor salt.toLong()) and 1023L) / 1024.0
}
