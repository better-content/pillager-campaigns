package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceRecord
import com.gerald.pillagercampaigns.data.RallyPresenceState
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
        val environment = EnvironmentSampler.sample(level, candidate.chunkX, candidate.chunkZ)
        val warlord = ensureWarlord(level, data, candidate.id, faction.id)
        val warband = PillagerWarband(
            id = candidate.id,
            factionId = faction.id,
            dimension = candidate.dimension,
            bannerSeed = (level.seed xor candidate.id.mostSignificantBits xor candidate.id.leastSignificantBits).toInt(),
            rallyChunkX = candidate.chunkX,
            rallyChunkZ = candidate.chunkZ,
            reserve = PillagerCampaignsConfig.initialReserve.get(),
            capacity = FormulaicWarbandRules.capacity(environment),
            aggression = PillagerCampaignsConfig.initialAggression.get(),
            environment = environment,
            preferences = FormulaicWarbandRules.initialPreferences(level.seed xor candidate.id.mostSignificantBits, environment),
            lastEconomyTick = now,
            defeated = false,
            warlordOfficerId = warlord.id,
            warlordEntityId = null,
            nextRaidTick = now,
            cooldownUntilTick = 0L,
            lastIntelTick = now,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
            activeCampaignLimit = 1,
            rallyPresence = RallyPresenceRecord(
                state = RallyPresenceState.DORMANT,
                warlordId = warlord.id,
            ),
        )
        TinkersArmoryOptimizer.seedLedger(warband)
        repeat(minOf(3, warband.reserve)) { TinkersArmoryOptimizer.create(warband, level.server)?.let { warband.armory += it.save(net.minecraft.nbt.CompoundTag()) } }
        data.warbands[candidate.id] = warband
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
                val warlord = PillagerIdentity.makeOfficer(
                    faction = faction,
                    homeWarbandId = warbandId,
                    seed = level.seed xor warbandId.mostSignificantBits,
                    role = OfficerRole.WARLORD,
                    rank = OfficerRank.DREAD_CAPTAIN,
                )
                warlord.title = "the Warlord"
                warlord.state = OfficerState.IDLE
                warlord.preferenceGraph.putAll(FormulaicWarbandRules.initialPreferences(level.seed xor warbandId.mostSignificantBits))
                data.officers[warlord.id] = warlord
                faction.bossOfficerId = warlord.id
                warlord
            }
        } ?: error("Faction $factionId missing while registering warband")

}
