package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.BaseForm
import com.gerald.pillagercampaigns.data.BaseMaterializationFailure
import com.gerald.pillagercampaigns.data.BaseState
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerBase
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.util.PillagerIdentity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

object PillagerBaseDiscoveryService {
    fun registerPlannedBase(level: ServerLevel, data: PillagerWorldData, candidate: PillagerBasePlacementRules.Candidate, now: Long): Boolean {
        if (data.bases.containsKey(candidate.id)) return false
        if (!PillagerBaseMaterializer.canMaterialize(level, candidate.structureId)) return false
        val existsAtAnchor = data.bases.values.any {
            it.dimension == candidate.dimension &&
                it.anchorChunkX == candidate.chunkX &&
                it.anchorChunkZ == candidate.chunkZ &&
                it.state != BaseState.DEFEATED
        }
        if (existsAtAnchor) return false

        val faction = PillagerIdentity.makeFaction(level.seed xor ChunkPos.asLong(candidate.chunkX, candidate.chunkZ))
        data.factions.putIfAbsent(faction.id, faction)
        data.bases[candidate.id] = PillagerBase(
            id = candidate.id,
            factionId = faction.id,
            dimension = candidate.dimension,
            structureId = candidate.structureId,
            bannerSeed = (level.seed xor candidate.id.mostSignificantBits xor candidate.id.leastSignificantBits).toInt(),
            difficulty = 0,
            defeated = false,
            state = BaseState.PLANNED,
            form = BaseForm.UNKNOWN,
            anchorChunkX = candidate.chunkX,
            anchorChunkZ = candidate.chunkZ,
            chunkX = candidate.chunkX,
            chunkZ = candidate.chunkZ,
            center = approximateCenter(level, candidate.chunkX, candidate.chunkZ),
            lastSeenTick = now,
            materializationAttempts = 0,
            materializationFailure = BaseMaterializationFailure.NONE,
            lastMaterializationAttemptTick = 0L,
            materializationSearchRadius = -1,
            materializationCursorIndex = 0,
            materializationBestChunkX = 0,
            materializationBestChunkZ = 0,
            materializationBestX = 0,
            materializationBestY = 0,
            materializationBestZ = 0,
            materializationBestScore = Int.MIN_VALUE,
        )
        ensureBossOfficer(level, data, candidate.id, faction.id)
        PillagerSettlementScheduler.onBaseRegistered(data.bases.getValue(candidate.id))
        data.markChanged()
        return true
    }

    fun markMaterialized(data: PillagerWorldData, base: PillagerBase, site: PillagerBaseMaterializer.Site, now: Long) {
        base.state = BaseState.MATERIALIZED
        base.form = BaseForm.JIGSAW_OUTPOST
        base.chunkX = site.chunkX
        base.chunkZ = site.chunkZ
        base.center = site.center
        base.lastSeenTick = now
        base.materializationAttempts = 0
        base.materializationFailure = BaseMaterializationFailure.NONE
        base.lastMaterializationAttemptTick = now
        resetMaterializationSearch(base)
        PillagerSettlementScheduler.onBaseMaterialized(base)
        data.markChanged()
    }

    fun resetMaterializationSearch(base: PillagerBase) {
        base.materializationSearchRadius = -1
        base.materializationCursorIndex = 0
        base.materializationBestChunkX = 0
        base.materializationBestChunkZ = 0
        base.materializationBestX = 0
        base.materializationBestY = 0
        base.materializationBestZ = 0
        base.materializationBestScore = Int.MIN_VALUE
    }

    internal fun effectiveDiscoveryRadius(baseDiscoveryRadiusChunks: Int, maxCampaignDistanceChunks: Int): Int {
        return maxOf(baseDiscoveryRadiusChunks, maxCampaignDistanceChunks).coerceAtLeast(1)
    }

    private fun ensureBossOfficer(level: ServerLevel, data: PillagerWorldData, baseId: java.util.UUID, factionId: java.util.UUID) {
        val faction = data.factions[factionId] ?: return
        if (faction.bossOfficerId != null && data.officers[faction.bossOfficerId] != null) return
        val boss = PillagerIdentity.makeOfficer(faction, baseId, level.seed xor baseId.mostSignificantBits, rank = OfficerRank.WARLORD)
        boss.title = "the Warlord"
        boss.state = OfficerState.AVAILABLE
        boss.officerClass = CampaignDifficultyRules.officerClassForDifficulty(0)
        boss.preferenceGraph.putAll(CampaignDifficultyRules.defaultPreferenceGraph(level.seed xor baseId.mostSignificantBits))
        data.officers[boss.id] = boss
        faction.bossOfficerId = boss.id
    }

    private fun approximateCenter(level: ServerLevel, chunkX: Int, chunkZ: Int): BlockPos {
        val chunk = ChunkPos(chunkX, chunkZ)
        return BlockPos(chunk.middleBlockX, level.seaLevel + 1, chunk.middleBlockZ)
    }
}
