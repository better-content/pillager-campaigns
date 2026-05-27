package com.gerald.pillagercampaigns.gametest

import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.system.PillagerBaseDiscoveryService
import com.gerald.pillagercampaigns.system.PillagerBaseMaterializer
import com.gerald.pillagercampaigns.system.PillagerBasePlacementRules
import com.gerald.pillagercampaigns.system.PillagerMaterializationSearchPlan
import com.gerald.pillagercampaigns.system.PillagerSettlementScheduler
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun plannedBaseRegistrationCreatesLogicalBaseWithoutMaterializing(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerBasePlacementRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:planned-base".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )

        val registered = PillagerBaseDiscoveryService.registerPlannedBase(level, data, candidate, level.gameTime)
        val base = data.bases[candidate.id]

        helper.assertTrue(registered, "planned base should register")
        helper.assertTrue(base != null, "base record should exist")
        helper.assertTrue(base!!.state == BaseState.PLANNED, "base should remain logical/planned")
        helper.assertTrue(base.form == BaseForm.UNKNOWN, "base should not be marked as materialized")
        helper.assertTrue(data.factions.containsKey(base.factionId), "base faction should exist")
        helper.assertTrue(data.factions.getValue(base.factionId).bossOfficerId != null, "base faction should have a boss officer")
        helper.assertTrue(data.warbands.containsKey(base.id), "planned base should migrate into a logical warband")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 80)
    fun schedulerMaterializationSearchIsBoundedInRealServerLevel(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val base = plannedBase(level.dimension().location(), anchor.x, anchor.z)
        data.bases[base.id] = base
        PillagerSettlementScheduler.requestMaterialization(base, force = false)

        PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 100L)

        helper.assertTrue(base.state == BaseState.PLANNED, "bounded search must not materialize in the first tick")
        helper.assertTrue(base.materializationFailure == BaseMaterializationFailure.IN_PROGRESS, "first tick should be marked in progress")
        helper.assertTrue(base.materializationCursorIndex in 1..16, "cursor should advance by the configured per-job chunk budget, was ${base.materializationCursorIndex}")
        helper.assertTrue(
            base.materializationCursorIndex < PillagerMaterializationSearchPlan.totalChunks(8),
            "search should not complete the full radius in one tick",
        )
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun samCommandsAreRegisteredAndNonBlocking(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerBasePlacementRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:sam-warband-command".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )
        val registered = PillagerBaseDiscoveryService.registerPlannedBase(level, data, candidate, level.gameTime)
        helper.assertTrue(registered, "planned warband should register")
        val source = level.server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput()
        val prefix = candidate.id.toString().take(8)

        val status = level.server.commands.performPrefixedCommand(source, "sam status")
        val list = level.server.commands.performPrefixedCommand(source, "sam settlements list")
        val warbands = level.server.commands.performPrefixedCommand(source, "sam warbands list")
        val materialized = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord $prefix")
        val missing = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord does-not-exist")

        helper.assertTrue(status == 1, "sam status should succeed")
        helper.assertTrue(list == 1, "sam settlements list should succeed")
        helper.assertTrue(warbands == 1, "sam warbands list should succeed")
        helper.assertTrue(materialized == 1, "sam warbands materialize_warlord should return a handled result")
        helper.assertTrue(missing == 0, "unknown warband materialize target should fail without throwing")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 40)
    fun vanillaLocateForOwnedPillagerStructureIsAllowed(helper: GameTestHelper) {
        resetWorldData(helper)
        val source = helper.level.server.createCommandSourceStack().withLevel(helper.level).withPermission(4).withSuppressedOutput()

        val result = helper.level.server.commands.performPrefixedCommand(source, "locate structure minecraft:pillager_outpost")

        helper.assertTrue(result >= 0, "vanilla locate should not be canceled by pillager campaigns")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun forcedMaterializationTransitionsPlannedBaseToMaterialized(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val base = plannedBase(level.dimension().location(), anchor.x, anchor.z)
        data.bases[base.id] = base

        // Ensure a loaded neighborhood so footprint checks cannot keep the base in in-progress state.
        for (dz in -3..3) {
            for (dx in -3..3) {
                level.getChunk(anchor.x + dx, anchor.z + dz)
            }
        }
        val anchorCenterX = (anchor.x shl 4) + 8
        val anchorCenterZ = (anchor.z shl 4) + 8
        val anchorY = 64

        // Seed a deterministic completed search result so this test validates
        // materialization execution/state transitions rather than terrain scoring.
        base.materializationSearchRadius = 0
        base.materializationCursorIndex = PillagerMaterializationSearchPlan.totalChunks(0)
        base.materializationBestChunkX = anchor.x
        base.materializationBestChunkZ = anchor.z
        base.materializationBestX = anchorCenterX
        base.materializationBestY = anchorY
        base.materializationBestZ = anchorCenterZ
        base.materializationBestScore = 1

        val result = PillagerBaseMaterializer.tryMaterializeLoadedBase(
            level = level,
            data = data,
            base = base,
            searchRadiusChunks = 0,
            now = level.gameTime,
            force = false,
            candidateCheckBudget = 1,
            allowPlacement = true,
        )

        helper.assertTrue(result.success, "materialization should succeed at loaded anchor chunk, failure=${result.failure}")
        helper.assertTrue(base.state == BaseState.MATERIALIZED, "forced scheduler materialization should resolve a planned base")
        helper.assertTrue(base.form == BaseForm.JIGSAW_OUTPOST, "materialized base should be tagged as jigsaw outpost")
        helper.assertTrue(base.materializationFailure == BaseMaterializationFailure.NONE, "materialized base should clear failure state")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun samMaterializeWarlordCommandRecordsExplicitPresenceResult(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val candidate = PillagerBasePlacementRules.Candidate(
            id = UUID.nameUUIDFromBytes("gametest:materialize-warlord-command".toByteArray()),
            dimension = level.dimension().location(),
            structureId = location("minecraft:pillager_outpost"),
            cellX = 0,
            cellZ = 0,
            chunkX = anchor.x,
            chunkZ = anchor.z,
        )
        val registered = PillagerBaseDiscoveryService.registerPlannedBase(level, data, candidate, level.gameTime)
        helper.assertTrue(registered, "planned warband should register")
        level.getChunk(anchor.x, anchor.z)
        val source = level.server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput()
        val result = level.server.commands.performPrefixedCommand(source, "sam warbands materialize_warlord ${candidate.id.toString().take(8)}")
        val warband = data.warbands[candidate.id]

        helper.assertTrue(result == 1, "sam warbands materialize_warlord should return a handled result")
        helper.assertTrue(warband != null, "warband should exist after registration")
        helper.assertTrue(warband!!.lastPresenceAttemptTick == level.gameTime, "warlord materialization should record an attempt tick")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 120)
    fun missingPoolMaterializationFailsOnceWithoutSilentRequeue(helper: GameTestHelper) {
        val data = resetWorldData(helper)
        val level = helper.level
        val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
        val base = plannedBase(level.dimension().location(), anchor.x, anchor.z).copy(
            structureId = ResourceLocation.tryParse("pillagercampaigns:missing_pool_for_test")!!,
        )
        data.bases[base.id] = base
        PillagerSettlementScheduler.requestMaterialization(base, force = false)

        for (dz in -3..3) {
            for (dx in -3..3) {
                level.getChunk(anchor.x + dx, anchor.z + dz)
            }
        }
        val anchorCenterX = (anchor.x shl 4) + 8
        val anchorCenterZ = (anchor.z shl 4) + 8
        val anchorY = 64
        base.materializationSearchRadius = 8
        base.materializationCursorIndex = PillagerMaterializationSearchPlan.totalChunks(8)
        base.materializationBestChunkX = anchor.x
        base.materializationBestChunkZ = anchor.z
        base.materializationBestX = anchorCenterX
        base.materializationBestY = anchorY
        base.materializationBestZ = anchorCenterZ
        base.materializationBestScore = 1

        // First tick performs evaluation and should hard-fail with POOL_MISSING.
        PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 200L)
        helper.assertTrue(base.state == BaseState.PLANNED, "missing pool should not materialize")
        helper.assertTrue(base.materializationFailure == BaseMaterializationFailure.POOL_MISSING, "missing pool must be surfaced as POOL_MISSING")
        helper.assertTrue(base.materializationAttempts == 1, "missing pool should consume exactly one attempt")
        val firstAttemptTick = base.lastMaterializationAttemptTick

        // Second tick should not silently churn retries because POOL_MISSING is terminal for queueing.
        PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 260L)
        helper.assertTrue(base.materializationAttempts == 1, "missing pool should not be silently requeued for repeated attempts")
        helper.assertTrue(base.lastMaterializationAttemptTick == firstAttemptTick, "missing pool should not advance attempt tick without explicit requeue")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun schedulerHonorsJobsPerTickCapUnderForcedQueue(helper: GameTestHelper) {
        val originalJobs = PillagerCampaignsConfig.materializationJobsPerTick.get()
        try {
            PillagerCampaignsConfig.materializationJobsPerTick.set(1)

            val data = resetWorldData(helper)
            val level = helper.level
            val anchor = ChunkPos(helper.absolutePos(BlockPos(2, 2, 2)))
            val bases = listOf(
                plannedBase(level.dimension().location(), anchor.x, anchor.z),
                plannedBase(level.dimension().location(), anchor.x + 5, anchor.z),
                plannedBase(level.dimension().location(), anchor.x + 10, anchor.z),
            )
            bases.forEach { base ->
                data.bases[base.id] = base
                PillagerSettlementScheduler.requestMaterialization(base, force = true)

                for (dz in -2..2) {
                    for (dx in -2..2) {
                        level.getChunk(base.chunkX + dx, base.chunkZ + dz)
                    }
                }
                val centerX = (base.chunkX shl 4) + 8
                val centerZ = (base.chunkZ shl 4) + 8
                base.materializationSearchRadius = 16
                base.materializationCursorIndex = PillagerMaterializationSearchPlan.totalChunks(16)
                base.materializationBestChunkX = base.chunkX
                base.materializationBestChunkZ = base.chunkZ
                base.materializationBestX = centerX
                base.materializationBestY = 64
                base.materializationBestZ = centerZ
                base.materializationBestScore = 1
            }

            fun materializedCount(): Int = bases.count { it.state == BaseState.MATERIALIZED }

            PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 400L)
            helper.assertTrue(materializedCount() == 1, "first tick should materialize exactly one base when jobs_per_tick=1")
            PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 401L)
            helper.assertTrue(materializedCount() == 2, "second tick should materialize second base")
            PillagerSettlementScheduler.tickMaterialization(level.server, data, now = 402L)
            helper.assertTrue(materializedCount() == 3, "third tick should materialize third base")
            helper.succeed()
        } finally {
            PillagerCampaignsConfig.materializationJobsPerTick.set(originalJobs)
        }
    }

    private fun resetWorldData(helper: GameTestHelper): PillagerWorldData {
        val data = PillagerWorldData.get(helper.level.server)
        data.factions.clear()
        data.bases.clear()
        data.warbands.clear()
        data.officers.clear()
        data.campaigns.clear()
        data.lastCampaignTick = 0L
        data.lastDiscoveryTick = 0L
        PillagerSettlementScheduler.reset()
        data.markChanged()
        return data
    }

    private fun plannedBase(dimension: ResourceLocation, chunkX: Int, chunkZ: Int): PillagerBase = PillagerBase(
        id = UUID.randomUUID(),
        factionId = UUID.randomUUID(),
        dimension = dimension,
        structureId = location("minecraft:pillager_outpost"),
        bannerSeed = 0,
        difficulty = 0,
        defeated = false,
        state = BaseState.PLANNED,
        form = BaseForm.UNKNOWN,
        anchorChunkX = chunkX,
        anchorChunkZ = chunkZ,
        chunkX = chunkX,
        chunkZ = chunkZ,
        center = BlockPos(chunkX shl 4, 65, chunkZ shl 4),
        lastSeenTick = 0L,
        materializationAttempts = 0,
        materializationFailure = BaseMaterializationFailure.NONE,
        lastMaterializationAttemptTick = -100L,
        materializationSearchRadius = -1,
        materializationCursorIndex = 0,
        materializationBestChunkX = 0,
        materializationBestChunkZ = 0,
        materializationBestX = 0,
        materializationBestY = 0,
        materializationBestZ = 0,
        materializationBestScore = Int.MIN_VALUE,
    )

    private fun location(value: String): ResourceLocation = ResourceLocation.tryParse(value)!!
}
