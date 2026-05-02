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
    fun tick(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        discover(server, data, now)
        dispatch(server, data)
        advance(server, data)
    }

    private fun discover(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        if (now - data.lastDiscoveryTick < PillagerCampaignsConfig.baseDiscoveryIntervalTicks.get()) return
        data.lastDiscoveryTick = now
        val level = server.overworld()
        val added = PillagerBaseDiscoveryService.discoverAroundPlayers(level, data, server.playerList.players, now)
        if (added > 0) {
            PillagerCampaignsMod.LOGGER.info("Discovered {} pillager base(s)", added)
        }
    }

    private fun dispatch(server: MinecraftServer, data: PillagerWorldData) {
        val maxPerBase = PillagerCampaignsConfig.maxCampaignsPerBase.get()
        val maxRange = PillagerCampaignsConfig.maxCampaignDistanceChunks.get()
        val players = server.playerList.players
        data.bases.values.forEach { base ->
            if (base.defeated) return@forEach
            val existing = data.campaigns.values.count { it.originBaseId == base.id && it.state != CampaignState.RESOLVED }
            if (existing >= maxPerBase) return@forEach
            val level = server.allLevels.firstOrNull { it.dimension().location() == base.dimension } ?: return@forEach
            val target = nearestPlayer(level, players, base.chunkX, base.chunkZ, maxRange) ?: return@forEach
            val alreadyTargetingPlayer = data.campaigns.values.any {
                it.originBaseId == base.id &&
                    it.targetPlayerId == target.uuid &&
                    it.state != CampaignState.RESOLVED
            }
            if (alreadyTargetingPlayer) return@forEach
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
            )
            officer.state = OfficerState.DEPLOYED
            data.campaigns[campaign.id] = campaign
            data.markChanged()
        }
    }

    private fun advance(server: MinecraftServer, data: PillagerWorldData) {
        val speed = PillagerCampaignsConfig.campaignSpeedTicksPerChunk.get()
        val dt = PillagerCampaignsConfig.campaignTickInterval.get()
        val materializeDistance = PillagerCampaignsConfig.materializeDistanceChunks.get()
        val toResolve = mutableListOf<UUID>()

        data.campaigns.values.forEach { campaign ->
            if (campaign.state == CampaignState.RESOLVED || campaign.state == CampaignState.ACTIVE) return@forEach
            val player = server.playerList.players.firstOrNull { it.uuid == campaign.targetPlayerId } ?: run {
                toResolve.add(campaign.id)
                return@forEach
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
                val level = player.serverLevel()
                tryMaterialize(level, campaign, player, materializeDistance, data)
            }
        }
        if (toResolve.isNotEmpty()) {
            toResolve.forEach { id -> resolveCampaign(data, id) }
            data.markChanged()
        }
    }

    private fun tryMaterialize(level: ServerLevel, campaign: PillagerCampaign, player: ServerPlayer, distanceChunks: Int, data: PillagerWorldData) {
        if (PillagerRuntime.hasLiveOfficerLeader(level, campaign.officerId) || PillagerRuntime.hasLiveCampaignMember(level, campaign.id)) {
            campaign.state = CampaignState.ACTIVE
            data.markChanged()
            return
        }
        val pos = PillagerSpawnPlacementRules.findMaterializationPos(level, player, campaign.currentChunkX, campaign.currentChunkZ, distanceChunks) ?: return
        val base = data.bases[campaign.originBaseId] ?: return
        val officer = data.officers[campaign.officerId] ?: return
        val spawned = PillagerRuntime.materializeFixedSquad(level, campaign, base, officer, player, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        if (spawned > 0) {
            campaign.state = CampaignState.ACTIVE
            data.markChanged()
            PillagerCampaignsMod.LOGGER.info("Materialized campaign {} with {} mobs at {},{}", campaign.id, spawned, pos.x, pos.z)
        }
    }

    fun resolveCampaign(data: PillagerWorldData, campaignId: UUID) {
        val campaign = data.campaigns[campaignId] ?: return
        campaign.state = CampaignState.RESOLVED
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
