package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerWorldData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

object PillagerDiscoveryCoordinator {
    private const val MAX_PENDING_CANDIDATES = 4096
    private val OVERWORLD: ResourceLocation = ResourceLocation("minecraft", "overworld")

    fun reset() = Unit

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val level = server.overworld()
        val rules = data.runtimeRules()
        val settings = PillagerWarbandDiscoveryRules.Settings(
            spacingChunks = rules.discoveryGridSpacingChunks,
            jitterChunks = rules.discoveryGridJitterChunks,
            spawnChancePercent = (rules.discoveryChance * 100.0).toInt(),
            minSpawnDistanceChunks = rules.discoveryMinimumPlayerDistanceChunks,
        )
        val radius = rules.discoveryMaximumDistanceChunks
        val seed = level.seed
        val candidates = server.playerList.players.asSequence()
            .filter { it.level().dimension().location() == OVERWORLD }
            .flatMap { player ->
                PillagerWarbandDiscoveryRules.cellsAround(
                    player.chunkPosition().x, player.chunkPosition().z, radius, settings.spacingChunks,
                )
            }
            .distinct()
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .filter { (cellX, cellZ) ->
                level.hasChunk(cellX * settings.spacingChunks, cellZ * settings.spacingChunks)
            }
            .take(MAX_PENDING_CANDIDATES)
            .mapNotNull { (cellX, cellZ) ->
                PillagerWarbandDiscoveryRules.candidateForCell(seed, OVERWORLD, cellX, cellZ, settings)
            }.toList()
        val added = PillagerWarbandDiscoveryService.registerDiscoveredWarbands(level, data, candidates, now)
        if (added > 0) data.markChanged()
    }

}
