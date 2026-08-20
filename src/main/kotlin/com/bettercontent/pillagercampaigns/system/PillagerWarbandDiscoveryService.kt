package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.PlayerFact
import com.gerald.warband.core.WarbandDiscoveryObservation
import net.minecraft.server.level.ServerLevel

object PillagerWarbandDiscoveryService {
    fun registerDiscoveredWarband(level: ServerLevel, data: PillagerWorldData, candidate: PillagerWarbandDiscoveryRules.Candidate, now: Long): Boolean {
        return registerDiscoveredWarbands(level, data, listOf(candidate), now) > 0
    }

    fun registerDiscoveredWarbands(
        level: ServerLevel,
        data: PillagerWorldData,
        candidates: List<PillagerWarbandDiscoveryRules.Candidate>,
        now: Long,
    ): Int {
        val observed = candidates.filter { it.dimension == level.dimension().location() }
        if (observed.isEmpty()) return 0
        val snapshot = data.snapshot()
        val players = level.server.playerList.players.map { player ->
            PlayerFact(
                player.uuid.toString(),
                ChunkPosition(
                    player.level().dimension().location().toString(), player.chunkPosition().x, player.chunkPosition().z,
                ),
                eligible = PillagerCampaignCoordinator.isCampaignTarget(player),
            )
        }
        val transition = WarbandCoreAdapter.transition(
            data,
            CoreFrame(
                elapsedTicks = if (snapshot.warbands.isEmpty()) (now - snapshot.tick).coerceAtLeast(0L) else 0L,
                players = players,
                discoveries = observed.map { candidate ->
                    val sites = if (level.hasChunk(candidate.chunkX, candidate.chunkZ)) {
                        PillagerSpawnPlacementRules.findRallyCandidates(level, candidate.chunkX, candidate.chunkZ)
                    } else {
                        emptyList()
                    }
                    WarbandDiscoveryObservation(
                        siteId = candidate.siteId,
                        rally = ChunkPosition(candidate.dimension.toString(), candidate.chunkX, candidate.chunkZ),
                        environment = EnvironmentSampler.sample(level, candidate.chunkX, candidate.chunkZ, data.environmentModel()),
                        cellX = candidate.cellX.takeIf { candidate.siteId.isBlank() },
                        cellZ = candidate.cellZ.takeIf { candidate.siteId.isBlank() },
                        worldSeed = level.seed,
                        siteCandidates = sites.map { site -> com.gerald.warband.core.BlockPosition(
                            level.dimension().location().toString(), site.x, site.y, site.z,
                        ) },
                        coveragePlayerId = candidate.coveragePlayerId,
                    )
                },
            ),
            level.server,
        )
        val accepted = transition.events.count { it.type == "warband_discovered" }
        if (accepted > 0) data.markChanged()
        return accepted
    }

    fun effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks: Int, maxCampaignDistanceChunks: Int): Int {
        return maxOf(warbandDiscoveryRadiusChunks, maxCampaignDistanceChunks).coerceAtLeast(1)
    }

}
