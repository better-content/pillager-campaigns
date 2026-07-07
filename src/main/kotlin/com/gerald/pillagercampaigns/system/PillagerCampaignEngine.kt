package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.OfficerRank
import com.gerald.pillagercampaigns.data.OfficerState
import com.gerald.pillagercampaigns.data.PillagerCampaign
import com.gerald.pillagercampaigns.data.PillagerOfficer
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.util.PillagerIdentity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

object PillagerCampaignEngine {
    const val INITIAL_WARBAND_STRENGTH: Int = 1
    private const val MATERIALIZE_LEASE_TICKS: Long = 200L
    private const val MIN_ACTIVE_LIVE_MEMBERS: Int = 1
    private const val RAID_COOLDOWN_TICKS: Long = 6_000L
    private const val PLAYER_KILL_COOLDOWN_TICKS: Long = 24_000L
    private const val INTEL_STABILITY_TICKS: Long = 24_000L
    private const val RALLY_WINDOW_TICKS: Long = 12_000L
    private const val RALLY_MAX_STEP_CHUNKS: Int = 3
    private var dispatchCursor: Int = 0

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        dispatch(server, data)
        advance(server, data, now)
    }

    fun discoveryTick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val before = data.warbands.size
        PillagerDiscoveryCoordinator.tick(server, data, now)
        val added = data.warbands.size - before
        if (added > 0) {
            PillagerCampaignsMod.LOGGER.info("Discovered {} pillager warband(s)", added)
        }
    }

    private fun dispatch(server: MinecraftServer, data: PillagerWorldData) {
        val maxRange = PillagerCampaignsConfig.maxCampaignDistanceChunks.get()
        val players = server.playerList.players
        val warbands = data.warbands.values.toList()
        if (warbands.isEmpty()) return
        val activeCampaignsByWarband = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .groupingBy { it.originWarbandId }
            .eachCount()
        val targetedPlayersByWarband = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .groupBy({ it.originWarbandId }, { it.targetPlayerId })
        val budget = PillagerCampaignsConfig.campaignDispatchWarbandsPerTick.get().coerceAtLeast(1)
        var inspected = 0
        while (inspected < budget && inspected < warbands.size) {
            val warband = warbands[Math.floorMod(dispatchCursor, warbands.size)]
            dispatchCursor++
            inspected++
            moveRallyIfDue(server, data, warband, server.overworld().gameTime)
            if (warband.defeated || warband.strength <= 0) continue
            val now = server.overworld().gameTime
            if (now < warband.nextRaidTick || now < warband.cooldownUntilTick) continue
            val existing = activeCampaignsByWarband[warband.id] ?: 0
            if (existing >= warband.activeCampaignLimit) continue
            val level = server.allLevels.firstOrNull { it.dimension().location() == warband.dimension } ?: continue
            val target = nearestPlayer(level, players, warband.rallyChunkX, warband.rallyChunkZ, maxRange) ?: continue
            val alreadyTargetingPlayer = targetedPlayersByWarband[warband.id]?.contains(target.uuid) == true
            if (alreadyTargetingPlayer) continue
            val officer = obtainOfficer(data, warband.factionId, warband.id, warband.strength)
            val difficultySnapshot = warband.strength.coerceAtLeast(0)
            val campaign = PillagerCampaign(
                id = UUID.randomUUID(),
                factionId = warband.factionId,
                originWarbandId = warband.id,
                officerId = officer.id,
                targetPlayerId = target.uuid,
                targetDimension = level.dimension().location(),
                currentChunkX = warband.rallyChunkX,
                currentChunkZ = warband.rallyChunkZ,
                targetChunkX = target.chunkPosition().x,
                targetChunkZ = target.chunkPosition().z,
                difficultySnapshot = difficultySnapshot,
                loadoutSeed = ThreadLocalRandom.current().nextLong(),
                tickDebt = 0,
                state = CampaignState.TRAVELING,
                materializeAttemptId = null,
                materializingUntilTick = 0L,
                squadMemberIds = mutableListOf(),
            )
            officer.state = OfficerState.DEPLOYED
            warband.nextRaidTick = now + RAID_COOLDOWN_TICKS
            data.campaigns[campaign.id] = campaign
            data.markChanged()
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val speed = PillagerCampaignsConfig.campaignSpeedTicksPerChunk.get()
        val dt = PillagerCampaignsConfig.campaignTickInterval.get()
        val materializeDistance = PillagerCampaignsConfig.materializeDistanceChunks.get()
        val toResolve = mutableListOf<UUID>()

        data.campaigns.values.forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED) return@forEach
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId } ?: run {
                toResolve.add(campaign.id)
                return@forEach
            }
            val level = player.serverLevel()
            when (campaign.state) {
                CampaignState.ACTIVE -> {
                    val alive = PillagerRuntime.countLiveMembers(level, campaign.squadMemberIds)
                    if (alive < MIN_ACTIVE_LIVE_MEMBERS) {
                        toResolve.add(campaign.id)
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
                        data.markChanged()
                    }
                    return@forEach
                }
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
            toResolve.forEach { id -> resolveCampaign(data, id, observedTick = now) }
            data.markChanged()
        }
    }

    private fun tryMaterialize(level: ServerLevel, campaign: PillagerCampaign, player: ServerPlayer, distanceChunks: Int, data: PillagerWorldData, now: Long) {
        if (campaign.state == CampaignState.MATERIALIZING && now < campaign.materializingUntilTick) {
            return
        }
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            campaign.state = CampaignState.ACTIVE
            data.markChanged()
            return
        }
        campaign.state = CampaignState.MATERIALIZING
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
            campaign.materializeAttemptId = null
            campaign.materializingUntilTick = 0L
            data.markChanged()
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} from warband {}", campaign.id, warband.id)
        }
    }

    fun resolveCampaign(data: PillagerWorldData, campaignId: UUID, defeatedByPlayer: Boolean = true, observedTick: Long = -1L) {
        val campaign = data.campaigns[campaignId] ?: return
        if (campaign.state != CampaignState.RESOLVED && defeatedByPlayer) {
            recordCampaignVictory(data, campaign.originWarbandId, observedTick)
        }
        campaign.state = CampaignState.RESOLVED
        campaign.materializeAttemptId = null
        campaign.materializingUntilTick = 0L
        campaign.squadMemberIds.clear()
        data.officers[campaign.officerId]?.state = OfficerState.AVAILABLE
        data.markChanged()
    }

    fun recordCampaignVictory(data: PillagerWorldData, warbandId: UUID, observedTick: Long = -1L) {
        data.warbands[warbandId]?.let { warband ->
            warband.strength = (warband.strength + 1).coerceAtLeast(0)
            if (observedTick >= 0L) warband.lastIntelTick = observedTick
        }
    }

    fun recordCampaignLoss(data: PillagerWorldData, warbandId: UUID) {
        data.warbands[warbandId]?.let { warband ->
            warband.strength = (warband.strength - 1).coerceAtLeast(0)
            if (warband.strength <= 0) {
                warband.defeated = true
            }
        }
    }

    fun abortCampaignAfterPlayerKill(data: PillagerWorldData, campaignId: UUID, observedTick: Long = -1L) {
        val campaign = data.campaigns[campaignId] ?: return
        data.warbands[campaign.originWarbandId]?.let { warband ->
            warband.strength = (warband.strength - 1).coerceAtLeast(INITIAL_WARBAND_STRENGTH)
            if (observedTick >= 0L) {
                warband.lastIntelTick = observedTick
                warband.cooldownUntilTick = maxOf(warband.cooldownUntilTick, observedTick + PLAYER_KILL_COOLDOWN_TICKS)
                warband.nextRaidTick = maxOf(warband.nextRaidTick, warband.cooldownUntilTick)
            }
        }
        resolveCampaign(data, campaign.id, defeatedByPlayer = false, observedTick = observedTick)
    }

    fun collapseFaction(data: PillagerWorldData, factionId: UUID) {
        val campaignIds = data.campaigns.values.filter { it.factionId == factionId }.map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id) }
        data.campaigns.entries.removeIf { (_, campaign) -> campaign.factionId == factionId }
        data.officers.entries.removeIf { (_, officer) -> officer.factionId == factionId }
        data.warbands.entries.removeIf { (_, warband) -> warband.factionId == factionId }
        data.factions.remove(factionId)
        data.markChanged()
    }

    fun collapseWarband(data: PillagerWorldData, warbandId: UUID) {
        val warband = data.warbands[warbandId] ?: return
        warband.defeated = true
        warband.strength = 0
        warband.warlordEntityId = null
        data.factions[warband.factionId]?.bossEntityId = null
        val campaignIds = data.campaigns.values
            .filter { it.originWarbandId == warbandId && it.state != CampaignState.RESOLVED }
            .map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id, defeatedByPlayer = false) }
        data.officers.values
            .filter { it.homeWarbandId == warbandId }
            .forEach { it.state = OfficerState.DEAD }
        data.markChanged()
    }

    private fun moveRallyIfDue(server: MinecraftServer, data: PillagerWorldData, warband: com.gerald.pillagercampaigns.data.PillagerWarband, now: Long) {
        if (warband.defeated || now - warband.lastIntelTick < INTEL_STABILITY_TICKS) return
        val level = server.allLevels.firstOrNull { it.dimension().location() == warband.dimension }
        warband.warlordEntityId?.let { id ->
            level?.getEntity(id)?.let { entity ->
                if (entity.isAlive) return
            }
        }
        val window = now / RALLY_WINDOW_TICKS
        val seed = server.overworld().seed xor warband.id.mostSignificantBits xor warband.id.leastSignificantBits xor window
        val dx = Math.floorMod(seed.toInt(), RALLY_MAX_STEP_CHUNKS * 2 + 1) - RALLY_MAX_STEP_CHUNKS
        val dz = Math.floorMod((seed ushr 32).toInt(), RALLY_MAX_STEP_CHUNKS * 2 + 1) - RALLY_MAX_STEP_CHUNKS
        if (dx == 0 && dz == 0) return
        val targetChunkX = warband.rallyChunkX + dx
        val targetChunkZ = warband.rallyChunkZ + dz
        val isLoaded: ((Int, Int) -> Boolean)? = level?.let { loadedLevel -> { chunkX, chunkZ -> loadedLevel.hasChunk(chunkX, chunkZ) } }
        if (!shouldApplyRallyDrift(isLoaded, warband.rallyChunkX, warband.rallyChunkZ, targetChunkX, targetChunkZ)) return
        warband.rallyChunkX = targetChunkX
        warband.rallyChunkZ = targetChunkZ
        data.markChanged()
    }

    internal fun shouldApplyRallyDrift(isChunkLoaded: ((Int, Int) -> Boolean)?, currentChunkX: Int, currentChunkZ: Int, targetChunkX: Int, targetChunkZ: Int): Boolean {
        if (isChunkLoaded == null) return true
        if (isChunkLoaded(currentChunkX, currentChunkZ)) return false
        if (isChunkLoaded(targetChunkX, targetChunkZ)) return false
        return true
    }

    private fun nearestPlayer(level: ServerLevel, players: List<ServerPlayer>, chunkX: Int, chunkZ: Int, maxRange: Int): ServerPlayer? {
        return players
            .asSequence()
            .filter { it.level() == level }
            .filter { CampaignMath.manhattan(chunkX, chunkZ, it.chunkPosition().x, it.chunkPosition().z) <= maxRange }
            .minByOrNull { CampaignMath.manhattan(chunkX, chunkZ, it.chunkPosition().x, it.chunkPosition().z) }
    }

    private fun obtainOfficer(data: PillagerWorldData, factionId: UUID, homeWarbandId: UUID, baseDifficulty: Int): PillagerOfficer {
        val bossId = data.factions[factionId]?.bossOfficerId
        val pooled = data.officers.values.firstOrNull {
            it.factionId == factionId &&
                it.state == OfficerState.AVAILABLE &&
                it.id != bossId
        }
        if (pooled != null) {
            pooled.homeWarbandId = homeWarbandId
            return pooled
        }
        val faction = data.factions[factionId] ?: PillagerIdentity.makeFaction(homeWarbandId.leastSignificantBits).also { data.factions[it.id] = it }
        val officer = PillagerIdentity.makeOfficer(
            faction,
            homeWarbandId,
            homeWarbandId.leastSignificantBits xor data.officers.size.toLong(),
            rank = OfficerRank.CAPTAIN,
            officerClass = CampaignDifficultyRules.officerClassForDifficulty(baseDifficulty),
            preferenceGraph = CampaignDifficultyRules.defaultPreferenceGraph(homeWarbandId.mostSignificantBits xor homeWarbandId.leastSignificantBits xor data.officers.size.toLong()),
        )
        data.officers[officer.id] = officer
        return officer
    }
}
