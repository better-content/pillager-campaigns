package com.bettercontent.pillagercampaigns.gametest

import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.system.InvasionRoster
import com.bettercontent.pillagercampaigns.system.SurfaceGridSampler
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun runtimeRosterIsCompleteAndRevisioned(helper: GameTestHelper) {
        val spec = InvasionRoster.runtimeSpec()
        spec.requireValid()
        helper.assertTrue(spec.revision == spec.computedRevision(), "Runtime roster revision must cover every decision input")
        helper.assertTrue(spec.recruits.any { it.entityId == "minecraft:pillager" }, "Vanilla pillager fallback must exist")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun surfaceSamplingNeverLoadsRemoteChunks(helper: GameTestHelper) {
        val origin = helper.absolutePos(BlockPos(8, 2, 8))
        val chunkX = (origin.x shr 4) + 20
        val chunkZ = origin.z shr 4
        helper.assertTrue(helper.level.chunkSource.getChunkNow(chunkX, chunkZ) == null, "Remote chunk must start unloaded")
        val cell = SurfaceGridSampler.surfaceCell(helper.level, chunkX shl 4, chunkZ shl 4)
        helper.assertTrue(cell == null, "Unknown surface must remain unknown")
        helper.assertTrue(helper.level.chunkSource.getChunkNow(chunkX, chunkZ) == null, "Surface observation must not load its chunk")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun surfaceModelExposesWallsAsUnclimbableHeightChanges(helper: GameTestHelper) {
        val origin = helper.absolutePos(BlockPos(8, 2, 8))
        val body = BlockPos(origin.x, helper.level.maxBuildHeight - 5, origin.z)
        helper.level.getChunk(body.x shr 4, body.z shr 4)
        helper.level.setBlockAndUpdate(body.below(), Blocks.STONE.defaultBlockState())
        helper.level.setBlockAndUpdate(body, Blocks.AIR.defaultBlockState())
        helper.level.setBlockAndUpdate(body.above(), Blocks.AIR.defaultBlockState())
        val open = SurfaceGridSampler.surfaceCell(helper.level, body.x, body.z)
        helper.assertTrue(open?.passable == true, "Two-block-high dry column must be passable")
        val wall = body.east()
        helper.level.setBlockAndUpdate(wall.below(), Blocks.STONE.defaultBlockState())
        helper.level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState())
        helper.level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState())
        val wallTop = SurfaceGridSampler.surfaceCell(helper.level, wall.x, wall.z)
        helper.assertTrue(wallTop != null && open != null && wallTop.bodyY - open.bodyY > 1, "Wall must exceed the immaterial campaign's one-block ascent")
        helper.succeed()
    }
}
