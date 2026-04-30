package com.gerald.pillagerpressure

import com.gerald.pillagerpressure.data.*
import com.gerald.pillagerpressure.system.PillagerBaseService
import com.gerald.pillagerpressure.system.PillagerCampaignDirector
import com.gerald.pillagerpressure.system.OfficerAffixRules
import com.gerald.pillagerpressure.system.OfficerGeneRules
import com.gerald.pillagerpressure.system.OfficerOutcomeRules
import com.gerald.pillagerpressure.system.PillagerRuntime
import com.gerald.pillagerpressure.util.OfficerOrdersRules
import com.gerald.pillagerpressure.util.PillagerIdentity
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.AbstractIllager
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.entity.living.MobSpawnEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

object PillagerPressureEvents {
    private var ticks: Long = 0L
    private var attempts: Long = 0L
    private var groupsSpawned: Long = 0L
    private var mobsSpawned: Long = 0L
    private var lastStatus: String = "loaded"

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerPressureConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
            PillagerPressureMod.LOGGER.info("Disabled vanilla patrol spawning; Pillager Pressure owns patrol scheduling")
        }
        PillagerWorldData.get(event.server)
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        val chunk = event.chunk as? LevelChunk ?: return
        val data = PillagerWorldData.get(level.server)
        val added = PillagerBaseService.scanChunk(level, chunk, data)
        if (added > 0) PillagerPressureMod.LOGGER.info("Registered {} pillager base(s) from chunk {},{}", added, chunk.pos.x, chunk.pos.z)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onFinalizeSpawn(event: MobSpawnEvent.FinalizeSpawn) {
        if (!PillagerPressureConfig.replaceNaturalOutpostSpawns.get()) return
        if (event.spawnType != MobSpawnType.NATURAL) return
        val level = event.level as? ServerLevel ?: return
        val mob = event.entity
        if (mob !is AbstractIllager) return
        val data = PillagerWorldData.get(level.server)
        val pos = BlockPos.containing(event.x, event.y, event.z)
        val base = PillagerBaseService.baseAt(level, data, pos) ?: return
        if (base.manpower <= 0) {
            event.setSpawnCancelled(true)
            return
        }
        event.setSpawnCancelled(true)
        val faction = data.factions[base.factionId]
        val officer = PillagerBaseService.officerForBase(data, base)
        val spend = 4
        base.manpower = (base.manpower - spend).coerceAtLeast(0)
        PillagerRuntime.spawnSquad(level, data, pos, null, base, faction, null, officer, 2, 1, leader = true)
        data.markChanged()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !PillagerPressureConfig.enabled.get()) return
        ticks++
        val server = event.server
        val data = PillagerWorldData.get(server)
        val now = server.overworld().gameTime
        if (PillagerPressureConfig.campaignEnabled.get() && now - data.lastCampaignTick >= PillagerPressureConfig.campaignTickInterval.get()) {
            data.lastCampaignTick = now
            val materialized = PillagerCampaignDirector.tick(server, data)
            server.allLevels.forEach { level -> PillagerRuntime.cleanupEngineeredBlocks(level, data) }
            if (materialized > 0) {
                groupsSpawned += materialized.toLong()
                lastStatus = "campaign materialized groups=$materialized"
            }
            data.markChanged()
        }
        val interval = PillagerPressureConfig.intervalTicks.get().toLong().coerceAtLeast(20L)
        if (ticks % interval == 0L) runAttempt(server, force = false, source = "fallback")
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingTickEvent) {
        val mob = event.entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        if (!mob.isAlive) return
        if (level.gameTime % 10L != 0L) return
        val tag = mob.persistentData
        PillagerRuntime.pullFollowerTowardOfficer(level, mob)
        if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) {
            PillagerRuntime.tryOfficerEngineering(level, PillagerWorldData.get(level.server), mob)
        }
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val killer = event.source.entity as? Mob ?: return
        val tag = killer.persistentData
        if (!tag.hasUUID(PillagerRuntime.FACTION_TAG)) return
        val level = player.serverLevel()
        val data = PillagerWorldData.get(level.server)
        val factionId = tag.getUUID(PillagerRuntime.FACTION_TAG)
        val officerId = if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) tag.getUUID(PillagerRuntime.OFFICER_TAG) else null
        officerId?.let { id ->
            data.officers[id]?.let { officer ->
                officer.killedPlayers += 1
                officer.victories += 1
                officer.grudges[player.uuid] = (officer.grudges[player.uuid] ?: 0) + 3
                officer.title = "the Grave-Marker"
            }
        }
        val requested = PillagerPressureConfig.deathFlagsPerKill.get()
        val placed = data.factions[factionId]?.let { faction ->
            PillagerRuntime.placeFactionFlags(level, faction, player.blockPosition(), requested)
        } ?: 0
        val remaining = (requested - placed).coerceAtLeast(0)
        if (remaining > 0) data.pendingMarkers.add(PendingFlagMarker(factionId, officerId, level.dimension().location(), player.blockPosition(), level.gameTime, 0, remaining))
        data.markChanged()
    }

    @SubscribeEvent
    fun onLivingDrops(event: LivingDropsEvent) {
        val mob = event.entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        val tag = mob.persistentData
        if (tag.getBoolean("PillagerPressureDropsDone")) return
        val data = PillagerWorldData.get(level.server)
        val officerId = if (tag.hasUUID(PillagerRuntime.OFFICER_TAG)) tag.getUUID(PillagerRuntime.OFFICER_TAG) else null
        val baseId = if (tag.hasUUID(PillagerRuntime.BASE_TAG)) tag.getUUID(PillagerRuntime.BASE_TAG) else null
        val factionId = if (tag.hasUUID(PillagerRuntime.FACTION_TAG)) tag.getUUID(PillagerRuntime.FACTION_TAG) else null
        val base = baseId?.let { data.bases[it] } ?: factionId?.let { f -> data.bases.values.firstOrNull { it.factionId == f } }
        val faction = base?.let { data.factions[it.factionId] } ?: factionId?.let { data.factions[it] }
        if (officerId != null) data.officers[officerId]?.let { officer ->
            officer.defeats += 1
            val outcomes = OfficerOutcomeRules.outcomesFor(officer)
            faction?.let { OfficerGeneRules.recordOutcome(it.warMemory, officer.genes, outcomes) }
            officer.affixes.addAll(OfficerAffixRules.affixesFor(officer.genes, officer.rank, outcomes))
            officer.state = OfficerState.DEAD
        }
        if (base != null && faction != null) {
            event.drops.add(ItemEntity(level, mob.x, mob.y, mob.z, PillagerRuntime.baseMap(level, base, faction)))
            val officer = officerId?.let { data.officers[it] }
            val campaign = officerId?.let { id -> data.campaigns.values.firstOrNull { it.officerId == id } }
            val orders = OfficerOrdersRules.generate(faction, base, officer, campaign)
            event.drops.add(ItemEntity(level, mob.x, mob.y, mob.z, PillagerIdentity.ordersPaper(orders.title, orders.loreLines)))
        }
        tag.putBoolean("PillagerPressureDropsDone", true)
        data.markChanged()
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher, "pillagerpressure")
        register(event.dispatcher, "ppatrol")
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>, name: String) {
        dispatcher.register(
            Commands.literal(name).requires { it.hasPermission(2) }
                .then(Commands.literal("status").executes { context -> context.source.sendSuccess({ Component.literal(statusLine(context.source.server)) }, false); Command.SINGLE_SUCCESS })
                .then(Commands.literal("now").executes { context ->
                    val spawned = runAttempt(context.source.server, force = true, source = "command", commandPlayer = context.source.player)
                    context.source.sendSuccess({ Component.literal("Pillager Pressure forced attempt spawned_groups=$spawned status=$lastStatus") }, true)
                    Command.SINGLE_SUCCESS
                })
                .then(Commands.literal("tick_once").executes { context -> tickCampaignOnce(context.source) })
                .then(
                    Commands.literal("base")
                        .then(Commands.literal("list").executes { context -> listBases(context.source) })
                        .then(Commands.literal("add_here").executes { context -> addBaseHere(context.source) })
                        .then(Commands.literal("rescan_here").executes { context -> rescanHere(context.source) })
                        .then(Commands.literal("econ").executes { context -> listEconomy(context.source) }.then(Commands.literal("tick").executes { context -> tickEconomy(context.source) }))
                )
                .then(Commands.literal("faction").then(Commands.literal("list").executes { context -> listFactions(context.source) }))
                .then(Commands.literal("campaign").then(Commands.literal("list").executes { context -> listCampaigns(context.source) }))
                .then(Commands.literal("reset").executes { context -> resetData(context.source) })
        )
    }

    private fun runAttempt(server: MinecraftServer, force: Boolean, source: String, commandPlayer: ServerPlayer? = null): Int {
        attempts++
        var spawnedGroups = 0
        val data = PillagerWorldData.get(server)
        val players = if (force && commandPlayer != null) listOf(commandPlayer) else server.playerList.players
        for (player in players) {
            if (!force && !PillagerRuntime.eligible(player)) { lastStatus = "no eligible player: ${player.gameProfile.name}"; continue }
            if (force && PillagerPressureConfig.overworldOnly.get() && player.serverLevel().dimension() != Level.OVERWORLD) { lastStatus = "forced player not in overworld: ${player.gameProfile.name}"; continue }
            if (!force && player.random.nextDouble() > PillagerPressureConfig.spawnChance.get()) { lastStatus = "skipped chance for ${player.gameProfile.name}"; continue }
            if (spawnFallbackPatrolFor(data, player, force)) spawnedGroups++
        }
        if (spawnedGroups == 0 && players.isEmpty()) lastStatus = "no players online"
        PillagerPressureMod.LOGGER.info("Pillager Pressure attempt source={} force={} players={} spawned_groups={} status={}", source, force, players.size, spawnedGroups, lastStatus)
        return spawnedGroups
    }

    private fun spawnFallbackPatrolFor(data: PillagerWorldData, player: ServerPlayer, force: Boolean): Boolean {
        val level = player.serverLevel()
        val active = PillagerRuntime.countActivePatrolMobs(level, player.blockPosition())
        if (!force && active >= PillagerPressureConfig.maxActiveNearPlayer.get()) { lastStatus = "active cap near ${player.gameProfile.name}: $active"; return false }
        val pos = (if (force) PillagerRuntime.chooseForcedSpawnPos(level, player.blockPosition()) else PillagerRuntime.chooseSpawnPos(level, player.blockPosition()))
            ?: run { lastStatus = "no valid loaded spawn surface near ${player.gameProfile.name}"; return false }
        val base = PillagerBaseService.nearestActiveBase(level, data, player.blockPosition())
        val faction = base?.let { data.factions[it.factionId] }
        val officer = base?.let { PillagerBaseService.officerForBase(data, it) }
        val spawned = PillagerRuntime.spawnSquad(level, data, pos, player, base, faction, null, officer, PillagerPressureConfig.maxPillagers.get(), PillagerPressureConfig.specialAmount.get(), leader = PillagerPressureConfig.spawnLeader.get())
        if (spawned <= 0) { lastStatus = "all entity spawns failed near ${player.gameProfile.name}"; return false }
        groupsSpawned++; mobsSpawned += spawned.toLong(); lastStatus = "spawned $spawned near ${player.gameProfile.name} at ${pos.x} ${pos.y} ${pos.z} active_before=$active"; return true
    }

    private fun statusLine(server: MinecraftServer): String {
        val data = PillagerWorldData.get(server)
        return "enabled=${PillagerPressureConfig.enabled.get()} ticks=$ticks attempts=$attempts groups=$groupsSpawned mobs=$mobsSpawned factions=${data.factions.size} bases=${data.bases.size} campaigns=${data.campaigns.size} officers=${data.officers.size} last=$lastStatus"
    }

    private fun listBases(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val summary = data.bases.values.joinToString { "${it.type}:${it.state}@${it.center.x},${it.center.z}" }
        source.sendSuccess({ Component.literal("Bases: $summary") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun addBaseHere(source: CommandSourceStack): Int {
        val player = source.playerOrException
        val data = PillagerWorldData.get(source.server)
        val faction = PillagerBaseService.factionForNewMajorBase(data, player.level().random.nextLong())
        val base = PillagerBase(UUID.randomUUID(), faction.id, null, BaseType.MAJOR, player.serverLevel().dimension().location(), null, player.blockPosition(), ChunkRef.of(player.blockPosition()), null, BaseState.ACTIVE, 72, 140, 80, 20, 100, 80, player.serverLevel().gameTime)
        data.bases[base.id] = base
        PillagerBaseService.officerForBase(data, base)
        data.markChanged()
        source.sendSuccess({ Component.literal("Added pillager base ${base.id} for ${faction.name}") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun rescanHere(source: CommandSourceStack): Int {
        val player = source.playerOrException
        val level = player.serverLevel()
        val data = PillagerWorldData.get(source.server)
        val chunk = level.getChunk(player.blockPosition()) as LevelChunk
        val added = PillagerBaseService.scanChunk(level, chunk, data)
        source.sendSuccess({ Component.literal("Rescanned chunk ${chunk.pos.x},${chunk.pos.z}; added_bases=$added") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun listEconomy(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val summary = data.bases.values.joinToString { "${it.type}@${it.center.x},${it.center.z} manpower=${it.manpower}/${it.maxManpower()} supplies=${it.supplies}/${it.maxSupplies()} morale=${it.morale}" }
        source.sendSuccess({ Component.literal("Base economy: $summary") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun tickEconomy(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        PillagerBaseService.tickEconomy(data)
        source.sendSuccess({ Component.literal("Advanced pillager base economy once") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun tickCampaignOnce(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val materialized = PillagerCampaignDirector.tick(source.server, data)
        source.sendSuccess({ Component.literal("Advanced pillager campaign director once; materialized_groups=$materialized") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun listFactions(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val summary = data.factions.values.joinToString { it.name }
        source.sendSuccess({ Component.literal("Factions: $summary") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun listCampaigns(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        val summary = data.campaigns.values.joinToString { "${it.state}@${it.current.x},${it.current.z}->${it.target.x},${it.target.z}" }
        source.sendSuccess({ Component.literal("Campaigns: $summary") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun resetData(source: CommandSourceStack): Int {
        val data = PillagerWorldData.get(source.server)
        data.factions.clear()
        data.bases.clear()
        data.campaigns.clear()
        data.officers.clear()
        data.pendingMarkers.clear()
        data.markChanged()
        source.sendSuccess({ Component.literal("Pillager Pressure saved data cleared") }, true)
        return Command.SINGLE_SUCCESS
    }
}
