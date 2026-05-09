package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.PillagerWorldData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

object PillagerDiscoveryCoordinator {
    private const val MAX_CANDIDATE_REGISTRATIONS_PER_TICK = 4
    private const val MAX_PENDING_CANDIDATES = 4096

    private data class PlayerSnapshot(
        val dimension: ResourceLocation,
        val chunkX: Int,
        val chunkZ: Int,
    )

    private data class CandidateTask(
        val candidate: PillagerBasePlacementRules.Candidate,
        val requestedAtTick: Long,
    )

    private val pending = ConcurrentLinkedQueue<CandidateTask>()
    private val planning = AtomicBoolean(false)
    private val planner = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread(r, "pillagercampaigns-discovery-planner").apply { isDaemon = true }
        }
    )

    fun reset() {
        pending.clear()
        planning.set(false)
    }

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        if (now - data.lastDiscoveryTick >= PillagerCampaignsConfig.baseDiscoveryIntervalTicks.get()) {
            data.lastDiscoveryTick = now
            enqueuePlan(server, now)
        }

        val level = server.overworld()
        var added = 0
        var budget = MAX_CANDIDATE_REGISTRATIONS_PER_TICK
        while (budget > 0) {
            val task = pending.poll() ?: break
            if (task.candidate.dimension == level.dimension().location() && PillagerBaseDiscoveryService.registerPlannedBase(level, data, task.candidate, task.requestedAtTick)) {
                added++
            }
            budget--
        }
        if (added > 0) data.markChanged()
    }

    private fun enqueuePlan(server: MinecraftServer, now: Long) {
        if (!planning.compareAndSet(false, true)) return
        val settings = placementSettings()
        val seed = server.overworld().seed
        val radius = PillagerBaseDiscoveryService.effectiveDiscoveryRadius(
            baseDiscoveryRadiusChunks = PillagerCampaignsConfig.baseDiscoveryRadiusChunks.get(),
            maxCampaignDistanceChunks = PillagerCampaignsConfig.maxCampaignDistanceChunks.get(),
        )
        val snapshots = server.playerList.players.map { it.snapshot() }
        planner.execute {
            try {
                for (snapshot in snapshots) {
                    if (pending.size >= MAX_PENDING_CANDIDATES) break
                    val cells = PillagerBasePlacementRules.cellsAround(snapshot.chunkX, snapshot.chunkZ, radius, settings.spacingChunks)
                    for ((cellX, cellZ) in cells) {
                        if (pending.size >= MAX_PENDING_CANDIDATES) break
                        val candidate = PillagerBasePlacementRules.candidateForCell(seed, snapshot.dimension, cellX, cellZ, settings) ?: continue
                        pending.offer(CandidateTask(candidate, now))
                    }
                }
            } finally {
                planning.set(false)
            }
        }
    }

    private fun ServerPlayer.snapshot(): PlayerSnapshot = PlayerSnapshot(
        dimension = level().dimension().location(),
        chunkX = chunkPosition().x,
        chunkZ = chunkPosition().z,
    )

    private fun placementSettings(): PillagerBasePlacementRules.Settings {
        val structures = PillagerCampaignsConfig.structureBaseIds.get().mapNotNull { ResourceLocation.tryParse(it) }
        return PillagerBasePlacementRules.Settings(
            spacingChunks = PillagerCampaignsConfig.baseGridSpacingChunks.get(),
            jitterChunks = PillagerCampaignsConfig.baseGridJitterChunks.get(),
            spawnChancePercent = PillagerCampaignsConfig.baseSpawnChancePercent.get(),
            minSpawnDistanceChunks = PillagerCampaignsConfig.baseMinSpawnDistanceChunks.get(),
            structureIds = structures,
        )
    }
}
