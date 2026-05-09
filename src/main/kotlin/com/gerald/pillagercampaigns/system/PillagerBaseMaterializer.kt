package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import kotlin.math.abs

object PillagerBaseMaterializer {
    private const val REQUIRED_LOADED_RADIUS_CHUNKS = 3

    private val START_POOLS_BY_STRUCTURE_ID = mapOf(
        rl("minecraft:pillager_outpost") to rl("minecraft:pillager_outpost/base_plates"),
        rl("takesapillage:bastille") to rl("takesapillage:bastille/start_pool"),
        rl("takesapillage:pillager_camp") to rl("takesapillage:pillager_camp/start_pool"),
        rl("towns_and_towers:exclusives/pillager_outpost_classic") to rl("kaisyn:outpost/forts/exclusives/classic/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_iberian") to rl("kaisyn:outpost/forts/exclusives/iberian/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_mediterranean") to rl("kaisyn:outpost/forts/exclusives/mediterranean/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_oriental") to rl("kaisyn:outpost/towers/exclusives/oriental/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_rustic") to rl("kaisyn:outpost/forts/exclusives/rustic/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_swedish") to rl("kaisyn:outpost/towers/exclusives/swedish/base_plate"),
        rl("towns_and_towers:exclusives/pillager_outpost_tudor") to rl("kaisyn:outpost/towers/exclusives/tudor/base_plate"),
        rl("towns_and_towers:pillager_outpost_badlands") to rl("kaisyn:outpost/forts/badlands/base_plate"),
        rl("towns_and_towers:pillager_outpost_beach") to rl("kaisyn:outpost/camps/beach/base_plate"),
        rl("towns_and_towers:pillager_outpost_birch_forest") to rl("kaisyn:outpost/towers/birch/base_plate"),
        rl("towns_and_towers:pillager_outpost_desert") to rl("kaisyn:outpost/forts/desert/base_plate"),
        rl("towns_and_towers:pillager_outpost_flower_forest") to rl("kaisyn:outpost/towers/flower_forest/base_plate"),
        rl("towns_and_towers:pillager_outpost_forest") to rl("kaisyn:outpost/towers/forest/base_plate"),
        rl("towns_and_towers:pillager_outpost_grove") to rl("kaisyn:outpost/forts/grove/base_plate"),
        rl("towns_and_towers:pillager_outpost_jungle") to rl("kaisyn:outpost/forts/jungle/base_plate"),
        rl("towns_and_towers:pillager_outpost_meadow") to rl("kaisyn:outpost/towers/meadow/base_plate"),
        rl("towns_and_towers:pillager_outpost_mushroom_fields") to rl("kaisyn:outpost/towers/mushroom_fields/base_plate"),
        rl("towns_and_towers:pillager_outpost_ocean") to rl("kaisyn:ships/pillager_outpost_ocean/base_plate"),
        rl("towns_and_towers:pillager_outpost_old_growth_taiga") to rl("kaisyn:outpost/forts/old_growth_taiga/base_plate"),
        rl("towns_and_towers:pillager_outpost_savanna") to rl("kaisyn:outpost/towers/savanna/base_plate"),
        rl("towns_and_towers:pillager_outpost_savanna_plateau") to rl("kaisyn:outpost/camps/savanna_plateau/base_plate"),
        rl("towns_and_towers:pillager_outpost_snowy_beach") to rl("kaisyn:outpost/camps/snowy_beach/base_plate"),
        rl("towns_and_towers:pillager_outpost_snowy_plains") to rl("kaisyn:outpost/towers/snowy_plains/base_plate"),
        rl("towns_and_towers:pillager_outpost_snowy_slopes") to rl("kaisyn:outpost/towers/snowy_slopes/base_plate"),
        rl("towns_and_towers:pillager_outpost_snowy_taiga") to rl("kaisyn:outpost/towers/snowy_taiga/base_plate"),
        rl("towns_and_towers:pillager_outpost_sparse_jungle") to rl("kaisyn:outpost/camps/sparse_jungle/base_plate"),
        rl("towns_and_towers:pillager_outpost_sunflower_plains") to rl("kaisyn:outpost/towers/sunflower_plains/base_plate"),
        rl("towns_and_towers:pillager_outpost_swamp") to rl("kaisyn:outpost/towers/swamp/base_plate"),
        rl("towns_and_towers:pillager_outpost_taiga") to rl("kaisyn:outpost/towers/taiga/base_plate"),
        rl("towns_and_towers:pillager_outpost_wooded_badlands") to rl("kaisyn:outpost/camps/wooded_badlands/base_plate"),
    )

    data class Site(
        val chunkX: Int,
        val chunkZ: Int,
        val center: BlockPos,
        val score: Int,
    )

    data class MaterializationResult(
        val success: Boolean,
        val failure: BaseMaterializationFailure,
        val inProgress: Boolean = false,
        val scoredChunks: Int = 0,
    )

    fun tryMaterializeLoadedBase(
        level: ServerLevel,
        data: PillagerWorldData,
        base: PillagerBase,
        searchRadiusChunks: Int,
        now: Long,
        force: Boolean = false,
        candidateCheckBudget: Int = Int.MAX_VALUE,
        allowPlacement: Boolean = true,
    ): MaterializationResult {
        if (base.state != BaseState.PLANNED || base.dimension != level.dimension().location()) {
            return MaterializationResult(success = false, failure = BaseMaterializationFailure.NONE)
        }
        val strict = !force && base.materializationAttempts < 6
        val radius = (searchRadiusChunks + if (force) 8 else (base.materializationAttempts / 4)).coerceAtMost(24)
        val search = PillagerMaterializationSearchPlan.advance(base, radius, candidateCheckBudget.coerceAtLeast(1)) { chunkX, chunkZ ->
            if (!level.hasChunk(chunkX, chunkZ)) null else scoreChunk(level, chunkX, chunkZ, strict)
        }
        if (!search.complete) {
            return MaterializationResult(
                success = false,
                failure = BaseMaterializationFailure.IN_PROGRESS,
                inProgress = true,
                scoredChunks = search.scoredChunks,
            )
        }
        val site = PillagerMaterializationSearchPlan.bestSite(base)
            ?: return MaterializationResult(success = false, failure = BaseMaterializationFailure.NO_SITE, scoredChunks = search.scoredChunks)
        if (!hasLoadedFootprint(level, site.chunkX, site.chunkZ, if (strict) REQUIRED_LOADED_RADIUS_CHUNKS else 2)) {
            return MaterializationResult(
                success = false,
                failure = BaseMaterializationFailure.FOOTPRINT_NOT_LOADED,
                inProgress = true,
                scoredChunks = search.scoredChunks,
            )
        }
        if (!canMaterialize(level, base.structureId)) {
            return MaterializationResult(success = false, failure = BaseMaterializationFailure.POOL_MISSING, scoredChunks = search.scoredChunks)
        }
        if (!allowPlacement) {
            return MaterializationResult(success = false, failure = BaseMaterializationFailure.IN_PROGRESS, inProgress = true, scoredChunks = search.scoredChunks)
        }
        val placementStart = System.nanoTime()
        if (!placeJigsawBase(level, base.structureId, site.center)) {
            return MaterializationResult(success = false, failure = BaseMaterializationFailure.JIGSAW_FAILED, scoredChunks = search.scoredChunks)
        }
        val placementMs = (System.nanoTime() - placementStart) / 1_000_000.0
        if (placementMs >= 25.0) {
            PillagerCampaignsMod.LOGGER.warn("Pillager base jigsaw placement took {} ms at {},{}", "%.3f".format(placementMs), site.chunkX, site.chunkZ)
        }
        PillagerBaseDiscoveryService.markMaterialized(data, base, site, now)
        return MaterializationResult(success = true, failure = BaseMaterializationFailure.NONE, scoredChunks = search.scoredChunks)
    }

    fun canMaterialize(level: ServerLevel, structureId: ResourceLocation): Boolean {
        val poolKey = ResourceKey.create(Registries.TEMPLATE_POOL, startPoolFor(structureId))
        return level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getHolder(poolKey).isPresent
    }

    internal fun findBestLoadedSite(level: ServerLevel, anchorChunkX: Int, anchorChunkZ: Int, searchRadiusChunks: Int, strict: Boolean): Site? {
        var best: Site? = null
        val radius = searchRadiusChunks.coerceAtLeast(0)
        for (dz in -radius..radius) {
            for (dx in -radius..radius) {
                val chunkX = anchorChunkX + dx
                val chunkZ = anchorChunkZ + dz
                if (!level.hasChunk(chunkX, chunkZ)) continue
                val site = scoreChunk(level, chunkX, chunkZ, strict) ?: continue
                val currentBest = best
                if (currentBest == null || site.score > currentBest.score) {
                    best = site
                }
            }
        }
        return best
    }

    private fun scoreChunk(level: ServerLevel, chunkX: Int, chunkZ: Int, strict: Boolean): Site? {
        val chunk = ChunkPos(chunkX, chunkZ)
        val centerX = chunk.middleBlockX
        val centerZ = chunk.middleBlockZ
        val sampleOffsets = intArrayOf(-6, -3, 0, 3, 6)
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var fluidPenalty = 0
        var solidSamples = 0

        for (xOffset in sampleOffsets) {
            for (zOffset in sampleOffsets) {
                val x = centerX + xOffset
                val z = centerZ + zOffset
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val ground = BlockPos(x, y - 1, z)
                val groundState = level.getBlockState(ground)
                if (!groundState.fluidState.isEmpty) fluidPenalty += 8
                if (groundState.isSolidRender(level, ground)) solidSamples++
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }

        val slope = maxY - minY
        val minSolid = if (strict) 20 else 14
        val maxSlope = if (strict) 8 else 14
        val maxFluidPenalty = if (strict) 16 else 32
        if (solidSamples < minSolid || slope > maxSlope || fluidPenalty >= maxFluidPenalty) return null
        val centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ)
        val score = 100 - slope * 8 - fluidPenalty - abs(centerY - level.seaLevel)
        return Site(chunkX, chunkZ, BlockPos(centerX, centerY, centerZ), score)
    }

    private fun placeJigsawBase(level: ServerLevel, structureId: ResourceLocation, center: BlockPos): Boolean {
        val poolId = startPoolFor(structureId)
        val poolKey = ResourceKey.create(Registries.TEMPLATE_POOL, poolId)
        val pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getHolder(poolKey).orElse(null) ?: return false
        val generator = level.chunkSource.generator
        val templateManager = level.structureManager
        val start = BlockPos(center.x - 8, center.y, center.z - 8)
        val context = Structure.GenerationContext(
            level.registryAccess(),
            generator,
            generator.biomeSource,
            level.chunkSource.randomState(),
            templateManager,
            level.seed,
            ChunkPos(start),
            level,
        ) { true }
        val stub = JigsawPlacement.addPieces(
            context,
            pool,
            java.util.Optional.empty(),
            7,
            start,
            false,
            java.util.Optional.empty(),
            80,
        ).orElse(null) ?: return false

        val pieces = stub.getPiecesBuilder().build().pieces()
        if (pieces.isEmpty()) return false
        val structureManager = level.structureManager()
        val random = level.random
        for (piece in pieces) {
            if (piece is PoolElementStructurePiece) {
                piece.place(level, structureManager, generator, random, BoundingBox.infinite(), start, true)
            }
        }
        return true
    }

    private fun hasLoadedFootprint(level: ServerLevel, chunkX: Int, chunkZ: Int, radiusChunks: Int): Boolean {
        for (dz in -radiusChunks..radiusChunks) {
            for (dx in -radiusChunks..radiusChunks) {
                if (!level.hasChunk(chunkX + dx, chunkZ + dz)) return false
            }
        }
        return true
    }

    private fun startPoolFor(structureId: ResourceLocation): ResourceLocation {
        START_POOLS_BY_STRUCTURE_ID[structureId]?.let { return it }
        if (structureId.path.contains("/")) return structureId
        return ResourceLocation.tryParse("${structureId.namespace}:${structureId.path}/base_plates")!!
    }

    private fun rl(id: String): ResourceLocation = ResourceLocation.tryParse(id)!!
}
