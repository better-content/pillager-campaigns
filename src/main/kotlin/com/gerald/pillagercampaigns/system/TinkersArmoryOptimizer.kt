package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.engine.CapabilityVector
import com.gerald.pillagercampaigns.engine.ChunkPosition
import com.gerald.pillagercampaigns.engine.EngineCatalog
import com.gerald.pillagercampaigns.engine.EngineState
import com.gerald.pillagercampaigns.engine.EquipmentDefinition
import com.gerald.pillagercampaigns.engine.EquipmentManifest
import com.gerald.pillagercampaigns.engine.MaterialDefinition
import com.gerald.pillagercampaigns.engine.WarbandEngine
import com.gerald.pillagercampaigns.engine.WarbandState
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.common.TinkerTags
import slimeknights.tconstruct.library.materials.MaterialRegistry
import slimeknights.tconstruct.library.materials.definition.IMaterial
import slimeknights.tconstruct.library.materials.definition.MaterialVariant
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes
import slimeknights.tconstruct.library.tools.definition.module.material.ToolPartsHook
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook
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

    internal data class LiveEquipmentCandidate(val definition: EquipmentDefinition, val stack: ItemStack)

    fun seedLedger(warband: PillagerWarband, amount: Double = 24.0) {
        if (warband.materialLedger.isNotEmpty()) return
        val materials = extractableMaterials(warband).take(3)
        if (materials.isEmpty()) return
        materials.forEach { material -> warband.materialLedger[material.identifier.toString()] = amount / materials.size }
    }

    fun extract(warband: PillagerWarband, amount: Double = 1.0): Boolean {
        val core = engineWarband(warband)
        val state = EngineState(sequence = warband.materialLedger.size.toLong(), warbands = linkedMapOf(core.id to core))
        val material = WarbandEngine.chooseMaterial(state, core, EngineCatalog("live-tcon", emptyList(), materialDefinitions(warband))) ?: return false
        val id = material.id
        warband.materialLedger[id] = warband.materialLedger.getOrDefault(id, 0.0) + amount.coerceAtLeast(0.0)
        warband.materialSelectionMemory[id] = warband.materialSelectionMemory.getOrDefault(id, 0.0) + 1.0
        return true
    }

    fun create(warband: PillagerWarband, server: MinecraftServer): ItemStack? {
        val candidates = liveEquipmentCandidates(warband, server)
        if (candidates.isEmpty()) return null
        val core = engineWarband(warband)
        val state = EngineState(sequence = warband.armory.size.toLong(), warbands = linkedMapOf(core.id to core))
        val selected = WarbandEngine.chooseEquipment(
            state, core, EngineCatalog("live-tcon", emptyList(), equipment = candidates.map(LiveEquipmentCandidate::definition)),
        ) ?: return null
        return realize(warband, candidates.single { it.definition.id == selected.id }, consume = true)?.also {
            warband.equipmentSelectionMemory[selected.id] = warband.equipmentSelectionMemory.getOrDefault(selected.id, 0.0) + 1.0
        }
    }

    internal fun materialDefinitions(warband: PillagerWarband): List<MaterialDefinition> =
        if (!MaterialRegistry.isFullyLoaded()) emptyList() else MaterialRegistry.getInstance().visibleMaterials.asSequence()
            .filter { it.isCraftable && !it.isHidden }
            .map { material ->
                val id = material.identifier.toString()
                MaterialDefinition(
                    id,
                    material.tier.coerceAtLeast(1),
                    FormulaicWarbandRules.extractionThreshold(
                        material.tier,
                        WarbandFormulaData.ingredientWeights.keys.count { it.startsWith(id) },
                    ),
                    CapabilityVector(durability = material.tier.toDouble(), control = deterministicFraction(warband, id, 0)),
                )
            }.sortedBy(MaterialDefinition::id).toList()

    internal fun liveEquipmentCandidates(warband: PillagerWarband, server: MinecraftServer): List<LiveEquipmentCandidate> {
        if (!MaterialRegistry.isFullyLoaded()) return emptyList()
        val materials = MaterialRegistry.getInstance().visibleMaterials.asSequence()
            .filter { it.isCraftable && !it.isHidden && warband.materialLedger.getOrDefault(it.identifier.toString(), 0.0) > 0.0 }
            .sortedBy { it.identifier.toString() }.toList()
        if (materials.isEmpty()) return emptyList()
        val materialDefinitions = materialDefinitions(warband).associateBy(MaterialDefinition::id)
        val core = engineWarband(warband)
        val engineState = EngineState(sequence = warband.armory.size.toLong(), warbands = linkedMapOf(core.id to core))

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
            val available = warband.materialLedger.toMutableMap()
            val cost = linkedMapOf<String, Double>()
            val chosen = statTypes.mapIndexedNotNull { index, statType ->
                val part = parts.getOrNull(index)
                val partId = part?.let { ForgeRegistries.ITEMS.getKey(it.asItem())?.toString() }
                // Material-stat-only equipment (notably armor) has no Part Builder
                // recipe. Each functional material contribution is one ledger unit;
                // concrete part recipes override that with their live recipe cost.
                val units = partId?.let(partCosts::get)?.toDouble() ?: 1.0
                val compatible = materials.filter { material ->
                    part?.canUseMaterial(material.identifier)
                        ?: MaterialRegistry.getInstance().getAllStats(material.identifier).any { it.identifier == statType }
                }.mapTo(linkedSetOf()) { it.identifier.toString() }
                WarbandEngine.choosePartMaterial(
                    engineState, core, materialDefinitions.values, compatible, available, units, index,
                )?.let { selected -> materials.firstOrNull { it.identifier.toString() == selected.id } }
                    ?.also { material ->
                        val id = material.identifier.toString()
                        available[id] = available.getOrDefault(id, 0.0) - units
                        cost[id] = cost.getOrDefault(id, 0.0) + units
                    }?.let(MaterialVariant::of)
            }
            if (chosen.size != statTypes.size || cost.isEmpty()) return@mapNotNull null
            val tool = runCatching { ToolStack.createTool(item, definition, MaterialNBT(chosen)).also { it.rebuildStats() } }.getOrNull() ?: return@mapNotNull null
            val stack = tool.createStack()
            val attributes = capabilities(tool, stack)
            val itemId = ForgeRegistries.ITEMS.getKey(item)?.toString() ?: return@mapNotNull null
            val formulation = chosen.map { it.id.toString() }
            val id = "$itemId|${formulation.joinToString(",")}"
            LiveEquipmentCandidate(EquipmentDefinition(id, formulation, attributes, cost, actions(stack)), stack)
        }.sortedBy { it.definition.id }.toList()
    }

    internal fun realize(warband: PillagerWarband, candidate: LiveEquipmentCandidate, consume: Boolean): ItemStack? {
        if (consume && candidate.definition.cost.any { (id, amount) -> warband.materialLedger.getOrDefault(id, 0.0) < amount }) return null
        if (consume) candidate.definition.cost.forEach { (id, amount) ->
            warband.materialLedger[id] = (warband.materialLedger.getOrDefault(id, 0.0) - amount).coerceAtLeast(0.0)
        }
        return candidate.stack.copy().also { stack ->
            stack.orCreateTag.put(COST_TAG, CompoundTag().also { tag -> candidate.definition.cost.forEach(tag::putDouble) })
            stack.orCreateTag.putString(FORMULATION_TAG, candidate.definition.formulation.joinToString(","))
        }
    }

    internal fun cost(stack: ItemStack): Map<String, Double> = stack.tag?.getCompound(COST_TAG)?.let { tag -> tag.allKeys.associateWith(tag::getDouble) }.orEmpty()

    internal fun manifest(id: String, stack: ItemStack): EquipmentManifest {
        val toolStats = runCatching { ToolStack.from(stack).stats }.getOrNull()
        val capabilities = toolStats?.let { capabilities(ToolStack.from(stack), stack) } ?: CapabilityVector()
        val itemId = ForgeRegistries.ITEMS.getKey(stack.item)?.toString().orEmpty()
        val formulation = stack.tag?.getString(FORMULATION_TAG)?.split(',')?.filter(String::isNotBlank).orEmpty()
        val durability = if (stack.isDamageableItem) 1.0 - stack.damageValue.toDouble() / stack.maxDamage.coerceAtLeast(1) else 1.0
        return EquipmentManifest(id, itemId, formulation, cost(stack), capabilities, actions(stack), durability.coerceIn(0.0, 1.0))
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

    private fun engineWarband(warband: PillagerWarband) = PillagerEngineBridge.coreWarband(warband)

    private fun extractableMaterials(warband: PillagerWarband): List<IMaterial> {
        if (!MaterialRegistry.isFullyLoaded()) return emptyList()
        val available = warband.reserve * (0.5 + warband.environment.mineralPotential + warband.environment.exoticPotential)
        return MaterialRegistry.getInstance().visibleMaterials.asSequence().filter { it.isCraftable && !it.isHidden }
            .filter { material -> FormulaicWarbandRules.extractionThreshold(material.tier, WarbandFormulaData.ingredientWeights.keys.count { it.startsWith(material.identifier.toString()) }) <= available }
            .sortedBy { it.identifier.toString() }.toList()
    }

    private fun deterministicFraction(warband: PillagerWarband, id: String, salt: Int): Double =
        ((warband.id.mostSignificantBits xor id.hashCode().toLong() xor salt.toLong()) and 1023L) / 1024.0
}
