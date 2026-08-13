package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.PillagerCampaignsConfig
import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.data.CampaignOutcome
import com.bettercontent.pillagercampaigns.data.CampaignState
import com.bettercontent.pillagercampaigns.data.NemesisEvent
import com.bettercontent.pillagercampaigns.data.NemesisEventType
import com.bettercontent.pillagercampaigns.data.OfficerState
import com.bettercontent.pillagercampaigns.data.PillagerCampaign
import com.bettercontent.pillagercampaigns.data.PillagerOfficer
import com.bettercontent.pillagercampaigns.data.PillagerWarband
import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import com.bettercontent.pillagercampaigns.data.MaterializationAttemptSidecar
import com.gerald.warband.core.CampaignOutcomeKind
import com.gerald.warband.core.CampaignOutcomeObservation
import com.gerald.warband.core.PlayerFact
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.GameType
import java.util.UUID
import kotlin.math.max

object PillagerCampaignCoordinator {
    val PLAYER_RESPAWN_PROTECTION_TICKS: Long get() = PillagerCampaignsConfig.respawnProtectionTicks.get().toLong()
    private const val MAX_HISTORY_EVENTS: Int = 8
    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        updateTerritorialRelations(server, data)
        advance(server, data, now)
    }

    fun discoveryTick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val before = data.warbands.size
        PillagerDiscoveryCoordinator.tick(server, data, now)
        val added = data.warbands.size - before
        if (added > 0) {
            PillagerCampaignsMod.LOGGER.debug("Discovered {} pillager warband(s)", added)
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val snapshot = data.snapshot()
        val players = server.playerList.players.map { player ->
            PlayerFact(
                player.uuid.toString(),
                com.gerald.warband.core.ChunkPosition(
                    player.level().dimension().location().toString(), player.chunkPosition().x, player.chunkPosition().z,
                ),
                eligible = isCampaignTarget(player),
            )
        }.toMutableList()
        val terrain = mutableListOf<com.gerald.warband.core.TerrainObservation>()
        val snapshots = mutableListOf<com.gerald.warband.core.CampaignSnapshotResult>()
        val positions = mutableListOf<com.gerald.warband.core.PositionObservation>()
        val materializationSites = mutableListOf<com.gerald.warband.core.MaterializationSiteObservation>()
        val priorStates = linkedMapOf<UUID, CampaignState>()
        val validPlayers = linkedMapOf<UUID, ServerPlayer>()
        val levels = linkedMapOf<UUID, ServerLevel>()

        data.campaigns.values.toList().forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED) return@forEach
            val warband = data.warbands[campaign.originWarbandId] ?: return@forEach
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId }
            if (player == null && players.none { it.id == campaign.targetPlayerId.toString() }) {
                val coreCampaign = snapshot.campaigns[campaign.id.toString()]
                if (coreCampaign != null) {
                    players += PlayerFact(
                        campaign.targetPlayerId.toString(),
                        coreCampaign.target,
                        eligible = false,
                        gameModeEligible = false,
                        physicallyAvailable = false,
                    )
                }
            }
            val level = player?.serverLevel() ?: server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: return@forEach
            levels[campaign.id] = level
            priorStates[campaign.id] = campaign.state
            if (PillagerRuntime.releaseLoadedCaches(level, campaign) > 0) data.markChanged()
            val physical = PillagerRuntime.hasLiveCampaignMember(level, campaign.id)
            if (campaign.state == CampaignState.RETURNING && physical) {
                val capture = snapshot.pendingEffects.values.firstOrNull {
                    it.kind == com.gerald.warband.core.EffectKind.CAPTURE_SNAPSHOTS && it.campaignId == campaign.id.toString()
                }
                if (capture != null && PillagerRuntime.snapshotCampaign(level, campaign) > 0) {
                    snapshots += WarbandCoreAdapter.snapshotResult(campaign, capture.effectId)
                }
            } else if (campaign.state == CampaignState.ACTIVE && physical) {
                PillagerRuntime.syncLiveCampaignState(level, campaign)
            }
            if (physical && snapshots.none { it.campaignId == campaign.id.toString() }) {
                positions += com.gerald.warband.core.PositionObservation(
                    campaign.id.toString(),
                    com.gerald.warband.core.ChunkPosition(
                        campaign.targetDimension.toString(), campaign.currentChunkX, campaign.currentChunkZ,
                    ),
                )
            }

            val protected = player?.let { data.isPlayerProtected(it.uuid, now) } == true
            val validPlayer = player?.takeIf { isCampaignTarget(it) && !protected }
            validPlayer?.let { validPlayers[campaign.id] = it }
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && validPlayer != null) {
                val candidates = PillagerSpawnPlacementRules.findMaterializationSites(
                    level, validPlayer, campaign.currentChunkX, campaign.currentChunkZ,
                    data.runtimeRules().materializeDistanceChunks,
                ).map { it.pos }
                if (candidates.isNotEmpty()) {
                    materializationSites += com.gerald.warband.core.MaterializationSiteObservation(
                        campaign.id.toString(),
                        candidates.map { pos -> com.gerald.warband.core.BlockPosition(
                            level.dimension().location().toString(), pos.x, pos.y, pos.z,
                        ) },
                    )
                }
            }
            terrain += EnvironmentSampler.corridor(
                level, warband.rallyChunkX, warband.rallyChunkZ, campaign.targetChunkX, campaign.targetChunkZ,
                data.environmentModel(),
            )
        }

        val transition = WarbandCoreAdapter.advanceCanonical(
            server,
            data,
            now,
            com.gerald.warband.core.CoreFrame(
                0L,
                players = players,
                snapshots = snapshots,
                physicalPositions = positions,
                materializationSites = materializationSites,
                terrain = terrain.distinctBy { "${it.position.dimension}:${it.position.x}:${it.position.z}" },
            ),
        )

        priorStates.forEach { (campaignId, priorState) ->
            val campaign = data.campaigns[campaignId] ?: return@forEach
            if (priorState != CampaignState.RETURNING && campaign.state == CampaignState.RETURNING) {
                campaign.returnStartedTick = now
                campaign.returnOutcome = if (campaign.returnReason == "morale") CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT else CampaignOutcome.ABORTED
            }
            val validPlayer = validPlayers[campaign.id]
            val level = levels[campaign.id]
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && validPlayer != null && level != null) {
                transition.effects.firstOrNull {
                    it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE && it.campaignId == campaign.id.toString()
                }?.let { effect -> tryMaterialize(level, campaign, validPlayer, data, now, effect) }
            }
        }
        if (transition.events.isNotEmpty() || transition.effects.isNotEmpty()) data.markChanged()
    }

    private fun tryMaterialize(
        level: ServerLevel,
        campaign: PillagerCampaign,
        player: ServerPlayer,
        data: PillagerWorldData,
        now: Long,
        effect: com.gerald.warband.core.CoreEffect,
    ) {
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            acknowledgeMaterialization(
                level, data, campaign, true,
                PillagerRuntime.liveManifestIds(level, campaign),
            )
            return
        }
        val attemptId = runCatching { UUID.fromString(effect.effectId) }
            .getOrElse { UUID.nameUUIDFromBytes(effect.effectId.toByteArray(Charsets.UTF_8)) }
        data.minecraftSidecar.materializationAttempts[campaign.id.toString()] =
            MaterializationAttemptSidecar(attemptId, now)
        data.markChanged()

        val warband = data.warbands[campaign.originWarbandId] ?: return
        val result = PillagerWarbandPresenceSystem.materializeInvasionSquad(level, data, warband, campaign, player, effect, now)
        val success = result.status == com.bettercontent.pillagercampaigns.data.PresenceMaterializationResult.SUCCESS ||
            result.status == com.bettercontent.pillagercampaigns.data.PresenceMaterializationResult.LIVE_ALREADY_PRESENT
        acknowledgeMaterialization(
            level, data, campaign, success, result.physicalMemberIds, result.attemptedMemberIds, effect.effectId,
        )
        data.minecraftSidecar.materializationAttempts.remove(campaign.id.toString())
        if (success) {
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} from warband {}", campaign.id, warband.id)
        }
    }

    private fun acknowledgeMaterialization(
        level: ServerLevel,
        data: PillagerWorldData,
        campaign: PillagerCampaign,
        success: Boolean,
        physicalMemberIds: Set<String> = emptySet(),
        attemptedMemberIds: Set<String> = emptySet(),
        effectId: String? = null,
    ) {
        val snapshot = data.snapshot()
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                0L,
            materializations = listOf(com.gerald.warband.core.MaterializationResult(
                campaign.id.toString(), success, physicalMemberIds,
                effectId ?: snapshot.pendingEffects.values.firstOrNull {
                    it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE && it.campaignId == campaign.id.toString()
                }?.effectId ?: return,
                attemptedMemberIds,
            )),
            ),
            level.server,
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
        data.markChanged()
    }

    fun resolveCampaign(
        data: PillagerWorldData,
        campaignId: UUID,
        defeatedByPlayer: Boolean = true,
        outcome: CampaignOutcome = if (defeatedByPlayer) CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT else CampaignOutcome.ABORTED,
    ) {
        val snapshot = data.snapshot()
        val campaign = snapshot.campaigns[campaignId.toString()] ?: return
        if (campaign.phase == com.gerald.warband.core.CampaignPhase.RESOLVED) return
        val coreOutcome = when (outcome) {
            CampaignOutcome.CAPTAIN_VICTORY -> com.gerald.warband.core.CampaignOutcomeKind.CAPTAIN_VICTORY
            CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT -> com.gerald.warband.core.CampaignOutcomeKind.SURVIVING_DEFEAT
            CampaignOutcome.CAPTAIN_KILLED -> com.gerald.warband.core.CampaignOutcomeKind.CAPTAIN_KILLED
            CampaignOutcome.WARBAND_COLLAPSE -> com.gerald.warband.core.CampaignOutcomeKind.WARBAND_COLLAPSE
            CampaignOutcome.ABORTED -> com.gerald.warband.core.CampaignOutcomeKind.ABORTED
        }
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                outcomes = listOf(com.gerald.warband.core.CampaignOutcomeObservation(campaign.id, coreOutcome, outcome.name.lowercase())),
            ),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    fun abortCampaignAfterPlayerKill(data: PillagerWorldData, campaignId: UUID) {
        val snapshot = data.snapshot()
        val campaign = snapshot.campaigns[campaignId.toString()] ?: return
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                outcomes = listOf(com.gerald.warband.core.CampaignOutcomeObservation(
                    campaign.id, com.gerald.warband.core.CampaignOutcomeKind.CAPTAIN_VICTORY, "captain_victory",
                )),
            ),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    fun collapseFaction(data: PillagerWorldData, factionId: UUID) {
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                commands = listOf(com.gerald.warband.core.CoreCommand.CollapseFaction(factionId.toString(), "warlord_defeated")),
            ),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    fun collapseWarband(data: PillagerWorldData, warbandId: UUID) {
        if (warbandId.toString() !in data.snapshot().warbands) return
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                commands = listOf(com.gerald.warband.core.CoreCommand.CollapseWarband(warbandId.toString(), "warlord_defeated")),
            ),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
        data.warbands[warbandId]?.let { warband ->
            warband.warlordEntityId = null
            warband.rallyPresence?.state = com.bettercontent.pillagercampaigns.data.RallyPresenceState.LOST
            warband.rallyPresence?.entityId = null
            data.factions[warband.factionId]?.bossEntityId = null
        }
    }

    internal fun shouldApplyRallyDrift(isChunkLoaded: ((Int, Int) -> Boolean)?, currentChunkX: Int, currentChunkZ: Int, targetChunkX: Int, targetChunkZ: Int): Boolean {
        if (isChunkLoaded == null) return false
        val minX = minOf(currentChunkX, targetChunkX)
        val maxX = maxOf(currentChunkX, targetChunkX)
        val minZ = minOf(currentChunkZ, targetChunkZ)
        val maxZ = maxOf(currentChunkZ, targetChunkZ)
        for (chunkX in minX..maxX) {
            for (chunkZ in minZ..maxZ) {
                if (isChunkLoaded(chunkX, chunkZ)) return false
            }
        }
        return false
    }

    internal fun isCampaignTarget(player: ServerPlayer): Boolean = isCampaignTargetGameMode(player.gameMode.gameModeForPlayer)

    internal fun isCampaignTargetGameMode(gameType: GameType): Boolean = gameType == GameType.SURVIVAL

    internal fun pauseCampaignsForPlayer(data: PillagerWorldData, playerId: UUID) {
        val snapshot = data.snapshot()
        val campaign = snapshot.campaigns.values.firstOrNull {
            it.targetPlayerId == playerId.toString() &&
                it.phase != com.gerald.warband.core.CampaignPhase.RESOLVED &&
                it.phase != com.gerald.warband.core.CampaignPhase.RETURNING
        } ?: return
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                players = listOf(PlayerFact(
                    playerId.toString(), campaign.target,
                    eligible = false, gameModeEligible = false, physicallyAvailable = false,
                )),
            ),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    internal fun appendCaptainEvent(captain: PillagerOfficer, event: NemesisEvent) {
        captain.nemesisHistory += event
        while (captain.nemesisHistory.size > MAX_HISTORY_EVENTS) {
            captain.nemesisHistory.removeAt(0)
        }
    }


    private fun updateTerritorialRelations(server: MinecraftServer, data: PillagerWorldData) {
        val snapshot = data.snapshot()
        val playersById = server.playerList.players.associateBy { it.uuid.toString() }
        val contacts = snapshot.warbands.values.filterNot { it.defeated }.flatMap { warband ->
            server.playerList.players.asSequence()
                .filter { it.level().dimension().location().toString() == warband.rally.dimension }
                .map { player ->
                    val dx = (player.chunkPosition().x - warband.rally.x).toDouble()
                    val dz = (player.chunkPosition().z - warband.rally.z).toDouble()
                    com.gerald.warband.core.TerritoryContactObservation(
                        warband.id,
                        player.uuid.toString(),
                        kotlin.math.sqrt(dx * dx + dz * dz),
                    )
                }.toList()
        }
        if (contacts.isEmpty()) return
        val result = WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                territoryContacts = contacts,
            ),
            server,
        )
        val warnings = result.effects.filter { it.kind == com.gerald.warband.core.EffectKind.WARN_PLAYER }
        warnings.forEach { effect ->
            val player = effect.playerId?.let(playersById::get) ?: return@forEach
            if (effect.memberIds.singleOrNull() == "hostile") {
                player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.25f, 0.75f)
            } else {
                player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.0f, 0.9f)
            }
        }
        if (warnings.isNotEmpty()) {
            WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    elapsedTicks = 0L,
                    acknowledgements = warnings.map { com.gerald.warband.core.EffectAcknowledgement(it.effectId) },
                ),
                server,
            )
        }
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }
}
