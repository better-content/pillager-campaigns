package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerWarband
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.library.materials.MaterialRegistry
import slimeknights.tconstruct.library.materials.definition.MaterialVariant
import slimeknights.tconstruct.library.tools.definition.module.material.ToolPartsHook
import slimeknights.tconstruct.library.tools.item.IModifiable
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT
import slimeknights.tconstruct.library.tools.nbt.ToolStack
import slimeknights.tconstruct.library.tools.stat.ToolStats

/** Constructs actual TConstruct stacks from the current registries; it contains no named tool or material catalogue. */
object TinkersArmoryOptimizer {
    fun create(warband: PillagerWarband): ItemStack? {
        if (!MaterialRegistry.isFullyLoaded()) return null
        val registry = MaterialRegistry.getInstance()
        val available = warband.reserve * (0.5 + warband.environment.mineralPotential + warband.environment.exoticPotential)
        val materials = registry.visibleMaterials
            .asSequence().filter { it.isCraftable && !it.isHidden }
            .filter { material -> FormulaicWarbandRules.extractionThreshold(material.tier, WarbandFormulaData.ingredientWeights.keys.count { it.startsWith(material.identifier.toString()) }) <= available }
            .sortedBy { it.identifier.toString() }.toList()
        if (materials.isEmpty()) return null

        return ForgeRegistries.ITEMS.values.asSequence().mapNotNull { item ->
            val modifiable = item as? IModifiable ?: return@mapNotNull null
            val definition = modifiable.toolDefinition
            if (!definition.hasMaterials() || !definition.isDataLoaded) return@mapNotNull null
            val parts = ToolPartsHook.parts(definition)
            if (parts.isEmpty()) return@mapNotNull null
            val chosen = parts.mapIndexedNotNull { index, part ->
                materials.filter { part.canUseMaterial(it.identifier) }.maxByOrNull { material ->
                    val jitter = ((warband.id.mostSignificantBits xor material.identifier.hashCode().toLong() xor index.toLong()) and 1023L) / 1024.0
                    material.tier * (1.0 + warband.preferences.getOrDefault("exotic", 0.0) * warband.environment.exoticPotential) + jitter
                }?.let(MaterialVariant::of)
            }
            if (chosen.size != parts.size) return@mapNotNull null
            val tool = runCatching { ToolStack.createTool(item, definition, MaterialNBT(chosen)).also { it.rebuildStats() } }.getOrNull() ?: return@mapNotNull null
            val stats = tool.stats
            val attributes = mapOf(
                "durability" to stats.get(ToolStats.DURABILITY).toDouble() / 1000.0,
                "damage" to stats.get(ToolStats.ATTACK_DAMAGE).toDouble() / 10.0,
                "mobility" to stats.get(ToolStats.ATTACK_SPEED).toDouble() / 4.0,
                "range" to (stats.get(ToolStats.VELOCITY).toDouble() + stats.get(ToolStats.DRAW_SPEED).toDouble()) / 4.0,
            )
            val id = ForgeRegistries.ITEMS.getKey(item)?.toString() ?: return@mapNotNull null
            val score = FormulaicWarbandRules.score(FormulaCandidate(id, 1.0, attributes), warband.preferences, emptyMap())
            tool.createStack() to score
        }.maxByOrNull { it.second }?.first
    }
}
