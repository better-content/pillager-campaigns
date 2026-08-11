package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.data.PresenceMaterializationResult
import com.gerald.pillagercampaigns.data.RallyPresenceState
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

object PillagerWarbandPresenceSystem {
    private const val RETRY_COOLDOWN_TICKS: Long = 100L

    data class InvasionMaterialization(
        val status: PresenceMaterializationResult,
        val physicalMemberIds: Set<String> = emptySet(),
        val attemptedMemberIds: Set<String> = emptySet(),
    )

    fun materializeInvasionSquad(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        campaign: PillagerCampaign,
        player: ServerPlayer,
        distanceChunks: Int,
        now: Long,
    ): InvasionMaterialization {
        if (now - warband.lastPresenceAttemptTick < RETRY_COOLDOWN_TICKS) {
            return InvasionMaterialization(record(warband, PresenceMaterializationResult.COOLDOWN, now))
        }
        data.officers[campaign.officerId]?.let { captain -> captain.lastSeenTick = now }
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            return InvasionMaterialization(
                record(warband, PresenceMaterializationResult.LIVE_ALREADY_PRESENT, now),
                PillagerRuntime.liveManifestIds(level, campaign),
            )
        }
        val site = PillagerSpawnPlacementRules.findMaterializationSite(level, player, campaign.currentChunkX, campaign.currentChunkZ, distanceChunks)
            ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        val pos = site.pos
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) {
            return InvasionMaterialization(record(warband, PresenceMaterializationResult.NOT_LOADED, now))
        }
        val officer = data.officers[campaign.officerId]
            ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        val faction = data.factions[warband.factionId]
            ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        val spawnedIds = if (campaign.memberSnapshots.isNotEmpty()) {
            PillagerRuntime.restoreSnapshots(level, campaign, pos).also { ids ->
                ids.mapNotNull { level.getEntity(it) as? net.minecraft.world.entity.Mob }.forEach { it.target = player }
            }
        } else {
            PillagerRuntime.materializeWarbandSquad(
                level = level,
                warband = warband,
                campaign = campaign,
                bannerSeed = faction.bannerSeed,
                officerRecord = officer,
                player = player,
                x = pos.x + 0.5,
                y = pos.y.toDouble(),
                z = pos.z + 0.5,
            )
        }
        val attempted = campaign.plannedMembers.mapNotNullTo(linkedSetOf()) { it.manifestId.takeIf(String::isNotBlank) }
        if (spawnedIds.isEmpty()) {
            return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now), attemptedMemberIds = attempted)
        }
        campaign.squadMemberIds.clear()
        campaign.squadMemberIds.addAll(spawnedIds)
        return InvasionMaterialization(
            record(warband, PresenceMaterializationResult.SUCCESS, now),
            PillagerRuntime.liveManifestIds(level, campaign),
            attempted,
        )
    }

    fun materializeWarlord(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        now: Long,
        force: Boolean = false,
    ): PresenceMaterializationResult {
        warband.rallyPresence?.entityId?.let { cachedId ->
            val cached = level.getEntity(cachedId)
            if (cached != null && cached.isAlive) {
                warband.warlordEntityId = cachedId
                warband.rallyPresence?.state = RallyPresenceState.MATERIALIZED
                return record(warband, PresenceMaterializationResult.LIVE_ALREADY_PRESENT, now)
            }
        }
        warband.warlordEntityId?.let { cachedId ->
            val cached = level.getEntity(cachedId)
            if (cached != null && cached.isAlive) {
                return record(warband, PresenceMaterializationResult.LIVE_ALREADY_PRESENT, now)
            }
        }
        if (!force && now - warband.lastPresenceAttemptTick < RETRY_COOLDOWN_TICKS) {
            return record(warband, PresenceMaterializationResult.COOLDOWN, now)
        }
        if (!level.hasChunk(warband.rallyChunkX, warband.rallyChunkZ)) {
            return record(warband, PresenceMaterializationResult.NOT_LOADED, now)
        }
        val pos = PillagerSpawnPlacementRules.findRallyPos(level, warband.rallyChunkX, warband.rallyChunkZ)
            ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        val faction = data.factions[warband.factionId] ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        val warlord = data.officers[warband.warlordOfficerId] ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        val spawned = PillagerRuntime.materializeWarlord(level, warband, faction, warlord, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
            ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        warband.warlordEntityId = spawned
        warband.rallyPresence?.apply {
            state = RallyPresenceState.MATERIALIZED
            entityId = spawned
            anchorX = pos.x
            anchorY = pos.y
            anchorZ = pos.z
            lastMaterializedTick = now
        }
        return record(warband, PresenceMaterializationResult.SUCCESS, now)
    }

    fun statusLine(data: PillagerWorldData): String {
        val failures = data.warbands.values.groupingBy { it.lastPresenceFailure }.eachCount()
            .entries
            .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }
            .ifBlank { "none" }
        return "warband_presence_failures=$failures"
    }

    private fun record(warband: PillagerWarband, result: PresenceMaterializationResult, now: Long): PresenceMaterializationResult {
        warband.lastPresenceAttemptTick = now
        warband.lastPresenceFailure = result
        return result
    }
}
