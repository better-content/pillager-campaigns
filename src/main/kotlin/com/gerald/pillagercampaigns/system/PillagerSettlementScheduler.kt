package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerWorldData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.UUID

object PillagerSettlementScheduler {
    private const val MATERIALIZATION_RETRY_TICKS: Long = 10L
    private const val BOSS_ENSURE_BUDGET: Int = 32

    private val pendingMaterialization = DeduplicatingWorkQueue<UUID>()
    private val forcedMaterializationIds = mutableSetOf<UUID>()
    private val chunkIndex = PillagerSettlementChunkIndex()
    private var bossEnsureCursor: Int = 0
    private var placementCooldownUntilTick: Long = 0L

    private var lastJobs: Int = 0
    private var lastScoredChunks: Int = 0
    private var lastTickMs: Double = 0.0
    private var lastOverBudget: String = "none"

    fun reset() {
        pendingMaterialization.clear()
        forcedMaterializationIds.clear()
        chunkIndex.clear()
        bossEnsureCursor = 0
        placementCooldownUntilTick = 0L
        lastJobs = 0
        lastScoredChunks = 0
        lastTickMs = 0.0
        lastOverBudget = "none"
    }

    fun rebuild(data: PillagerWorldData) {
        reset()
        data.bases.values.forEach { base ->
            when (base.state) {
                BaseState.PLANNED -> enqueueMaterialization(base, front = false)
                BaseState.MATERIALIZED -> indexMaterialized(base)
                BaseState.DEFEATED -> {}
            }
        }
    }

    fun onBaseRegistered(base: PillagerBase) {
        if (base.state == BaseState.PLANNED) enqueueMaterialization(base, front = false)
    }

    fun onBaseMaterialized(base: PillagerBase) {
        pendingMaterialization.remove(base.id)
        forcedMaterializationIds.remove(base.id)
        indexMaterialized(base)
    }

    fun requestMaterialization(base: PillagerBase, force: Boolean) {
        if (force) {
            forcedMaterializationIds += base.id
            PillagerBaseDiscoveryService.resetMaterializationSearch(base)
        }
        enqueueMaterialization(base, front = force)
    }

    fun tickMaterialization(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val start = System.nanoTime()
        val maxJobs = PillagerCampaignsConfig.materializationJobsPerTick.get().coerceAtLeast(1)
        val maxMillis = PillagerCampaignsConfig.materializationMaxMillisPerTick.get().coerceAtLeast(0.5)
        val maxChecks = PillagerCampaignsConfig.materializationCandidateChecksPerJob.get().coerceAtLeast(1)
        val searchRadius = PillagerCampaignsConfig.baseMaterializationSearchRadiusChunks.get()
        val initialQueueSize = pendingMaterialization.size
        var inspected = 0
        var jobs = 0
        var scored = 0

        while (inspected < initialQueueSize && jobs < maxJobs && elapsedMs(start) < maxMillis) {
            val baseId = pendingMaterialization.poll() ?: break
            inspected++

            val base = data.bases[baseId] ?: continue
            if (base.defeated || base.state != BaseState.PLANNED) continue
            val forced = base.id in forcedMaterializationIds
            if (!forced && now - base.lastMaterializationAttemptTick < MATERIALIZATION_RETRY_TICKS) {
                enqueueMaterialization(base, front = false)
                continue
            }
            val level = server.allLevels.firstOrNull { it.dimension().location() == base.dimension }
            if (level == null) {
                enqueueMaterialization(base, front = false)
                continue
            }

            val allowPlacement = forced || now >= placementCooldownUntilTick
            val result = PillagerBaseMaterializer.tryMaterializeLoadedBase(
                level = level,
                data = data,
                base = base,
                searchRadiusChunks = searchRadius,
                now = now,
                force = forced,
                candidateCheckBudget = maxChecks,
                allowPlacement = allowPlacement,
            )
            jobs++
            scored += result.scoredChunks
            base.lastMaterializationAttemptTick = now
            base.materializationFailure = result.failure

            if (result.success) {
                placementCooldownUntilTick = now + PillagerCampaignsConfig.materializationPlacementCooldownTicks.get().toLong()
                PillagerCampaignsMod.LOGGER.info("Materialized pillager base {} at {},{}", base.id, base.chunkX, base.chunkZ)
            } else {
                if (!result.inProgress) {
                    base.materializationAttempts += 1
                    if (result.failure != BaseMaterializationFailure.POOL_MISSING) {
                        PillagerBaseDiscoveryService.resetMaterializationSearch(base)
                    }
                }
                if (base.state == BaseState.PLANNED && result.failure != BaseMaterializationFailure.POOL_MISSING) {
                    enqueueMaterialization(base, front = false)
                }
            }
            data.markChanged()
        }

        lastJobs = jobs
        lastScoredChunks = scored
        lastTickMs = elapsedMs(start)
        if (lastTickMs >= maxMillis) {
            lastOverBudget = "materialization ${"%.3f".format(lastTickMs)}ms >= ${"%.3f".format(maxMillis)}ms"
        }
    }

    fun ensureBossPresenceSlice(server: MinecraftServer, data: PillagerWorldData) {
        val ids = chunkIndex.materializedIds
        if (ids.isEmpty()) return
        var inspected = 0
        while (inspected < BOSS_ENSURE_BUDGET && ids.isNotEmpty()) {
            if (bossEnsureCursor >= ids.size) bossEnsureCursor = 0
            val baseId = ids[bossEnsureCursor]
            bossEnsureCursor++
            inspected++

            val base = data.bases[baseId] ?: continue
            if (base.defeated || base.state != BaseState.MATERIALIZED) continue
            val level = server.allLevels.firstOrNull { it.dimension().location() == base.dimension } ?: continue
            if (!level.hasChunk(base.chunkX, base.chunkZ)) continue
            ensureBossAtBase(level, data, base)
        }
    }

    fun onChunkLoad(level: ServerLevel, data: PillagerWorldData, chunkX: Int, chunkZ: Int) {
        val ids = chunkIndex.idsAt(level.dimension().location(), chunkX, chunkZ)
        ids.forEach { id ->
            val base = data.bases[id] ?: return@forEach
            if (!base.defeated && base.state == BaseState.MATERIALIZED) {
                ensureBossAtBase(level, data, base)
            }
        }
    }

    fun statusLine(): String =
        "sam_pending_materialization=${pendingMaterialization.size} sam_last_jobs=$lastJobs sam_last_scored_chunks=$lastScoredChunks sam_last_ms=${"%.3f".format(lastTickMs)} sam_last_over_budget=$lastOverBudget"

    private fun enqueueMaterialization(base: PillagerBase, front: Boolean) {
        pendingMaterialization.add(base.id, front = front)
    }

    private fun indexMaterialized(base: PillagerBase) {
        chunkIndex.index(base)
    }

    private fun ensureBossAtBase(level: ServerLevel, data: PillagerWorldData, base: PillagerBase) {
        val faction = data.factions[base.factionId] ?: return
        val bossOfficerId = faction.bossOfficerId ?: return
        val bossOfficer = data.officers[bossOfficerId] ?: return
        PillagerRuntime.ensureBossAtBase(level, base, faction, bossOfficer)
    }

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0
}
