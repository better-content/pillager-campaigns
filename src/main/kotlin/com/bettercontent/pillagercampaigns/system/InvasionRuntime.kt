package com.bettercontent.pillagercampaigns.system

import com.bettercontent.pillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.core.DirectorEffect
import com.bettercontent.pillagercampaigns.core.EffectKind
import com.bettercontent.pillagercampaigns.core.EffectResult
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraftforge.registries.ForgeRegistries
import java.util.ArrayDeque

object InvasionRuntime {
    private val spawnTicks = ArrayDeque<Long>()
    const val INVASION_TAG = "PillagerCampaignsInvasion"
    const val MEMBER_TAG = "PillagerCampaignsMember"
    const val TARGET_TAG = "PillagerCampaignsTarget"
    const val KIND_TAG = "PillagerCampaignsKind"
    const val WAVE_TAG = "PillagerCampaignsWave"

    fun execute(server: MinecraftServer, effects: List<DirectorEffect>): List<EffectResult> = effects.map { effect ->
        when (effect.kind) {
            EffectKind.WARN -> EffectResult(effect.effectId, warn(server, effect))
            EffectKind.MATERIALIZE -> EffectResult(effect.effectId, materialize(server, effect))
            EffectKind.RETIRE -> EffectResult(effect.effectId, retire(server, effect.invasionId))
        }
    }

    private fun warn(server: MinecraftServer, effect: DirectorEffect): Boolean {
        val player = player(server, effect.playerId) ?: return false
        player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.1f, 0.85f)
        player.displayClientMessage(Component.literal("A pillager assault is forming. You have two minutes."), true)
        return true
    }

    internal fun materialize(server: MinecraftServer, effect: DirectorEffect): Boolean {
        val intended = player(server, effect.playerId) ?: return false
        val level = intended.serverLevel()
        val anchor = effect.anchor?.takeIf { it.dimension == level.dimension().location().toString() } ?: return false
        val anchorPos = BlockPos(anchor.x, anchor.y, anchor.z)
        val target = nearestEligible(level, anchorPos, intended, effect.participantPlayerIds) ?: return false
        return materializeAgainst(level, target, effect)
    }

    internal fun materializeAgainst(level: ServerLevel, target: ServerPlayer, effect: DirectorEffect): Boolean {
        val server = level.server
        val anchor = effect.anchor?.takeIf { it.dimension == level.dimension().location().toString() } ?: return false
        val anchorPos = BlockPos(anchor.x, anchor.y, anchor.z)
        val targetPos = target.blockPosition()
        if (!SurfaceGridSampler.loadedRectangle(level, anchorPos, targetPos)) return false
        val existing = liveMembers(server, effect.invasionId).associateBy { it.persistentData.getString(MEMBER_TAG) }
        val missing = effect.members.filter { it.memberId !in existing }
        if (missing.isEmpty()) return true
        val positions = SurfaceGridSampler.memberPositions(level, anchorPos, missing.size)
        if (positions.size != missing.size) return false

        val prepared = missing.zip(positions).mapNotNull { (member, pos) ->
            val type = ResourceLocation.tryParse(member.recruitId)?.let(ForgeRegistries.ENTITY_TYPES::getValue)
                ?: return@mapNotNull null
            val mob = type.create(level) as? Mob ?: return@mapNotNull null
            runCatching {
                mob.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
                require(exactCandidate(level, mob, pos))
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT,
                    null as SpawnGroupData?, null as CompoundTag?)
                require(SurfaceGridSampler.loadedRectangle(level, anchorPos, targetPos))
                mob.setPersistenceRequired()
                mob.persistentData.putString(INVASION_TAG, effect.invasionId)
                mob.persistentData.putString(MEMBER_TAG, member.memberId)
                mob.persistentData.putUUID(TARGET_TAG, target.uuid)
                mob.persistentData.putString(KIND_TAG, effect.encounterKind.name.lowercase())
                mob.persistentData.putInt(WAVE_TAG, effect.waveIndex)
                mob.target = target
                if (mob is PatrollingMonster) {
                    mob.patrolTarget = targetPos
                    mob.isPatrolLeader = member == effect.members.first()
                }
                mob
            }.getOrElse { error ->
                PillagerCampaignsMod.LOGGER.debug("Rejected campaign recruit {} at {}: {}", member.recruitId, pos, error.message)
                mob.discard()
                null
            }
        }
        if (prepared.size != missing.size || !SurfaceGridSampler.loadedRectangle(level, anchorPos, targetPos)) {
            prepared.forEach(Entity::discard)
            return false
        }
        val spawned = mutableListOf<Mob>()
        prepared.forEach { mob ->
            if (level.addFreshEntity(mob)) spawned += mob
            else {
                spawned.forEach(Entity::discard)
                prepared.filter { it !in spawned }.forEach(Entity::discard)
                return false
            }
        }
        val pathsReach = spawned.all { mob ->
            // The candidate was just proven to have a solid floor; freshly added mobs have not received
            // their first physics tick yet, so expose that exact fact to GroundPathNavigation.
            mob.setOnGround(true)
            SurfaceGridSampler.loadedRectangle(level, anchorPos, targetPos) &&
                mob.navigation.createPath(targetPos, 0)?.canReach() == true
        }
        if (!pathsReach) {
            PillagerCampaignsMod.LOGGER.warn("Rejected campaign packet {} because an added mob path was null or could not reach {}",
                effect.invasionId, targetPos)
            spawned.forEach(Entity::discard)
            return false
        }
        synchronized(spawnTicks) { repeat(spawned.size) { spawnTicks.addLast(server.overworld().gameTime) } }
        PillagerCampaignsMod.LOGGER.info("Materialized {} {} wave {} packet with {} members for {}",
            effect.encounterKind.name.lowercase(), effect.invasionId, effect.waveIndex, spawned.size, target.scoreboardName)
        return true
    }

    internal fun exactCandidate(level: ServerLevel, mob: Mob, pos: BlockPos): Boolean {
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) return false
        if (!level.worldBorder.isWithinBounds(pos)) return false
        if (!level.getFluidState(pos).isEmpty || !level.getFluidState(pos.above()).isEmpty) return false
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty) return false
        return level.noCollision(mob)
    }

    fun retire(server: MinecraftServer, invasionId: String): Boolean {
        liveMembers(server, invasionId).forEach(Entity::discard)
        return true
    }

    fun liveMembers(server: MinecraftServer, invasionId: String): List<Mob> = server.allLevels.flatMap { level ->
        level.allEntities.filterIsInstance<Mob>()
            .filter { it.persistentData.getString(INVASION_TAG) == invasionId }.toList()
    }

    fun liveCampaignPopulation(server: MinecraftServer): Int = server.allLevels.sumOf { level ->
        level.allEntities.filterIsInstance<Mob>().count { it.persistentData.contains(INVASION_TAG) }
    }

    fun spawnsLastSecond(now: Long): Int = synchronized(spawnTicks) {
        while (spawnTicks.firstOrNull()?.let { now - it >= 20L } == true) spawnTicks.removeFirst()
        spawnTicks.size
    }

    fun maintainTarget(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(TARGET_TAG)) return
        val level = mob.level() as? ServerLevel ?: return
        val target = level.getPlayerByUUID(tag.getUUID(TARGET_TAG)) ?: return
        if (target.isAlive && mob.target !== target) mob.target = target
    }

    fun invasionId(entity: Entity?): String? =
        (entity as? Mob)?.persistentData?.getString(INVASION_TAG)?.takeIf(String::isNotBlank)
    fun memberId(entity: Entity?): String? =
        (entity as? Mob)?.persistentData?.getString(MEMBER_TAG)?.takeIf(String::isNotBlank)

    private fun nearestEligible(
        level: ServerLevel,
        origin: BlockPos,
        fallback: ServerPlayer,
        participantIds: List<String>,
    ): ServerPlayer? =
        level.players().filterIsInstance<ServerPlayer>().filter {
            it.isAlive && it.gameMode.gameModeForPlayer == GameType.SURVIVAL &&
                it.serverLevel().dimension() == Level.OVERWORLD &&
                (participantIds.isEmpty() || it.uuid.toString() in participantIds)
        }.minWithOrNull(compareBy<ServerPlayer> { it.distanceToSqr(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5) }
            .thenBy { it.uuid.toString() }) ?: fallback.takeIf { it.isAlive }

    private fun player(server: MinecraftServer, playerId: String): ServerPlayer? =
        runCatching { java.util.UUID.fromString(playerId) }.getOrNull()?.let(server.playerList::getPlayer)
}
