package com.gerald.pillagercampaigns.gametest

import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.registries.ForgeRegistries

object OpeningProgressionRuntimeValidation {
    private val blockTagCache = mutableMapOf<String, Set<String>>()
    private val itemTagCache = mutableMapOf<String, Set<String>>()

    fun validate(level: ServerLevel, origin: BlockPos = level.sharedSpawnPos.offset(8, 0, 8)) {
        level.getChunkAt(origin)
        val player = FakePlayerFactory.getMinecraft(level)
        resetPlayer(player)
        player.inventory.add(ItemStack(Items.STICK))

        val gravelProbe = origin.offset(1, 2, 1)
        val stoneProbe = origin.offset(2, 2, 1)
        val grassProbe = origin.offset(3, 2, 1)
        val logProbe = origin.offset(4, 2, 1)

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        placeSolid(level, gravelProbe, Blocks.GRAVEL)
        requireCondition(realisticHandsAllows(player, gravelProbe), "Expected placed gravel to stay breakable by hand")

        placeSolid(level, stoneProbe, Blocks.STONE)
        requireCondition(!realisticHandsAllows(player, stoneProbe), "Expected placed stone to remain blocked for bare hands")

        val flintDrops = collectGravelFlint(level, player, origin.offset(1, 2, 4))
        requireCondition(
            flintDrops >= 5,
            "Expected placed gravel to expose at least five live flint drops across the probe set, found $flintDrops"
        )

        val knifeRecipe = requireRecipe(level, "runtime primitive butcher knife") { recipe ->
            resultId(recipe) == "additionalweaponry:butcher_knife" &&
                ingredientCount(recipe, Items.FLINT) == 3 &&
                ingredientCount(recipe, Items.STICK) == 1
        }
        val butcherKnife = knifeRecipe.getResultItem(level.registryAccess()).copy()
        requireCondition(
            tagText(butcherKnife).contains("tconstruct:flint") && tagText(butcherKnife).contains("tconstruct:wood"),
            "Expected primitive butcher knife recipe output to preserve TConstruct flint/wood materials"
        )

        player.setItemInHand(InteractionHand.MAIN_HAND, butcherKnife.copy())
        placeTallGrass(level, grassProbe)
        requireCondition(
            realisticHandsAllows(player, grassProbe),
            "Expected butcher knife to pass the runtime break gate for tall grass"
        )
        requireCondition(
            isTaggedBlock(player.server.resourceManager, level.getBlockState(grassProbe).block, "knife_straw") &&
                isTaggedItem(player.server.resourceManager, butcherKnife.item, "knife"),
            "Expected placed tall grass and the primitive butcher knife to satisfy the runtime straw-loot policy"
        )

        placeLog(level, logProbe)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        requireCondition(!realisticHandsAllows(player, logProbe), "Expected oak logs to remain blocked for bare hands")

        val handAxeRecipe = requireRecipe(level, "runtime primitive hand axe") { recipe ->
            resultId(recipe) == "tconstruct:hand_axe" &&
                ingredientCount(recipe, Items.FLINT) == 2 &&
                ingredientCount(recipe, Items.STICK) == 1 &&
                ingredientCount(recipe, item("farmersdelight:straw")) == 1
        }
        val handAxe = handAxeRecipe.getResultItem(level.registryAccess()).copy()
        requireCondition(
            tagText(handAxe).contains("tconstruct:flint") &&
                tagText(handAxe).contains("tconstruct:wood") &&
                tagText(handAxe).contains("tconstruct:string"),
            "Expected primitive hand axe recipe output to preserve flint/wood/string materials"
        )

        player.setItemInHand(InteractionHand.MAIN_HAND, handAxe.copy())
        placeLog(level, logProbe)
        requireCondition(
            realisticHandsAllows(player, logProbe),
            "Expected primitive hand axe to satisfy the runtime break gate for oak logs"
        )
    }

    private fun requireCondition(condition: Boolean, message: String) {
        if (!condition) error(message)
    }

    private fun requireRecipe(level: ServerLevel, label: String, predicate: (CraftingRecipe) -> Boolean): CraftingRecipe {
        return level.server.recipeManager
            .getAllRecipesFor(RecipeType.CRAFTING)
            .firstOrNull(predicate)
            ?: error("Missing $label recipe in runtime recipe manager")
    }

    private fun resultId(recipe: CraftingRecipe): String =
        itemId(recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY).item)

    private fun ingredientCount(recipe: CraftingRecipe, item: Item): Int =
        recipe.ingredients.count { ingredient -> ingredient.test(ItemStack(item)) }

    private fun item(id: String): Item =
        requireNotNull(ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(id))) {
            "Missing runtime item: $id"
        }

    private fun itemId(item: Item): String = ForgeRegistries.ITEMS.getKey(item)?.toString().orEmpty()

    private fun tagText(stack: ItemStack): String = stack.tag?.toString().orEmpty()

    private fun realisticHandsAllows(player: ServerPlayer, pos: BlockPos): Boolean {
        val state = player.serverLevel().getBlockState(pos)
        return !shouldDenyByResources(player, state.block, player.mainHandItem)
    }

    private fun shouldDenyByResources(player: ServerPlayer, block: Block, stack: ItemStack): Boolean {
        if (player.isCreative) return false
        if (!isPolicyBlock(player.server.resourceManager, block)) return false
        if (isTaggedBlock(player.server.resourceManager, block, "hand")) return false
        return !matches(player.server.resourceManager, block, stack, "knife") &&
            !matches(player.server.resourceManager, block, stack, "axe") &&
            !matches(player.server.resourceManager, block, stack, "pickaxe") &&
            !matches(player.server.resourceManager, block, stack, "shovel") &&
            !matches(player.server.resourceManager, block, stack, "hoe") &&
            !matches(player.server.resourceManager, block, stack, "sword")
    }

    private fun isPolicyBlock(resourceManager: ResourceManager, block: Block): Boolean =
        isTaggedBlock(resourceManager, block, "hand") ||
            isTaggedBlock(resourceManager, block, "knife") ||
            isTaggedBlock(resourceManager, block, "axe") ||
            isTaggedBlock(resourceManager, block, "pickaxe") ||
            isTaggedBlock(resourceManager, block, "shovel") ||
            isTaggedBlock(resourceManager, block, "hoe") ||
            isTaggedBlock(resourceManager, block, "sword")

    private fun matches(resourceManager: ResourceManager, block: Block, stack: ItemStack, category: String): Boolean =
        isTaggedBlock(resourceManager, block, category) &&
            !stack.isEmpty &&
            isTaggedItem(resourceManager, stack.item, category)

    private fun isTaggedBlock(resourceManager: ResourceManager, block: Block, category: String): Boolean {
        val blockId = ForgeRegistries.BLOCKS.getKey(block)?.toString().orEmpty()
        return blockId.isNotEmpty() &&
            loadTagValues(resourceManager, "tags/blocks/realistic_hands", category, blockTagCache).contains(blockId)
    }

    private fun isTaggedItem(resourceManager: ResourceManager, item: Item, category: String): Boolean {
        val itemId = itemId(item)
        return itemId.isNotEmpty() &&
            loadTagValues(resourceManager, "tags/items/realistic_hands/tools", category, itemTagCache).contains(itemId)
    }

    private fun loadTagValues(
        resourceManager: ResourceManager,
        basePath: String,
        category: String,
        cache: MutableMap<String, Set<String>>
    ): Set<String> {
        val key = "$basePath/$category"
        return cache.getOrPut(key) {
            loadTagValuesRecursive(resourceManager, basePath, category, mutableSetOf())
        }
    }

    private fun loadTagValuesRecursive(
        resourceManager: ResourceManager,
        basePath: String,
        category: String,
        visited: MutableSet<String>
    ): Set<String> {
        val key = "$basePath/$category"
        if (!visited.add(key)) return emptySet()

        val resourceId = ResourceLocation("btmfixes", "$basePath/$category.json")
        val resource = resourceManager.getResource(resourceId).orElse(null) ?: return emptySet()

        resource.openAsReader().use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            val values = linkedSetOf<String>()
            root.getAsJsonArray("values")?.forEach { element ->
                val value = element.asString
                if (value.startsWith("#")) {
                    val nested = value.removePrefix("#")
                    val nestedNamespace = nested.substringBefore(':', "minecraft")
                    val nestedPath = nested.substringAfter(':', nested)
                    if (nestedNamespace == "btmfixes" && nestedPath.startsWith("realistic_hands/")) {
                        val nestedCategory = nestedPath.removePrefix("realistic_hands/")
                        values += loadTagValuesRecursive(
                            resourceManager,
                            basePath,
                            nestedCategory,
                            visited
                        )
                    }
                } else {
                    values += value
                }
            }
            return values
        }
    }

    private fun collectGravelFlint(level: ServerLevel, player: ServerPlayer, origin: BlockPos): Int {
        var flint = 0
        repeat(96) { index ->
            val pos = origin.offset(index % 12, 0, index / 12)
            placeSolid(level, pos, Blocks.GRAVEL)
            flint += Block.getDrops(level.getBlockState(pos), level, pos, null, player, ItemStack.EMPTY)
                .filter { it.`is`(Items.FLINT) }
                .sumOf(ItemStack::getCount)
        }
        return flint
    }

    private fun placeSolid(level: ServerLevel, pos: BlockPos, block: Block) {
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
        level.setBlockAndUpdate(pos, block.defaultBlockState())
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
    }

    private fun placeTallGrass(level: ServerLevel, pos: BlockPos) {
        level.setBlockAndUpdate(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState())
        level.setBlockAndUpdate(
            pos,
            Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER)
        )
        level.setBlockAndUpdate(
            pos.above(),
            Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)
        )
    }

    private fun placeLog(level: ServerLevel, pos: BlockPos) {
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
        level.setBlockAndUpdate(pos, Blocks.OAK_LOG.defaultBlockState())
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
    }

    private fun resetPlayer(player: ServerPlayer) {
        player.setGameMode(GameType.SURVIVAL)
        player.abilities.instabuild = false
        player.abilities.invulnerable = false
        player.abilities.mayBuild = true
        player.onUpdateAbilities()
        player.inventory.clearContent()
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY)
    }
}
