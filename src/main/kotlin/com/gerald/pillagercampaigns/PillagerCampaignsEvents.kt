package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.system.PillagerCampaignEngine
import com.gerald.pillagercampaigns.system.PillagerRuntime
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object PillagerCampaignsEvents {
    private var lastBossEnsureTick: Long = 0L
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerCampaignsConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
        }
        PillagerWorldData.get(event.server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !PillagerCampaignsConfig.enabled.get()) return
        val server = event.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        if (now - data.lastCampaignTick >= PillagerCampaignsConfig.campaignTickInterval.get()) {
            data.lastCampaignTick = now
            PillagerCampaignEngine.tick(server, data, now)
            data.markChanged()
        }
        if (now - lastBossEnsureTick >= 100L) {
            lastBossEnsureTick = now
            ensureBossPresence(server, data)
        }
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        val chunk = event.chunk as? LevelChunk ?: return
        val data = PillagerWorldData.get(level.server)
        data.bases.values
            .filter { !it.defeated && it.dimension == level.dimension().location() && it.chunkX == chunk.pos.x && it.chunkZ == chunk.pos.z }
            .forEach { base ->
                val faction = data.factions[base.factionId] ?: return@forEach
                val bossOfficerId = faction.bossOfficerId ?: return@forEach
                val bossOfficer = data.officers[bossOfficerId] ?: return@forEach
                PillagerRuntime.ensureBossAtBase(level, base, faction, bossOfficer)
            }
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingTickEvent) {
        val mob = event.entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        if (!mob.isAlive || level.gameTime % 10L != 0L) return
        PillagerRuntime.keepSquadCohesive(level, mob)
        PillagerRuntime.pushOfficerTowardPlayer(level, mob)
        PillagerRuntime.tickOfficerVisuals(level, mob)
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        val killerMob = event.source.entity as? Mob
        if (event.entity is Player && killerMob != null) {
            val killerTag = killerMob.persistentData
            if (killerTag.hasUUID(PillagerRuntime.CAMPAIGN_TAG)) {
                val campaign = data.campaigns[killerTag.getUUID(PillagerRuntime.CAMPAIGN_TAG)]
                val base = campaign?.let { data.bases[it.originBaseId] }
                if (base != null) {
                    base.difficulty = (base.difficulty - 1).coerceAtLeast(0)
                    data.markChanged()
                }
            }
            return
        }

        val mob = event.entity as? Mob ?: return
        val tag = mob.persistentData
        if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) {
            val officer = data.officers[tag.getUUID(PillagerRuntime.OFFICER_TAG)]
            val base = officer?.let { data.bases[it.homeBaseId] }
            if (base != null) {
                base.difficulty += 1
                data.markChanged()
                if (tag.getBoolean(PillagerRuntime.LEADER_TAG) || tag.getBoolean(PillagerRuntime.BOSS_TAG)) {
                    val map = createBaseIntelMap(level, base.center.x, base.center.z)
                    map.hoverName = Component.literal("Officer Orders: Base Location")
                    mob.spawnAtLocation(map)
                }
            }
        }

        if (tag.getBoolean(PillagerRuntime.BOSS_TAG) && tag.hasUUID(PillagerRuntime.FACTION_TAG)) {
            val officerId = if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) tag.getUUID(PillagerRuntime.OFFICER_TAG) else null
            val baseId = officerId?.let { data.officers[it]?.homeBaseId }
            if (baseId != null) {
                PillagerCampaignEngine.collapseBase(data, baseId)
                PillagerCampaignsMod.LOGGER.info("Base {} defeated after boss death", baseId)
            } else {
                val factionId = tag.getUUID(PillagerRuntime.FACTION_TAG)
                PillagerCampaignEngine.collapseFaction(data, factionId)
                PillagerCampaignsMod.LOGGER.info("Faction {} collapsed after boss death", factionId)
            }
            return
        }
        if (!tag.hasUUID(PillagerRuntime.CAMPAIGN_TAG)) return
        val campaign = data.campaigns[tag.getUUID(PillagerRuntime.CAMPAIGN_TAG)] ?: return
        if (tag.getBoolean(PillagerRuntime.LEADER_TAG)) {
            PillagerCampaignEngine.resolveCampaign(data, campaign.id)
        } else if (campaign.state == CampaignState.ACTIVE) {
            val survivors = level.getEntitiesOfClass(Mob::class.java, mob.boundingBox.inflate(80.0)) { candidate ->
                candidate.persistentData.hasUUID(PillagerRuntime.CAMPAIGN_TAG) &&
                    candidate.persistentData.getUUID(PillagerRuntime.CAMPAIGN_TAG) == campaign.id &&
                    candidate.isAlive
            }
            if (survivors.isEmpty()) {
                PillagerCampaignEngine.resolveCampaign(data, campaign.id)
            }
        }
    }

    @SubscribeEvent
    fun onEntitySize(event: EntityEvent.Size) {
        val scale = event.entity.persistentData.getDouble(PillagerRuntime.SCALE_TAG)
        if (scale <= 0.0 || scale == 1.0) return
        event.newSize = event.oldSize.scale(scale.toFloat())
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("pillagercampaigns")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("status").executes { status(it.source) })
                .then(Commands.literal("tick_once").executes { tickOnce(it.source) })
                .then(Commands.literal("list").then(Commands.literal("bases").executes { listBases(it.source) }))
                .then(Commands.literal("list").then(Commands.literal("campaigns").executes { listCampaigns(it.source) }))
                .then(Commands.literal("list").then(Commands.literal("officers").executes { listOfficers(it.source) }))
                .then(Commands.literal("reset").executes { reset(it.source) }),
        )
    }

    private fun status(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({
            Component.literal(
                "enabled=${PillagerCampaignsConfig.enabled.get()} bases=${data.bases.size} factions=${data.factions.size} officers=${data.officers.size} campaigns=${data.campaigns.values.count { it.state != CampaignState.RESOLVED }}"
            )
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun tickOnce(source: CommandSourceStack): Int {
        val server = source.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        PillagerCampaignEngine.tick(server, data, now)
        data.markChanged()
        source.sendSuccess({ Component.literal("Campaign engine ticked once") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun listBases(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({ Component.literal("Bases (${data.bases.size})") }, false)
        data.bases.values.forEach { base ->
            source.sendSuccess({
                Component.literal("  ${base.id.toString().take(8)} dim=${base.dimension} chunk=${base.chunkX},${base.chunkZ} defeated=${base.defeated}")
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun listCampaigns(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val active = data.campaigns.values.filter { it.state != CampaignState.RESOLVED }
        source.sendSuccess({ Component.literal("Campaigns (${active.size})") }, false)
        active.forEach { campaign ->
            source.sendSuccess({
                Component.literal(
                    "  ${campaign.id.toString().take(8)} state=${campaign.state.name.lowercase()} chunk=${campaign.currentChunkX},${campaign.currentChunkZ} target=${campaign.targetChunkX},${campaign.targetChunkZ}"
                )
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun listOfficers(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({ Component.literal("Officers (${data.officers.size})") }, false)
        data.officers.values.forEach { officer ->
            source.sendSuccess({
                Component.literal("  ${officer.id.toString().take(8)} ${officer.name} ${officer.title} rank=${officer.rank.name.lowercase()} state=${officer.state.name.lowercase()}")
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun reset(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        data.factions.clear()
        data.bases.clear()
        data.officers.clear()
        data.campaigns.clear()
        data.lastCampaignTick = 0L
        data.lastDiscoveryTick = 0L
        data.markChanged()
        source.sendSuccess({ Component.literal("Pillager Campaigns state reset") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun ensureBossPresence(server: net.minecraft.server.MinecraftServer, data: PillagerWorldData) {
        server.allLevels.forEach { level ->
            data.bases.values
                .filter { !it.defeated && it.dimension == level.dimension().location() && level.hasChunk(it.chunkX, it.chunkZ) }
                .forEach { base ->
                    val faction = data.factions[base.factionId] ?: return@forEach
                    val bossOfficerId = faction.bossOfficerId ?: return@forEach
                    val bossOfficer = data.officers[bossOfficerId] ?: return@forEach
                    PillagerRuntime.ensureBossAtBase(level, base, faction, bossOfficer)
                }
        }
    }

    private fun createBaseIntelMap(level: ServerLevel, x: Int, z: Int): ItemStack {
        val map = MapItem.create(level, x, z, 2, true, true)
        MapItem.renderBiomePreviewMap(level, map)
        return map
    }
}
