package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.system.CampaignMath
import com.gerald.pillagercampaigns.system.PillagerCampaignEngine
import com.gerald.pillagercampaigns.system.PillagerDiscoveryCoordinator
import com.gerald.pillagercampaigns.system.PillagerLocateGuard
import com.gerald.pillagercampaigns.system.PillagerRuntime
import com.gerald.pillagercampaigns.system.PillagerSettlementScheduler
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.CommandEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object PillagerCampaignsEvents {
    private var lastBossEnsureTick: Long = 0L
    private var lastMaterializationTick: Long = 0L
    private const val METRIC_LOG_INTERVAL_TICKS: Long = 200L
    private const val MATERIALIZATION_INTERVAL_TICKS: Long = 1L
    private const val PHASE_WARN_THRESHOLD_MS: Double = 25.0
    private var metricsWindowStartTick: Long = 0L
    private var metricsSamples: Int = 0
    private var discoveryTotalMs: Double = 0.0
    private var campaignTotalMs: Double = 0.0
    private var bossEnsureTotalMs: Double = 0.0
    private var discoveryMaxMs: Double = 0.0
    private var campaignMaxMs: Double = 0.0
    private var bossEnsureMaxMs: Double = 0.0
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerCampaignsConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
        }
        PillagerDiscoveryCoordinator.reset()
        PillagerSettlementScheduler.rebuild(PillagerWorldData.get(event.server))
        PillagerRuntime.resetLiveIndexes()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !PillagerCampaignsConfig.enabled.get()) return
        val server = event.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        if (metricsWindowStartTick == 0L) metricsWindowStartTick = now

        val discoveryMs = measureMs { PillagerCampaignEngine.discoveryTick(server, data, now) }
        recordDiscovery(discoveryMs)
        if (now - data.lastCampaignTick >= PillagerCampaignsConfig.campaignTickInterval.get()) {
            data.lastCampaignTick = now
            val campaignMs = measureMs { PillagerCampaignEngine.tick(server, data, now) }
            recordCampaign(campaignMs)
            data.markChanged()
        }
        if (now - lastBossEnsureTick >= 100L) {
            lastBossEnsureTick = now
            val ensureMs = measureMs { PillagerSettlementScheduler.ensureBossPresenceSlice(server, data) }
            recordBossEnsure(ensureMs)
        }
        if (now - lastMaterializationTick >= MATERIALIZATION_INTERVAL_TICKS) {
            lastMaterializationTick = now
            PillagerSettlementScheduler.tickMaterialization(server, data, now)
        }
        if (now - metricsWindowStartTick >= METRIC_LOG_INTERVAL_TICKS) {
            flushMetricsWindow()
            metricsWindowStartTick = now
        }
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        val chunk = event.chunk as? LevelChunk ?: return
        val data = PillagerWorldData.get(level.server)
        PillagerSettlementScheduler.onChunkLoad(level, data, chunk.pos.x, chunk.pos.z)
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
    fun onEntityJoinLevel(event: EntityJoinLevelEvent) {
        val mob = event.entity as? Mob ?: return
        if (!mob.persistentData.hasUUID(PillagerRuntime.OFFICER_TAG)) return
        PillagerRuntime.registerLiveMob(mob)
        PillagerRuntime.syncOfficerVisuals(mob)
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
        PillagerRuntime.forgetLiveMob(mob)
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

    @SubscribeEvent
    fun onCommand(event: CommandEvent) {
        val blockedTarget = PillagerLocateGuard.blockedTarget(
            event.parseResults.reader.string,
            PillagerCampaignsConfig.structureBaseIds.get().map { it.toString() },
        ) ?: return
        val source = event.parseResults.context.source
        source.sendFailure(
            Component.literal(
                "Vanilla /locate is disabled for SAM-owned pillager base '$blockedTarget' because it can synchronously probe ungenerated jigsaw structures. Use /sam settlements list instead.",
            ),
        )
        event.isCanceled = true
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("pillagercampaigns")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("status").executes { status(it.source) })
                .then(Commands.literal("tick_once").executes { tickOnce(it.source) })
                .then(Commands.literal("list").then(Commands.literal("bases").executes { listBases(it.source) }))
                .then(
                    Commands.literal("list")
                        .then(
                            Commands.literal("campaigns")
                                .executes { listCampaigns(it.source) }
                                .then(Commands.literal("closed").executes { listClosedCampaigns(it.source) }),
                        ),
                )
                .then(Commands.literal("list").then(Commands.literal("officers").executes { listOfficers(it.source) }))
                .then(
                    Commands.literal("force_materialize")
                        .then(
                            Commands.argument("base", StringArgumentType.word())
                                .executes { forceMaterialize(it.source, StringArgumentType.getString(it, "base")) }
                        )
                )
                .then(Commands.literal("reset").executes { reset(it.source) }),
        )
        dispatcher.register(
            Commands.literal("sam")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("status").executes { status(it.source) })
                .then(Commands.literal("settlements").then(Commands.literal("list").executes { listBases(it.source) }))
                .then(
                    Commands.literal("settlements")
                        .then(
                            Commands.literal("materialize")
                                .then(
                                    Commands.argument("base", StringArgumentType.word())
                                        .executes { forceMaterialize(it.source, StringArgumentType.getString(it, "base")) },
                                ),
                        ),
                ),
        )
    }

    private fun status(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({
            Component.literal(
                "enabled=${PillagerCampaignsConfig.enabled.get()} bases=${data.bases.size} factions=${data.factions.size} officers=${data.officers.size} campaigns=${data.campaigns.values.count { it.state != CampaignState.RESOLVED }}"
            )
                .append(" ")
                .append(Component.literal(PillagerSettlementScheduler.statusLine()))
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
                Component.literal(formatBaseLine(base))
                    .append(" ")
                    .append(tpLink(base.dimension.toString(), base.center.x, base.center.y, base.center.z))
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun forceMaterialize(source: CommandSourceStack, basePrefix: String): Int {
        val data = PillagerWorldData.get(source.server)
        val matches = data.bases.values.filter { it.id.toString().startsWith(basePrefix, ignoreCase = true) }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No base matches prefix '$basePrefix'"))
            return 0
        }
        if (matches.size > 1) {
            source.sendFailure(Component.literal("Ambiguous base prefix '$basePrefix' (${matches.size} matches)"))
            return 0
        }
        val base = matches.first()
        PillagerSettlementScheduler.requestMaterialization(base, force = true)
        data.markChanged()
        source.sendSuccess({ Component.literal("Queued forced materialization for base ${base.id.toString().take(8)}. Use /sam status to watch progress.") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun listCampaigns(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val active = data.campaigns.values.filter { it.state != CampaignState.RESOLVED }
        source.sendSuccess({ Component.literal("Campaigns (${active.size})") }, false)
        active.forEach { campaign ->
            val eta = when (campaign.state) {
                CampaignState.TRAVELING -> {
                    val remainingChunks = CampaignMath.manhattan(
                        campaign.currentChunkX,
                        campaign.currentChunkZ,
                        campaign.targetChunkX,
                        campaign.targetChunkZ
                    )
                    val ticksPerChunk = PillagerCampaignsConfig.campaignSpeedTicksPerChunk.get().coerceAtLeast(1)
                    val remainingTicks = (remainingChunks * ticksPerChunk - campaign.tickDebt).coerceAtLeast(0)
                    formatEta(remainingTicks)
                }
                CampaignState.READY_TO_MATERIALIZE -> "ready"
                CampaignState.MATERIALIZING -> "materializing"
                CampaignState.ACTIVE -> "active"
                CampaignState.RESOLVED -> "resolved"
            }
            source.sendSuccess({
                val currentBlockX = campaign.currentChunkX shl 4
                val currentBlockZ = campaign.currentChunkZ shl 4
                val targetBlockX = campaign.targetChunkX shl 4
                val targetBlockZ = campaign.targetChunkZ shl 4
                val y = source.position.y.toInt().coerceAtLeast(64)
                val dim = source.level.dimension().location().toString()
                Component.literal(
                    "  ${campaign.id.toString().take(8)} state=${campaign.state.name.lowercase()} chunk=${campaign.currentChunkX},${campaign.currentChunkZ} current_xz=$currentBlockX,$currentBlockZ target_chunk=${campaign.targetChunkX},${campaign.targetChunkZ} target_xz=$targetBlockX,$targetBlockZ eta=$eta"
                ).append(" ").append(tpLink(dim, targetBlockX, y, targetBlockZ))
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun listClosedCampaigns(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val closed = data.campaigns.values.filter { it.state == CampaignState.RESOLVED }
        source.sendSuccess({ Component.literal("Closed Campaigns (${closed.size})") }, false)
        closed.forEach { campaign ->
            source.sendSuccess({
                val currentBlockX = campaign.currentChunkX shl 4
                val currentBlockZ = campaign.currentChunkZ shl 4
                val targetBlockX = campaign.targetChunkX shl 4
                val targetBlockZ = campaign.targetChunkZ shl 4
                val y = source.position.y.toInt().coerceAtLeast(64)
                val dim = source.level.dimension().location().toString()
                Component.literal(
                    "  ${campaign.id.toString().take(8)} state=${campaign.state.name.lowercase()} chunk=${campaign.currentChunkX},${campaign.currentChunkZ} current_xz=$currentBlockX,$currentBlockZ target_chunk=${campaign.targetChunkX},${campaign.targetChunkZ} target_xz=$targetBlockX,$targetBlockZ"
                ).append(" ").append(tpLink(dim, targetBlockX, y, targetBlockZ))
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun formatEta(totalTicks: Int): String {
        val totalSeconds = (totalTicks / 20).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun listOfficers(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({ Component.literal("Officers (${data.officers.size})") }, false)
        data.officers.values.forEach { officer ->
            val homeBase = data.bases[officer.homeBaseId]
            val homePos = homeBase?.center?.let { "${it.x},${it.y},${it.z}" } ?: "unknown"
            source.sendSuccess({
                val line = Component.literal(
                    "  ${officer.id.toString().take(8)} ${officer.name} ${officer.title} rank=${officer.rank.name.lowercase()} state=${officer.state.name.lowercase()} home_base_xyz=$homePos"
                )
                if (homeBase != null) {
                    line.append(" ").append(tpLink(homeBase.dimension.toString(), homeBase.center.x, homeBase.center.y, homeBase.center.z))
                }
                line
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun tpLink(dimensionId: String, x: Int, y: Int, z: Int): Component {
        val command = "/execute in $dimensionId run tp @s $x $y $z"
        return Component.literal("[tp]").withStyle { style ->
            style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))
        }
    }

    internal fun formatBaseLine(base: com.gerald.pillagercampaigns.data.PillagerBase): String {
        val center = "${base.center.x},${base.center.y},${base.center.z}"
        return "  ${base.id.toString().take(8)} dim=${base.dimension} state=${base.state.name.lowercase()} form=${base.form.name.lowercase()} anchor_chunk=${base.anchorChunkX},${base.anchorChunkZ} chunk=${base.chunkX},${base.chunkZ} center_xyz=$center attempts=${base.materializationAttempts} failure=${base.materializationFailure.name.lowercase()}"
    }

    private fun reset(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        data.factions.clear()
        data.bases.clear()
        data.officers.clear()
        data.campaigns.clear()
        data.lastCampaignTick = 0L
        data.lastDiscoveryTick = 0L
        PillagerDiscoveryCoordinator.reset()
        PillagerSettlementScheduler.reset()
        data.markChanged()
        source.sendSuccess({ Component.literal("Pillager Campaigns state reset") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun createBaseIntelMap(level: ServerLevel, x: Int, z: Int): ItemStack {
        val map = MapItem.create(level, x, z, 2, true, true)
        MapItem.renderBiomePreviewMap(level, map)
        return map
    }

    private inline fun measureMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        val elapsedNs = System.nanoTime() - start
        return elapsedNs / 1_000_000.0
    }

    private fun recordDiscovery(ms: Double) {
        metricsSamples++
        discoveryTotalMs += ms
        if (ms > discoveryMaxMs) discoveryMaxMs = ms
        if (ms >= PHASE_WARN_THRESHOLD_MS) {
            PillagerCampaignsMod.LOGGER.warn("Discovery tick took {} ms", "%.3f".format(ms))
        }
    }

    private fun recordCampaign(ms: Double) {
        campaignTotalMs += ms
        if (ms > campaignMaxMs) campaignMaxMs = ms
        if (ms >= PHASE_WARN_THRESHOLD_MS) {
            PillagerCampaignsMod.LOGGER.warn("Campaign tick took {} ms", "%.3f".format(ms))
        }
    }

    private fun recordBossEnsure(ms: Double) {
        bossEnsureTotalMs += ms
        if (ms > bossEnsureMaxMs) bossEnsureMaxMs = ms
        if (ms >= PHASE_WARN_THRESHOLD_MS) {
            PillagerCampaignsMod.LOGGER.warn("Boss ensure pass took {} ms", "%.3f".format(ms))
        }
    }

    private fun flushMetricsWindow() {
        if (metricsSamples <= 0) return
        val samples = metricsSamples.coerceAtLeast(1)
        PillagerCampaignsMod.LOGGER.info(
            "Perf window: discovery avg={}ms max={}ms | campaign avg={}ms max={}ms | bossEnsure avg={}ms max={}ms",
            "%.3f".format(discoveryTotalMs / samples),
            "%.3f".format(discoveryMaxMs),
            "%.3f".format(campaignTotalMs / samples),
            "%.3f".format(campaignMaxMs),
            "%.3f".format(bossEnsureTotalMs / samples),
            "%.3f".format(bossEnsureMaxMs),
        )
        metricsSamples = 0
        discoveryTotalMs = 0.0
        campaignTotalMs = 0.0
        bossEnsureTotalMs = 0.0
        discoveryMaxMs = 0.0
        campaignMaxMs = 0.0
        bossEnsureMaxMs = 0.0
    }
}
