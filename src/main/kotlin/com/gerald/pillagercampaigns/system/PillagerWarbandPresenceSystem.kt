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
        effect: com.gerald.warband.core.CoreEffect,
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
        val anchor = effect.blockPosition ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        if (anchor.dimension != level.dimension().location().toString() || !level.hasChunk(anchor.x shr 4, anchor.z shr 4)) {
            return InvasionMaterialization(record(warband, PresenceMaterializationResult.NOT_LOADED, now))
        }
        val officer = data.officers[campaign.officerId]
            ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        val faction = data.factions[warband.factionId]
            ?: return InvasionMaterialization(record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now))
        val spawnedIds = PillagerRuntime.materializeWarbandSquad(
            level, data, warband, campaign, faction.bannerSeed, officer, player, effect,
        )
        val attempted = effect.memberIds.toSet()
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
        val effect = data.snapshot().pendingEffects.values.firstOrNull {
            it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE_WARLORD && it.warbandId == warband.id.toString()
        }
        val faction = data.factions[warband.factionId]
        val warlord = data.officers[warband.warlordOfficerId]
        warband.rallyPresence?.entityId?.let { cachedId ->
            val cached = level.getEntity(cachedId)
            if (cached != null && cached.isAlive) {
                warband.warlordEntityId = cachedId
                warband.rallyPresence?.state = RallyPresenceState.MATERIALIZED
                if (faction != null && warlord != null) realizePendingGarrison(level, data, warband, faction, warlord)
                return record(warband, PresenceMaterializationResult.LIVE_ALREADY_PRESENT, now)
            }
        }
        warband.warlordEntityId?.let { cachedId ->
            val cached = level.getEntity(cachedId)
            if (cached != null && cached.isAlive) {
                if (faction != null && warlord != null) realizePendingGarrison(level, data, warband, faction, warlord)
                return record(warband, PresenceMaterializationResult.LIVE_ALREADY_PRESENT, now)
            }
        }
        if (!force && now - warband.lastPresenceAttemptTick < RETRY_COOLDOWN_TICKS) {
            return record(warband, PresenceMaterializationResult.COOLDOWN, now)
        }
        val exact = effect?.blockPosition
            ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        if (exact.dimension != level.dimension().location().toString() || !level.hasChunk(exact.x shr 4, exact.z shr 4)) {
            return record(warband, PresenceMaterializationResult.NOT_LOADED, now)
        }
        val pos = net.minecraft.core.BlockPos(exact.x, exact.y, exact.z)
        if (faction == null || warlord == null) return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        val member = effect?.memberManifest
            ?: return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        val spawned = PillagerRuntime.materializeWarlord(
            level, warband, faction, warlord, member, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5,
        )
        if (spawned == null) {
            WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    0L,
                    acknowledgements = listOf(com.gerald.warband.core.EffectAcknowledgement(
                        effect.effectId, false, "warlord_realization_failed",
                    )),
                ),
                level.server,
            )
            return record(warband, PresenceMaterializationResult.NO_SAFE_SITE, now)
        }
        warband.warlordEntityId = spawned
        warband.rallyPresence?.apply {
            state = RallyPresenceState.MATERIALIZED
            entityId = spawned
            anchorX = pos.x
            anchorY = pos.y
            anchorZ = pos.z
            lastMaterializedTick = now
        }
        data.minecraftSidecar.entityIds[member.id] = spawned
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                0L,
                acknowledgements = listOf(com.gerald.warband.core.EffectAcknowledgement(
                    effect.effectId, true, "warlord_materialized",
                )),
            ),
            level.server,
        )
        realizePendingGarrison(level, data, warband, faction, warlord)
        return record(warband, PresenceMaterializationResult.SUCCESS, now)
    }

    private fun realizePendingGarrison(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        faction: com.gerald.pillagercampaigns.data.PillagerFaction,
        warlord: com.gerald.pillagercampaigns.data.PillagerOfficer,
    ) {
        data.snapshot().pendingEffects.values.firstOrNull {
            it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE_GARRISON && it.warbandId == warband.id.toString()
        }?.let { garrisonEffect ->
            val garrisonId = garrisonEffect.garrisonId ?: return@let
            val garrisonMembers = PillagerRuntime.materializeGarrison(
                level, data, warband, faction, warlord, garrisonEffect,
            )
            WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    0L,
                    garrisonResults = listOf(com.gerald.warband.core.GarrisonResult(
                        garrisonId, garrisonMembers.isNotEmpty(), garrisonMembers, garrisonEffect.effectId,
                    )),
                ),
                level.server,
            )
        }
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
