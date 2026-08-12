package com.gerald.pillagercampaigns.system

import com.gerald.warband.core.CapabilityVector
import com.gerald.warband.core.EquipmentComponentDefinition
import com.gerald.warband.core.EquipmentManifest
import com.gerald.warband.core.EquipmentPlatformDefinition
import com.gerald.warband.core.MaterialDefinition
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.common.TinkerTags
import slimeknights.tconstruct.library.materials.MaterialRegistry
import slimeknights.tconstruct.library.materials.definition.IMaterial
import slimeknights.tconstruct.library.materials.definition.MaterialId
import slimeknights.tconstruct.library.materials.definition.MaterialVariant
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes
import slimeknights.tconstruct.library.tools.definition.module.material.ToolPartsHook
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook
import slimeknights.tconstruct.library.tools.item.IModifiable
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT
import slimeknights.tconstruct.library.tools.nbt.ToolStack
import slimeknights.tconstruct.library.tools.stat.ToolStats
import slimeknights.tconstruct.tools.stats.GripMaterialStats
import slimeknights.tconstruct.tools.stats.HandleMaterialStats
import slimeknights.tconstruct.tools.stats.HeadMaterialStats
import slimeknights.tconstruct.tools.stats.LimbMaterialStats
import slimeknights.tconstruct.tools.stats.PlatingMaterialStats

/**
 * State-independent TConstruct registry adapter. It publishes raw platforms,
 * component compatibility and materials to Core, then realizes only the exact
 * formulation carried by a Core effect/manifest.
 */
object TinkersArmoryOptimizer {
    private const val COST_TAG = "PillagerMaterialCost"
    private const val FORMULATION_TAG = "PillagerTconFormulation"

    internal fun materialDefinitions(): List<MaterialDefinition> {
        if (!MaterialRegistry.isFullyLoaded()) return emptyList()
        val materials = MaterialRegistry.getInstance().visibleMaterials.filter { it.isCraftable && !it.isHidden }
        val raw = materials.associateWith(::materialCapabilities)
        fun scale(select: (CapabilityVector) -> Double): Double = raw.values.maxOfOrNull { kotlin.math.abs(select(it)) }
            ?.coerceAtLeast(0.0001) ?: 1.0
        val durabilityScale = scale(CapabilityVector::durability)
        val damageScale = scale(CapabilityVector::damage)
        val mobilityScale = scale(CapabilityVector::mobility)
        val rangeScale = scale(CapabilityVector::range)
        val controlScale = scale(CapabilityVector::control)
        return materials.asSequence().map { material ->
                val id = material.identifier.toString()
                val capabilities = raw.getValue(material)
                MaterialDefinition(
                    id,
                    material.tier.coerceAtLeast(1),
                    extractionThreshold(
                        material.tier,
                        WarbandFormulaData.ingredientWeights.keys.count { it.startsWith(id) },
                    ),
                    CapabilityVector(
                        capabilities.durability / durabilityScale,
                        capabilities.damage / damageScale,
                        capabilities.mobility / mobilityScale,
                        capabilities.range / rangeScale,
                        capabilities.control / controlScale,
                    ),
                )
            }.sortedBy(MaterialDefinition::id).toList()
    }

    internal fun equipmentPlatforms(server: MinecraftServer): List<EquipmentPlatformDefinition> {
        if (!MaterialRegistry.isFullyLoaded()) return emptyList()
        val materials = MaterialRegistry.getInstance().visibleMaterials.asSequence()
            .filter { it.isCraftable && !it.isHidden }
            .sortedBy { it.identifier.toString() }
            .toList()
        if (materials.isEmpty()) return emptyList()

        val partCosts = server.recipeManager.getAllRecipesFor(TinkerRecipeTypes.PART_BUILDER.get())
            .groupBy { recipe -> ForgeRegistries.ITEMS.getKey(recipe.getResultItem(server.registryAccess()).item)?.toString().orEmpty() }
            .mapValues { (_, recipes) -> recipes.minOf { it.cost }.coerceAtLeast(1) }

        return ForgeRegistries.ITEMS.values.asSequence().mapNotNull { item ->
            val modifiable = item as? IModifiable ?: return@mapNotNull null
            val definition = modifiable.toolDefinition
            if (!definition.hasMaterials() || !definition.isDataLoaded) return@mapNotNull null
            val parts = ToolPartsHook.parts(definition)
            val statTypes = ToolMaterialHook.stats(definition)
            if (statTypes.isEmpty()) return@mapNotNull null
            val components = statTypes.mapIndexed { index, statType ->
                val part = parts.getOrNull(index)
                val partId = part?.let { ForgeRegistries.ITEMS.getKey(it.asItem())?.toString() }
                val units = partId?.let(partCosts::get)?.toDouble() ?: 1.0
                val compatible = materials.filter { material ->
                    part?.canUseMaterial(material.identifier)
                        ?: MaterialRegistry.getInstance().getAllStats(material.identifier).any { it.identifier == statType }
                }.mapTo(linkedSetOf()) { it.identifier.toString() }
                EquipmentComponentDefinition(
                    id = partId ?: "${ForgeRegistries.ITEMS.getKey(item)}:material:$index",
                    statKind = statType.toString(),
                    compatibleMaterialIds = compatible,
                    requiredUnits = units,
                )
            }
            if (components.any { it.compatibleMaterialIds.isEmpty() }) return@mapNotNull null
            val itemId = ForgeRegistries.ITEMS.getKey(item)?.toString() ?: return@mapNotNull null
            val emptyStack = ItemStack(item)
            EquipmentPlatformDefinition(
                id = itemId,
                equipmentSlot = equipmentSlot(emptyStack).name.lowercase(),
                supportedActions = actions(emptyStack),
                components = components,
            )
        }.sortedBy(EquipmentPlatformDefinition::id).toList()
    }

    /** Returns null on any registry, compatibility or construction failure. */
    internal fun realize(manifest: EquipmentManifest): ItemStack? {
        if (!MaterialRegistry.isFullyLoaded() || manifest.formulation.isEmpty()) return null
        val suffix = ":${manifest.formulation.joinToString("+")}"
        if (!manifest.definitionId.endsWith(suffix)) return null
        val platformId = manifest.definitionId.removeSuffix(suffix)
        val item = net.minecraft.resources.ResourceLocation.tryParse(platformId)
            ?.let(ForgeRegistries.ITEMS::getValue) as? IModifiable ?: return null
        val materials = manifest.formulation.map { id ->
            val materialId = MaterialId.tryParse(id) ?: return null
            val material = MaterialRegistry.getInstance().getMaterial(materialId)
            if (material.identifier != materialId) return null
            MaterialVariant.of(material)
        }
        val definition = item.toolDefinition
        val tool = runCatching {
            ToolStack.createTool(item.asItem(), definition, MaterialNBT(materials)).also { it.rebuildStats() }
        }.getOrNull() ?: return null
        return tool.createStack().also { stack ->
            stack.orCreateTag.put(COST_TAG, CompoundTag().also { tag -> manifest.billOfMaterials.forEach(tag::putDouble) })
            stack.orCreateTag.putString(FORMULATION_TAG, manifest.formulation.joinToString(","))
        }
    }

    internal fun cost(stack: ItemStack): Map<String, Double> = stack.tag?.getCompound(COST_TAG)?.let { tag -> tag.allKeys.associateWith(tag::getDouble) }.orEmpty()

    internal fun manifest(id: String, stack: ItemStack): EquipmentManifest {
        val toolStats = runCatching { ToolStack.from(stack).stats }.getOrNull()
        val capabilities = toolStats?.let { capabilities(ToolStack.from(stack), stack) } ?: CapabilityVector()
        val itemId = ForgeRegistries.ITEMS.getKey(stack.item)?.toString().orEmpty()
        val formulation = stack.tag?.getString(FORMULATION_TAG)?.split(',')?.filter(String::isNotBlank).orEmpty()
        val durability = if (stack.isDamageableItem) 1.0 - stack.damageValue.toDouble() / stack.maxDamage.coerceAtLeast(1) else 1.0
        val definitionId = if (formulation.isEmpty()) itemId else "$itemId:${formulation.joinToString("+")}"
        return EquipmentManifest(id, definitionId, formulation, cost(stack), capabilities, actions(stack), durability.coerceIn(0.0, 1.0))
    }

    /** TCon's own functional tags are authoritative; numeric ranged defaults are not item semantics. */
    internal fun actions(stack: ItemStack): Set<String> = buildSet {
        if (stack.`is`(TinkerTags.Items.MELEE)) add("melee")
        if (stack.`is`(TinkerTags.Items.RANGED) || stack.`is`(TinkerTags.Items.AMMO)) add("ranged")
        if (stack.`is`(TinkerTags.Items.ARMOR) || stack.`is`(TinkerTags.Items.SHIELDS)) add("defense")
        if (stack.`is`(TinkerTags.Items.HARVEST) || stack.`is`(TinkerTags.Items.FISHING_RODS)) add("utility")
    }

    internal fun equipmentSlot(stack: ItemStack): EquipmentSlot = when {
        stack.`is`(TinkerTags.Items.HELMETS) -> EquipmentSlot.HEAD
        stack.`is`(TinkerTags.Items.CHESTPLATES) -> EquipmentSlot.CHEST
        stack.`is`(TinkerTags.Items.LEGGINGS) -> EquipmentSlot.LEGS
        stack.`is`(TinkerTags.Items.BOOTS) -> EquipmentSlot.FEET
        stack.`is`(TinkerTags.Items.SHIELDS) -> EquipmentSlot.OFFHAND
        else -> EquipmentSlot.MAINHAND
    }

    private fun capabilities(tool: ToolStack, stack: ItemStack): CapabilityVector {
        val stats = tool.stats
        val ranged = stack.`is`(TinkerTags.Items.RANGED) || stack.`is`(TinkerTags.Items.AMMO)
        return CapabilityVector(
            durability = stats.get(ToolStats.DURABILITY).toDouble() / 1000.0 +
                stats.get(ToolStats.ARMOR).toDouble() / 10.0 + stats.get(ToolStats.ARMOR_TOUGHNESS).toDouble() / 10.0 +
                stats.get(ToolStats.BLOCK_AMOUNT).toDouble() / 10.0,
            damage = stats.get(ToolStats.ATTACK_DAMAGE).toDouble() / 10.0 +
                stats.get(ToolStats.PROJECTILE_DAMAGE).toDouble() / 10.0,
            mobility = stats.get(ToolStats.ATTACK_SPEED).toDouble() / 4.0 + stats.get(ToolStats.MINING_SPEED).toDouble() / 10.0,
            range = if (ranged) (stats.get(ToolStats.VELOCITY).toDouble() + stats.get(ToolStats.DRAW_SPEED).toDouble()) / 4.0 else 0.0,
            control = stats.get(ToolStats.ACCURACY).toDouble() + stats.get(ToolStats.BLOCK_ANGLE).toDouble() / 180.0 +
                stats.get(ToolStats.KNOCKBACK_RESISTANCE).toDouble(),
        )
    }

    /**
     * Projects the live TCon material-stat registry into the Core's shared
     * capability space. Averaging prevents materials with more compatible part
     * types from winning merely because they expose more records.
     */
    private fun materialCapabilities(material: IMaterial): CapabilityVector {
        val vectors = MaterialRegistry.getInstance().getAllStats(material.identifier).mapNotNull { stats ->
            when (stats) {
                is HeadMaterialStats -> CapabilityVector(
                    durability = stats.durability().toDouble() / 1000.0,
                    damage = stats.attack().toDouble() / 10.0,
                    mobility = stats.miningSpeed().toDouble() / 10.0,
                )
                is HandleMaterialStats -> CapabilityVector(
                    durability = stats.durability().toDouble(),
                    damage = stats.attackDamage().toDouble(),
                    mobility = (stats.meleeSpeed() + stats.miningSpeed()).toDouble() / 2.0,
                )
                is LimbMaterialStats -> CapabilityVector(
                    durability = stats.durability().toDouble() / 1000.0,
                    range = (stats.drawSpeed() + stats.velocity()).toDouble() / 2.0,
                    control = stats.accuracy().toDouble(),
                )
                is GripMaterialStats -> CapabilityVector(
                    durability = stats.durability().toDouble(),
                    damage = stats.meleeDamage().toDouble(),
                    control = stats.accuracy().toDouble(),
                )
                is PlatingMaterialStats -> CapabilityVector(
                    durability = stats.durability().toDouble() / 1000.0 + stats.armor().toDouble() / 10.0 +
                        stats.toughness().toDouble() / 10.0,
                    control = stats.knockbackResistance().toDouble(),
                )
                else -> null
            }
        }
        if (vectors.isEmpty()) return CapabilityVector()
        return vectors.fold(CapabilityVector(), CapabilityVector::plus) * (1.0 / vectors.size)
    }

    private fun extractionThreshold(tier: Int, additionalIngredientGroups: Int): Double =
        12.0 * tier.coerceAtLeast(1) * tier.coerceAtLeast(1) *
            (1.0 + 0.25 * additionalIngredientGroups.coerceAtLeast(0))
}
