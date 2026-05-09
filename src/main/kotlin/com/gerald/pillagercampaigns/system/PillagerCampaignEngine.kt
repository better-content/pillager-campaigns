package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.PillagerCampaignsMod
import com.gerald.pillagercampaigns.data.BaseState
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
    private const val MATERIALIZE_LEASE_TICKS: Long = 200L
    private const val MIN_ACTIVE_LIVE_MEMBERS: Int = 1
    private var dispatchCursor: Int = 0

    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        dispatch(server, data)
        advance(server, data, now)
    }

    fun discoveryTick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val before = data.bases.size
        PillagerDiscoveryCoordinator.tick(server, data, now)
        val added = data.bases.size - before
        if (added > 0) {
            PillagerCampaignsMod.LOGGER.info("Discovered {} pillager base(s)", added)
        }
    }

    private fun dispatch(server: MinecraftServer, data: PillagerWorldData) {
        val maxPerBase = PillagerCampaignsConfig.maxCampaignsPerBase.get()
        val maxRange = PillagerCampaignsConfig.maxCampaignDistanceChunks.get()
        val players = server.playerList.players
        val bases = data.bases.values.toList()
        if (bases.isEmpty()) return
        val activeCampaignsByBase = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .groupingBy { it.originBaseId }
            .eachCount()
        val targetedPlayersByBase = data.campaigns.values
            .asSequence()
            .filter { it.state != CampaignState.RESOLVED }
            .groupBy({ it.originBaseId }, { it.targetPlayerId })
        val budget = PillagerCampaignsConfig.campaignDispatchBasesPerTick.get().coerceAtLeast(1)
        var inspected = 0
        while (inspected < budget && inspected < bases.size) {
            val base = bases[Math.floorMod(dispatchCursor, bases.size)]
            dispatchCursor++
            inspected++
            if (base.defeated || base.state == BaseState.DEFEATED) continue
            val existing = activeCampaignsByBase[base.id] ?: 0
            if (existing >= maxPerBase) continue
            val level = server.allLevels.firstOrNull { it.dimension().location() == base.dimension } ?: continue
            val target = nearestPlayer(level, players, base.chunkX, base.chunkZ, maxRange) ?: continue
            val alreadyTargetingPlayer = targetedPlayersByBase[base.id]?.contains(target.uuid) == true
            if (alreadyTargetingPlayer) continue
            val officer = obtainOfficer(data, base.factionId, base.id, base.difficulty)
            val difficultySnapshot = base.difficulty.coerceAtLeast(0)
            val campaign = PillagerCampaign(
                id = UUID.randomUUID(),
                factionId = base.factionId,
                originBaseId = base.id,
                officerId = officer.id,
                targetPlayerId = target.uuid,
                targetDimension = level.dimension().location(),
                currentChunkX = base.chunkX,
                currentChunkZ = base.chunkZ,
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
            toResolve.forEach { id -> resolveCampaign(data, id) }
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

        val pos = PillagerSpawnPlacementRules.findMaterializationPos(level, player, campaign.currentChunkX, campaign.currentChunkZ, distanceChunks) ?: return
        val base = data.bases[campaign.originBaseId] ?: return
        val officer = data.officers[campaign.officerId] ?: return
        val spawnedIds = PillagerRuntime.materializeFixedSquad(level, campaign, base, officer, player, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        if (spawnedIds.isNotEmpty()) {
            campaign.squadMemberIds.clear()
            campaign.squadMemberIds.addAll(spawnedIds)
            campaign.state = CampaignState.ACTIVE
            campaign.materializeAttemptId = null
            campaign.materializingUntilTick = 0L
            data.markChanged()
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} with {} mobs at {},{}", campaign.id, spawnedIds.size, pos.x, pos.z)
        }
    }

    fun resolveCampaign(data: PillagerWorldData, campaignId: UUID) {
        val campaign = data.campaigns[campaignId] ?: return
        campaign.state = CampaignState.RESOLVED
        campaign.materializeAttemptId = null
        campaign.materializingUntilTick = 0L
        campaign.squadMemberIds.clear()
        data.officers[campaign.officerId]?.state = OfficerState.AVAILABLE
        data.markChanged()
    }

    fun collapseFaction(data: PillagerWorldData, factionId: UUID) {
        val campaignIds = data.campaigns.values.filter { it.factionId == factionId }.map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id) }
        data.campaigns.entries.removeIf { (_, campaign) -> campaign.factionId == factionId }
        data.officers.entries.removeIf { (_, officer) -> officer.factionId == factionId }
        data.bases.entries.removeIf { (_, base) -> base.factionId == factionId }
        data.factions.remove(factionId)
        data.markChanged()
    }

    fun collapseBase(data: PillagerWorldData, baseId: UUID) {
        val base = data.bases[baseId] ?: return
        base.defeated = true
        base.state = BaseState.DEFEATED
        data.factions[base.factionId]?.bossEntityId = null
        val campaignIds = data.campaigns.values
            .filter { it.originBaseId == baseId && it.state != CampaignState.RESOLVED }
            .map { it.id }
        campaignIds.forEach { id -> resolveCampaign(data, id) }
        data.officers.values
            .filter { it.homeBaseId == baseId }
            .forEach { it.state = OfficerState.DEAD }
        data.markChanged()
    }

    private fun nearestPlayer(level: ServerLevel, players: List<ServerPlayer>, chunkX: Int, chunkZ: Int, maxRange: Int): ServerPlayer? {
        return players
            .asSequence()
            .filter { it.level() == level }
            .filter { CampaignMath.manhattan(chunkX, chunkZ, it.chunkPosition().x, it.chunkPosition().z) <= maxRange }
            .minByOrNull { CampaignMath.manhattan(chunkX, chunkZ, it.chunkPosition().x, it.chunkPosition().z) }
    }

    private fun obtainOfficer(data: PillagerWorldData, factionId: UUID, homeBaseId: UUID, baseDifficulty: Int): PillagerOfficer {
        val bossId = data.factions[factionId]?.bossOfficerId
        val pooled = data.officers.values.firstOrNull {
            it.factionId == factionId &&
                it.state == OfficerState.AVAILABLE &&
                it.id != bossId
        }
        if (pooled != null) {
            pooled.homeBaseId = homeBaseId
            return pooled
        }
        val faction = data.factions[factionId] ?: PillagerIdentity.makeFaction(homeBaseId.leastSignificantBits).also { data.factions[it.id] = it }
        val officer = PillagerIdentity.makeOfficer(
            faction,
            homeBaseId,
            homeBaseId.leastSignificantBits xor data.officers.size.toLong(),
            rank = OfficerRank.CAPTAIN,
            officerClass = CampaignDifficultyRules.officerClassForDifficulty(baseDifficulty),
            preferenceGraph = CampaignDifficultyRules.defaultPreferenceGraph(homeBaseId.mostSignificantBits xor homeBaseId.leastSignificantBits xor data.officers.size.toLong()),
        )
        data.officers[officer.id] = officer
        return officer
    }
}
