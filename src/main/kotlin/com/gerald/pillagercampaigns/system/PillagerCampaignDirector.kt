package com.gerald.pillagercampaigns.system

import com.gerald.pillagercampaigns.PillagerCampaignsConfig
import com.gerald.pillagercampaigns.data.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import java.util.UUID

object PillagerCampaignDirector {
    fun tick(server: MinecraftServer, data: PillagerWorldData): Int {
        if (!PillagerCampaignsConfig.campaignEnabled.get()) return 0
        val level = server.overworld()
        val now = level.gameTime
        markPlayerRegions(server, data, now)
        if (now % 1200L == 0L) PillagerBaseService.tickEconomy(data)
        maybeDispatchCampaigns(level, data, now)
        val materialized = advanceCampaigns(server, data, now)
        processPendingFlags(server, data)
        return materialized
    }

    private fun markPlayerRegions(server: MinecraftServer, data: PillagerWorldData, now: Long) {
        val size = PillagerCampaignsConfig.regionSizeChunks.get()
        server.playerList.players.filter { PillagerRuntime.eligible(it) }.forEach { player ->
            val center = ChunkRef.of(player.blockPosition())
            for (dx in -1..1) for (dz in -1..1) {
                val key = RegionKey.fromChunk(ChunkRef(center.x + dx * size, center.z + dz * size), size)
                data.regions[PillagerWorldData.regionKey(key)] = RegionActivity(key, now)
            }
        }
        data.lastRegionTick = now
        data.markChanged()
    }

    private fun maybeDispatchCampaigns(level: ServerLevel, data: PillagerWorldData, now: Long) {
        data.bases.values.filter { it.isActive() }.forEach { base ->
            val active = PillagerCampaignRules.activeCampaignsForBase(data, base.id)
            if (active >= PillagerCampaignsConfig.maxCampaignsPerBase.get()) return@forEach
            val intel = PillagerCampaignRules.bestIntel(base, now)
            if (intel != null && intel.confidence > 0 && base.manpower >= 8 && base.supplies >= 12) {
                dispatch(level, data, base, CampaignState.APPROACHING_INTEL, intel.lastSeenChunk, 7 + base.aggression / 20, 1)
                base.manpower -= 8; base.supplies -= 12
            } else if (base.manpower >= 3 && base.supplies >= 5 && level.random.nextFloat() < 0.35f) {
                val target = blindScoutTarget(base, level)
                dispatch(level, data, base, CampaignState.SCOUTING, target, 3, 0)
                base.manpower -= 3; base.supplies -= 5
            } else if (base.type == BaseType.MAJOR && base.manpower >= 20 && base.supplies >= 80 && level.random.nextFloat() < 0.08f) {
                staleTarget(data, base)?.let { target ->
                    dispatch(level, data, base, CampaignState.EXPANDING, target, 12, 1)
                    base.manpower -= 20; base.supplies -= 80
                }
            }
        }
        data.markChanged()
    }

    private fun dispatch(level: ServerLevel, data: PillagerWorldData, base: PillagerBase, state: CampaignState, target: ChunkRef, pillagers: Int, specials: Int): PillagerCampaign {
        val officer = PillagerBaseService.officerForBase(data, base)
        val campaign = PillagerCampaign(UUID.randomUUID(), base.factionId, base.id, officer?.id, state, base.chunk, target, PillagerCampaignsConfig.campaignSpeedTicksPerChunk.get(), 0, pillagers, specials, level.gameTime, 0L)
        data.campaigns[campaign.id] = campaign
        return campaign
    }

    private fun advanceCampaigns(server: MinecraftServer, data: PillagerWorldData, now: Long): Int {
        var spawned = 0
        val dead = mutableListOf<UUID>()
        data.campaigns.values.forEach { campaign ->
            if (campaign.state == CampaignState.DISBANDED) { dead += campaign.id; return@forEach }
            PillagerCampaignRules.advanceTravel(campaign, PillagerCampaignsConfig.campaignTickInterval.get())
            if (campaign.current == campaign.target) {
                when (campaign.state) {
                    CampaignState.EXPANDING -> {
                        val level = server.getLevel(Level.OVERWORLD) ?: return@forEach
                        data.bases[campaign.originBaseId]?.let { parent -> PillagerBaseService.createSatellite(level, data, parent, campaign.target) }
                        campaign.state = CampaignState.DISBANDED
                    }
                    CampaignState.RETURNING_TO_BASE, CampaignState.RETREATING_WITH_INTEL -> campaign.state = CampaignState.DISBANDED
                    else -> {}
                }
            }
            spawned += materializeIfNearPlayer(server, data, campaign, now)
            if (PillagerCampaignRules.isExpired(campaign, now)) campaign.state = CampaignState.DISBANDED
        }
        dead.forEach { data.campaigns.remove(it) }
        if (dead.isNotEmpty() || spawned > 0) data.markChanged()
        return spawned
    }

    private fun materializeIfNearPlayer(server: MinecraftServer, data: PillagerWorldData, campaign: PillagerCampaign, now: Long): Int {
        if (now - campaign.lastMaterializedTick < 2400L) return 0
        val player = server.playerList.players.firstOrNull { PillagerRuntime.eligible(it) && ChunkRef.of(it.blockPosition()).distanceManhattan(campaign.current) <= 6 } ?: return 0
        val level = player.serverLevel()
        val active = PillagerRuntime.countActivePatrolMobs(level, player.blockPosition())
        if (active >= PillagerCampaignsConfig.maxActiveNearPlayer.get()) return 0
        val pos = PillagerRuntime.chooseSpawnPos(level, player.blockPosition()) ?: campaign.current.centerBlock(player.blockY)
        val base = data.bases[campaign.originBaseId]
        val faction = data.factions[campaign.factionId]
        val officer = campaign.officerId?.let { data.officers[it] }
        val plan = PillagerCampaignMaterializationRules.planFor(campaign)
        val originalState = campaign.state
        campaign.state = plan.nextState
        val objectivePlayer = if (plan.targetPlayerImmediately) player else null
        val count = PillagerRuntime.spawnSquad(level, data, pos, objectivePlayer, base, faction, campaign, officer, campaign.pillagers, campaign.specials, leader = true)
        if (count > 0) {
            campaign.lastMaterializedTick = now
        } else {
            campaign.state = originalState
        }
        return if (count > 0) 1 else 0
    }

    fun reportIntel(level: ServerLevel, data: PillagerWorldData, baseId: UUID?, officerId: UUID?, player: ServerPlayer) {
        val base = baseId?.let { data.bases[it] } ?: PillagerBaseService.nearestActiveBase(level, data, player.blockPosition()) ?: return
        val existing = base.intel.firstOrNull { it.playerUuid == player.uuid }
        if (existing == null) base.intel.add(PlayerIntel(player.uuid, player.gameProfile.name, ChunkRef.of(player.blockPosition()), level.gameTime, 8, officerId))
        else {
            existing.lastSeenChunk = ChunkRef.of(player.blockPosition()); existing.lastSeenTick = level.gameTime; existing.confidence = (existing.confidence + 6).coerceAtMost(30); existing.sourceOfficerId = officerId
        }
        data.markChanged()
    }

    private fun processPendingFlags(server: MinecraftServer, data: PillagerWorldData) {
        val iter = data.pendingMarkers.iterator()
        while (iter.hasNext()) {
            val marker = iter.next()
            val level = server.allLevels.firstOrNull { it.dimension().location() == marker.dimension }
            if (level == null) {
                if (++marker.attempts > 20) {
                    iter.remove()
                    data.markChanged()
                }
                continue
            }
            val faction = data.factions[marker.factionId]
            if (faction == null) {
                iter.remove()
                data.markChanged()
                continue
            }
            if (level.hasChunkAt(marker.pos)) {
                val count = if (marker.count > 0) marker.count else PillagerCampaignsConfig.deathFlagsPerKill.get()
                PillagerRuntime.placeFactionFlags(level, faction, marker.pos, count)
                iter.remove(); data.markChanged()
            } else if (++marker.attempts > 20) {
                iter.remove(); data.markChanged()
            }
        }
    }

    private fun blindScoutTarget(base: PillagerBase, level: ServerLevel): ChunkRef {
        val radius = 24 + (base.influence / 3)
        return ChunkRef(base.chunk.x + level.random.nextInt(radius * 2 + 1) - radius, base.chunk.z + level.random.nextInt(radius * 2 + 1) - radius)
    }

    private fun staleTarget(data: PillagerWorldData, base: PillagerBase): ChunkRef? {
        val size = PillagerCampaignsConfig.regionSizeChunks.get()
        val now = data.lastRegionTick
        for (r in 10..48 step 6) {
            val candidate = ChunkRef(base.chunk.x + r, base.chunk.z + r / 2)
            val key = RegionKey.fromChunk(candidate, size)
            val activity = data.regions[PillagerWorldData.regionKey(key)]?.lastPlayerActiveTick ?: Long.MIN_VALUE
            if (now - activity > PillagerCampaignsConfig.staleRegionTicks.get()) return candidate
        }
        return null
    }
}
