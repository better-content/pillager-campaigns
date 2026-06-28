package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.util.PillagerIdentity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

object PillagerWarbandDiscoveryService {
    fun registerDiscoveredWarband(level: ServerLevel, data: PillagerWorldData, candidate: PillagerWarbandDiscoveryRules.Candidate, now: Long): Boolean {
        if (candidate.dimension != level.dimension().location()) return false
        if (data.warbands.containsKey(candidate.id)) return false
        val existsAtRally = data.warbands.values.any {
            it.dimension == candidate.dimension &&
                it.rallyChunkX == candidate.chunkX &&
                it.rallyChunkZ == candidate.chunkZ &&
                !it.defeated
        }
        if (existsAtRally) return false

        val faction = PillagerIdentity.makeFaction(level.seed xor ChunkPos.asLong(candidate.chunkX, candidate.chunkZ))
        data.factions.putIfAbsent(faction.id, faction)
        val warlord = ensureWarlord(level, data, candidate.id, faction.id)
        data.warbands[candidate.id] = PillagerWarband(
            id = candidate.id,
            factionId = faction.id,
            dimension = candidate.dimension,
            structureId = candidate.structureId,
            bannerSeed = (level.seed xor candidate.id.mostSignificantBits xor candidate.id.leastSignificantBits).toInt(),
            rallyChunkX = candidate.chunkX,
            rallyChunkZ = candidate.chunkZ,
            strength = 3,
            defeated = false,
            warlordOfficerId = warlord.id,
            warlordEntityId = null,
            nextRaidTick = now,
            cooldownUntilTick = 0L,
            lastIntelTick = now,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
            activeCampaignLimit = PillagerCampaignsConfig.maxCampaignsPerWarband.get().coerceAtLeast(0),
        )
        data.markChanged()
        return true
    }

    fun effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks: Int, maxCampaignDistanceChunks: Int): Int {
        return maxOf(warbandDiscoveryRadiusChunks, maxCampaignDistanceChunks).coerceAtLeast(1)
    }

    private fun ensureWarlord(level: ServerLevel, data: PillagerWorldData, warbandId: java.util.UUID, factionId: java.util.UUID) =
        data.factions[factionId]?.let { faction ->
            val existing = faction.bossOfficerId?.let { data.officers[it] }
            if (existing != null) {
                existing.homeWarbandId = warbandId
                existing
            } else {
                val warlord = PillagerIdentity.makeOfficer(faction, warbandId, level.seed xor warbandId.mostSignificantBits, rank = OfficerRank.WARLORD)
                warlord.title = "the Warlord"
                warlord.state = OfficerState.AVAILABLE
                warlord.officerClass = CampaignDifficultyRules.officerClassForDifficulty(0)
                warlord.preferenceGraph.putAll(CampaignDifficultyRules.defaultPreferenceGraph(level.seed xor warbandId.mostSignificantBits))
                data.officers[warlord.id] = warlord
                faction.bossOfficerId = warlord.id
                warlord
            }
        } ?: error("Faction $factionId missing while registering warband")
}
