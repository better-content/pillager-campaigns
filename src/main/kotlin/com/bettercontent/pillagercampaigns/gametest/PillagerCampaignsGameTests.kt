package com.bettercontent.pillagercampaigns.gametest

import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import com.bettercontent.pillagercampaigns.system.WarbandCoreAdapter
import com.bettercontent.pillagercampaigns.system.PillagerSpawnPlacementRules
import com.bettercontent.pillagercampaigns.system.WarbandResourceCatalog
import com.gerald.warband.core.WarbandRuntimeSpec
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraft.world.level.block.Blocks

/**
 * Behavioral GameTests intentionally remain out of this source-refactor phase.
 * This registration smoke test only guards the shared runtime-spec boundary;
 * full Minecraft behavior validation is restored in the next requested phase.
 */
@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun runtimeSpecAttachesToPrivateEngine(helper: GameTestHelper) {
        val data = PillagerWorldData.get(helper.level.server)
        val spec = WarbandCoreAdapter.runtimeSpec(helper.level.server)
        helper.assertTrue(
            spec.schemaVersion == WarbandRuntimeSpec.CURRENT_SCHEMA_VERSION,
            "Forge and Core must agree on the runtime-spec schema",
        )
        helper.assertTrue(spec.revision == spec.computedRevision(), "runtime-spec revision must cover all decision inputs")
        data.attachRuntimeSpec(spec)
        helper.assertTrue(data.runtimeSpecRevision() == spec.revision, "world must retain the exact runtime-spec revision")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun deferredRallyLookupNeverLoadsRemoteChunks(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(8, 2, 8))
        val remoteChunkX = (origin.x shr 4) + 20
        val remoteChunkZ = origin.z shr 4
        helper.assertTrue(level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null, "Remote probe chunk must start unloaded")

        val remote = PillagerSpawnPlacementRules.findRallyPos(level, remoteChunkX, remoteChunkZ)

        helper.assertTrue(remote == null, "Unloaded rally must defer physical placement")
        helper.assertTrue(level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null, "Rally lookup must not load its target chunk")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun deferredRallyResolvesAfterItsChunkIsLoaded(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(8, 2, 8))
        val chunkX = origin.x shr 4
        val chunkZ = origin.z shr 4
        val probeX = (chunkX shl 4) + 8
        val probeZ = (chunkZ shl 4) + 8
        level.getChunk(chunkX, chunkZ)
        val probe = BlockPos(probeX, level.maxBuildHeight - 2, probeZ)
        helper.assertTrue(level.setBlockAndUpdate(probe.below(), Blocks.STONE.defaultBlockState()), "Dry rally floor must be placeable")
        level.setBlockAndUpdate(probe, Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(probe.above(), Blocks.AIR.defaultBlockState())
        val loadedChunk = level.chunkSource.getChunkNow(chunkX, chunkZ)
        helper.assertTrue(loadedChunk != null, "Explicitly loaded rally chunk must be visible without another load")
        val surfaceY = loadedChunk!!.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8)
        helper.assertTrue(surfaceY + 1 == probe.y, "Dry rally heightmap must resolve immediately below the prepared body; got $surfaceY")

        val resolved = PillagerSpawnPlacementRules.findRallyPos(level, chunkX, chunkZ)

        helper.assertTrue(resolved != null, "Loaded dry rally must resolve to a physical site")
        helper.assertTrue(
            level.chunkSource.getChunkNow(resolved!!.x shr 4, resolved.z shr 4) != null,
            "Resolved rally must remain inside an already-loaded chunk",
        )
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun campaignMemberPlacementReprojectsToItsOwnSurface(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(8, 2, 8))
        val requested = BlockPos(origin.x, level.maxBuildHeight - 8, origin.z)
        val raisedFloor = requested.above(3)
        level.getChunk(requested.x shr 4, requested.z shr 4)
        helper.assertTrue(level.setBlockAndUpdate(raisedFloor, Blocks.STONE.defaultBlockState()), "Raised surface must be placeable")
        level.setBlockAndUpdate(raisedFloor.above(), Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(raisedFloor.above(2), Blocks.AIR.defaultBlockState())

        val resolved = PillagerSpawnPlacementRules.findMemberSurfacePos(level, requested.x, requested.z, emptySet())

        helper.assertTrue(
            resolved == raisedFloor.above(),
            "Each campaign member must be reprojected above its own terrain column; got $resolved",
        )
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun campaignProvisionsUseFieldRations(helper: GameTestHelper) {
        val sustenance = WarbandResourceCatalog.definitions()
            .filter { it.unitsPerItem.sustenance > 0.0 }
            .associateBy { it.itemId }

        helper.assertTrue("minecraft:bread" in sustenance, "Bread must remain available as a campaign ration")
        helper.assertTrue("minecraft:cooked_beef" in sustenance, "Ordinary cooked meat must remain available as a campaign ration")
        helper.assertTrue("minecraft:pumpkin_pie" !in sustenance, "Desserts must not enter campaign logistics automatically")
        helper.assertTrue("minecraft:golden_carrot" !in sustenance, "Luxury foods must not enter campaign logistics automatically")
        helper.assertTrue(
            sustenance.keys.all { id ->
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation(id))
                    ?.let { net.minecraft.world.item.ItemStack(it).`is`(WarbandResourceCatalog.WARBAND_RATIONS) } == true
            },
            "Every campaign food resource must be explicitly tagged as a warband ration",
        )
        helper.succeed()
    }
}
