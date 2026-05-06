package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.util.PillagerIdentity
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

object PillagerBaseDiscoveryService {
    fun discoverAroundPlayers(level: ServerLevel, data: PillagerWorldData, players: List<ServerPlayer>, now: Long): Int {
        val radius = effectiveDiscoveryRadius(
            baseDiscoveryRadiusChunks = PillagerCampaignsConfig.baseDiscoveryRadiusChunks.get(),
            maxCampaignDistanceChunks = PillagerCampaignsConfig.maxCampaignDistanceChunks.get(),
        )
        val maxAdds = PillagerCampaignsConfig.maxBaseDiscoveriesPerTick.get()
        val maxProbePoints = PillagerCampaignsConfig.maxBaseDiscoveryProbePointsPerPlayer.get()
        val structureIds = PillagerCampaignsConfig.structureBaseIds.get().mapNotNull { ResourceLocation.tryParse(it as String) }
        if (structureIds.isEmpty()) return 0
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        val structures = structureIds.mapNotNull { id ->
            val key = ResourceKey.create(Registries.STRUCTURE, id)
            registry.getHolder(key).orElse(null)
        }
        if (structures.isEmpty()) return 0
        val holderSet = HolderSet.direct(structures)

        var added = 0
        for (player in players) {
            if (added >= maxAdds) break
            val probes = buildProbePoints(player.blockPosition(), radius, maxProbePoints)
            val seenChunkKeys = HashSet<String>()
            for (probe in probes) {
                if (added >= maxAdds) break
                val found = level.chunkSource.generator.findNearestMapStructure(level, holderSet, probe, radius, false) ?: continue
                val chunk = ChunkPos(found.first)
                val key = "${level.dimension().location()}:${chunk.x},${chunk.z}"
                if (!seenChunkKeys.add(key)) continue
                val exists = data.bases.values.any { it.dimension == level.dimension().location() && it.chunkX == chunk.x && it.chunkZ == chunk.z }
                if (exists) continue
                val faction = PillagerIdentity.makeFaction(level.seed xor ChunkPos.asLong(chunk.x, chunk.z))
                data.factions.putIfAbsent(faction.id, faction)
                val baseId = UUID.nameUUIDFromBytes("pillagercampaigns:base:$key".toByteArray())
                data.bases.putIfAbsent(
                    baseId,
                    PillagerBase(
                        id = baseId,
                        factionId = faction.id,
                        dimension = level.dimension().location(),
                        bannerSeed = (level.seed xor baseId.mostSignificantBits xor baseId.leastSignificantBits).toInt(),
                        difficulty = 0,
                        defeated = false,
                        chunkX = chunk.x,
                        chunkZ = chunk.z,
                        center = BlockPos(
                            chunk.middleBlockX,
                            level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chunk.middleBlockX, chunk.middleBlockZ),
                            chunk.middleBlockZ,
                        ),
                        lastSeenTick = now,
                    ),
                )
                val liveFaction = data.factions[faction.id] ?: faction
                if (liveFaction.bossOfficerId == null || data.officers[liveFaction.bossOfficerId] == null) {
                    val boss = PillagerIdentity.makeOfficer(liveFaction, baseId, level.seed xor baseId.mostSignificantBits, rank = OfficerRank.WARLORD)
                    boss.title = "the Warlord"
                    boss.state = OfficerState.AVAILABLE
                    boss.officerClass = CampaignDifficultyRules.officerClassForDifficulty(0)
                    boss.preferenceGraph.putAll(CampaignDifficultyRules.defaultPreferenceGraph(level.seed xor baseId.mostSignificantBits))
                    data.officers[boss.id] = boss
                    liveFaction.bossOfficerId = boss.id
                }
                added++
            }
        }
        if (added > 0) data.markChanged()
        return added
    }

    internal fun buildProbePoints(origin: BlockPos, radiusChunks: Int, maxProbes: Int): List<BlockPos> {
        val maxR = radiusChunks.coerceAtLeast(1)
        val candidateOffsets = generateCandidateOffsets(maxR, maxProbes)
        val orderedOffsets = orderByFarthestFromExisting(candidateOffsets, maxProbes)
        return orderedOffsets.map { (dx, dz) ->
            BlockPos(
                origin.x + dx * 16,
                origin.y,
                origin.z + dz * 16,
            )
        }
    }

    internal fun generateCandidateOffsets(maxR: Int, budget: Int): List<Pair<Int, Int>> {
        val offsets = LinkedHashSet<Pair<Int, Int>>()
        val ringCount = budget.coerceAtLeast(1).coerceAtMost(12)
        val ringStep = (maxR / (ringCount + 1)).coerceAtLeast(1)
        offsets.add(0 to 0)
        for (ring in 1..ringCount) {
            val r = ring * ringStep
            val axisStep = kotlin.math.max(1, r / 4)
            for (dx in -r..r step axisStep) {
                offsets.add(dx to r)
                offsets.add(dx to -r)
            }
            for (dz in (-r + axisStep) until r step axisStep) {
                offsets.add(r to dz)
                offsets.add(-r to dz)
            }
        }
        return offsets.toList()
    }

    internal fun orderByFarthestFromExisting(points: List<Pair<Int, Int>>, maxPoints: Int): List<Pair<Int, Int>> {
        if (points.isEmpty()) {
            return emptyList()
        }
        val remaining = points.toMutableList()
        val ordered = ArrayList<Pair<Int, Int>>(maxPoints)
        var current = remaining.removeAt(0)
        ordered.add(current)
        while (ordered.size < maxPoints && remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestScore = -1L
            for (i in remaining.indices) {
                val candidate = remaining[i]
                var score = 0L
                for (visited in ordered) {
                    score += manhattan(candidate, visited)
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
            current = remaining.removeAt(bestIndex)
            ordered.add(current)
        }
        return ordered
    }

    internal fun manhattan(a: Pair<Int, Int>, b: Pair<Int, Int>): Long {
        return (a.first - b.first).let { if (it < 0) -it else it } +
            (a.second - b.second).let { if (it < 0) -it else it }.toLong()
    }

    internal fun effectiveDiscoveryRadius(baseDiscoveryRadiusChunks: Int, maxCampaignDistanceChunks: Int): Int {
        return maxOf(baseDiscoveryRadiusChunks, maxCampaignDistanceChunks)
    }
}
