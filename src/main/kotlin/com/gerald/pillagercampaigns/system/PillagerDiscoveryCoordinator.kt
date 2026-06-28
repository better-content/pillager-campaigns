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
    private const val MAX_PENDING_CANDIDATES = 4096
    private val OVERWORLD: ResourceLocation = ResourceLocation("minecraft", "overworld")

    private data class PlayerSnapshot(
        val chunkX: Int,
        val chunkZ: Int,
    )

    private data class CandidateTask(
        val candidate: PillagerWarbandDiscoveryRules.Candidate,
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
        if (now - data.lastDiscoveryTick >= PillagerCampaignsConfig.warbandDiscoveryIntervalTicks.get()) {
            data.lastDiscoveryTick = now
            enqueuePlan(server, now)
        }

        val level = server.overworld()
        var added = 0
        var budget = PillagerCampaignsConfig.warbandRegistrationsPerTick.get().coerceAtLeast(1)
        while (budget > 0) {
            val task = pending.poll() ?: break
            if (PillagerWarbandDiscoveryService.registerDiscoveredWarband(level, data, task.candidate, task.requestedAtTick)) {
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
        val radius = PillagerWarbandDiscoveryService.effectiveDiscoveryRadius(
            warbandDiscoveryRadiusChunks = PillagerCampaignsConfig.warbandDiscoveryRadiusChunks.get(),
            maxCampaignDistanceChunks = PillagerCampaignsConfig.maxCampaignDistanceChunks.get(),
        )
        val snapshots = server.playerList.players.mapNotNull { it.snapshot() }
        planner.execute {
            try {
                for (snapshot in snapshots) {
                    if (pending.size >= MAX_PENDING_CANDIDATES) break
                    val cells = PillagerWarbandDiscoveryRules.cellsAround(snapshot.chunkX, snapshot.chunkZ, radius, settings.spacingChunks)
                    for ((cellX, cellZ) in cells) {
                        if (pending.size >= MAX_PENDING_CANDIDATES) break
                        val candidate = PillagerWarbandDiscoveryRules.candidateForCell(seed, OVERWORLD, cellX, cellZ, settings) ?: continue
                        pending.offer(CandidateTask(candidate, now))
                    }
                }
            } finally {
                planning.set(false)
            }
        }
    }

    private fun ServerPlayer.snapshot(): PlayerSnapshot? =
        if (level().dimension().location() == OVERWORLD) {
            PlayerSnapshot(chunkPosition().x, chunkPosition().z)
        } else {
            null
        }

    private fun placementSettings(): PillagerWarbandDiscoveryRules.Settings {
        val structures = PillagerCampaignsConfig.structureWarbandIds.get().mapNotNull { ResourceLocation.tryParse(it) }
        return PillagerWarbandDiscoveryRules.Settings(
            spacingChunks = PillagerCampaignsConfig.warbandGridSpacingChunks.get(),
            jitterChunks = PillagerCampaignsConfig.warbandGridJitterChunks.get(),
            spawnChancePercent = PillagerCampaignsConfig.warbandSpawnChancePercent.get(),
            minSpawnDistanceChunks = PillagerCampaignsConfig.warbandMinSpawnDistanceChunks.get(),
            structureIds = structures,
        )
    }
}
