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
        val radius = PillagerCampaignsConfig.baseDiscoveryRadiusChunks.get()
        val maxAdds = PillagerCampaignsConfig.maxBaseDiscoveriesPerTick.get()
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
            val found = level.chunkSource.generator.findNearestMapStructure(level, holderSet, player.blockPosition(), radius, false) ?: continue
            val chunk = ChunkPos(found.first)
            val key = "${level.dimension().location()}:${chunk.x},${chunk.z}"
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
                val officerSeed = level.seed xor baseId.mostSignificantBits
                val boss = PillagerIdentity.makeOfficer(liveFaction, baseId, level.seed xor baseId.mostSignificantBits, rank = OfficerRank.WARLORD)
                boss.title = "the Warlord"
                boss.state = OfficerState.AVAILABLE
                boss.officerClass = CampaignDifficultyRules.officerClassForDifficulty(0)
                boss.preferenceGraph.putAll(CampaignDifficultyRules.defaultPreferenceGraph(officerSeed))
                data.officers[boss.id] = boss
                liveFaction.bossOfficerId = boss.id
            }
            added++
        }
        if (added > 0) data.markChanged()
        return added
    }
}
