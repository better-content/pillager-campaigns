package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.CombatStyle
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.OfficerClass
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceRecord
import com.gerald.pillagercampaigns.data.RallyPresenceState
import com.gerald.pillagercampaigns.data.WarbandArchetype
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
        val archetype = PillagerWarbandArchetypeRules.select(level.seed, candidate.id)
        val warlord = ensureWarlord(level, data, candidate.id, faction.id, archetype)
        data.warbands[candidate.id] = PillagerWarband(
            id = candidate.id,
            factionId = faction.id,
            dimension = candidate.dimension,
            structureId = candidate.structureId,
            bannerSeed = (level.seed xor candidate.id.mostSignificantBits xor candidate.id.leastSignificantBits).toInt(),
            rallyChunkX = candidate.chunkX,
            rallyChunkZ = candidate.chunkZ,
            strength = PillagerCampaignEngine.INITIAL_WARBAND_STRENGTH,
            defeated = false,
            warlordOfficerId = warlord.id,
            warlordEntityId = null,
            nextRaidTick = now,
            cooldownUntilTick = 0L,
            lastIntelTick = now,
            lastPresenceFailure = PresenceMaterializationResult.SUCCESS,
            activeCampaignLimit = PillagerCampaignsConfig.maxCampaignsPerWarband.get().coerceAtLeast(0),
            archetype = archetype,
            rallyPresence = RallyPresenceRecord(
                state = RallyPresenceState.DORMANT,
                warlordId = warlord.id,
            ),
        )
        data.markChanged()
        return true
    }

    fun effectiveDiscoveryRadius(warbandDiscoveryRadiusChunks: Int, maxCampaignDistanceChunks: Int): Int {
        return maxOf(warbandDiscoveryRadiusChunks, maxCampaignDistanceChunks).coerceAtLeast(1)
    }

    private fun ensureWarlord(level: ServerLevel, data: PillagerWorldData, warbandId: java.util.UUID, factionId: java.util.UUID, archetype: WarbandArchetype) =
        data.factions[factionId]?.let { faction ->
            val existing = faction.bossOfficerId?.let { data.officers[it] }
            if (existing != null) {
                existing.homeWarbandId = warbandId
                existing.officerClass = officerClassForWarlordArchetype(archetype)
                existing
            } else {
                val warlord = PillagerIdentity.makeOfficer(
                    faction = faction,
                    homeWarbandId = warbandId,
                    seed = level.seed xor warbandId.mostSignificantBits,
                    role = OfficerRole.WARLORD,
                    rank = OfficerRank.DREAD_CAPTAIN,
                    combatStyle = combatStyleForArchetype(archetype),
                )
                warlord.title = "the Warlord"
                warlord.state = OfficerState.IDLE
                warlord.officerClass = officerClassForWarlordArchetype(archetype)
                warlord.preferenceGraph.putAll(CampaignDifficultyRules.defaultPreferenceGraph(level.seed xor warbandId.mostSignificantBits))
                data.officers[warlord.id] = warlord
                faction.bossOfficerId = warlord.id
                warlord
            }
        } ?: error("Faction $factionId missing while registering warband")

    private fun officerClassForWarlordArchetype(archetype: WarbandArchetype): OfficerClass = when (archetype) {
        WarbandArchetype.BLACKGUARD -> OfficerClass.VINDICATOR
        WarbandArchetype.HEX -> OfficerClass.EVOKER
        else -> OfficerClass.PILLAGER
    }

    private fun combatStyleForArchetype(archetype: WarbandArchetype): CombatStyle = when (archetype) {
        WarbandArchetype.SKIRMISHER -> CombatStyle.HARRIER
        WarbandArchetype.BLACKGUARD -> CombatStyle.BUTCHER
        WarbandArchetype.HEX -> CombatStyle.HEXER
        WarbandArchetype.SABOTEUR -> CombatStyle.SABOTEUR
    }
}
