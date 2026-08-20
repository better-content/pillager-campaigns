package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import com.gerald.warband.core.ChunkPosition

object PillagerDiscoveryCoordinator {
    private const val MAX_PENDING_CANDIDATES = 4096
    private val OVERWORLD: ResourceLocation = ResourceLocation("minecraft", "overworld")

    fun reset() = Unit

    fun statusLine(server: MinecraftServer, data: PillagerWorldData): String {
        val snapshot = data.snapshot()
        val maximumDistance = data.runtimeRules().maximumDispatchDistanceChunks
        val eligible = server.playerList.players.filter {
            it.level().dimension().location() == OVERWORLD && PillagerCampaignCoordinator.isCampaignTarget(it)
        }
        val covered = eligible.count { player ->
            snapshot.warbands.values.any { warband ->
                !warband.defeated && warband.rally.dimension == OVERWORLD.toString() &&
                    manhattan(warband.rally, player.chunkPosition().x, player.chunkPosition().z) <= maximumDistance
            }
        }
        val deferred = snapshot.pendingEffects.values.count {
            it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE_WARLORD && it.blockPosition == null
        }
        return "pressure_coverage=$covered/${eligible.size} deferred_rallies=$deferred"
    }

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val level = server.overworld()
        val rules = data.runtimeRules()
        val snapshot = data.snapshot()
        if (snapshot.lastDiscoveryTick > 0L && now - snapshot.lastDiscoveryTick < rules.discoveryIntervalTicks) return
        val settings = PillagerWarbandDiscoveryRules.Settings(
            spacingChunks = rules.discoveryGridSpacingChunks,
            jitterChunks = rules.discoveryGridJitterChunks,
            spawnChancePercent = (rules.discoveryChance * 100.0).toInt(),
            minSpawnDistanceChunks = rules.discoveryMinimumPlayerDistanceChunks,
        )
        val radius = rules.discoveryMaximumDistanceChunks
        val seed = level.seed
        val players = server.playerList.players.asSequence()
            .filter { it.level().dimension().location() == OVERWORLD }
            .filter(PillagerCampaignCoordinator::isCampaignTarget)
            .toList()
        val coverageCandidates = players.asSequence()
            .filter { player ->
                snapshot.warbands.values.none { warband ->
                    !warband.defeated && warband.rally.dimension == OVERWORLD.toString() &&
                        manhattan(warband.rally, player.chunkPosition().x, player.chunkPosition().z) <= rules.maximumDispatchDistanceChunks
                }
            }
            .flatMap { player ->
                PillagerWarbandDiscoveryRules.coverageCandidates(
                    seed, OVERWORLD, player.uuid.toString(), player.chunkPosition().x, player.chunkPosition().z,
                    rules.discoveryMinimumPlayerDistanceChunks, rules.maximumDispatchDistanceChunks,
                ).asSequence()
            }
            .toList()
        val proceduralCandidates = players.asSequence()
            .flatMap { player ->
                PillagerWarbandDiscoveryRules.cellsAround(
                    player.chunkPosition().x, player.chunkPosition().z, radius, settings.spacingChunks,
                )
            }
            .distinct()
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .mapNotNull { (cellX, cellZ) ->
                PillagerWarbandDiscoveryRules.candidateForCell(seed, OVERWORLD, cellX, cellZ, settings)
            }.toList()
        val candidates = (coverageCandidates + proceduralCandidates)
            .distinctBy(PillagerWarbandDiscoveryRules.Candidate::id)
            .take(MAX_PENDING_CANDIDATES)
        val added = PillagerWarbandDiscoveryService.registerDiscoveredWarbands(level, data, candidates, now)
        if (added > 0) data.markChanged()
    }

    private fun manhattan(position: ChunkPosition, chunkX: Int, chunkZ: Int): Int =
        kotlin.math.abs(position.x - chunkX) + kotlin.math.abs(position.z - chunkZ)

}
