package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object OpeningProgressionGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "opening_progression", timeoutTicks = 80)
    fun primitiveOpeningRouteRemainsRuntimeReachable(helper: GameTestHelper) {
        val level = helper.level
        val player = FakePlayerFactory.getMinecraft(level)
        resetPlayer(player)
        player.inventory.add(ItemStack(Items.STICK))

        val gravelProbe = helper.absolutePos(BlockPos(1, 2, 1))
        val stoneProbe = helper.absolutePos(BlockPos(2, 2, 1))
        val grassProbe = helper.absolutePos(BlockPos(3, 2, 1))
        val logProbe = helper.absolutePos(BlockPos(4, 2, 1))

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        placeSolid(level, gravelProbe, Blocks.GRAVEL)
        helper.assertTrue(
            breakEventAllowed(player, gravelProbe),
            "Expected placed gravel to stay breakable by hand"
        )

        placeSolid(level, stoneProbe, Blocks.STONE)
        helper.assertTrue(
            !breakEventAllowed(player, stoneProbe),
            "Expected placed stone to remain blocked for bare hands"
        )

        val flintDrops = collectGravelFlint(level, player, helper.absolutePos(BlockPos(1, 2, 4)))
        helper.assertTrue(
            flintDrops >= 5,
            "Expected placed gravel to expose at least five live flint drops across the probe set, found $flintDrops"
        )

        val knifeRecipe = requireRecipe(level, "runtime primitive butcher knife") { recipe ->
            resultId(recipe) == "additionalweaponry:butcher_knife" &&
                ingredientCount(recipe, Items.FLINT) == 3 &&
                ingredientCount(recipe, Items.STICK) == 1
        }
        val butcherKnife = knifeRecipe.getResultItem(level.registryAccess()).copy()
        helper.assertTrue(
            tagText(butcherKnife).contains("tconstruct:flint") && tagText(butcherKnife).contains("tconstruct:wood"),
            "Expected primitive butcher knife recipe output to preserve TConstruct flint/wood materials"
        )

        player.setItemInHand(InteractionHand.MAIN_HAND, butcherKnife.copy())
        placeTallGrass(level, grassProbe)
        helper.assertTrue(
            breakEventAllowed(player, grassProbe),
            "Expected butcher knife to pass the runtime break gate for tall grass"
        )
        val grassDrops = Block.getDrops(level.getBlockState(grassProbe), level, grassProbe, null, player, butcherKnife)
        helper.assertTrue(
            grassDrops.any { itemId(it.item) == "farmersdelight:straw" },
            "Expected placed tall grass cut with the primitive butcher knife to drop straw at runtime"
        )

        placeLog(level, logProbe)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        helper.assertTrue(
            !breakEventAllowed(player, logProbe),
            "Expected oak logs to remain blocked for bare hands"
        )

        val handAxeRecipe = requireRecipe(level, "runtime primitive hand axe") { recipe ->
            resultId(recipe) == "tconstruct:hand_axe" &&
                ingredientCount(recipe, Items.FLINT) == 2 &&
                ingredientCount(recipe, Items.STICK) == 1 &&
                ingredientCount(recipe, item("farmersdelight:straw")) == 1
        }
        val handAxe = handAxeRecipe.getResultItem(level.registryAccess()).copy()
        helper.assertTrue(
            tagText(handAxe).contains("tconstruct:flint") &&
                tagText(handAxe).contains("tconstruct:wood") &&
                tagText(handAxe).contains("tconstruct:string"),
            "Expected primitive hand axe recipe output to preserve flint/wood/string materials"
        )

        player.setItemInHand(InteractionHand.MAIN_HAND, handAxe.copy())
        placeLog(level, logProbe)
        helper.assertTrue(
            breakEventAllowed(player, logProbe),
            "Expected primitive hand axe to satisfy the runtime break gate for oak logs"
        )

        helper.succeed()
    }

    private fun requireRecipe(
        level: net.minecraft.server.level.ServerLevel,
        label: String,
        predicate: (CraftingRecipe) -> Boolean,
    ): CraftingRecipe {
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

    private fun breakEventAllowed(
        player: net.minecraft.server.level.ServerPlayer,
        pos: BlockPos,
    ): Boolean {
        val level = player.serverLevel()
        val event = BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player)
        return !MinecraftForge.EVENT_BUS.post(event)
    }

    private fun collectGravelFlint(
        level: net.minecraft.server.level.ServerLevel,
        player: net.minecraft.server.level.ServerPlayer,
        origin: BlockPos,
    ): Int {
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

    private fun placeSolid(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, block: Block) {
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
        level.setBlockAndUpdate(pos, block.defaultBlockState())
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
    }

    private fun placeTallGrass(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
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

    private fun placeLog(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
        level.setBlockAndUpdate(pos, Blocks.OAK_LOG.defaultBlockState())
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
    }

    private fun resetPlayer(player: net.minecraft.server.level.ServerPlayer) {
        player.inventory.clearContent()
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY)
    }
}
