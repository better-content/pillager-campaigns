package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.CampaignOutcome
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.NemesisEvent
import com.gerald.pillagercampaigns.data.NemesisEventType
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
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
    internal enum class MaterializationLeaseAction { WAIT, SUCCEEDED, FAILED }
    const val INITIAL_RESERVE: Int = FormulaicWarbandRules.INITIAL_RESERVE
    val PLAYER_RESPAWN_PROTECTION_TICKS: Long get() = PillagerCampaignsConfig.respawnProtectionTicks.get().toLong()
    private const val MATERIALIZE_LEASE_TICKS: Long = 200L
    private const val MIN_ACTIVE_LIVE_MEMBERS: Int = 1
    private const val MAX_HISTORY_EVENTS: Int = 8
    private var dispatchCursor: Int = 0

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        updateTerritorialRelations(server, data)
        advance(server, data, now)
        pruneResolved(data, now)
        dispatch(server, data, now)
    }

    fun discoveryTick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val before = data.warbands.size
        PillagerDiscoveryCoordinator.tick(server, data, now)
        val added = data.warbands.size - before
        if (added > 0) {
            PillagerCampaignsMod.LOGGER.debug("Discovered {} pillager warband(s)", added)
        }
    }

    private fun dispatch(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val maxRange = PillagerCampaignsConfig.territoryRadiusChunks.get()
        val players = server.playerList.players
        val warbands = data.warbands.values.toList()
        if (warbands.isEmpty()) return
        val catalog = WarbandCoreAdapter.liveCatalog(server, data)
        val activeCampaignsByWarband = data.coreState.campaigns.values
            .asSequence()
            .filter { it.phase != com.gerald.warband.core.CampaignPhase.RESOLVED }
            .groupingBy { it.warbandId }
            .eachCount()
        val targetedPlayers = data.coreState.campaigns.values
            .asSequence()
            .filter { it.phase != com.gerald.warband.core.CampaignPhase.RESOLVED }
            .mapNotNull { runCatching { UUID.fromString(it.targetPlayerId) }.getOrNull() }
            .toSet()
        val budget = PillagerCampaignsConfig.workBudgetPerTick.get().coerceAtLeast(1)
        var inspected = 0
        while (inspected < budget && inspected < warbands.size) {
            val warband = warbands[Math.floorMod(dispatchCursor, warbands.size)]
            dispatchCursor++
            inspected++
            if (warband.defeated || warband.raidPool < 1.0) continue
            if (now < warband.nextRaidTick || now < warband.cooldownUntilTick) continue
            val existing = activeCampaignsByWarband[warband.id.toString()] ?: 0
            if (existing >= warband.activeCampaignLimit) continue
            val level = server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: continue
            val assignment = chooseAssignment(level, players, warband, data, now, maxRange, targetedPlayers) ?: continue
            val captain = assignment.first
            val target = assignment.second
            val campaignId = UUID.randomUUID()
            val result = WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    elapsedTicks = 0L,
                    terrain = EnvironmentSampler.corridor(
                        level, warband.rallyChunkX, warband.rallyChunkZ,
                        target.chunkPosition().x, target.chunkPosition().z,
                    ),
                    commands = listOf(com.gerald.warband.core.CoreCommand.Dispatch(
                        warband.id.toString(),
                        target.uuid.toString(),
                        captain.id.toString(),
                        campaignId.toString(),
                        com.gerald.warband.core.ChunkPosition(
                            level.dimension().location().toString(), target.chunkPosition().x, target.chunkPosition().z,
                        ),
                    )),
                    advanceEconomy = false,
                    allowAutomaticDispatch = false,
                ),
                catalog,
            )
            if (result.events.any { it.type == "dispatched" && it.subjectId == campaignId.toString() }) {
                WarbandCoreAdapter.synchronizeNativeViews(data)
            }
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val materializeDistance = PillagerCampaignsConfig.materializeDistanceChunks.get()
        val players = mutableListOf<PlayerFact>()
        val terrain = mutableListOf<com.gerald.warband.core.TerrainObservation>()
        val combat = mutableListOf<com.gerald.warband.core.CombatObservation>()
        val snapshots = mutableListOf<com.gerald.warband.core.CampaignSnapshotResult>()
        val outcomes = mutableListOf<CampaignOutcomeObservation>()
        val positions = mutableListOf<com.gerald.warband.core.PositionObservation>()
        val priorStates = linkedMapOf<UUID, CampaignState>()
        val validPlayers = linkedMapOf<UUID, ServerPlayer>()
        val levels = linkedMapOf<UUID, ServerLevel>()
        val queuedOutcomeIds = linkedSetOf<UUID>()

        data.campaigns.values.toList().forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED) return@forEach
            val warband = data.warbands[campaign.originWarbandId] ?: return@forEach
            val officer = data.officers[campaign.officerId]
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId }
            val level = player?.serverLevel() ?: server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: return@forEach
            levels[campaign.id] = level
            priorStates[campaign.id] = campaign.state
            if (PillagerRuntime.releaseLoadedCaches(level, campaign) > 0) data.markChanged()
            if (campaign.state == CampaignState.MATERIALIZING) {
                val alive = PillagerRuntime.countLiveMembers(level, campaign.squadMemberIds)
                when (materializationLeaseAction(alive, now, campaign.materializingUntilTick)) {
                    MaterializationLeaseAction.WAIT -> Unit
                    MaterializationLeaseAction.SUCCEEDED ->
                        acknowledgeMaterialization(
                            level, data, campaign, true,
                            PillagerRuntime.liveManifestIds(level, campaign),
                        )
                    MaterializationLeaseAction.FAILED -> {
                        acknowledgeMaterialization(level, data, campaign, false)
                        campaign.squadMemberIds.clear()
                        officer?.let { appendCaptainEvent(it, NemesisEvent(now, NemesisEventType.FAILED_MATERIALIZATION, campaignId = campaign.id, warbandId = campaign.originWarbandId)) }
                    }
                }
                data.markChanged()
            }
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && player != null) {
                tryMaterialize(level, campaign, player, materializeDistance, data, now)
            }

            val physical = PillagerRuntime.hasLiveCampaignMember(level, campaign.id)
            if (campaign.state == CampaignState.RETURNING && physical) {
                when (PillagerRuntime.withdrawTowardHome(level, campaign, warband)) {
                    PillagerRuntime.WithdrawalProgress.PHYSICAL -> data.markChanged()
                    PillagerRuntime.WithdrawalProgress.DEMATERIALIZED,
                    PillagerRuntime.WithdrawalProgress.ARRIVED -> {
                        snapshots += WarbandCoreAdapter.snapshotResult(
                            campaign,
                            data.coreState.pendingEffects.values.firstOrNull {
                                it.kind == com.gerald.warband.core.EffectKind.CAPTURE_SNAPSHOTS && it.campaignId == campaign.id.toString()
                            }?.effectId,
                        )
                    }
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
            val queuedOutcome = campaign.pendingCoreOutcome?.let { outcome ->
                CampaignOutcomeObservation(
                    campaign.id.toString(),
                    when (outcome) {
                        CampaignOutcome.CAPTAIN_VICTORY -> CampaignOutcomeKind.CAPTAIN_VICTORY
                        CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT -> CampaignOutcomeKind.SURVIVING_DEFEAT
                        CampaignOutcome.CAPTAIN_KILLED -> CampaignOutcomeKind.CAPTAIN_KILLED
                        CampaignOutcome.WARBAND_COLLAPSE -> CampaignOutcomeKind.WARBAND_COLLAPSE
                        CampaignOutcome.ABORTED -> CampaignOutcomeKind.ABORTED
                    },
                    campaign.pendingCoreOutcomeReason ?: outcome.name.lowercase(),
                )
            }
            if (queuedOutcome != null) {
                outcomes += queuedOutcome
                queuedOutcomeIds += campaign.id
            } else when {
                player == null && campaign.state != CampaignState.RETURNING -> outcomes += CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_unavailable")
                player != null && !isCampaignTarget(player) && campaign.state != CampaignState.RETURNING -> outcomes += CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_ineligible")
                protected && campaign.state != CampaignState.RETURNING -> outcomes += CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_protected")
            }
            validPlayer?.let {
                players += PlayerFact(
                    it.uuid.toString(),
                    com.gerald.warband.core.ChunkPosition(it.level().dimension().location().toString(), it.chunkPosition().x, it.chunkPosition().z),
                    setOf(warband.id.toString()),
                )
            }
            terrain += EnvironmentSampler.corridor(
                level, warband.rallyChunkX, warband.rallyChunkZ, campaign.targetChunkX, campaign.targetChunkZ,
            )
            if (campaign.pendingCampaignDamage > 0.0 || campaign.pendingPlayerDamage > 0.0 || campaign.pendingCasualtyManifestIds.isNotEmpty()) {
                combat += com.gerald.warband.core.CombatObservation(
                    campaign.id.toString(), campaign.pendingCampaignDamage, campaign.pendingPlayerDamage,
                    campaign.pendingEffectiveRange, 0.6, 0.7, campaign.pendingCasualtyManifestIds.toSet(), applyHealthDamage = false,
                )
            }
        }

        val transition = WarbandCoreAdapter.advanceCanonical(
            server,
            data,
            now,
            com.gerald.warband.core.CoreFrame(
                0L,
                players = players.distinctBy(PlayerFact::id),
                combat = combat,
                snapshots = snapshots,
                outcomes = outcomes,
                physicalPositions = positions,
                terrain = terrain.distinctBy { "${it.position.dimension}:${it.position.x}:${it.position.z}" },
            ),
        )

        priorStates.forEach { (campaignId, priorState) ->
            val campaign = data.campaigns[campaignId] ?: return@forEach
            if (combat.any { it.campaignId == campaign.id.toString() }) {
                campaign.pendingCampaignDamage = 0.0
                campaign.pendingPlayerDamage = 0.0
                campaign.pendingEffectiveRange = 0.0
                campaign.pendingCasualtyManifestIds.clear()
            }
            if (campaign.id in queuedOutcomeIds) {
                campaign.returnOutcome = campaign.pendingCoreOutcome
                campaign.pendingCoreOutcome = null
                campaign.pendingCoreOutcomeReason = null
            }
            if (campaign.id !in queuedOutcomeIds && priorState != CampaignState.RETURNING && campaign.state == CampaignState.RETURNING) {
                campaign.returnStartedTick = now
                campaign.returnOutcome = if (campaign.returnReason == "morale") CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT else CampaignOutcome.ABORTED
            }
            if (campaign.state == CampaignState.RESOLVED) finalizeCanonicalResolution(data, campaign, now)
            val validPlayer = validPlayers[campaign.id]
            val level = levels[campaign.id]
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && validPlayer != null && level != null) {
                tryMaterialize(level, campaign, validPlayer, materializeDistance, data, now)
            }
        }
        if (transition.events.isNotEmpty() || transition.effects.isNotEmpty()) data.markChanged()
    }

    private fun finalizeCanonicalResolution(data: PillagerWorldData, campaign: PillagerCampaign, now: Long) {
        campaign.pendingEquipment.clear()
        campaign.memberEquipment.clear()
        campaign.memberThreat.clear()
        campaign.memberSnapshots.clear()
        campaign.committedThreat = 0.0
        campaign.resolvedTick = now
        data.markChanged()
    }

    private fun tryMaterialize(level: ServerLevel, campaign: PillagerCampaign, player: ServerPlayer, distanceChunks: Int, data: PillagerWorldData, now: Long) {
        if (campaign.state == CampaignState.MATERIALIZING && now < campaign.materializingUntilTick) return
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            acknowledgeMaterialization(
                level, data, campaign, true,
                PillagerRuntime.liveManifestIds(level, campaign),
            )
            return
        }
        campaign.state = CampaignState.MATERIALIZING
        campaign.resumeState = null
        campaign.materializeAttemptId = UUID.randomUUID()
        campaign.materializingUntilTick = now + MATERIALIZE_LEASE_TICKS
        campaign.squadMemberIds.clear()
        data.markChanged()

        val warband = data.warbands[campaign.originWarbandId] ?: return
        val result = PillagerWarbandPresenceSystem.materializeInvasionSquad(level, data, warband, campaign, player, distanceChunks, now)
        val success = result.status == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.SUCCESS ||
            result.status == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.LIVE_ALREADY_PRESENT
        acknowledgeMaterialization(
            level, data, campaign, success, result.physicalMemberIds, result.attemptedMemberIds,
        )
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
    ) {
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                0L,
            materializations = listOf(com.gerald.warband.core.MaterializationResult(
                campaign.id.toString(), success, physicalMemberIds,
                data.coreState.pendingEffects.values.firstOrNull {
                    it.kind == com.gerald.warband.core.EffectKind.MATERIALIZE && it.campaignId == campaign.id.toString()
                }?.effectId,
                attemptedMemberIds,
            )),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.liveCatalog(level.server, data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
        campaign.resumeState = null
        campaign.materializeAttemptId = null
        campaign.materializingUntilTick = 0L
        data.markChanged()
    }

    fun resolveCampaign(
        data: PillagerWorldData,
        campaignId: UUID,
        defeatedByPlayer: Boolean = true,
        observedTick: Long = -1L,
        outcome: CampaignOutcome = if (defeatedByPlayer) CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT else CampaignOutcome.ABORTED,
    ) {
        val campaign = data.coreState.campaigns[campaignId.toString()] ?: return
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
                commands = listOf(com.gerald.warband.core.CoreCommand.ResolveCampaign(campaign.id, outcome.name.lowercase())),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
        data.campaigns[campaignId]?.resolvedTick = observedTick.coerceAtLeast(0L)
    }

    fun abortCampaignAfterPlayerKill(data: PillagerWorldData, campaignId: UUID, observedTick: Long = -1L) {
        val campaign = data.coreState.campaigns[campaignId.toString()] ?: return
        val until = if (observedTick >= 0L) observedTick + PillagerCampaignsConfig.deathProtectionTicks.get() else data.coreState.tick
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                outcomes = listOf(com.gerald.warband.core.CampaignOutcomeObservation(
                    campaign.id, com.gerald.warband.core.CampaignOutcomeKind.CAPTAIN_VICTORY, "captain_victory",
                )),
                commands = listOf(com.gerald.warband.core.CoreCommand.DelayWarband(campaign.warbandId, until)),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    fun queueCampaignOutcome(campaign: PillagerCampaign, outcome: CampaignOutcome, reason: String) {
        if (campaign.state == CampaignState.RESOLVED) return
        campaign.pendingCoreOutcome = outcome
        campaign.pendingCoreOutcomeReason = reason
    }

    fun collapseFaction(data: PillagerWorldData, factionId: UUID) {
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                commands = listOf(com.gerald.warband.core.CoreCommand.CollapseFaction(factionId.toString(), "warlord_defeated")),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    fun collapseWarband(data: PillagerWorldData, warbandId: UUID) {
        if (warbandId.toString() !in data.coreState.warbands) return
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                commands = listOf(com.gerald.warband.core.CoreCommand.CollapseWarband(warbandId.toString(), "warlord_defeated")),
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
        data.warbands[warbandId]?.let { warband ->
            warband.warlordEntityId = null
            warband.rallyPresence?.state = com.gerald.pillagercampaigns.data.RallyPresenceState.LOST
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

    internal fun materializationLeaseAction(alive: Int, now: Long, leaseUntil: Long): MaterializationLeaseAction = when {
        alive >= MIN_ACTIVE_LIVE_MEMBERS -> MaterializationLeaseAction.SUCCEEDED
        now >= leaseUntil -> MaterializationLeaseAction.FAILED
        else -> MaterializationLeaseAction.WAIT
    }

    private fun chooseAssignment(
        level: ServerLevel,
        players: List<ServerPlayer>,
        warband: PillagerWarband,
        data: PillagerWorldData,
        now: Long,
        maxRange: Int,
        targetedPlayers: Set<UUID>,
    ): Pair<PillagerOfficer, ServerPlayer>? {
        val eligiblePlayers = players
            .asSequence()
            .filter { it.level() == level }
            .filter { isCampaignTarget(it) }
            .filter { !data.isPlayerProtected(it.uuid, now) }
            .filter { it.uuid !in targetedPlayers }
            .filter { warband.playerRelations[it.uuid] == TerritorialRelation.HOSTILE.name }
            .filter { CampaignMath.manhattan(warband.rallyChunkX, warband.rallyChunkZ, it.chunkPosition().x, it.chunkPosition().z) <= maxRange }
            .toList()
        if (eligiblePlayers.isEmpty()) return null
        val captains = availableCaptains(data, warband, now)
        if (captains.isEmpty()) return null
        val coreWarband = WarbandCoreAdapter.coreWarband(warband)
        val coreOfficers = captains.map { captain ->
            com.gerald.warband.core.OfficerState(
                captain.id.toString(), captain.factionId.toString(), captain.homeWarbandId.toString(),
                captain.preferenceGraph.toMutableMap(), captain.rank.ordinal + 1,
                captain.campaignVictories, captain.campaignDefeats, captain.injuryOrRecoveryUntilTick,
                captain.lastTargetPlayerId?.toString(),
            )
        }
        val corePlayers = eligiblePlayers.map { player ->
            PlayerFact(
                player.uuid.toString(),
                com.gerald.warband.core.ChunkPosition(level.dimension().location().toString(), player.chunkPosition().x, player.chunkPosition().z),
                setOf(coreWarband.id),
            )
        }
        val state = com.gerald.warband.core.CoreSnapshot(
            tick = now, warbands = linkedMapOf(coreWarband.id to coreWarband),
            officers = coreOfficers.associateTo(linkedMapOf()) { it.id to it },
        )
        val assignment = com.gerald.warband.core.WarbandCore.chooseAssignment(state, coreWarband, corePlayers, coreOfficers) ?: return null
        return captains.first { it.id.toString() == assignment.officerId } to
            eligiblePlayers.first { it.uuid.toString() == assignment.playerId }
    }

    internal fun availableCaptains(data: PillagerWorldData, warband: PillagerWarband, now: Long): List<PillagerOfficer> {
        return data.officers.values
            .asSequence()
            .filter { it.homeWarbandId == warband.id }
            .filter { it.state != OfficerState.DEAD }
            .onEach { captain ->
                if (captain.state == OfficerState.RECOVERING && captain.injuryOrRecoveryUntilTick <= now) {
                    captain.state = OfficerState.IDLE
                }
            }
            .filter { it.state == OfficerState.IDLE }
            .toList()
    }

    internal fun campaignDifficultyForCaptain(baseDifficulty: Int, captain: PillagerOfficer): Int {
        return baseDifficulty + captain.rank.ordinal + captain.promotionTier + (captain.campaignVictories / 2)
    }

    internal fun isCampaignTarget(player: ServerPlayer): Boolean = isCampaignTargetGameMode(player.gameMode.gameModeForPlayer)

    internal fun isCampaignTargetGameMode(gameType: GameType): Boolean = gameType == GameType.SURVIVAL

    internal fun pauseCampaignsForPlayer(data: PillagerWorldData, playerId: UUID) {
        val outcomes = data.coreState.campaigns.values
            .filter {
                it.targetPlayerId == playerId.toString() &&
                    it.phase != com.gerald.warband.core.CampaignPhase.RESOLVED &&
                    it.phase != com.gerald.warband.core.CampaignPhase.RETURNING
            }
            .map {
                com.gerald.warband.core.CampaignOutcomeObservation(
                    it.id, com.gerald.warband.core.CampaignOutcomeKind.ABORTED, "target_unavailable",
                )
            }
        if (outcomes.isEmpty()) return
        WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                outcomes = outcomes,
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
        )
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }

    internal fun appendCaptainEvent(captain: PillagerOfficer, event: NemesisEvent) {
        captain.nemesisHistory += event
        while (captain.nemesisHistory.size > MAX_HISTORY_EVENTS) {
            captain.nemesisHistory.removeAt(0)
        }
    }


    private fun pruneResolved(data: PillagerWorldData, now: Long) {
        val retention = PillagerCampaignsConfig.resolvedRetentionTicks.get().toLong()
        if (data.campaigns.entries.removeIf { (_, campaign) -> campaign.state == CampaignState.RESOLVED && campaign.resolvedTick > 0L && now - campaign.resolvedTick >= retention }) {
            data.markChanged()
        }
    }

    private fun updateTerritorialRelations(server: MinecraftServer, data: PillagerWorldData) {
        val playersById = server.playerList.players.associateBy { it.uuid.toString() }
        val contacts = data.coreState.warbands.values.filterNot { it.defeated }.flatMap { warband ->
            server.playerList.players.asSequence()
                .filter { it.level().dimension().location().toString() == warband.rally.dimension }
                .map { player ->
                    val dx = (player.chunkPosition().x - warband.rally.x).toDouble()
                    val dz = (player.chunkPosition().z - warband.rally.z).toDouble()
                    com.gerald.warband.core.TerritoryContactObservation(
                        warband.id,
                        player.uuid.toString(),
                        kotlin.math.sqrt(dx * dx + dz * dz),
                        PillagerCampaignsConfig.territoryRadiusChunks.get(),
                        PillagerCampaignsConfig.warningBandChunks.get(),
                        protectedUntilTick = data.coreState.protectedPlayersUntilTick[player.uuid.toString()] ?: 0L,
                    )
                }.toList()
        }
        if (contacts.isEmpty()) return
        val result = WarbandCoreAdapter.transition(
            data,
            com.gerald.warband.core.CoreFrame(
                elapsedTicks = 0L,
                territoryContacts = contacts,
                advanceEconomy = false,
                allowAutomaticDispatch = false,
            ),
            WarbandCoreAdapter.snapshotCatalog(data),
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
                    advanceEconomy = false,
                    allowAutomaticDispatch = false,
                ),
                WarbandCoreAdapter.snapshotCatalog(data),
            )
        }
        WarbandCoreAdapter.synchronizeNativeViews(data)
    }
}
