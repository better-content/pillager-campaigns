package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.CampaignOutcome
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.NemesisEvent
import com.gerald.pillagercampaigns.data.NemesisEventType
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWarband
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.util.PillagerIdentity
import com.gerald.pillagercampaigns.engine.CampaignOutcomeKind
import com.gerald.pillagercampaigns.engine.CampaignOutcomeObservation
import com.gerald.pillagercampaigns.engine.PlayerFact
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.GameType
import java.util.UUID
import kotlin.math.max

object PillagerCampaignEngine {
    internal enum class MaterializationLeaseAction { WAIT, SUCCEEDED, FAILED }
    const val INITIAL_RESERVE: Int = FormulaicWarbandRules.INITIAL_RESERVE
    val PLAYER_RESPAWN_PROTECTION_TICKS: Long get() = PillagerCampaignsConfig.respawnProtectionTicks.get().toLong()
    private const val MATERIALIZE_LEASE_TICKS: Long = 200L
    private const val MIN_ACTIVE_LIVE_MEMBERS: Int = 1
    private const val MAX_HISTORY_EVENTS: Int = 8
    private const val PROMOTION_SCORE_THRESHOLD: Int = 2
    private var dispatchCursor: Int = 0

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        advanceEconomies(server, data, now)
        updateTerritorialRelations(server, data)
        pruneResolved(data, now)
        dispatch(server, data, now)
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

    private fun dispatch(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val maxRange = PillagerCampaignsConfig.territoryRadiusChunks.get()
        val players = server.playerList.players
        val warbands = data.warbands.values.toList()
        if (warbands.isEmpty()) return
        val activeCampaignsByWarband = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .groupingBy { it.originWarbandId }
            .eachCount()
        val targetedPlayers = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .map { it.targetPlayerId }
            .toSet()
        val budget = PillagerCampaignsConfig.workBudgetPerTick.get().coerceAtLeast(1)
        var inspected = 0
        while (inspected < budget && inspected < warbands.size) {
            val warband = warbands[Math.floorMod(dispatchCursor, warbands.size)]
            dispatchCursor++
            inspected++
            if (warband.defeated || warband.raidPool < 1.0) continue
            if (now < warband.nextRaidTick || now < warband.cooldownUntilTick) continue
            val existing = activeCampaignsByWarband[warband.id] ?: 0
            if (existing >= warband.activeCampaignLimit) continue
            val level = server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: continue
            val assignment = chooseAssignment(level, players, warband, data, now, maxRange, targetedPlayers) ?: continue
            val captain = assignment.first
            val target = assignment.second
            val loadoutSeed = data.engineSequence
            val recruits = PillagerRuntime.recruitDefinitions(level, warband)
            val budgetThreat = PillagerEngineBridge.raidBudget(warband, captain.preferenceGraph, recruits, loadoutSeed)
            if (budgetThreat <= 0.0) continue
            val manifest = PillagerRuntime.planCampaignManifest(level, warband, captain, target, loadoutSeed, now, recruits) ?: continue
            data.engineSequence = manifest.nextSequence
            val plannedMembers = manifest.members
            if (plannedMembers.isEmpty()) continue
            val committedThreat = plannedMembers.sumOf { it.threat }
            val campaign = PillagerCampaign(
                id = UUID.randomUUID(),
                factionId = warband.factionId,
                originWarbandId = warband.id,
                officerId = captain.id,
                targetPlayerId = target.uuid,
                targetDimension = level.dimension().location(),
                currentChunkX = warband.rallyChunkX,
                currentChunkZ = warband.rallyChunkZ,
                targetChunkX = target.chunkPosition().x,
                targetChunkZ = target.chunkPosition().z,
                difficultySnapshot = campaignDifficultyForCaptain(kotlin.math.ceil(committedThreat).toInt(), captain),
                loadoutSeed = loadoutSeed,
                tickDebt = 0,
                state = CampaignState.TRAVELING,
                resumeState = null,
                materializeAttemptId = null,
                materializingUntilTick = 0L,
                squadMemberIds = mutableListOf(),
                lastCombatTick = now,
                committedThreat = committedThreat,
                plannedMembers = plannedMembers.toMutableList(),
                route = manifest.route.toMutableList(),
            )
            captain.state = OfficerState.DEPLOYED
            captain.lastTargetPlayerId = target.uuid
            captain.lastSeenTick = now
            data.campaigns[campaign.id] = campaign
            data.markChanged()
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val dt = PillagerCampaignsConfig.schedulerIntervalTicks.get()
        val materializeDistance = PillagerCampaignsConfig.materializeDistanceChunks.get()

        data.campaigns.values.forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED) return@forEach
            val warband = data.warbands[campaign.originWarbandId] ?: return@forEach
            val officer = data.officers[campaign.officerId]
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId }
            val level = player?.serverLevel() ?: server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: return@forEach
            if (PillagerRuntime.releaseLoadedCaches(level, campaign) > 0) data.markChanged()
            if (campaign.state == CampaignState.MATERIALIZING) {
                val alive = PillagerRuntime.countLiveMembers(level, campaign.squadMemberIds)
                when (materializationLeaseAction(alive, now, campaign.materializingUntilTick)) {
                    MaterializationLeaseAction.WAIT -> Unit
                    MaterializationLeaseAction.SUCCEEDED ->
                        acknowledgeMaterialization(level, data, warband, officer, campaign, player, now, true)
                    MaterializationLeaseAction.FAILED -> {
                        acknowledgeMaterialization(level, data, warband, officer, campaign, player, now, false)
                        campaign.squadMemberIds.clear()
                        officer?.let { appendCaptainEvent(it, NemesisEvent(now, NemesisEventType.FAILED_MATERIALIZATION, campaignId = campaign.id, warbandId = campaign.originWarbandId)) }
                    }
                }
                data.markChanged()
                return@forEach
            }
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && player != null) {
                tryMaterialize(level, campaign, player, materializeDistance, data, now)
                return@forEach
            }

            var snapshots = emptyList<com.gerald.pillagercampaigns.engine.CampaignSnapshotResult>()
            var physical = PillagerRuntime.hasLiveCampaignMember(level, campaign.id)
            if (campaign.state == CampaignState.RETURNING && physical) {
                when (PillagerRuntime.withdrawTowardHome(level, campaign, warband)) {
                    PillagerRuntime.WithdrawalProgress.PHYSICAL -> { data.markChanged(); return@forEach }
                    PillagerRuntime.WithdrawalProgress.DEMATERIALIZED,
                    PillagerRuntime.WithdrawalProgress.ARRIVED -> {
                        physical = true
                        snapshots = listOf(PillagerEngineBridge.snapshotResult(campaign))
                    }
                }
            } else if (campaign.state == CampaignState.ACTIVE && physical) {
                PillagerRuntime.syncLiveCampaignState(level, campaign)
            }

            val protected = player?.let { data.isPlayerProtected(it.uuid, now) } == true
            val validPlayer = player?.takeIf { isCampaignTarget(it) && !protected }
            val queuedOutcome = campaign.pendingEngineOutcome?.let { outcome ->
                CampaignOutcomeObservation(
                    campaign.id.toString(),
                    when (outcome) {
                        CampaignOutcome.CAPTAIN_VICTORY -> CampaignOutcomeKind.CAPTAIN_VICTORY
                        CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT -> CampaignOutcomeKind.SURVIVING_DEFEAT
                        CampaignOutcome.CAPTAIN_KILLED -> CampaignOutcomeKind.CAPTAIN_KILLED
                        CampaignOutcome.WARBAND_COLLAPSE -> CampaignOutcomeKind.WARBAND_COLLAPSE
                        CampaignOutcome.ABORTED -> CampaignOutcomeKind.ABORTED
                    },
                    campaign.pendingEngineOutcomeReason ?: outcome.name.lowercase(),
                )
            }
            val outcomes = queuedOutcome?.let(::listOf) ?: when {
                player == null && campaign.state != CampaignState.RETURNING -> listOf(CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_unavailable"))
                player != null && !isCampaignTarget(player) && campaign.state != CampaignState.RETURNING -> listOf(CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_ineligible"))
                protected && campaign.state != CampaignState.RETURNING -> listOf(CampaignOutcomeObservation(campaign.id.toString(), CampaignOutcomeKind.ABORTED, "target_protected"))
                else -> emptyList()
            }
            val facts = validPlayer?.let {
                listOf(PlayerFact(
                    it.uuid.toString(),
                    com.gerald.pillagercampaigns.engine.ChunkPosition(it.level().dimension().location().toString(), it.chunkPosition().x, it.chunkPosition().z),
                    setOf(warband.id.toString()),
                ))
            }.orEmpty()
            val priorState = campaign.state
            val combat = if (campaign.pendingCampaignDamage > 0.0 || campaign.pendingPlayerDamage > 0.0 || campaign.pendingCasualtyManifestIds.isNotEmpty()) {
                listOf(com.gerald.pillagercampaigns.engine.CombatObservation(
                    campaign.id.toString(), campaign.pendingCampaignDamage, campaign.pendingPlayerDamage,
                    campaign.pendingEffectiveRange, 0.6, 0.7, campaign.pendingCasualtyManifestIds.toSet(), applyHealthDamage = false,
                ))
            } else emptyList()
            val transition = PillagerEngineBridge.transitionCampaign(
                warband, officer, campaign, PillagerRuntime.recruitDefinitions(level, warband), now, dt.toLong(), physical,
                players = facts,
                terrain = EnvironmentSampler.corridor(level, warband.rallyChunkX, warband.rallyChunkZ, campaign.targetChunkX, campaign.targetChunkZ),
                combat = combat,
                snapshots = snapshots,
                outcomes = outcomes,
                sequence = data.engineSequence,
            )
            data.engineSequence = transition.result.state.sequence
            if (combat.isNotEmpty()) {
                campaign.pendingCampaignDamage = 0.0
                campaign.pendingPlayerDamage = 0.0
                campaign.pendingEffectiveRange = 0.0
                campaign.pendingCasualtyManifestIds.clear()
            }
            if (queuedOutcome != null) {
                campaign.returnOutcome = campaign.pendingEngineOutcome
                campaign.pendingEngineOutcome = null
                campaign.pendingEngineOutcomeReason = null
            }
            if (queuedOutcome == null && priorState != CampaignState.RETURNING && campaign.state == CampaignState.RETURNING) {
                campaign.returnStartedTick = now
                campaign.returnOutcome = if (campaign.returnReason == "morale") CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT else CampaignOutcome.ABORTED
            }
            if (campaign.state == CampaignState.RESOLVED) finalizeCanonicalResolution(data, campaign, now)
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE && validPlayer != null) {
                tryMaterialize(level, campaign, validPlayer, materializeDistance, data, now)
            }
            if (transition.result.events.isNotEmpty() || transition.effects.isNotEmpty()) data.markChanged()
        }
    }

    private fun finalizeCanonicalResolution(data: PillagerWorldData, campaign: PillagerCampaign, now: Long) {
        val outcome = campaign.returnOutcome ?: when (campaign.returnReason) {
            "morale", "supply_attrition" -> CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT
            else -> CampaignOutcome.ABORTED
        }
        data.officers[campaign.officerId]?.let { captain ->
            applyCampaignOutcome(data.warbands[campaign.originWarbandId], campaign, captain, outcome, now)
        }
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
            val warband = data.warbands[campaign.originWarbandId] ?: return
            acknowledgeMaterialization(level, data, warband, data.officers[campaign.officerId], campaign, player, now, true)
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
        val success = result == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.SUCCESS ||
            result == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.LIVE_ALREADY_PRESENT
        acknowledgeMaterialization(level, data, warband, data.officers[campaign.officerId], campaign, player, now, success)
        if (success) {
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} from warband {}", campaign.id, warband.id)
        }
    }

    private fun acknowledgeMaterialization(
        level: ServerLevel,
        data: PillagerWorldData,
        warband: PillagerWarband,
        officer: PillagerOfficer?,
        campaign: PillagerCampaign,
        player: ServerPlayer?,
        now: Long,
        success: Boolean,
    ) {
        val facts = player?.let {
            listOf(PlayerFact(
                it.uuid.toString(),
                com.gerald.pillagercampaigns.engine.ChunkPosition(it.level().dimension().location().toString(), it.chunkPosition().x, it.chunkPosition().z),
                setOf(warband.id.toString()),
            ))
        }.orEmpty()
        val transition = PillagerEngineBridge.transitionCampaign(
            warband, officer, campaign, PillagerRuntime.recruitDefinitions(level, warband), now, 0L, false,
            players = facts,
            materializations = listOf(com.gerald.pillagercampaigns.engine.MaterializationResult(campaign.id.toString(), success)),
            sequence = data.engineSequence,
        )
        data.engineSequence = transition.result.state.sequence
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
        val campaign = data.campaigns[campaignId] ?: return
        if (campaign.state == CampaignState.RESOLVED) return
        campaign.state = CampaignState.RESOLVED
        campaign.resumeState = null
        campaign.materializeAttemptId = null
        campaign.materializingUntilTick = 0L
        campaign.squadMemberIds.clear()
        campaign.resolvedTick = observedTick.coerceAtLeast(0L)

        val captain = data.officers[campaign.officerId]
        val warband = data.warbands[campaign.originWarbandId]
        if (captain != null) applyCampaignOutcome(warband, campaign, captain, outcome, observedTick)
        data.markChanged()
    }

    fun abortCampaignAfterPlayerKill(data: PillagerWorldData, campaignId: UUID, observedTick: Long = -1L) {
        val campaign = data.campaigns[campaignId] ?: return
        data.warbands[campaign.originWarbandId]?.let { warband ->
            if (observedTick >= 0L) {
                warband.lastIntelTick = observedTick
                warband.cooldownUntilTick = maxOf(warband.cooldownUntilTick, observedTick + PillagerCampaignsConfig.deathProtectionTicks.get())
                warband.nextRaidTick = maxOf(warband.nextRaidTick, warband.cooldownUntilTick)
            }
        }
        queueCampaignOutcome(campaign, CampaignOutcome.CAPTAIN_VICTORY, "captain_victory")
        data.markChanged()
    }

    fun queueCampaignOutcome(campaign: PillagerCampaign, outcome: CampaignOutcome, reason: String) {
        if (campaign.state == CampaignState.RESOLVED) return
        campaign.pendingEngineOutcome = outcome
        campaign.pendingEngineOutcomeReason = reason
    }

    fun collapseFaction(data: PillagerWorldData, factionId: UUID) {
        val campaignIds = data.campaigns.values.filter { it.factionId == factionId }.map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id, defeatedByPlayer = false, outcome = CampaignOutcome.WARBAND_COLLAPSE) }
        data.campaigns.entries.removeIf { (_, campaign) -> campaign.factionId == factionId }
        data.officers.entries.removeIf { (_, officer) -> officer.factionId == factionId }
        data.warbands.entries.removeIf { (_, warband) -> warband.factionId == factionId }
        data.factions.remove(factionId)
        data.markChanged()
    }

    fun collapseWarband(data: PillagerWorldData, warbandId: UUID, observedTick: Long = -1L) {
        val warband = data.warbands[warbandId] ?: return
        warband.defeated = true
        warband.reserve = 0
        warband.raidPool = 0.0
        warband.warlordEntityId = null
        warband.rallyPresence?.state = com.gerald.pillagercampaigns.data.RallyPresenceState.LOST
        warband.rallyPresence?.entityId = null
        data.factions[warband.factionId]?.bossEntityId = null
        val campaignIds = data.campaigns.values
            .filter { it.originWarbandId == warbandId && it.state != CampaignState.RESOLVED }
            .map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id, defeatedByPlayer = false, observedTick = observedTick, outcome = CampaignOutcome.WARBAND_COLLAPSE) }
        data.officers.values
            .filter { it.homeWarbandId == warbandId }
            .forEach { officer ->
                officer.state = OfficerState.DEAD
                appendCaptainEvent(
                    officer,
                    NemesisEvent(observedTick.coerceAtLeast(0L), NemesisEventType.WARBAND_COLLAPSED, warbandId = warbandId),
                )
            }
        data.markChanged()
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
        val captains = availableCaptains(data, warband, now).ifEmpty {
            listOf(obtainOfficer(data, warband.factionId, warband.id, now))
        }
        val coreWarband = PillagerEngineBridge.coreWarband(warband)
        val coreOfficers = captains.map { captain ->
            com.gerald.pillagercampaigns.engine.OfficerState(
                captain.id.toString(), captain.factionId.toString(), captain.homeWarbandId.toString(),
                captain.preferenceGraph.toMutableMap(), captain.rank.ordinal + 1,
                captain.campaignVictories, captain.campaignDefeats, captain.injuryOrRecoveryUntilTick,
                captain.lastTargetPlayerId?.toString(),
            )
        }
        val corePlayers = eligiblePlayers.map { player ->
            PlayerFact(
                player.uuid.toString(),
                com.gerald.pillagercampaigns.engine.ChunkPosition(level.dimension().location().toString(), player.chunkPosition().x, player.chunkPosition().z),
                setOf(coreWarband.id),
            )
        }
        val state = com.gerald.pillagercampaigns.engine.EngineState(
            tick = now, warbands = linkedMapOf(coreWarband.id to coreWarband),
            officers = coreOfficers.associateTo(linkedMapOf()) { it.id to it },
        )
        val assignment = com.gerald.pillagercampaigns.engine.WarbandEngine.chooseAssignment(state, coreWarband, corePlayers, coreOfficers) ?: return null
        return captains.first { it.id.toString() == assignment.officerId } to
            eligiblePlayers.first { it.uuid.toString() == assignment.playerId }
    }

    internal fun availableCaptains(data: PillagerWorldData, warband: PillagerWarband, now: Long): List<PillagerOfficer> {
        return data.officers.values
            .asSequence()
            .filter { it.homeWarbandId == warband.id }
            .filter { it.role == OfficerRole.CAPTAIN }
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
        var changed = false
        data.campaigns.values
            .asSequence()
            .filter { it.targetPlayerId == playerId && it.state != CampaignState.RESOLVED && it.state != CampaignState.RETURNING }
            .forEach { campaign ->
                queueCampaignOutcome(campaign, CampaignOutcome.ABORTED, "target_unavailable")
                changed = true
            }
        if (changed) data.markChanged()
    }

    private fun obtainOfficer(data: PillagerWorldData, factionId: UUID, homeWarbandId: UUID, now: Long): PillagerOfficer {
        val pooled = data.officers.values.firstOrNull {
            it.factionId == factionId &&
                it.homeWarbandId == homeWarbandId &&
                it.role == OfficerRole.CAPTAIN &&
                it.state == OfficerState.IDLE
        }
        if (pooled != null) return pooled
        val faction = data.factions[factionId] ?: PillagerIdentity.makeFaction(homeWarbandId.leastSignificantBits).also { data.factions[it.id] = it }
        val officer = PillagerIdentity.makeOfficer(
            faction = faction,
            homeWarbandId = homeWarbandId,
            seed = homeWarbandId.leastSignificantBits xor data.officers.size.toLong(),
            role = OfficerRole.CAPTAIN,
            rank = OfficerRank.CAPTAIN,
            preferenceGraph = FormulaicWarbandRules.initialPreferences(homeWarbandId.mostSignificantBits xor homeWarbandId.leastSignificantBits xor data.officers.size.toLong()),
        )
        officer.lastSeenTick = now
        data.officers[officer.id] = officer
        return officer
    }

    private fun applyCampaignOutcome(
        warband: PillagerWarband?,
        campaign: PillagerCampaign,
        captain: PillagerOfficer,
        outcome: CampaignOutcome,
        observedTick: Long,
    ) {
        val tick = observedTick.coerceAtLeast(0L)
        captain.lastSeenTick = tick
        when (outcome) {
            CampaignOutcome.CAPTAIN_VICTORY -> {
                captain.kills += 1
                captain.deathsInflicted += 1
                captain.state = OfficerState.RECOVERING
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.KILLED_PLAYER, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "kill"))
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.LED_SUCCESSFUL_ASSAULT, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "victory"))
            }
            CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT -> {
                captain.state = OfficerState.RECOVERING
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.LOST_CAMPAIGN, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "defeat"))
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.SURVIVED_RETREAT, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "survived"))
            }
            CampaignOutcome.CAPTAIN_KILLED -> {
                captain.state = OfficerState.DEAD
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.WAS_DEFEATED_BY_PLAYER, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "death"))
            }
            CampaignOutcome.WARBAND_COLLAPSE -> {
                captain.state = OfficerState.DEAD
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.WARBAND_COLLAPSED, warbandId = campaign.originWarbandId, campaignId = campaign.id))
            }
            CampaignOutcome.ABORTED -> {
                if (captain.state != OfficerState.DEAD) {
                    captain.state = OfficerState.IDLE
                }
            }
        }
        refreshCaptainStanding(captain, tick)
        if (warband != null) {
            warband.lastIntelTick = tick
        }
    }

    private fun refreshCaptainStanding(captain: PillagerOfficer, observedTick: Long) {
        if (captain.role != OfficerRole.CAPTAIN || captain.state == OfficerState.DEAD) return
        val score = captain.campaignVictories - captain.campaignDefeats
        val previousRank = captain.rank
        captain.rank = when {
            score >= PROMOTION_SCORE_THRESHOLD -> OfficerRank.DREAD_CAPTAIN
            score <= -PROMOTION_SCORE_THRESHOLD -> OfficerRank.SCOUT
            else -> OfficerRank.CAPTAIN
        }
        captain.promotionTier = captain.rank.ordinal
        if (captain.rank != previousRank) {
            val eventType = if (captain.rank.ordinal > previousRank.ordinal) NemesisEventType.PROMOTED else NemesisEventType.DEMOTED
            appendCaptainEvent(captain, NemesisEvent(observedTick, eventType, severity = captain.rank.name.lowercase()))
        }
        captain.title = titleForCaptain(captain)
    }

    private fun titleForCaptain(captain: PillagerOfficer): String = when (captain.rank) {
        OfficerRank.SCOUT -> "the Scout"
        OfficerRank.CAPTAIN -> "the Captain"
        OfficerRank.DREAD_CAPTAIN -> "the Dread Captain"
    }

    internal fun appendCaptainEvent(captain: PillagerOfficer, event: NemesisEvent) {
        captain.nemesisHistory += event
        while (captain.nemesisHistory.size > MAX_HISTORY_EVENTS) {
            captain.nemesisHistory.removeAt(0)
        }
    }


    private fun advanceEconomies(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        PillagerEngineBridge.advanceEconomies(server, data, now)
    }

    private fun pruneResolved(data: PillagerWorldData, now: Long) {
        val retention = PillagerCampaignsConfig.resolvedRetentionTicks.get().toLong()
        if (data.campaigns.entries.removeIf { (_, campaign) -> campaign.state == CampaignState.RESOLVED && campaign.resolvedTick > 0L && now - campaign.resolvedTick >= retention }) {
            data.markChanged()
        }
    }

    private fun updateTerritorialRelations(server: MinecraftServer, data: PillagerWorldData) {
        server.playerList.players.forEach { player ->
            data.warbands.values.asSequence().filter { !it.defeated && it.dimension == player.level().dimension().location() }.forEach { warband ->
                val distance = WarbandTerritoryRules.distanceChunks(warband.rallyChunkX, warband.rallyChunkZ, player.chunkPosition().x, player.chunkPosition().z)
                val previous = runCatching { TerritorialRelation.valueOf(warband.playerRelations[player.uuid] ?: TerritorialRelation.UNCONTACTED.name) }.getOrDefault(TerritorialRelation.UNCONTACTED)
                val next = when {
                    previous == TerritorialRelation.HOSTILE -> previous
                    else -> WarbandTerritoryRules.relation(distance, PillagerCampaignsConfig.territoryRadiusChunks.get(), PillagerCampaignsConfig.warningBandChunks.get())
                }
                    if (next != previous) {
                        warband.playerRelations[player.uuid] = next.name
                        if (next == TerritorialRelation.WARNED) {
                            player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.0f, 0.9f)
                        } else if (next == TerritorialRelation.HOSTILE) {
                            player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.25f, 0.75f)
                        }
                    data.markChanged()
                }
            }
        }
    }
}
