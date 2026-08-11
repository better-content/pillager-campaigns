package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceRecord
import com.gerald.pillagercampaigns.data.RallyPresenceState
import com.gerald.pillagercampaigns.data.CosmeticSidecar
import com.gerald.pillagercampaigns.util.PillagerIdentity
import com.gerald.warband.core.ChunkPosition
import com.gerald.warband.core.CoreCatalog
import com.gerald.warband.core.CoreFrame
import com.gerald.warband.core.WarbandDiscoveryObservation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

object PillagerWarbandDiscoveryService {
    fun registerDiscoveredWarband(level: ServerLevel, data: PillagerWorldData, candidate: PillagerWarbandDiscoveryRules.Candidate, now: Long): Boolean {
        if (candidate.dimension != level.dimension().location()) return false
        if (candidate.id.toString() in data.coreState.warbands || candidate.id.toString() in data.coreState.discoveredSiteIds) return false
        val existsAtRally = data.coreState.warbands.values.any {
            it.rally.dimension == candidate.dimension.toString() &&
                it.rally.x == candidate.chunkX &&
                it.rally.z == candidate.chunkZ &&
                !it.defeated
        }
        if (existsAtRally) return false

        val faction = PillagerIdentity.makeFaction(level.seed xor ChunkPos.asLong(candidate.chunkX, candidate.chunkZ))
        data.factions.putIfAbsent(faction.id, faction)
        val environment = EnvironmentSampler.sample(level, candidate.chunkX, candidate.chunkZ)
        val warlord = ensureWarlord(level, data, candidate.id, faction.id)
        data.minecraftSidecar.cosmetics[faction.id.toString()] = CosmeticSidecar(faction.name, bannerSeed = faction.bannerSeed)
        data.minecraftSidecar.cosmetics[warlord.id.toString()] = CosmeticSidecar(warlord.name, warlord.title)
        val preferenceSeed = level.seed xor candidate.id.mostSignificantBits
        WarbandCoreAdapter.transition(
            data,
            CoreFrame(
                elapsedTicks = if (data.coreState.warbands.isEmpty()) (now - data.coreState.tick).coerceAtLeast(0L) else 0L,
                discoveries = listOf(
                    WarbandDiscoveryObservation(
                        siteId = candidate.id.toString(),
                        rally = ChunkPosition(candidate.dimension.toString(), candidate.chunkX, candidate.chunkZ),
                        environment = environment,
                        factionName = faction.name,
                        bannerSeed = faction.bannerSeed,
                        preferenceSeed = preferenceSeed,
                        factionId = faction.id.toString(),
                        warbandId = candidate.id.toString(),
                        officerId = warlord.id.toString(),
                    ),
                ),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            CoreCatalog(WarbandCoreAdapter.LIVE_CATALOG_REVISION, emptyList()),
        )
        val core = data.coreState.warbands.getValue(candidate.id.toString())
        val warband = PillagerWarband(
            id = candidate.id,
            factionId = faction.id,
            dimension = candidate.dimension,
            bannerSeed = (level.seed xor candidate.id.mostSignificantBits xor candidate.id.leastSignificantBits).toInt(),
            rallyChunkX = candidate.chunkX,
            rallyChunkZ = candidate.chunkZ,
            reserve = core.reserveThreat.toInt(),
            capacity = core.capacity.toInt(),
            raidPool = core.raidPool,
            aggression = core.aggression,
            environment = environment,
            preferences = core.preferences.toMutableMap(),
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
