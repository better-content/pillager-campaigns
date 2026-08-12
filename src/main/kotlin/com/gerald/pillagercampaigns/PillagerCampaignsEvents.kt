package com.gerald.pillagercampaigns

import com.gerald.pillagercampaigns.data.CampaignState
import com.gerald.pillagercampaigns.data.CampaignOutcome
import com.gerald.pillagercampaigns.data.OfficerRole
import com.gerald.pillagercampaigns.data.PillagerWorldData
import com.gerald.pillagercampaigns.gametest.OpeningProgressionRuntimeValidation
import com.gerald.pillagercampaigns.system.CampaignMath
import com.gerald.pillagercampaigns.system.PillagerCampaignCoordinator
import com.gerald.pillagercampaigns.system.WarbandCoreAdapter
import com.gerald.pillagercampaigns.system.PillagerDiscoveryCoordinator
import com.gerald.pillagercampaigns.system.PillagerRuntime
import com.gerald.pillagercampaigns.system.PillagerWarbandPresenceSystem
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.GameType
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.CommandEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.AddReloadListenerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PillagerCampaignsEvents {
    private const val RESPAWN_PURGE_TAG = "classselector:respawn_purge"
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
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity
        val level = player.level() as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        WarbandCoreAdapter.observePlayerLifecycle(data, player.uuid, com.gerald.warband.core.PlayerLifecycleKind.JOINED)
    }

    @SubscribeEvent
    fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity
        val level = player.level() as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        WarbandCoreAdapter.observePlayerLifecycle(data, player.uuid, com.gerald.warband.core.PlayerLifecycleKind.RESPAWNED)
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity
        val level = player.level() as? ServerLevel ?: return
        PillagerCampaignCoordinator.pauseCampaignsForPlayer(PillagerWorldData.get(level.server), player.uuid)
    }

    @SubscribeEvent
    fun onPlayerChangeGameMode(event: PlayerEvent.PlayerChangeGameModeEvent) {
        val player = event.entity
        val level = player.level() as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        val current = event.currentGameMode
        val next = event.newGameMode

        if (PillagerCampaignCoordinator.isCampaignTargetGameMode(next) && !PillagerCampaignCoordinator.isCampaignTargetGameMode(current)) {
            WarbandCoreAdapter.observePlayerLifecycle(data, player.uuid, com.gerald.warband.core.PlayerLifecycleKind.RESPAWNED)
            return
        }

        if (!PillagerCampaignCoordinator.isCampaignTargetGameMode(next)) {
            PillagerCampaignCoordinator.pauseCampaignsForPlayer(data, player.uuid)
        }
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerCampaignsConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
        }
        val data = PillagerWorldData.get(event.server)
        data.attachRuntimeSpec(WarbandCoreAdapter.runtimeSpec(event.server))
        WarbandCoreAdapter.synchronizeNativeViews(data)
        PillagerDiscoveryCoordinator.reset()
        PillagerRuntime.resetLiveIndexes()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !PillagerCampaignsConfig.enabled.get()) return
        val server = event.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        if (metricsWindowStartTick == 0L) metricsWindowStartTick = now

        val discoveryMs = measureMs { PillagerCampaignCoordinator.discoveryTick(server, data, now) }
        recordDiscovery(discoveryMs)
        if (now - data.lastCampaignTick >= PillagerCampaignsConfig.schedulerIntervalTicks.get()) {
            val campaignMs = measureMs { PillagerCampaignCoordinator.tick(server, data, now) }
            recordCampaign(campaignMs)
            data.markChanged()
        }
        if (now - lastBossEnsureTick >= 100L) {
            lastBossEnsureTick = now
            val ensureMs = measureMs { ensureWarlordPresenceSlice(server.allLevels.toList(), data, now) }
            recordBossEnsure(ensureMs)
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
        data.warbands.values
            .filter { !it.defeated && it.dimension == level.dimension().location() && it.rallyChunkX == chunk.pos.x && it.rallyChunkZ == chunk.pos.z }
            .forEach { PillagerWarbandPresenceSystem.materializeWarlord(level, data, it, level.gameTime) }
    }

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? ServerLevel ?: return
        val chunk = event.chunk as? LevelChunk ?: return
        val box = AABB(
            chunk.pos.minBlockX.toDouble(), level.minBuildHeight.toDouble(), chunk.pos.minBlockZ.toDouble(),
            (chunk.pos.maxBlockX + 1).toDouble(), level.maxBuildHeight.toDouble(), (chunk.pos.maxBlockZ + 1).toDouble(),
        )
        val data = PillagerWorldData.get(level.server)
        val campaigns = level.getEntitiesOfClass(Mob::class.java, box) { it.persistentData.hasUUID(PillagerRuntime.CAMPAIGN_TAG) }
            .map { it.persistentData.getUUID(PillagerRuntime.CAMPAIGN_TAG) }.distinct().mapNotNull(data.campaigns::get)
        campaigns.forEach { campaign ->
            if (PillagerRuntime.snapshotCampaign(level, campaign) > 0) {
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        0L,
                    snapshots = listOf(WarbandCoreAdapter.snapshotResult(campaign)),
                    ),
                    level.server,
                )
                WarbandCoreAdapter.synchronizeNativeViews(data)
            }
        }
        if (campaigns.isNotEmpty()) data.markChanged()
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingTickEvent) {
        val mob = event.entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        PillagerRuntime.holdBossAtAnchor(mob)
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
    fun onEntityLeaveLevel(event: EntityLeaveLevelEvent) {
        val mob = event.entity as? Mob ?: return
        if (!mob.persistentData.getBoolean(RESPAWN_PURGE_TAG)) return
        val level = event.level as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        val tag = mob.persistentData
        PillagerRuntime.forgetLiveMob(mob)

        if (tag.hasUUID(PillagerRuntime.CAMPAIGN_TAG)) {
            data.campaigns[tag.getUUID(PillagerRuntime.CAMPAIGN_TAG)]?.let { campaign ->
                PillagerCampaignCoordinator.pauseCampaignsForPlayer(data, campaign.targetPlayerId)
            }
        }

        if (tag.getBoolean(PillagerRuntime.BOSS_TAG) && tag.hasUUID(PillagerRuntime.OFFICER_TAG)) {
            val officer = data.officers[tag.getUUID(PillagerRuntime.OFFICER_TAG)]
            val warband = officer?.let { data.warbands[it.homeWarbandId] }
            if (warband != null) {
                warband.warlordEntityId = null
                // The purge is part of respawn protection. Keep a purged warlord
                // dormant for that same window instead of rematerializing it on
                // the next presence retry.
                warband.lastPresenceAttemptTick = level.gameTime + PillagerCampaignCoordinator.PLAYER_RESPAWN_PROTECTION_TICKS
                warband.rallyPresence?.state = com.gerald.pillagercampaigns.data.RallyPresenceState.DORMANT
                warband.rallyPresence?.entityId = null
                data.factions[warband.factionId]?.bossEntityId = null
            }
        }
        data.markChanged()
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        val data = PillagerWorldData.get(level.server)
        (event.entity as? Player)?.let { player ->
            WarbandCoreAdapter.observePlayerLifecycle(data, player.uuid, com.gerald.warband.core.PlayerLifecycleKind.DIED)
        }
        val killerMob = event.source.entity as? Mob
        if (event.entity is Player && killerMob != null) {
            val killerTag = killerMob.persistentData
            if (killerTag.hasUUID(PillagerRuntime.CAMPAIGN_TAG)) {
                val campaign = data.campaigns[killerTag.getUUID(PillagerRuntime.CAMPAIGN_TAG)]
                val warband = campaign?.let { data.warbands[it.originWarbandId] }
                if (campaign != null && warband != null) {
                    PillagerRuntime.placeFactionDeathBanner(level, event.entity.blockPosition(), warband.bannerSeed)
                    PillagerCampaignCoordinator.abortCampaignAfterPlayerKill(data, campaign.id)
                    data.markChanged()
                }
            }
            return
        }

        val mob = event.entity as? Mob ?: return
        val killerPlayer = event.source.entity as? Player
        val tag = mob.persistentData
        PillagerRuntime.forgetLiveMob(mob)
        tag.getString(PillagerRuntime.GARRISON_ID_TAG).takeIf(String::isNotBlank)?.let { garrisonId ->
            val snapshot = data.snapshot()
            val garrison = snapshot.garrisons[garrisonId]
            if (garrison != null) {
                val survivors = garrison.members.mapNotNull { member ->
                    val entityId = data.minecraftSidecar.entityIds[member.id] ?: return@mapNotNull null
                    val survivor = level.getEntity(entityId) as? Mob ?: return@mapNotNull null
                    if (!survivor.isAlive || survivor.uuid == mob.uuid) return@mapNotNull null
                    val equipment = member.equipment?.let { manifest ->
                        val stack = net.minecraft.world.entity.EquipmentSlot.values().asSequence()
                            .map(survivor::getItemBySlot).firstOrNull { !it.isEmpty }
                        val durability = stack?.takeIf { it.isDamageableItem }
                            ?.let { 1.0 - it.damageValue.toDouble() / it.maxDamage.coerceAtLeast(1) } ?: manifest.durabilityFraction
                        manifest.copy(durabilityFraction = durability.coerceIn(0.0, 1.0))
                    }
                    com.gerald.warband.core.MemberSnapshot(
                        member.id,
                        (survivor.health / survivor.maxHealth.coerceAtLeast(1.0f)).toDouble(),
                        member.experience,
                        equipment,
                        member.cargo,
                    )
                }
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        elapsedTicks = 0L,
                        garrisonSnapshots = listOf(com.gerald.warband.core.GarrisonSnapshotResult(garrisonId, survivors)),
                    ),
                    level.server,
                )
                WarbandCoreAdapter.synchronizeNativeViews(data)
            }
        }
        if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) {
            val officer = data.officers[tag.getUUID(PillagerRuntime.OFFICER_TAG)]
            val warband = officer?.let { data.warbands[it.homeWarbandId] }
            if (warband != null) {
                warband.lastIntelTick = level.gameTime
                data.markChanged()
                if (tag.getBoolean(PillagerRuntime.LEADER_TAG) || tag.getBoolean(PillagerRuntime.BOSS_TAG)) {
                    val rally = warband.rallyBlockPos(level.seaLevel + 1)
                    val map = createBaseIntelMap(level, rally.x, rally.z)
                    map.hoverName = Component.literal("Captain Orders: Warband Rally")
                    mob.spawnAtLocation(map)
                }
            }
        }

        if (tag.getBoolean(PillagerRuntime.BOSS_TAG) && tag.hasUUID(PillagerRuntime.WARBAND_TAG)) {
            val warbandId = tag.getUUID(PillagerRuntime.WARBAND_TAG)
            val memberId = tag.getString(PillagerRuntime.MANIFEST_ID_TAG)
            if (memberId.isNotBlank()) {
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        elapsedTicks = 0L,
                        warlordDefeats = listOf(com.gerald.warband.core.WarlordDefeatObservation(
                            warbandId.toString(), memberId, killerPlayer?.uuid?.toString(),
                        )),
                    ),
                    level.server,
                )
                WarbandCoreAdapter.synchronizeNativeViews(data)
                PillagerCampaignsMod.LOGGER.info("Warband {} defeated after warlord death", warbandId)
            }
            return
        }
        if (!tag.hasUUID(PillagerRuntime.CAMPAIGN_TAG)) return
        val campaign = data.campaigns[tag.getUUID(PillagerRuntime.CAMPAIGN_TAG)] ?: return
        val manifestId = tag.getString(PillagerRuntime.MANIFEST_ID_TAG)
        if (killerPlayer != null && manifestId.isNotBlank()) {
            val authority = when {
                tag.getBoolean(PillagerRuntime.BOSS_TAG) -> 3.0
                tag.getBoolean(PillagerRuntime.LEADER_TAG) -> 1.0
                else -> 0.0
            }
            val rewardTransition = WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    elapsedTicks = 0L,
                    defeats = listOf(com.gerald.warband.core.DefeatObservation(
                        campaign.id.toString(), manifestId, killerPlayer.uuid.toString(), authority,
                    )),
                ),
                level.server,
            )
            val rewards = rewardTransition.effects.filter {
                it.kind == com.gerald.warband.core.EffectKind.REWARD_PLAYER && manifestId in it.memberIds
            }
            val rewardResults = rewards.map { effect ->
                val item = effect.itemId?.let(net.minecraft.resources.ResourceLocation::tryParse)
                    ?.let(net.minecraftforge.registries.ForgeRegistries.ITEMS::getValue)
                val realized = if (item != null && effect.count > 0) {
                    mob.spawnAtLocation(net.minecraft.world.item.ItemStack(item, effect.count))
                    true
                } else false
                com.gerald.warband.core.EffectAcknowledgement(
                    effect.effectId, realized, if (realized) "reward_realized" else "reward_item_unavailable",
                )
            }
            if (rewards.isNotEmpty()) {
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        elapsedTicks = 0L,
                        acknowledgements = rewardResults,
                    ),
                    level.server,
                )
            }
        }
        PillagerRuntime.dropCampaignCargo(mob, campaign)
        val casualtyTransition = manifestId.takeIf(String::isNotBlank)?.let { casualtyId ->
            WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    elapsedTicks = 0L,
                    combat = listOf(com.gerald.warband.core.CombatObservation(
                        campaign.id.toString(), 0.0, 0.0, 0.0, 0.6, 0.7,
                        casualties = setOf(casualtyId), applyHealthDamage = false,
                    )),
                ),
                level.server,
            )
        }
        if (tag.getBoolean(PillagerRuntime.LEADER_TAG)) {
            val selection = casualtyTransition?.effects?.firstOrNull {
                it.kind == com.gerald.warband.core.EffectKind.PROMOTE_SUCCESSOR && it.campaignId == campaign.id.toString()
            }
            val successorId = selection?.memberIds?.singleOrNull()
            val promoted = successorId?.let {
                PillagerRuntime.promoteSuccessor(level, campaign.id, campaign.officerId, it)
            } == true
            if (selection != null) {
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        elapsedTicks = 0L,
                        acknowledgements = listOf(com.gerald.warband.core.EffectAcknowledgement(
                            selection.effectId, promoted,
                            if (promoted) "successor_promoted" else "successor_not_physical",
                        )),
                    ),
                    level.server,
                )
            }
        }
    }

    @SubscribeEvent
    fun onLivingHurt(event: LivingHurtEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        val attacker = event.source.entity as? Mob
        val victim = event.entity
        val attackingPlayer = event.source.entity as? Player
        if (attackingPlayer != null && victim is Mob && victim.persistentData.hasUUID(PillagerRuntime.OFFICER_TAG)) {
            val data = PillagerWorldData.get(level.server)
            val snapshot = data.snapshot()
            snapshot.officers[victim.persistentData.getUUID(PillagerRuntime.OFFICER_TAG).toString()]?.let { officer ->
                val rally = snapshot.warbands[officer.homeWarbandId]?.rally ?: return@let
                val dx = (attackingPlayer.chunkPosition().x - rally.x).toDouble()
                val dz = (attackingPlayer.chunkPosition().z - rally.z).toDouble()
                WarbandCoreAdapter.transition(
                    data,
                    com.gerald.warband.core.CoreFrame(
                        elapsedTicks = 0L,
                        territoryContacts = listOf(com.gerald.warband.core.TerritoryContactObservation(
                            officer.homeWarbandId,
                            attackingPlayer.uuid.toString(),
                            kotlin.math.sqrt(dx * dx + dz * dz),
                            attacked = true,
                        )),
                    ),
                    level.server,
                )
                WarbandCoreAdapter.synchronizeNativeViews(data)
            }
        }
        val campaignId = when {
            attacker?.persistentData?.hasUUID(PillagerRuntime.CAMPAIGN_TAG) == true -> attacker.persistentData.getUUID(PillagerRuntime.CAMPAIGN_TAG)
            victim is Mob && victim.persistentData.hasUUID(PillagerRuntime.CAMPAIGN_TAG) -> victim.persistentData.getUUID(PillagerRuntime.CAMPAIGN_TAG)
            else -> null
        } ?: return
        val campaign = PillagerWorldData.get(level.server).campaigns[campaignId] ?: return
        val playerId = when {
            victim is Player -> victim.uuid
            event.source.entity is Player -> event.source.entity!!.uuid
            else -> null
        }
        if (playerId == campaign.targetPlayerId) {
            val campaignDealtDamage = attacker?.persistentData?.hasUUID(PillagerRuntime.CAMPAIGN_TAG) == true
            val distance = event.source.entity?.distanceTo(victim)?.toDouble() ?: 0.0
            val data = PillagerWorldData.get(level.server)
            WarbandCoreAdapter.transition(
                data,
                com.gerald.warband.core.CoreFrame(
                    elapsedTicks = 0L,
                    combat = listOf(com.gerald.warband.core.CombatObservation(
                        campaign.id.toString(),
                        if (campaignDealtDamage) event.amount.toDouble() else 0.0,
                        if (campaignDealtDamage) 0.0 else event.amount.toDouble(),
                        distance, 0.6, 0.7,
                        applyHealthDamage = false,
                    )),
                ),
                level.server,
            )
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
    @Suppress("UNUSED_PARAMETER")
    fun onCommand(event: CommandEvent) {
        return
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            LiteralArgumentBuilder.literal<CommandSourceStack>("pillagercampaigns")
                .requires { it.hasPermission(2) }
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("status").executes { status(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("tick_once").executes { tickOnce(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("warbands").then(LiteralArgumentBuilder.literal<CommandSourceStack>("list").executes { listWarbands(it.source) }))
                .then(
                    LiteralArgumentBuilder.literal<CommandSourceStack>("warbands")
                        .then(
                            LiteralArgumentBuilder.literal<CommandSourceStack>("materialize_warlord")
                                .then(RequiredArgumentBuilder.argument<CommandSourceStack, String>("warband", StringArgumentType.word()).executes { forceMaterializeWarlord(it.source, StringArgumentType.getString(it, "warband")) }),
                        ),
                )
                .then(
                    LiteralArgumentBuilder.literal<CommandSourceStack>("list")
                        .then(
                            LiteralArgumentBuilder.literal<CommandSourceStack>("campaigns")
                                .executes { listCampaigns(it.source) }
                                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("closed").executes { listClosedCampaigns(it.source) }),
                        ),
                )
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("list").then(LiteralArgumentBuilder.literal<CommandSourceStack>("officers").executes { listOfficers(it.source) }))
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("list").then(LiteralArgumentBuilder.literal<CommandSourceStack>("captains").executes { listOfficers(it.source) }))
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("validate_opening_progression").executes { validateOpeningProgression(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("export_runtime_spec").executes { exportRuntimeSpec(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("reset").executes { reset(it.source) }),
        )
        dispatcher.register(
            LiteralArgumentBuilder.literal<CommandSourceStack>("sam")
                .requires { it.hasPermission(2) }
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("status").executes { status(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("validate_opening_progression").executes { validateOpeningProgression(it.source) })
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("warbands").then(LiteralArgumentBuilder.literal<CommandSourceStack>("list").executes { listWarbands(it.source) }))
                .then(
                    LiteralArgumentBuilder.literal<CommandSourceStack>("warbands")
                        .then(
                            LiteralArgumentBuilder.literal<CommandSourceStack>("materialize_warlord")
                                .then(RequiredArgumentBuilder.argument<CommandSourceStack, String>("warband", StringArgumentType.word()).executes { forceMaterializeWarlord(it.source, StringArgumentType.getString(it, "warband")) }),
                        ),
                )
                .then(LiteralArgumentBuilder.literal<CommandSourceStack>("movements").then(LiteralArgumentBuilder.literal<CommandSourceStack>("list").executes { listCampaigns(it.source) })),
        )
    }

    private fun exportRuntimeSpec(source: CommandSourceStack): Int {
        return try {
            val spec = WarbandCoreAdapter.runtimeSpec(source.server)
            spec.requireValidRevision()
            require(spec.recruits.size >= 2) { "runtime specification needs at least two recruits for MVP play testing" }
            val channels = listOf<(com.gerald.warband.core.ResourceDefinition) -> Double>(
                { it.unitsPerItem.sustenance }, { it.unitsPerItem.munitions },
                { it.unitsPerItem.maintenance }, { it.unitsPerItem.recovery },
            )
            require(channels.all { channel -> spec.resources.any { channel(it) > 0.0 } }) {
                "runtime specification must cover sustenance, munitions, maintenance, and recovery"
            }
            require(spec.materials.isNotEmpty() && spec.equipmentPlatforms.any { platform ->
                platform.components.isNotEmpty() && platform.components.all { it.compatibleMaterialIds.isNotEmpty() }
            }) { "runtime specification needs compatible material-backed equipment platforms" }
            require(spec.rewards.any { it.value > 0.0 }) { "runtime specification needs a positive reward denomination" }

            val directory = source.server.getWorldPath(LevelResource.ROOT).resolve("pillagercampaigns/exports")
            Files.createDirectories(directory)
            val destination = directory.resolve("warband-runtime-spec.json")
            val temporary = directory.resolve("warband-runtime-spec.json.tmp")
            Files.writeString(temporary, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(spec))
            runCatching {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            source.sendSuccess({ Component.literal("Exported ${spec.revision} to $destination") }, true)
            Command.SINGLE_SUCCESS
        } catch (t: Throwable) {
            source.sendFailure(Component.literal("Runtime specification export failed: ${t.message ?: t.javaClass.simpleName}"))
            0
        }
    }

    private fun validateOpeningProgression(source: CommandSourceStack): Int {
        return try {
            OpeningProgressionRuntimeValidation.validate(source.server.overworld())
            PillagerCampaignsMod.LOGGER.info("OPENING_PROGRESSION_VALIDATION PASS")
            source.sendSuccess({ Component.literal("Opening progression runtime validation passed") }, true)
            Command.SINGLE_SUCCESS
        } catch (t: Throwable) {
            PillagerCampaignsMod.LOGGER.error("OPENING_PROGRESSION_VALIDATION FAIL: {}", t.message ?: t.javaClass.simpleName, t)
            source.sendFailure(Component.literal("Opening progression runtime validation failed: ${t.message ?: t.javaClass.simpleName}"))
            0
        }
    }

    private fun status(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        source.sendSuccess({
            Component.literal(
                "enabled=${PillagerCampaignsConfig.enabled.get()} factions=${data.factions.size} captains=${data.officers.count { it.value.role == OfficerRole.CAPTAIN }} warlords=${data.officers.count { it.value.role == OfficerRole.WARLORD }} warbands=${data.warbands.size} campaigns=${data.campaigns.values.count { it.state != CampaignState.RESOLVED }}"
            )
                .append(" ")
                .append(Component.literal(PillagerWarbandPresenceSystem.statusLine(data)))
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun tickOnce(source: CommandSourceStack): Int {
        val server = source.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        PillagerCampaignCoordinator.tick(server, data, now)
        data.markChanged()
        source.sendSuccess({ Component.literal("Campaign Core ticked once") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun listWarbands(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val activeCampaigns = data.campaigns.values
            .filter { it.state != CampaignState.RESOLVED }
            .groupingBy { it.originWarbandId }
            .eachCount()
        source.sendSuccess({ Component.literal("Warbands (${data.warbands.size})") }, false)
        data.warbands.values.forEach { warband ->
            val rally = warband.rallyBlockPos(source.level.seaLevel + 1)
            val cooldown = (warband.cooldownUntilTick - source.level.gameTime).coerceAtLeast(0L)
            val warlordState = when {
                warband.defeated -> "defeated"
                warband.rallyPresence?.state != null -> warband.rallyPresence!!.state.name.lowercase()
                warband.warlordEntityId != null -> "live_or_cached"
                else -> "unseen"
            }
            source.sendSuccess({
                Component.literal(
                    "  ${warband.id.toString().take(8)} dim=${warband.dimension} rally_chunk=${warband.rallyChunkX},${warband.rallyChunkZ} rally_xyz=${rally.x},${rally.y},${rally.z} reserve=${warband.reserve}/${warband.capacity} raid_pool=${"%.1f".format(warband.raidPool)} materials=${"%.1f".format(warband.materialLedger.values.sum())} learned=${warband.empiricalThreat.size} aggression=${warband.aggression} cooldown_ticks=$cooldown active=${activeCampaigns[warband.id] ?: 0}/${warband.activeCampaignLimit} warlord=$warlordState last_failure=${warband.lastPresenceFailure.name.lowercase()}"
                ).append(" ").append(tpLink(warband.dimension.toString(), rally.x, rally.y, rally.z))
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun forceMaterializeWarlord(source: CommandSourceStack, warbandPrefix: String): Int {
        val data = PillagerWorldData.get(source.server)
        val matches = data.warbands.values.filter { it.id.toString().startsWith(warbandPrefix, ignoreCase = true) }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No warband matches prefix '$warbandPrefix'"))
            return 0
        }
        if (matches.size > 1) {
            source.sendFailure(Component.literal("Ambiguous warband prefix '$warbandPrefix' (${matches.size} matches)"))
            return 0
        }
        val warband = matches.first()
        val level = source.server.allLevels.firstOrNull { it.dimension().location() == warband.dimension }
        if (level == null) {
            source.sendFailure(Component.literal("Warband dimension is not loaded"))
            return 0
        }
        val result = PillagerWarbandPresenceSystem.materializeWarlord(level, data, warband, level.gameTime, force = true)
        data.markChanged()
        source.sendSuccess({ Component.literal("Warlord materialization result=${result.name.lowercase()}") }, true)
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
                    val ticksPerChunk = 120
                    val remainingTicks = (remainingChunks * ticksPerChunk - campaign.tickDebt).coerceAtLeast(0)
                    formatEta(remainingTicks)
                }
                CampaignState.READY_TO_MATERIALIZE -> "ready"
                CampaignState.MATERIALIZING -> "materializing"
                CampaignState.ACTIVE -> "active"
                CampaignState.PAUSED -> "paused"
                CampaignState.RETURNING -> "returning"
                CampaignState.RESOLVED -> "resolved"
            }
            source.sendSuccess({
                val currentBlockX = campaign.currentChunkX shl 4
                val currentBlockZ = campaign.currentChunkZ shl 4
                val targetBlockX = campaign.targetChunkX shl 4
                val targetBlockZ = campaign.targetChunkZ shl 4
                val y = source.position.y.toInt().coerceAtLeast(64)
                val dim = source.level.dimension().location().toString()
                val captain = data.officers[campaign.officerId]
                val history = captain?.nemesisHistory?.lastOrNull()?.type?.name?.lowercase() ?: "none"
                Component.literal(
                    "  ${campaign.id.toString().take(8)} captain=${campaign.officerId.toString().take(8)} state=${campaign.state.name.lowercase()} chunk=${campaign.currentChunkX},${campaign.currentChunkZ} current_xz=$currentBlockX,$currentBlockZ target_chunk=${campaign.targetChunkX},${campaign.targetChunkZ} target_xz=$targetBlockX,$targetBlockZ eta=$eta last_history=$history"
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
        source.sendSuccess({ Component.literal("Captains And Warlords (${data.officers.size})") }, false)
        data.officers.values.forEach { officer ->
            val homeWarband = data.warbands[officer.homeWarbandId]
            val homePos = homeWarband?.rallyBlockPos(source.level.seaLevel + 1)?.let { pos -> "${pos.x},${pos.y},${pos.z}" } ?: "unknown"
            val recentHistory = officer.nemesisHistory.takeLast(2).joinToString(",") { it.type.name.lowercase() }.ifBlank { "none" }
            source.sendSuccess({
                val line = Component.literal(
                    "  ${officer.id.toString().take(8)} role=${officer.role.name.lowercase()} ${officer.name} ${officer.title} rank=${officer.rank.name.lowercase()} state=${officer.state.name.lowercase()} preferences=${officer.preferenceGraph.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key}=${"%.2f".format(it.value)}" }} victories=${officer.campaignVictories} defeats=${officer.campaignDefeats} grudge=${officer.lastTargetPlayerId?.toString()?.take(8) ?: "none"} recent=$recentHistory home_rally_xyz=$homePos"
                )
                if (homeWarband != null) {
                    val rally = homeWarband.rallyBlockPos(source.level.seaLevel + 1)
                    line.append(" ").append(tpLink(homeWarband.dimension.toString(), rally.x, rally.y, rally.z))
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

    internal fun formatWarbandLine(warband: com.gerald.pillagercampaigns.data.PillagerWarband): String {
        val rally = warband.rallyBlockPos()
        return "  ${warband.id.toString().take(8)} dim=${warband.dimension} rally_chunk=${warband.rallyChunkX},${warband.rallyChunkZ} rally_xyz=${rally.x},${rally.y},${rally.z} reserve=${warband.reserve}/${warband.capacity} raid_pool=${"%.1f".format(warband.raidPool)} materials=${"%.1f".format(warband.materialLedger.values.sum())} learned=${warband.empiricalThreat.size} aggression=${warband.aggression} warlord=${warband.warlordOfficerId.toString().take(8)} rally_presence=${warband.rallyPresence?.state?.name?.lowercase() ?: "unknown"} failure=${warband.lastPresenceFailure.name.lowercase()}"
    }

    private fun reset(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        WarbandCoreAdapter.resetWorld(data)
        PillagerDiscoveryCoordinator.reset()
        data.markChanged()
        source.sendSuccess({ Component.literal("Pillager Campaigns state reset") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun ensureWarlordPresenceSlice(levels: List<ServerLevel>, data: PillagerWorldData, now: Long) {
        data.warbands.values
            .asSequence()
            .filter { !it.defeated }
            .take(16)
            .forEach { warband ->
                val level = levels.firstOrNull { it.dimension().location() == warband.dimension } ?: return@forEach
                if (level.hasChunk(warband.rallyChunkX, warband.rallyChunkZ)) {
                    PillagerWarbandPresenceSystem.materializeWarlord(level, data, warband, now)
                }
            }
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
        PillagerCampaignsMod.LOGGER.debug(
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
    @SubscribeEvent
    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(com.gerald.pillagercampaigns.system.WarbandFormulaData)
    }
