package com.bettercontent.pillagercampaigns

import com.bettercontent.pillagercampaigns.core.*
import com.bettercontent.pillagercampaigns.data.PillagerWorldData
import com.bettercontent.pillagercampaigns.data.TerrainAtlasData
import com.bettercontent.pillagercampaigns.system.InvasionRoster
import com.bettercontent.pillagercampaigns.system.InvasionRuntime
import com.bettercontent.pillagercampaigns.system.SurfaceGridSampler
import com.bettercontent.pillagercampaigns.system.DownedCompat
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object PillagerCampaignsEvents {
    private val defeatedMembers = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val combatInvasions = ConcurrentHashMap.newKeySet<String>()
    private val deadTargets = ConcurrentHashMap.newKeySet<String>()
    private val changedChunks = ConcurrentHashMap.newKeySet<Long>()

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerCampaignsConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
        }
        PillagerWorldData.get(event.server).attach(InvasionRoster.runtimeSpec())
        TerrainAtlasData.get(event.server)
        SurfaceGridSampler.clear()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val server = event.server
        val atlas = TerrainAtlasData.get(server)
        changedChunks.toList().also { changedChunks.removeAll(it.toSet()) }.forEach { packed ->
            val chunkX = net.minecraft.world.level.ChunkPos.getX(packed)
            val chunkZ = net.minecraft.world.level.ChunkPos.getZ(packed)
            server.overworld().chunkSource.getChunkNow(chunkX, chunkZ)?.let { atlas.observe(server.overworld(), it) }
        }
        if (!PillagerCampaignsConfig.enabled.get()) return
        val now = server.overworld().gameTime
        val interval = PillagerCampaignsConfig.intervalTicks.get().toLong()
        if (now % interval != 0L) return
        advance(server, interval)
    }

    private fun advance(server: MinecraftServer, elapsedTicks: Long, commands: List<DirectorCommand> = emptyList()): DirectorTransition {
        val data = PillagerWorldData.get(server)
        val spec = InvasionRoster.runtimeSpec()
        val snapshot = data.snapshot()
        val players = server.playerList.players.map(::observePlayer)
        val atlas = TerrainAtlasData.get(server)
        val routes = snapshot.tracks.values.mapNotNull { track ->
            val invasion = track.invasion?.takeIf { it.phase == InvasionPhase.APPROACHING } ?: return@mapNotNull null
            val target = invasion.routeTarget ?: return@mapNotNull null
            val needsRoute = invasion.strategicRoute.isEmpty() ||
                (invasion.strategicFrontier == StrategicFrontier.UNKNOWN && invasion.strategicAtlasRevision != atlas.revision)
            if (!needsRoute) return@mapNotNull null
            val cells = atlas.cellsAround(target.x, target.z, spec.rules.strategicOriginMaximumBlocks + 16)
            val result = StrategicRoutePlanner.plan(cells, target, spec.rules,
                routeSeed(snapshot.worldSeed, invasion.invasionId, invasion.currentWave),
                invasion.strategicPosition)
            StrategicRouteObservation(track.playerId, invasion.invasionId, target, atlas.revision,
                result?.route?.map { BlockPoint(target.dimension, it.x, it.bodyY, it.z) }.orEmpty(),
                result?.frontier ?: StrategicFrontier.UNKNOWN)
        }
        val frame = DirectorFrame(
            elapsedTicks = elapsedTicks,
            players = players,
            strategicRoutes = routes,
            memberDefeats = defeatedMembers.toList().also { defeatedMembers.clear() }.map { (invasion, member) -> MemberDefeatObservation(invasion, member) },
            combat = combatInvasions.toList().also { combatInvasions.clear() }.map(::CombatObservation),
            targetDeaths = deadTargets.toList().also { deadTargets.clear() }.map(::TargetDeathObservation),
            commands = commands,
            liveCampaignMobs = InvasionRuntime.liveCampaignPopulation(server),
            secondSpawnCount = InvasionRuntime.spawnsLastSecond(server.overworld().gameTime),
        )
        val transition = data.transition(frame, spec)
        val results = InvasionRuntime.execute(server, transition.effects)
        if (results.isNotEmpty()) data.transition(DirectorFrame(0L, effectResults = results), spec)
        return transition
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? net.minecraft.server.level.ServerLevel ?: return
        val chunk = event.chunk as? net.minecraft.world.level.chunk.LevelChunk ?: return
        TerrainAtlasData.get(level.server).observe(level, chunk)
    }

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? net.minecraft.server.level.ServerLevel ?: return
        val chunk = event.chunk as? net.minecraft.world.level.chunk.LevelChunk ?: return
        TerrainAtlasData.get(level.server).observe(level, chunk)
    }

    @SubscribeEvent
    fun onBlockPlaced(event: BlockEvent.EntityPlaceEvent) = queueChangedChunk(event.level, event.pos)

    @SubscribeEvent
    fun onBlockBroken(event: BlockEvent.BreakEvent) = queueChangedChunk(event.level, event.pos)

    @SubscribeEvent
    fun onFluidPlaced(event: BlockEvent.FluidPlaceBlockEvent) = queueChangedChunk(event.level, event.pos)

    @SubscribeEvent
    fun onExplosion(event: ExplosionEvent.Detonate) {
        event.affectedBlocks.forEach { queueChangedChunk(event.level, it) }
    }

    private fun queueChangedChunk(levelAccessor: net.minecraft.world.level.LevelAccessor, pos: net.minecraft.core.BlockPos) {
        val level = levelAccessor as? net.minecraft.server.level.ServerLevel ?: return
        if (level.dimension() == Level.OVERWORLD) changedChunks += net.minecraft.world.level.ChunkPos.asLong(pos.x shr 4, pos.z shr 4)
    }

    private fun routeSeed(vararg values: Any): Long = values.fold(-3750763034362895579L) { hash, value ->
        value.toString().fold(hash) { next, character -> (next xor character.code.toLong()) * 1099511628211L }
    }

    private fun observePlayer(player: ServerPlayer): PlayerObservation {
        val eligible = player.serverLevel().dimension() == Level.OVERWORLD && player.gameMode.gameModeForPlayer == GameType.SURVIVAL
        return PlayerObservation(
            player.uuid.toString(), eligible,
            eligible && SurfaceGridSampler.isNearSurface(player),
            physicallyAvailable = player.isAlive,
            position = BlockPoint(player.serverLevel().dimension().location().toString(), player.blockX, player.blockY, player.blockZ),
            lowHealth = player.health / player.maxHealth.coerceAtLeast(1.0f) < 0.35f,
            downed = DownedCompat.isDowned(player),
        )
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val invasion = InvasionRuntime.invasionId(event.entity)
        val member = InvasionRuntime.memberId(event.entity)
        if (invasion != null && member != null) defeatedMembers += invasion to member
        if (event.entity is ServerPlayer) deadTargets += event.entity.uuid.toString()
    }

    @SubscribeEvent
    fun onLivingHurt(event: LivingHurtEvent) {
        InvasionRuntime.invasionId(event.entity)?.let(combatInvasions::add)
        InvasionRuntime.invasionId(event.source.entity)?.let(combatInvasions::add)
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingTickEvent) {
        (event.entity as? Mob)?.takeIf { InvasionRuntime.invasionId(it) != null }?.let(InvasionRuntime::maintainTarget)
    }

    @SubscribeEvent
    fun onEntityJoin(event: EntityJoinLevelEvent) {
        val mob = event.entity as? Mob ?: return
        val invasionId = InvasionRuntime.invasionId(mob) ?: return
        val server = event.level.server ?: return
        val active = PillagerWorldData.get(server).snapshot().tracks.values.any { it.invasion?.invasionId == invasionId }
        if (!active) mob.discard()
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val root = Commands.literal("pillager_campaigns")
            .then(Commands.literal("status")
                .executes { status(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word()).executes { status(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("force").requires { it.hasPermission(2) }
                .executes { force(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word()).executes { force(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("reset").requires { it.hasPermission(2) }
                .executes { reset(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word()).executes { reset(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("export_runtime_spec").requires { it.hasPermission(2) }.executes { exportSpec(it.source) })
        event.dispatcher.register(root)
    }

    private fun status(source: CommandSourceStack, name: String?): Int {
        val player = target(source, name)
        if (player == null) {
            source.sendFailure(Component.literal("Player is not online")); return 0
        }
        val track = PillagerWorldData.get(source.server).snapshot().tracks[player.uuid.toString()]
        val message = if (track == null) "pressure=uninitialized" else buildString {
            append("eligible_ticks=${track.eligibleTicks} next_scout=${track.nextScoutEligibleTick} next_assault=${track.nextAssaultEligibleTick} outcome_adjustment=${track.outcomeAdjustment}")
            track.invasion?.let { append(" encounter=${it.invasionId} kind=${it.kind.name.lowercase()} phase=${it.phase.name.lowercase()} wave=${it.currentWave + 1}/${it.waves.size} intensity=${it.intensity} members=${it.members.size} origin=${it.strategicOrigin} position=${it.strategicPosition} frontier=${it.strategicFrontier.name.lowercase()} atlas_revision=${it.strategicAtlasRevision}") }
        }
        source.sendSuccess({ Component.literal(message) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun force(source: CommandSourceStack, name: String?): Int {
        val player = target(source, name)
        if (player == null) { source.sendFailure(Component.literal("Player is not online")); return 0 }
        advance(source.server, 0L, listOf(DirectorCommand.Force(player.uuid.toString())))
        source.sendSuccess({ Component.literal("Forced assault pressure for ${player.scoreboardName}") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun reset(source: CommandSourceStack, name: String?): Int {
        val snapshot = PillagerWorldData.get(source.server).snapshot()
        val playerId = name?.let { source.server.playerList.getPlayerByName(it)?.uuid?.toString() }
        if (name != null && playerId == null) { source.sendFailure(Component.literal("Player is not online")); return 0 }
        snapshot.tracks.values.filter { playerId == null || it.playerId == playerId }.mapNotNull { it.invasion?.invasionId }
            .forEach { InvasionRuntime.retire(source.server, it) }
        advance(source.server, 0L, listOf(DirectorCommand.Reset(playerId)))
        source.sendSuccess({ Component.literal(if (playerId == null) "Reset all invasion pressure" else "Reset invasion pressure for $name") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun exportSpec(source: CommandSourceStack): Int {
        val directory = source.server.getWorldPath(LevelResource.ROOT).resolve("pillager_campaigns/exports")
        Files.createDirectories(directory)
        val target = directory.resolve("invasion-runtime-spec.json")
        val temporary = directory.resolve("invasion-runtime-spec.json.tmp")
        Files.writeString(temporary, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(InvasionRoster.runtimeSpec()))
        runCatching { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        source.sendSuccess({ Component.literal("Exported $target") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun target(source: CommandSourceStack, name: String?): ServerPlayer? =
        if (name != null) source.server.playerList.getPlayerByName(name) else source.player
}
