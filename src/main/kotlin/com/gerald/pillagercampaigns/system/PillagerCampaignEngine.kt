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
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import net.minecraft.nbt.CompoundTag
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max

object PillagerCampaignEngine {
    const val INITIAL_RESERVE: Int = FormulaicWarbandRules.INITIAL_RESERVE
    val PLAYER_RESPAWN_PROTECTION_TICKS: Long get() = PillagerCampaignsConfig.respawnProtectionTicks.get().toLong()
    private const val MATERIALIZE_LEASE_TICKS: Long = 200L
    private const val MIN_ACTIVE_LIVE_MEMBERS: Int = 1
    private const val RAID_COOLDOWN_TICKS: Long = 6_000L
    private const val CAPTAIN_RECOVERY_TICKS: Long = 6_000L
    private const val CAPTAIN_SUCCESS_RECOVERY_TICKS: Long = 2_400L
    private const val MAX_HISTORY_EVENTS: Int = 8
    private const val CAPTAIN_GRUDGE_WEIGHT: Int = 48
    private const val CAPTAIN_DISTANCE_WEIGHT: Int = 4
    private const val PROMOTION_SCORE_THRESHOLD: Int = 2
    private var dispatchCursor: Int = 0

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        advanceEconomies(data, now)
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
            val committedThreat = minOf(warband.raidPool.toInt(), warband.aggression.coerceAtLeast(1))
            if (committedThreat <= 0) continue
            warband.raidPool -= committedThreat
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
                difficultySnapshot = campaignDifficultyForCaptain(committedThreat, captain),
                loadoutSeed = ThreadLocalRandom.current().nextLong(),
                tickDebt = 0,
                state = CampaignState.TRAVELING,
                resumeState = null,
                materializeAttemptId = null,
                materializingUntilTick = 0L,
                squadMemberIds = mutableListOf(),
                lastCombatTick = now,
                committedThreat = committedThreat,
            )
            repeat(minOf(committedThreat, warband.armory.size)) { campaign.pendingEquipment += warband.armory.removeAt(0) }
            captain.state = OfficerState.DEPLOYED
            captain.lastTargetPlayerId = target.uuid
            captain.lastSeenTick = now
            warband.nextRaidTick = now + RAID_COOLDOWN_TICKS
            data.campaigns[campaign.id] = campaign
            data.markChanged()
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val speed = 120
        val dt = PillagerCampaignsConfig.schedulerIntervalTicks.get()
        val materializeDistance = PillagerCampaignsConfig.materializeDistanceChunks.get()
        val toResolve = mutableListOf<Pair<UUID, CampaignOutcome>>()

        data.campaigns.values.forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED) return@forEach
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId } ?: run {
                toResolve += campaign.id to CampaignOutcome.ABORTED
                return@forEach
            }
            val level = player.serverLevel()
            if (!isCampaignTarget(player)) {
                pauseCampaign(level, campaign, data)
                return@forEach
            }
            if (campaign.state == CampaignState.PAUSED) {
                if (data.isPlayerProtected(player.uuid, now)) return@forEach
                resumeCampaign(campaign, data)
            }
            if (data.isPlayerProtected(player.uuid, now)) {
                PillagerRuntime.dismissCampaign(level, campaign.id, campaign.squadMemberIds)
                toResolve += campaign.id to CampaignOutcome.ABORTED
                return@forEach
            }
            when (campaign.state) {
                CampaignState.ACTIVE -> {
                    val alive = PillagerRuntime.countLiveMembers(level, campaign.squadMemberIds)
                    val liveThreat = PillagerRuntime.liveThreat(level, campaign)
                    val conservation = data.officers[campaign.officerId]?.preferenceGraph?.get("conservation") ?: 0.5
                    val retreatAt = FormulaicWarbandRules.retreatThreshold(conservation, data.warbands[campaign.originWarbandId]?.aggression ?: 6)
                    if (alive < MIN_ACTIVE_LIVE_MEMBERS) {
                        toResolve += campaign.id to CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT
                    } else if (campaign.committedThreat > 0 && liveThreat / campaign.committedThreat <= retreatAt) {
                        PillagerRuntime.dismissCampaign(level, campaign.id, campaign.squadMemberIds)
                        returnCampaign(data, campaign, now, liveThreat)
                    } else if (now - campaign.lastCombatTick >= PillagerCampaignsConfig.idleReturnTicks.get()) {
                        PillagerRuntime.dismissCampaign(level, campaign.id, campaign.squadMemberIds)
                        returnCampaign(data, campaign, now, liveThreat)
                    }
                    return@forEach
                }
                CampaignState.MATERIALIZING -> {
                    val alive = PillagerRuntime.countLiveMembers(level, campaign.squadMemberIds)
                    if (alive >= MIN_ACTIVE_LIVE_MEMBERS) {
                        campaign.state = CampaignState.ACTIVE
                        campaign.materializeAttemptId = null
                        campaign.materializingUntilTick = 0L
                        data.markChanged()
                        return@forEach
                    }
                    if (now >= campaign.materializingUntilTick) {
                        campaign.state = CampaignState.READY_TO_MATERIALIZE
                        campaign.materializeAttemptId = null
                        campaign.materializingUntilTick = 0L
                        campaign.squadMemberIds.clear()
                        val captain = data.officers[campaign.officerId]
                        if (captain != null) {
                            appendCaptainEvent(
                                captain,
                                NemesisEvent(now, NemesisEventType.FAILED_MATERIALIZATION, campaignId = campaign.id, warbandId = campaign.originWarbandId),
                            )
                        }
                        data.markChanged()
                    }
                    return@forEach
                }
                CampaignState.PAUSED, CampaignState.RETURNING -> return@forEach
                else -> {}
            }
            if (player.level().dimension().location() != campaign.targetDimension) {
                campaign.targetDimension = player.level().dimension().location()
            }
            campaign.targetChunkX = player.chunkPosition().x
            campaign.targetChunkZ = player.chunkPosition().z
            campaign.tickDebt += dt
            while (campaign.tickDebt >= speed && campaign.state == CampaignState.TRAVELING) {
                campaign.tickDebt -= speed
                val next = CampaignMath.stepToward(campaign.currentChunkX, campaign.currentChunkZ, campaign.targetChunkX, campaign.targetChunkZ)
                campaign.currentChunkX = next.first
                campaign.currentChunkZ = next.second
                val distance = CampaignMath.manhattan(campaign.currentChunkX, campaign.currentChunkZ, campaign.targetChunkX, campaign.targetChunkZ)
                if (distance <= materializeDistance) {
                    campaign.state = CampaignState.READY_TO_MATERIALIZE
                }
            }
            if (campaign.state == CampaignState.READY_TO_MATERIALIZE) {
                tryMaterialize(level, campaign, player, materializeDistance, data, now)
            }
        }
        if (toResolve.isNotEmpty()) {
            toResolve.forEach { (id, outcome) -> resolveCampaign(data, id, outcome = outcome, observedTick = now) }
            data.markChanged()
        }
    }

    private fun tryMaterialize(level: ServerLevel, campaign: PillagerCampaign, player: ServerPlayer, distanceChunks: Int, data: PillagerWorldData, now: Long) {
        if (campaign.state == CampaignState.MATERIALIZING && now < campaign.materializingUntilTick) return
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            campaign.state = CampaignState.ACTIVE
            data.markChanged()
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
        if (result == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.SUCCESS ||
            result == com.gerald.pillagercampaigns.data.PresenceMaterializationResult.LIVE_ALREADY_PRESENT
        ) {
            campaign.state = CampaignState.ACTIVE
            campaign.resumeState = null
            campaign.materializeAttemptId = null
            campaign.materializingUntilTick = 0L
            data.markChanged()
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} from warband {}", campaign.id, warband.id)
        }
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
        if (captain != null) applyCampaignOutcome(data, warband, campaign, captain, outcome, observedTick)
        data.markChanged()
    }

    fun recordCampaignVictory(data: PillagerWorldData, warbandId: UUID, observedTick: Long = -1L) {
        data.warbands[warbandId]?.let { warband ->
            warband.aggression = (warband.aggression - 1).coerceAtLeast(PillagerCampaignsConfig.minimumAggression.get())
            if (observedTick >= 0L) warband.lastIntelTick = observedTick
        }
    }

    fun recordCampaignLoss(data: PillagerWorldData, warbandId: UUID, observedTick: Long = -1L) {
        data.warbands[warbandId]?.let { warband ->
            warband.aggression = (warband.aggression + 1).coerceAtMost(PillagerCampaignsConfig.maximumAggression.get())
            if (observedTick >= 0L) warband.lastIntelTick = observedTick
        }
    }

    fun recordCombatObservation(data: PillagerWorldData, campaign: PillagerCampaign, preference: String, contribution: Double) {
        data.warbands[campaign.originWarbandId]?.let { warband ->
            val current = warband.preferences[preference] ?: 0.0
            warband.preferences[preference] = FormulaicWarbandRules.updatePreference(current, contribution, PillagerCampaignsConfig.warbandLearningRate.get())
        }
        data.officers[campaign.officerId]?.let { captain ->
            val current = captain.preferenceGraph[preference] ?: 0.0
            captain.preferenceGraph[preference] = FormulaicWarbandRules.updatePreference(current, contribution, PillagerCampaignsConfig.captainLearningRate.get())
        }
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
        resolveCampaign(data, campaign.id, defeatedByPlayer = false, observedTick = observedTick, outcome = CampaignOutcome.CAPTAIN_VICTORY)
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
            listOf(obtainOfficer(data, warband.factionId, warband.id, warband.aggression, now))
        }
        var best: Pair<PillagerOfficer, ServerPlayer>? = null
        var bestScore = Int.MIN_VALUE
        for (player in eligiblePlayers) {
            val distance = CampaignMath.manhattan(warband.rallyChunkX, warband.rallyChunkZ, player.chunkPosition().x, player.chunkPosition().z)
            for (captain in captains) {
                val score = assignmentWeight(captain, player.uuid, distance, data.isPlayerProtected(player.uuid, now))
                if (score > bestScore) {
                    best = captain to player
                    bestScore = score
                }
            }
        }
        return best
    }

    internal fun assignmentWeight(captain: PillagerOfficer, targetPlayerId: UUID, distance: Int, isProtected: Boolean): Int {
        if (captain.role != OfficerRole.CAPTAIN || captain.state == OfficerState.DEAD || isProtected) return Int.MIN_VALUE / 4
        val grudge = if (captain.lastTargetPlayerId == targetPlayerId) CAPTAIN_GRUDGE_WEIGHT else 0
        val rankBias = captain.rank.ordinal * 6
        val recoveryDebt = if (captain.state == OfficerState.RECOVERING) 18 else 0
        return grudge + rankBias + captain.campaignVictories * 5 - captain.campaignDefeats * 3 - distance * CAPTAIN_DISTANCE_WEIGHT - recoveryDebt
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

    internal fun pauseCampaignRecord(campaign: PillagerCampaign) {
        campaign.resumeState = pausedResumeState(campaign.state)
        campaign.state = CampaignState.PAUSED
        campaign.materializeAttemptId = null
        campaign.materializingUntilTick = 0L
        campaign.squadMemberIds.clear()
    }

    internal fun pausedResumeState(state: CampaignState): CampaignState = when (state) {
        CampaignState.ACTIVE, CampaignState.MATERIALIZING -> CampaignState.READY_TO_MATERIALIZE
        CampaignState.PAUSED -> CampaignState.READY_TO_MATERIALIZE
        else -> state
    }

    internal fun isCampaignTarget(player: ServerPlayer): Boolean = isCampaignTargetGameMode(player.gameMode.gameModeForPlayer)

    internal fun isCampaignTargetGameMode(gameType: GameType): Boolean = gameType == GameType.SURVIVAL

    internal fun pauseCampaignsForPlayer(server: MinecraftServer, data: PillagerWorldData, playerId: UUID) {
        var changed = false
        data.campaigns.values
            .asSequence()
            .filter { it.targetPlayerId == playerId && it.state != CampaignState.RESOLVED && it.state != CampaignState.PAUSED }
            .forEach { campaign ->
                server.allLevels.firstOrNull { it.dimension().location() == campaign.targetDimension }?.let { level ->
                    PillagerRuntime.dismissCampaign(level, campaign.id, campaign.squadMemberIds)
                }
                pauseCampaignRecord(campaign)
                changed = true
            }
        if (changed) data.markChanged()
    }

    private fun pauseCampaign(level: ServerLevel, campaign: PillagerCampaign, data: PillagerWorldData) {
        if (campaign.state == CampaignState.PAUSED || campaign.state == CampaignState.RESOLVED) return
        PillagerRuntime.dismissCampaign(level, campaign.id, campaign.squadMemberIds)
        pauseCampaignRecord(campaign)
        data.markChanged()
    }

    private fun resumeCampaign(campaign: PillagerCampaign, data: PillagerWorldData) {
        campaign.state = campaign.resumeState ?: CampaignState.TRAVELING
        campaign.resumeState = null
        data.markChanged()
    }

    private fun obtainOfficer(data: PillagerWorldData, factionId: UUID, homeWarbandId: UUID, baseDifficulty: Int, now: Long): PillagerOfficer {
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
        data: PillagerWorldData,
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
                captain.campaignVictories += 1
                captain.kills += 1
                captain.deathsInflicted += 1
                captain.state = OfficerState.RECOVERING
                captain.injuryOrRecoveryUntilTick = tick + CAPTAIN_SUCCESS_RECOVERY_TICKS
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.KILLED_PLAYER, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "kill"))
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.LED_SUCCESSFUL_ASSAULT, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "victory"))
                recordCampaignVictory(data, campaign.originWarbandId, tick)
            }
            CampaignOutcome.CAPTAIN_SURVIVED_DEFEAT -> {
                captain.campaignDefeats += 1
                captain.state = OfficerState.RECOVERING
                captain.injuryOrRecoveryUntilTick = tick + CAPTAIN_RECOVERY_TICKS
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.LOST_CAMPAIGN, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "defeat"))
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.SURVIVED_RETREAT, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "survived"))
                recordCampaignLoss(data, campaign.originWarbandId, tick)
            }
            CampaignOutcome.CAPTAIN_KILLED -> {
                captain.campaignDefeats += 1
                captain.state = OfficerState.DEAD
                appendCaptainEvent(captain, NemesisEvent(tick, NemesisEventType.WAS_DEFEATED_BY_PLAYER, playerId = campaign.targetPlayerId, warbandId = campaign.originWarbandId, campaignId = campaign.id, severity = "death"))
                recordCampaignLoss(data, campaign.originWarbandId, tick)
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


    private fun advanceEconomies(data: PillagerWorldData, now: Long) {
        data.warbands.values.asSequence().filter { !it.defeated }.forEach { warband ->
            val elapsed = (now - warband.lastEconomyTick).coerceAtLeast(0L)
            if (elapsed == 0L) return@forEach
            warband.lastEconomyTick = now
            warband.recruitTickDebt += elapsed
            val recruitTicks = FormulaicWarbandRules.grossRecruitTicksPerStrength(warband.environment) * 20.0
            while (warband.recruitTickDebt >= recruitTicks && warband.reserve + warband.raidPool < warband.capacity) {
                warband.recruitTickDebt -= recruitTicks
                warband.reserve += 1
                if (warband.armory.size < warband.capacity) TinkersArmoryOptimizer.create(warband)?.let { warband.armory += it.save(CompoundTag()) }
            }
            warband.mobilizationTickDebt += elapsed
            val mobilizeTicks = FormulaicWarbandRules.mobilizationTicksPerStrength(warband.environment) * 20.0
            while (warband.mobilizationTickDebt >= mobilizeTicks && warband.reserve > 0) {
                warband.mobilizationTickDebt -= mobilizeTicks
                warband.reserve -= 1
                warband.raidPool += 1.0
            }
        }
    }

    private fun returnCampaign(data: PillagerWorldData, campaign: PillagerCampaign, now: Long, survivingThreat: Double) {
        data.warbands[campaign.originWarbandId]?.let { warband ->
            warband.raidPool = (warband.raidPool + survivingThreat).coerceAtMost(warband.capacity.toDouble())
            warband.armory += campaign.pendingEquipment.map { it.copy() }
            warband.armory += campaign.memberEquipment.values.map { it.copy() }
            campaign.pendingEquipment.clear()
            campaign.memberEquipment.clear()
            campaign.memberThreat.clear()
            warband.aggression = (warband.aggression + 1).coerceAtMost(PillagerCampaignsConfig.maximumAggression.get())
            warband.lastIntelTick = now
        }
        campaign.committedThreat = 0
        resolveCampaign(data, campaign.id, defeatedByPlayer = false, observedTick = now, outcome = CampaignOutcome.ABORTED)
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
                        player.displayClientMessage(Component.literal("A pillager horn warns you away from claimed ground"), true)
                        player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.0f, 0.9f)
                    } else if (next == TerritorialRelation.HOSTILE) {
                        player.displayClientMessage(Component.literal("The warband now considers you hostile"), true)
                        player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.25f, 0.75f)
                    }
                    data.markChanged()
                }
            }
        }
    }
}
