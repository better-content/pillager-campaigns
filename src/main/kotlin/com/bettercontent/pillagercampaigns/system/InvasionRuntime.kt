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
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraftforge.registries.ForgeRegistries

object InvasionRuntime {
    const val INVASION_TAG = "PillagerCampaignsInvasion"
    const val MEMBER_TAG = "PillagerCampaignsMember"
    const val TARGET_TAG = "PillagerCampaignsTarget"

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
        player.displayClientMessage(Component.literal("A pillager invasion is approaching overland."), true)
        return true
    }

    private fun materialize(server: MinecraftServer, effect: DirectorEffect): Boolean {
        val player = player(server, effect.playerId) ?: return false
        val level = player.serverLevel()
        val anchor = effect.anchor?.takeIf { it.dimension == level.dimension().location().toString() } ?: return false
        if (liveMembers(server, effect.invasionId).isNotEmpty()) return true
        val positions = SurfaceGridSampler.memberPositions(level, BlockPos(anchor.x, anchor.y, anchor.z), effect.members.size)
        if (positions.size != effect.members.size) return false

        val prepared = effect.members.zip(positions).mapNotNull { (member, pos) ->
            val type = ResourceLocation.tryParse(member.recruitId)?.let(ForgeRegistries.ENTITY_TYPES::getValue) ?: return@mapNotNull null
            val mob = type.create(level) as? Mob ?: return@mapNotNull null
            runCatching {
                mob.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null as SpawnGroupData?, null as CompoundTag?)
                mob.setPersistenceRequired()
                mob.persistentData.putString(INVASION_TAG, effect.invasionId)
                mob.persistentData.putString(MEMBER_TAG, member.memberId)
                mob.persistentData.putUUID(TARGET_TAG, player.uuid)
                mob.target = player
                if (mob is PatrollingMonster) {
                    mob.patrolTarget = player.blockPosition()
                    mob.isPatrolLeader = member == effect.members.first()
                }
                mob
            }.getOrElse { error ->
                PillagerCampaignsMod.LOGGER.warn("Could not prepare invasion recruit {}", member.recruitId, error)
                mob.discard()
                null
            }
        }
        if (prepared.size != effect.members.size) {
            prepared.forEach(Entity::discard)
            return false
        }
        val spawned = mutableListOf<Mob>()
        prepared.forEach { mob ->
            if (level.addFreshEntity(mob)) spawned += mob else {
                prepared.forEach(Entity::discard)
                return false
            }
        }
        PillagerCampaignsMod.LOGGER.info("Materialized invasion {} for {} with {} members", effect.invasionId, player.scoreboardName, spawned.size)
        return true
    }

    fun retire(server: MinecraftServer, invasionId: String): Boolean {
        liveMembers(server, invasionId).forEach(Entity::discard)
        return true
    }

    fun liveMembers(server: MinecraftServer, invasionId: String): List<Mob> = server.allLevels.flatMap { level ->
        level.allEntities.filterIsInstance<Mob>().filter { it.persistentData.getString(INVASION_TAG) == invasionId }.toList()
    }

    fun maintainTarget(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(TARGET_TAG)) return
        val level = mob.level() as? ServerLevel ?: return
        val target = level.getPlayerByUUID(tag.getUUID(TARGET_TAG)) ?: return
        if (target.isAlive && mob.target !== target) mob.target = target
    }

    fun invasionId(entity: Entity?): String? = (entity as? Mob)?.persistentData?.getString(INVASION_TAG)?.takeIf(String::isNotBlank)
    fun memberId(entity: Entity?): String? = (entity as? Mob)?.persistentData?.getString(MEMBER_TAG)?.takeIf(String::isNotBlank)

    private fun player(server: MinecraftServer, playerId: String): ServerPlayer? =
        runCatching { java.util.UUID.fromString(playerId) }.getOrNull()?.let(server.playerList::getPlayer)
}
