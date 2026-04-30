package com.gerald.pillagerpressure

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !PillagerPressureConfig.enabled.get()) {
            return
        }
        ticks++
        val interval = PillagerPressureConfig.intervalTicks.get().toLong().coerceAtLeast(20L)
        if (ticks % interval != 0L) {
            return
        }
        runAttempt(event.server, force = false, source = "scheduled")
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher, "pillagerpressure")
        register(event.dispatcher, "ppatrol")
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>, name: String) {
        dispatcher.register(
            Commands.literal(name)
                .requires { it.hasPermission(2) }
                .then(Commands.literal("status").executes { context ->
                    context.source.sendSuccess({ Component.literal(statusLine()) }, false)
                    Command.SINGLE_SUCCESS
                })
                .then(Commands.literal("now").executes { context ->
                    val spawned = runAttempt(context.source.server, force = true, source = "command")
                    context.source.sendSuccess({ Component.literal("Pillager Pressure forced attempt spawned_groups=$spawned status=$lastStatus") }, true)
                    Command.SINGLE_SUCCESS
                })
        )
    }

    private fun runAttempt(server: MinecraftServer, force: Boolean, source: String): Int {
        attempts++
        var spawnedGroups = 0
        val players = server.playerList.players
        for (player in players) {
            if (!eligible(player)) continue
            if (!force && player.random.nextDouble() > PillagerPressureConfig.spawnChance.get()) {
                lastStatus = "skipped chance for ${player.gameProfile.name}"
                continue
            }
            if (spawnPatrolFor(player, force)) {
                spawnedGroups++
            }
        }
        if (spawnedGroups == 0 && players.isEmpty()) {
            lastStatus = "no players online"
        }
        PillagerPressureMod.LOGGER.info(
            "Pillager Pressure attempt source={} force={} players={} spawned_groups={} status={}",
            source,
            force,
            players.size,
            spawnedGroups,
            lastStatus,
        )
        return spawnedGroups
    }

    private fun eligible(player: ServerPlayer): Boolean {
        if (PillagerPressureConfig.skipSpectatorPlayers.get() && player.isSpectator) {
            return false
        }
        if (!PillagerPressureConfig.allowCreativePlayers.get() && player.isCreative) {
            return false
        }
        if (PillagerPressureConfig.overworldOnly.get() && player.serverLevel().dimension() != Level.OVERWORLD) {
            return false
        }
        return true
    }

    private fun spawnPatrolFor(player: ServerPlayer, force: Boolean): Boolean {
        val level = player.serverLevel()
        val active = countActivePatrolMobs(level, player.blockPosition())
        if (!force && active >= PillagerPressureConfig.maxActiveNearPlayer.get()) {
            lastStatus = "active cap near ${player.gameProfile.name}: $active"
            return false
        }

        val pos = chooseSpawnPos(level, player.blockPosition())
        if (pos == null) {
            lastStatus = "no valid spawn surface near ${player.gameProfile.name}"
            return false
        }

        val patrolTarget = player.blockPosition()
        var spawned = 0
        val minPillagers = PillagerPressureConfig.minPillagers.get()
        val maxPillagers = max(PillagerPressureConfig.maxPillagers.get(), minPillagers)
        val pillagerCount = if (maxPillagers <= minPillagers) minPillagers else level.random.nextInt(maxPillagers - minPillagers + 1) + minPillagers

        if (PillagerPressureConfig.spawnLeader.get()) {
            if (spawnMob(level, "minecraft:pillager", jitter(pos, level, 3), player, patrolTarget, leader = true)) spawned++
        }
        repeat(pillagerCount) {
            if (spawnMob(level, "minecraft:pillager", jitter(pos, level, 5), player, patrolTarget, leader = false)) spawned++
        }

        if (PillagerPressureConfig.specialAmount.get() > 0 && level.random.nextDouble() <= PillagerPressureConfig.specialChance.get()) {
            repeat(PillagerPressureConfig.specialAmount.get()) {
                val id = chooseSpecial(level) ?: return@repeat
                if (spawnMob(level, id, jitter(pos, level, 6), player, patrolTarget, leader = false)) spawned++
            }
        }

        if (spawned <= 0) {
            lastStatus = "all entity spawns failed near ${player.gameProfile.name} at ${pos.x} ${pos.y} ${pos.z}"
            return false
        }

        groupsSpawned++
        mobsSpawned += spawned.toLong()
        lastStatus = "spawned $spawned near ${player.gameProfile.name} at ${pos.x} ${pos.y} ${pos.z} active_before=$active"
        return true
    }

    private fun chooseSpecial(level: ServerLevel): String? {
        val candidates = PillagerPressureConfig.specialIllagers.get()
            .mapNotNull { raw -> raw as? String }
            .filter { ForgeRegistries.ENTITY_TYPES.containsKey(ResourceLocation.parse(it)) }
        if (candidates.isEmpty()) return null
        return candidates[level.random.nextInt(candidates.size)]
    }

    private fun spawnMob(
        level: ServerLevel,
        entityId: String,
        pos: BlockPos,
        target: ServerPlayer,
        patrolTarget: BlockPos,
        leader: Boolean,
    ): Boolean {
        val id = ResourceLocation.tryParse(entityId) ?: return false
        val type = ForgeRegistries.ENTITY_TYPES.getValue(id) as? EntityType<*> ?: return false
        val entity = type.create(level) as? Mob ?: return false

        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        runCatching {
            entity.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null)
        }.onFailure { error ->
            PillagerPressureMod.LOGGER.debug("finalizeSpawn failed for {} at {}", entityId, pos, error)
        }
        entity.persistentData.putBoolean(PillagerPressureMod.PATROL_TAG, true)
        entity.persistentData.putUUID("BoundToMatterPressureTarget", target.uuid)
        if (PillagerPressureConfig.persistentPatrolMobs.get()) {
            entity.setPersistenceRequired()
        }
        if (PillagerPressureConfig.targetPlayerImmediately.get()) {
            entity.target = target
        }
        if (entity is PatrollingMonster) {
            entity.patrolTarget = patrolTarget
            if (leader) {
                entity.isPatrolLeader = true
            }
        }

        return level.addFreshEntity(entity)
    }

    private fun chooseSpawnPos(level: ServerLevel, center: BlockPos): BlockPos? {
        val minRadius = min(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(8)
        val maxRadius = max(PillagerPressureConfig.minRadius.get(), PillagerPressureConfig.maxRadius.get()).coerceAtLeast(minRadius)
        repeat(PillagerPressureConfig.spawnAttempts.get()) {
            val angle = level.random.nextDouble() * Math.PI * 2.0
            val radius = if (maxRadius == minRadius) minRadius else level.random.nextInt(maxRadius - minRadius + 1) + minRadius
            val x = center.x + (cos(angle) * radius).toInt()
            val z = center.z + (sin(angle) * radius).toInt()
            val probe = BlockPos(x, center.y, z)
            if (!level.hasChunkAt(probe)) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (validSpawnSurface(level, pos)) {
                return pos
            }
        }
        return null
    }

    private fun validSpawnSurface(level: ServerLevel, pos: BlockPos): Boolean {
        if (pos.y <= level.minBuildHeight + 1 || pos.y >= level.maxBuildHeight - 2) return false
        val state = level.getBlockState(pos)
        val above = level.getBlockState(pos.above())
        val below = level.getBlockState(pos.below())
        if (!isOpen(level, pos, state) || !isOpen(level, pos.above(), above)) return false
        if (below.isAir || below.fluidState.isSource) return false
        if (state.fluidState.isSource || above.fluidState.isSource) return false
        return below.isCollisionShapeFullBlock(level, pos.below())
    }

    private fun isOpen(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean =
        state.isAir || state.getCollisionShape(level, pos).isEmpty

    private fun jitter(pos: BlockPos, level: ServerLevel, radius: Int): BlockPos {
        val dx = level.random.nextInt(radius * 2 + 1) - radius
        val dz = level.random.nextInt(radius * 2 + 1) - radius
        val x = pos.x + dx
        val z = pos.z + dz
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val candidate = BlockPos(x, y, z)
        return if (validSpawnSurface(level, candidate)) candidate else pos
    }

    private fun countActivePatrolMobs(level: ServerLevel, center: BlockPos): Int {
        val radius = PillagerPressureConfig.activeCheckRadius.get().toDouble()
        val box = AABB(center).inflate(radius, 96.0, radius)
        return level.getEntitiesOfClass(Mob::class.java, box) { mob ->
            mob.isAlive && mob.persistentData.getBoolean(PillagerPressureMod.PATROL_TAG)
        }.size
    }

    private fun statusLine(): String {
        return "enabled=${PillagerPressureConfig.enabled.get()} ticks=$ticks attempts=$attempts groups=$groupsSpawned mobs=$mobsSpawned last=$lastStatus"
    }
}
